package com.streamify.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamify.app.data.models.Track
import com.streamify.app.data.remote.ListeningSession
import com.streamify.app.data.remote.SupabaseClient
import com.streamify.app.data.remote.jamTrackFromJson
import com.streamify.app.jam.JamEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

sealed class JamUiState {
    object Idle : JamUiState()
    object Loading : JamUiState()
    data class Active(val session: ListeningSession, val isHost: Boolean) : JamUiState()
    data class Error(val message: String) : JamUiState()
}

/**
 * Guest-side PLL: hard-seek beyond ±2.5s, perfect lock under ±20ms,
 * proportional-integral speed nudging (0.96x–1.04x) between boundaries.
 */
class JamPhaseLockedLoop(
    private val playerViewModel: PlayerViewModel
) {
    private var lastIntegralError = 0.0
    private val KP = 0.00008
    private val KI = 0.00001
    private val MAX_SPEED_NUDGE = 0.04f

    fun evaluatePhaseError(
        reportedPositionMs: Long,
        hostEpochMs: Long,
        durationMs: Long,
        rttMs: Long = 60L
    ) {
        val now = System.currentTimeMillis()
        val oneWayTransit = (rttMs / 2).coerceAtLeast(0L)
        val estimatedHostPosition = reportedPositionMs + (now - hostEpochMs).coerceAtLeast(0L) + oneWayTransit
        val boundedHostPosition = if (durationMs > 0) estimatedHostPosition.coerceAtMost(durationMs) else estimatedHostPosition
        val currentLocalPosition = playerViewModel.getAcousticPositionMs()
        val errorMs = (boundedHostPosition - currentLocalPosition).toDouble()

        if (kotlin.math.abs(errorMs) > 2500.0) {
            playerViewModel.isApplyingJamSync = true
            playerViewModel.seekTo(boundedHostPosition)
            playerViewModel.setPlaybackSpeed(1.0f)
            lastIntegralError = 0.0
            playerViewModel.isApplyingJamSync = false
            return
        }

        if (kotlin.math.abs(errorMs) < 20.0) {
            playerViewModel.setPlaybackSpeed(1.0f)
            lastIntegralError = 0.0
            return
        }

        lastIntegralError = (lastIntegralError + errorMs).coerceIn(-5000.0, 5000.0)
        val adjustment = (KP * errorMs + KI * lastIntegralError).toFloat()
        val targetSpeed = (1.0f + adjustment).coerceIn(1.0f - MAX_SPEED_NUDGE, 1.0f + MAX_SPEED_NUDGE)
        playerViewModel.setPlaybackSpeed(targetSpeed)
    }

    fun reset() {
        lastIntegralError = 0.0
        playerViewModel.setPlaybackSpeed(1.0f)
    }
}

/**
 * JAM VIEWMODEL v2 — thin executor over [JamEngine].
 *
 * The protocol brain lives in the process-wide engine singleton so sessions
 * survive navigation; this class binds it to a live PlayerViewModel, executes
 * protocol commands against playback, drives presence pulses and performs the
 * authoritative join/reconnect handshake.
 */
class JamViewModel(
    private val appContext: android.content.Context? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow<JamUiState>(JamUiState.Idle)
    val uiState: StateFlow<JamUiState> = _uiState.asStateFlow()

    // Canonical shared queue mirror (fed by engine protocol decisions).
    val jamQueue: StateFlow<List<Track>> = JamEngine.queue
    val members: StateFlow<List<JamEngine.Member>> = JamEngine.members
    val connStatus: StateFlow<JamEngine.ConnStatus> = JamEngine.connStatus
    val policy: StateFlow<JamEngine.ControlPolicy> = JamEngine.policy

    private var pll: JamPhaseLockedLoop? = null
    private var attachedPlayer: PlayerViewModel? = null
    private var executorsStarted = false
    private var wasConnected = false

    init {
        // Room lifecycle mirrors the cloud session row.
        viewModelScope.launch {
            SupabaseClient.activeJam.collect { session ->
                if (session != null) {
                    val isHost = session.hostUserId == SupabaseClient.currentUser.value?.id
                    _uiState.value = JamUiState.Active(session, isHost)
                } else if (_uiState.value is JamUiState.Active) {
                    _uiState.value = JamUiState.Idle
                }
            }
        }
    }

    fun startJam(currentTrack: Track?, currentPosition: Long) {
        if (currentTrack == null) {
            _uiState.value = JamUiState.Error("Play a track before starting a Jam session")
            return
        }
        viewModelScope.launch {
            _uiState.value = JamUiState.Loading
            val result = SupabaseClient.createJamSession(currentTrack, currentPosition)
            result.onSuccess { session ->
                _uiState.value = JamUiState.Active(session, isHost = true)
                JamEngine.startRuntime()
                JamEngine.noteSelf()
            }.onFailure { err ->
                _uiState.value = JamUiState.Error(err.message ?: "Failed to create Jam room")
            }
        }
    }

    fun joinJam(code: String, playerViewModel: PlayerViewModel) {
        val cleanCode = code.trim().uppercase()
        if (cleanCode.length != 6) {
            _uiState.value = JamUiState.Error("Please enter a valid 6-character room code")
            return
        }
        viewModelScope.launch {
            _uiState.value = JamUiState.Loading
            val result = SupabaseClient.joinJamSession(cleanCode)
            result.onSuccess { session ->
                attachedPlayer = playerViewModel
                pll = JamPhaseLockedLoop(playerViewModel)
                _uiState.value = JamUiState.Active(session, isHost = false)
                JamEngine.startRuntime()
                startExecutors(playerViewModel)
                // LOCKSTEP HANDSHAKE: adopt the room's exact position immediately.
                performHandshake(session)
            }.onFailure { err ->
                _uiState.value = JamUiState.Error(err.message ?: "Could not join Jam session")
            }
        }
    }

    /** Explicit detach used by the Leave button (never by lifecycle death). */
    fun leaveJam(endForEveryone: Boolean = false) {
        JamEngine.leaveSession(endForEveryone)
        pll?.reset()
        pll = null
        _uiState.value = JamUiState.Idle
    }

    // ═══════════════ Shared queue (routed through the engine protocol) ═══════════════

    fun addToJamQueue(track: Track) {
        val name = SupabaseClient.currentUser.value?.displayName ?: "Someone"
        JamEngine.addToQueue(track, addedByName = name)
    }

    fun removeFromJamQueue(track: Track) {
        JamEngine.removeFromQueue(track)
    }

    fun cycleControlPolicy() {
        val next = if (JamEngine.policy.value == JamEngine.ControlPolicy.EVERYONE)
            JamEngine.ControlPolicy.HOST_ONLY else JamEngine.ControlPolicy.EVERYONE
        JamEngine.setPolicy(next)
    }

    fun inviteShareText(): String {
        val code = (JamUiStateActiveSessionOrNull() ?: return "").sessionCode
        return "🎵 Join my Streamify Jam!\nCode: $code\nOr tap: streamify://jam/$code"
    }

    private fun JamUiStateActiveSessionOrNull(): ListeningSession? =
        (uiState.value as? JamUiState.Active)?.session

    // ═══════════════ Protocol executors ═══════════════

    private fun startExecutors(playerViewModel: PlayerViewModel) {
        if (executorsStarted) return
        executorsStarted = true
        pll = pll ?: JamPhaseLockedLoop(playerViewModel)

        // 1. Execute protocol decisions against live playback.
        viewModelScope.launch {
            JamEngine.commands.collect { cmd ->
                val pvm = attachedPlayer ?: return@collect
                when (cmd) {
                    is JamEngine.Command.ApplyTrack -> {
                        pvm.isApplyingJamSync = true
                        pvm.playTrack(cmd.track, listOf(cmd.track), autoHydrateRadio = false)
                        delay(300)
                        if (cmd.positionMs > 0L) pvm.seekTo(cmd.positionMs)
                        if (!cmd.play) pvm.pause() else pvm.play()
                        pvm.isApplyingJamSync = false
                    }
                    is JamEngine.Command.ApplySeek -> {
                        pvm.isApplyingJamSync = true
                        pvm.seekTo(cmd.positionMs)
                        pvm.isApplyingJamSync = false
                    }
                    is JamEngine.Command.ApplyPlayPause -> {
                        pvm.isApplyingJamSync = true
                        if (cmd.play) pvm.play() else pvm.pause()
                        pvm.isApplyingJamSync = false
                    }
                    is JamEngine.Command.ApplyPllTick -> {
                        pll?.evaluatePhaseError(
                            reportedPositionMs = cmd.hostPositionMs,
                            hostEpochMs = cmd.hostEpochMs,
                            durationMs = cmd.durationMs
                        )
                        if (pvm.playerState.value.isPlaying != cmd.play) {
                            pvm.isApplyingJamSync = true
                            if (cmd.play) pvm.play() else pvm.pause()
                            pvm.isApplyingJamSync = false
                        }
                    }
                    JamEngine.Command.SessionEnded -> {
                        UiEventBus.emitEvent(UiEvent.ShowSnackbar("Jam ended by host"))
                    }
                }
            }
        }

        // 2. Feed every inbound wire packet into the protocol brain.
        viewModelScope.launch {
            SupabaseClient.jamPlaybackUpdates.collect { payload ->
                JamEngine.onPayload(payload)
            }
        }

        // 3. Presence heartbeat — roster liveness every 5 seconds.
        viewModelScope.launch {
            while (isActive) {
                if (JamEngine.isActive()) {
                    val me = SupabaseClient.currentUser.value
                    JamEngine.pulsePresence(me?.displayName ?: "Listener", me?.avatarUrl)
                }
                delay(5000)
            }
        }

        // 4. Reconnect reconciliation: socket healed → re-adopt room truth.
        viewModelScope.launch {
            SupabaseClient.isRealtimeConnected.collect { connected ->
                if (connected && !wasConnected && JamEngine.isActive()) {
                    (uiState.value as? JamUiState.Active)?.session?.let { performHandshake(it) }
                }
                wasConnected = connected
            }
        }
    }

    /**
     * Authoritative join/reconnect reconciliation: fetch the DB row, extrapolate
     * where the host should be RIGHT NOW, and adopt that exact state locally.
     */
    private suspend fun performHandshake(session: ListeningSession) {
        val pvm = attachedPlayer ?: return
        val snap = JamEngine.reconcile() ?: return
        val track = jamTrackFromJson(snap.currentTrackJson) ?: return
        val expectedPos = JamEngine.extrapolatePosition(snap)

        val current = pvm.playerState.value.currentTrack
        val sameTrack = current?.title?.equals(track.title, ignoreCase = true) == true &&
                current.artist.equals(track.artist, ignoreCase = true)

        pvm.isApplyingJamSync = true
        try {
            if (!sameTrack) {
                pvm.playTrack(track, listOf(track), autoHydrateRadio = false)
                delay(350) // allow pipeline warm-up before pinning position
            }
            if (expectedPos > 0L) pvm.seekTo(expectedPos)
            if (snap.isPlaying != pvm.playerState.value.isPlaying || !sameTrack) {
                if (snap.isPlaying) pvm.play() else pvm.pause()
            }
        } finally {
            pvm.isApplyingJamSync = false
        }
    }
}
