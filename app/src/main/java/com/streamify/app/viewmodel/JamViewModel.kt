package com.streamify.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamify.app.data.models.Track
import com.streamify.app.data.remote.ListeningSession
import com.streamify.app.data.remote.SupabaseClient
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

class JamViewModel(
    private val appContext: android.content.Context? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow<JamUiState>(JamUiState.Idle)
    val uiState: StateFlow<JamUiState> = _uiState.asStateFlow()

    private var syncJob: Job? = null
    
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
        syncJob = viewModelScope.launch {
            while (isActive) {
                delay(1500)
                val stateRes = SupabaseClient.joinJamSession(code)
                stateRes.onSuccess { session ->
                    val isHost = session.hostUserId == SupabaseClient.currentUser.value?.id
                    _uiState.value = JamUiState.Active(session, isHost)

                    // 1. Align PTP clock with any discovered LAN peers
                    meshEngine?.discoveredPeers?.value?.values?.firstOrNull()?.let { peer ->
                        precisionProtocol?.startClockAlignment(peer.ipAddress, isHost)
                    }

                    // 2. If not host, synchronize track and adjust clock drift using Hybrid PTP + PLL Resolution
                    if (!isHost && playerViewModel != null) {
                        val currentLocalTrack = playerViewModel.playerState.value.currentTrack
                        val targetTrack: Track? = if (session.currentTrackJson != null) {
                            // Tier 1: JSONB Snapshot (Instant 0ms resolution)
                            val art = session.currentTrackJson.optString("coverArtPath", "")
                            val fp = session.currentTrackJson.optString("filepath", "")
                            Track(
                                id = session.currentTrackJson.optInt("id", 0),
                                title = session.currentTrackJson.optString("title", "Jam Track"),
                                artist = session.currentTrackJson.optString("artist", "Artist"),
                                album = "Streamify Jam",
                                durationSec = session.currentTrackJson.optInt("durationSec", 180),
                                bpm = 120f,
                                key = "",
                                coverArtPath = if (art.isNotBlank()) art else null,
                                lyricsPath = null,
                                filepath = fp,
                                source = "jam"
                            )
                        } else if (session.currentTrackId.isNotBlank()) {
                            // Tier 2: Supabase Cloud Catalog lookup
                            val cloudTrack = SupabaseClient.fetchTrackById(session.currentTrackId)
                            // Tier 3: Local SQLite Library lookup
                            cloudTrack ?: com.streamify.app.data.TrackRepository.getAllTracks().find {
                                it.id.toString() == session.currentTrackId || it.filepath.contains(session.currentTrackId)
                            }
                        } else null

                        if (targetTrack != null) {
                            val isDifferentTrack = (targetTrack.id != 0 && targetTrack.id != (currentLocalTrack?.id ?: 0)) ||
                                    (targetTrack.title.isNotBlank() && targetTrack.title != (currentLocalTrack?.title ?: "")) ||
                                    (targetTrack.artist.isNotBlank() && targetTrack.artist != (currentLocalTrack?.artist ?: ""))

                            if (isDifferentTrack) {
                                val ptp = precisionProtocol
                                if (ptp != null && ptp.isSynchronized.value) {
                                    val targetAtomicTs = ptp.getSynchronizedClockMs() + 350L
                                    playerViewModel.scheduleAtomicPlayback(targetTrack, targetAtomicTs, session.positionMs, ptp)
                                } else {
                                    playerViewModel.playTrack(targetTrack, listOf(targetTrack))
                                }
                            } else {
                                // 3. Sub-15ms PLL Drift and Playhead Alignment
                                val synchronizedNow = precisionProtocol?.getSynchronizedClockMs() ?: System.currentTimeMillis()
                                val hostElapsed = if (session.isPlaying) {
                                    (synchronizedNow - session.hostClockTimestamp).coerceAtLeast(0L)
                                } else 0L

                                val maxDurMs = if (targetTrack.durationSec > 0) targetTrack.durationSec * 1000L else 300000L
                                val expectedHostAcousticPos = (session.positionMs + hostElapsed).coerceIn(0L, maxDurMs)
                                val clientAcousticPos = playerViewModel.getAcousticPositionMs()
                                val driftMs = clientAcousticPos - expectedHostAcousticPos
                                val absDrift = kotlin.math.abs(driftMs)

                                when {
                                    absDrift <= 12L -> {
                                        // Zone 1: Sub-15ms locked! Restore standard playback speed
                                        playerViewModel.setPlaybackSpeed(1.0f)
                                    }
                                    absDrift in 13L..150L -> {
                                        // Zone 2: Continuous Phase Lock Loop micro-pitch scaling (Inaudible)
                                        val targetSpeed = if (driftMs > 0) 0.996f else 1.004f
                                        playerViewModel.setPlaybackSpeed(targetSpeed)
                                    }
                                    absDrift > 150L -> {
                                        // Zone 3: Major scrub or lag -> Fast seek
                                        playerViewModel.setPlaybackSpeed(1.0f)
                                        playerViewModel.seekTo(expectedHostAcousticPos)
                                    }
                                }

                                val isLocalPlaying = playerViewModel.playerState.value.isPlaying
                                if (isLocalPlaying != session.isPlaying) {
                                    if (session.isPlaying) playerViewModel.play() else playerViewModel.pause()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun broadcastPlayback(currentTrack: Track?, positionMs: Long, isPlaying: Boolean) {
        val currentSession = (uiState.value as? JamUiState.Active)?.session ?: return
        if (currentTrack == null) return

        val synchronizedNow = precisionProtocol?.getSynchronizedClockMs() ?: System.currentTimeMillis()

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
        syncJob = null
        meshEngine?.stopDiscovery()
        precisionProtocol?.stop()
        SupabaseClient.leaveJamSession()
        _uiState.value = JamUiState.Idle
    }
}
