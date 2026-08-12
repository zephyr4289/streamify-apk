package com.streamify.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class IngestionState(
    val totalFiles: Int = 0,
    val processedFiles: Int = 0,
    val currentFile: String = "",
    val currentStep: String = "",
    val isActive: Boolean = false,
    val progress: Float = 0f
)

class IngestionViewModel : ViewModel() {
    private val _ingestionState = MutableStateFlow(IngestionState())
    val ingestionState: StateFlow<IngestionState> = _ingestionState.asStateFlow()

    fun updateState(newState: IngestionState) {
        _ingestionState.value = newState
    }

    fun simulateIngestion() {
        viewModelScope.launch {
            _ingestionState.value = IngestionState(
                totalFiles = 10,
                processedFiles = 5,
                currentFile = "mock_song.mp3",
                currentStep = "Generating embeddings...",
                isActive = true,
                progress = 0.5f
            )
        }
    }
}
