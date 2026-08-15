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

                    // If not host, adjust player drift if delta > 2000ms
                    if (!isHost && playerViewModel != null && session.currentTrackJson != null) {
                        val remoteTrackId = session.currentTrackJson.optInt("id", 0)
                        val currentLocalId = playerViewModel.playerState.value.currentTrack?.id ?: 0

                        if (remoteTrackId != 0 && remoteTrackId != currentLocalId) {
                            val art = session.currentTrackJson.optString("coverArtPath", "")
                            val track = Track(
                                id = remoteTrackId,
                                title = session.currentTrackJson.optString("title", "Jam Track"),
                                artist = session.currentTrackJson.optString("artist", "Artist"),
                                album = "Streamify Jam",
                                durationSec = session.currentTrackJson.optInt("durationSec", 180),
                                bpm = 120f,
                                key = "",
                                coverArtPath = if (art.isNotBlank()) art else null,
                                lyricsPath = null,
                                filepath = session.currentTrackJson.optString("filepath", ""),
                                source = "jam"
                            )
                            playerViewModel.playTrack(track, listOf(track))
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
