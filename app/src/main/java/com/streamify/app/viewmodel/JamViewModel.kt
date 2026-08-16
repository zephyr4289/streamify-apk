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

class JamViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<JamUiState>(JamUiState.Idle)
    val uiState: StateFlow<JamUiState> = _uiState.asStateFlow()

    private var syncJob: Job? = null

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
                delay(2000)
                val stateRes = SupabaseClient.joinJamSession(code)
                stateRes.onSuccess { session ->
                    val isHost = session.hostUserId == SupabaseClient.currentUser.value?.id
                    _uiState.value = JamUiState.Active(session, isHost)

                    // If not host, synchronize track and adjust clock drift using Hybrid Resolution
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
                                playerViewModel.playTrack(targetTrack, listOf(targetTrack))
                            } else {
                                // Synchronize position & playback state with host clock
                                val hostElapsed = if (session.isPlaying) {
                                    (System.currentTimeMillis() - session.hostClockTimestamp).coerceAtLeast(0L)
                                } else 0L
                                val maxDurMs = if (targetTrack.durationSec > 0) targetTrack.durationSec * 1000L else 300000L
                                val expectedPos = (session.positionMs + hostElapsed).coerceIn(0L, maxDurMs)
                                val localPos = playerViewModel.playerState.value.currentPosition
                                val driftMs = kotlin.math.abs(expectedPos - localPos)

                                if (driftMs > 2000L) {
                                    playerViewModel.seekTo(expectedPos)
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
        SupabaseClient.leaveJamSession()
        _uiState.value = JamUiState.Idle
    }
}
