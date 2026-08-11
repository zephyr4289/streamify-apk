package com.streamify.app.viewmodel

import androidx.lifecycle.ViewModel
import com.streamify.app.data.models.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0,
    val duration: Long = 0
)

class PlayerViewModel : ViewModel() {
    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    fun playTrack(track: Track) {
        _playerState.value = _playerState.value.copy(
            currentTrack = track,
            isPlaying = true,
            duration = track.durationSec * 1000L
        )
    }

    fun togglePlayPause() {
        val currentState = _playerState.value
        _playerState.value = currentState.copy(isPlaying = !currentState.isPlaying)
    }

    fun seekTo(position: Long) {
        _playerState.value = _playerState.value.copy(currentPosition = position)
    }
}
