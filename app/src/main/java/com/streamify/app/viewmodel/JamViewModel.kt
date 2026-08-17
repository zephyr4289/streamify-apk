package com.streamify.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamify.app.data.models.Track
import com.streamify.app.data.remote.ListeningSession
import com.streamify.app.data.remote.SupabaseClient
import org.json.JSONObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class JamUiState {
    object Idle : JamUiState()
    object Loading : JamUiState()
    data class Active(val session: ListeningSession, val isHost: Boolean) : JamUiState()
    data class Error(val message: String) : JamUiState()
}

class JamPhaseLockedLoop(
    private val playerViewModel: PlayerViewModel
) {
    private var lastIntegralError = 0.0
    private val KP = 0.00008
    private val KI = 0.00001
    private val MAX_SPEED_NUDGE = 0.04f // 0.96x to 1.04x

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

        // 1. Boundary: Critical Disconnect (> 2.5s) -> Hard Seek
        if (kotlin.math.abs(errorMs) > 2500.0) {
            playerViewModel.seekTo(boundedHostPosition)
            playerViewModel.setPlaybackSpeed(1.0f)
            lastIntegralError = 0.0
            return
        }

        // 2. Boundary: In-Phase Tolerance (< 20ms) -> Perfect Lock
        if (kotlin.math.abs(errorMs) < 20.0) {
            playerViewModel.setPlaybackSpeed(1.0f)
            lastIntegralError = 0.0
            return
        }

        // 3. Boundary: Continuous Micro-Adjustment (PI Controller)
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

class JamViewModel(
    private val appContext: android.content.Context? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow<JamUiState>(JamUiState.Idle)
    val uiState: StateFlow<JamUiState> = _uiState.asStateFlow()

    private var syncJob: Job? = null
    private var wsSyncJob: Job? = null
    private var pll: JamPhaseLockedLoop? = null
    
    private val meshEngine: com.streamify.app.data.network.MeshDiscoveryEngine? by lazy {
        appContext?.let { com.streamify.app.data.network.MeshDiscoveryEngine.getInstance(it) }
    }

    private val precisionProtocol: com.streamify.app.service.PrecisionTimeProtocol? by lazy {
        meshEngine?.let { com.streamify.app.service.PrecisionTimeProtocol.getInstance(it) }
    }

    init {
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

        val user = SupabaseClient.currentUser.value
        if (user != null) {
            meshEngine?.startDiscovery(user.id)
        }

        viewModelScope.launch {
            _uiState.value = JamUiState.Loading
            val result = SupabaseClient.createJamSession(currentTrack, currentPosition)
            result.onSuccess { session ->
                _uiState.value = JamUiState.Active(session, isHost = true)
                startPeriodicSync(session.sessionCode)
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

        val user = SupabaseClient.currentUser.value
        if (user != null) {
            meshEngine?.startDiscovery(user.id)
        }

        viewModelScope.launch {
            _uiState.value = JamUiState.Loading
            val result = SupabaseClient.joinJamSession(cleanCode)
            result.onSuccess { session ->
                val isHost = session.hostUserId == SupabaseClient.currentUser.value?.id
                _uiState.value = JamUiState.Active(session, isHost)
                startPeriodicSync(session.sessionCode, playerViewModel)
            }.onFailure { err ->
                _uiState.value = JamUiState.Error(err.message ?: "Could not join Jam session")
            }
        }
    }

    private fun startPeriodicSync(code: String, playerViewModel: PlayerViewModel? = null) {
        syncJob?.cancel()
        wsSyncJob?.cancel()

        if (playerViewModel != null) {
            pll = JamPhaseLockedLoop(playerViewModel)
        }

        // Channel B: Ephemeral Realtime WebSocket Media Plane (<15ms latency)
        if (playerViewModel != null) {
            wsSyncJob = viewModelScope.launch {
                SupabaseClient.jamPlaybackUpdates.collect { payload: JSONObject ->
                    val sessionCode = payload.optString("session_code", "")
                    if (sessionCode.equals(code, ignoreCase = true)) {
                        val trackId = payload.optString("track_id", "")
                        val isPlaying = payload.optBoolean("is_playing", false)
                        val posMs = payload.optLong("position_ms", 0L)
                        val hostEpoch = payload.optLong("host_epoch_ms", System.currentTimeMillis())

                        val currentLocalTrack = playerViewModel.playerState.value.currentTrack
                        if (trackId.isNotBlank() && currentLocalTrack?.id.toString() != trackId) {
                            val cloudTrack = SupabaseClient.fetchTrackById(trackId)
                            val allLocalTracks = com.streamify.app.data.TrackRepository.getAllTracks()
                            val target = cloudTrack ?: allLocalTracks.find {
                                it.id.toString() == trackId || (it.filepath.isNotBlank() && it.filepath.contains(trackId))
                            }
                            if (target != null) {
                                playerViewModel.playTrack(target, listOf(target))
                            }
                        } else {
                            val durMs = (currentLocalTrack?.durationSec ?: 180) * 1000L
                            pll?.evaluatePhaseError(
                                reportedPositionMs = posMs,
                                hostEpochMs = hostEpoch,
                                durationMs = durMs
                            )
                            if (playerViewModel.playerState.value.isPlaying != isPlaying) {
                                if (isPlaying) playerViewModel.play() else playerViewModel.pause()
                            }
                        }
                    }
                }
            }
        }

        // Channel A: Relational Control Plane Presence Heartbeat (Every 5s)
        syncJob = viewModelScope.launch {
            while (isActive) {
                delay(5000)
                val stateRes = SupabaseClient.joinJamSession(code)
                stateRes.onSuccess { session ->
                    val isHost = session.hostUserId == SupabaseClient.currentUser.value?.id
                    _uiState.value = JamUiState.Active(session, isHost)

                    // Align PTP clock with discovered LAN peers
                    meshEngine?.discoveredPeers?.value?.values?.firstOrNull()?.let { peer ->
                        precisionProtocol?.startClockAlignment(peer.ipAddress, isHost)
                    }
                }
            }
        }
    }

    fun broadcastPlayback(currentTrack: Track?, positionMs: Long, isPlaying: Boolean) {
        val currentSession = (uiState.value as? JamUiState.Active)?.session ?: return
        if (currentTrack == null) return

        viewModelScope.launch {
            SupabaseClient.updateJamPlayback(
                sessionCode = currentSession.sessionCode,
                track = currentTrack,
                positionMs = positionMs,
                isPlaying = isPlaying
            )
        }
    }

    fun leaveJam() {
        syncJob?.cancel()
        wsSyncJob?.cancel()
        syncJob = null
        wsSyncJob = null
        pll?.reset()
        pll = null
        meshEngine?.stopDiscovery()
        precisionProtocol?.stop()
        SupabaseClient.leaveJamSession()
        _uiState.value = JamUiState.Idle
    }
}
