package com.streamify.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamify.app.data.TrackRepository
import com.streamify.app.data.models.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class HomeUiState {
    object Loading : HomeUiState()
    data class Success(val recommendations: List<Track>, val recent: List<Track>, val allTracks: List<Track>) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}

class HomeViewModel(private val repository: TrackRepository = TrackRepository()) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                val allTracks = repository.getAllTracks()
                // If there are tracks, try to get recommendations for the first one as a mock "recent history" based recommendation
                val recommendations = if (allTracks.isNotEmpty()) {
                    val aiRecs = repository.getRecommendations(allTracks.first().id, limit = 5)
                    if (aiRecs.isNotEmpty()) aiRecs else allTracks.take(5)
                } else {
                    emptyList()
                }
                
                val recent = allTracks.takeLast(6)

                _uiState.value = HomeUiState.Success(
                    recommendations = recommendations,
                    recent = recent,
                    allTracks = allTracks
                )
            } catch (e: Exception) {
                _uiState.value = HomeUiState.Error(e.message ?: "Failed to load home data")
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
