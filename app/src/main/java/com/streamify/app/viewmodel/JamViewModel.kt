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
 * Guest-side PLL — Phase 1.4: delegates every decision to the native 1D
 * Kalman filter (`kalman_pll.rs`) over the skew-free synced clock.
 *
 * Decision bands (native):
 *   ≤150ms drift  → smooth speed scalar 0.98x–1.02x (inaudible micro-stretch)
 *   >150ms drift  → feed-forward HARD SEEK to host position + full state reset
 *   PAUSED regime → velocity clamped / state wiped (zero integral windup)
 *
 * Gap-repaired ticks arrive pre-synthesized by `tick_matrix`, so the filter
 * never mistakes a dropped packet for a stall.
 */
class JamPhaseLockedLoop(
    private val playerViewModel: PlayerViewModel
) {

    fun evaluatePhaseError(
        reportedPositionMs: Long,
        hostMonoMs: Long,
        durationMs: Long,
        @Suppress("UNUSED_PARAMETER") rttMs: Long = 60L
    ) {
        val nb = com.streamify.app.data.NativeBridge
        val nowSync = nb.getSyncedJamMonotonicMs()

        // Bound the measurement to the live track so a stale tick can never
        // seek past the end during transition races.
        val z = if (durationMs > 0) reportedPositionMs.coerceIn(0L, durationMs) else reportedPositionMs

        val decision = nb.kalmanPllDecide(z, nowSync, hostMonoMs, playing = true)

        when (decision[0].toInt()) {
            DECISION_SEEK -> {
                playerViewModel.isApplyingJamSync = true
                try {
                    playerViewModel.seekTo(decision[2])
                    playerViewModel.setPlaybackSpeed(1.0f)
                } finally {
                    playerViewModel.isApplyingJamSync = false
                }
                com.streamify.app.util.SLog.d(TAG_PLL, "feed-forward seek → ${decision[2]}ms")
            }
            DECISION_SPEED -> {
                val scalarMilli = decision[1]
                if (scalarMilli in 980..1020) {
                    playerViewModel.setPlaybackSpeed(scalarMilli / 1000f)
                    // Secondary path: micro PCM stretch when the processor is
                    // attached to a render chain (no-op on stock ExoPlayer).
                    com.streamify.app.service.SyncAudioProcessor.setSpeedScalar(scalarMilli / 1000f)
                }
            }
            else -> {
                // HOLD: inside lock band.
                if (playerViewModel.playbackSpeed() != 1.0f) {
                    playerViewModel.setPlaybackSpeed(1.0f)
                    com.streamify.app.service.SyncAudioProcessor.setSpeedScalar(1.0f)
                }
            }
        }
    }

    fun reset() {
        com.streamify.app.data.NativeBridge.kalmanPllReset()
        playerViewModel.setPlaybackSpeed(1.0f)
    }

    companion object {
        private const val TAG_PLL = "KalmanPll"
        private const val DECISION_SEEK = 2
        private const val DECISION_SPEED = 1
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
                            hostMonoMs = cmd.hostEpochMs,
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

        // 3.5 Skew-free bootstrap (P1): guests probe until the Cristian filter locks.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (isActive) {
                if (JamEngine.clockSyncProbeDue()) {
                    JamEngine.fireClockSyncProbe()
                }
                delay(1_500)
            }
        }

        // 3.6 Zero-gap handoff (P3): host NEXT_IS → guest shadow pre-buffer.
        JamEngine.setOnNextIsListener { nextTrack ->
            com.streamify.app.service.PredictivePreBufferManager.JamPreBuffer.notifyNextIs(nextTrack)
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
