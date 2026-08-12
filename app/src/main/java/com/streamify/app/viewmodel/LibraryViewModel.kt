package com.streamify.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamify.app.data.TrackRepository
import com.streamify.app.data.models.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class LibraryUiState {
    object Loading : LibraryUiState()
    data class Success(val likedTracks: List<Track>) : LibraryUiState()
    data class Error(val message: String) : LibraryUiState()
}

class LibraryViewModel(private val repository: TrackRepository = TrackRepository()) : ViewModel() {

    private val _uiState = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        loadLibrary()
    }

    fun loadLibrary() {
        viewModelScope.launch {
            try {
                val likedTracks = repository.getLikedTracks()
                _uiState.value = LibraryUiState.Success(likedTracks)
            } catch (e: Exception) {
                _uiState.value = LibraryUiState.Error(e.message ?: "Failed to load library")
            }
        }
    }
}
