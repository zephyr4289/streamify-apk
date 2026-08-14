package com.streamify.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamify.app.data.ReRanker
import com.streamify.app.data.TrackRepository
import com.streamify.app.data.models.Track
import com.streamify.app.data.network.iTunesSearchApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class HomeUiState {
    object Loading : HomeUiState()
    data class Success(
        val sessionRecommendations: List<Track>,
        val madeForYou: List<Track>,
        val onlineDiscoveries: List<Track>,
        val recent: List<Track>,
        val topPlayed: List<Track>,
        val allTracks: List<Track>
    ) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}

class HomeViewModel(private val repository: TrackRepository = TrackRepository) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        viewModelScope.launch {
            repository.allTracks.collect { allTracks ->
                computeHomeRecommendations(allTracks)
            }
        }
        loadData()
    }

    private fun computeHomeRecommendations(allTracks: List<Track>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Session-Aware Candidates (EMA V_session)
                val rawSessionRecs = try { repository.getSessionRecommendations(30) } catch (e: Exception) { emptyList() }
                val sessionRecs = ReRanker.reRank(
                    candidates = rawSessionRecs,
                    maxPerArtist = 2,
                    explorationRatio = 0.15f,
                    explorationPool = allTracks,
                    limit = 8
                )

                // 2. Multi-Modal Long-Term Profile (V_long)
                val rawLongRecs = try { repository.getLongTermRecommendations(userId = 1, limit = 30) } catch (e: Exception) { emptyList() }
                val madeForYou = ReRanker.reRank(
                    candidates = if (rawLongRecs.isNotEmpty()) rawLongRecs else allTracks,
                    maxPerArtist = 2,
                    explorationRatio = 0.25f,
                    explorationPool = allTracks,
                    limit = 8
                )

                // 3. Top Heavy Rotations
                val topPlayed = try { repository.getTopPlayedTracks(20) } catch (e: Exception) { emptyList() }
                val recent = allTracks.takeLast(6)

                // 4. Online 2-Hop Graph Discovery
                val topArtists = ReRanker.extractTopArtists(sessionRecs.ifEmpty { topPlayed.ifEmpty { allTracks } }, limit = 2)
                val onlineDiscoveries = mutableListOf<Track>()

                for (artist in topArtists) {
                    val queryResults = try { iTunesSearchApi.search("$artist mix", maxResults = 5) } catch (e: Exception) { emptyList() }
                    for (item in queryResults) {
                        val pseudoId = -(item.url.hashCode())
                        onlineDiscoveries.add(
                            Track(
                                id = pseudoId,
                                title = item.title,
                                artist = item.uploader,
                                album = "Online Discovery",
                                durationSec = item.duration,
                                filepath = item.url,
                                coverArtPath = item.thumbnail,
                                bpm = 0f,
                                key = "",
                                lyricsPath = null,
                                source = "online"
                            )
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    _uiState.value = HomeUiState.Success(
                        sessionRecommendations = sessionRecs,
                        madeForYou = madeForYou,
                        onlineDiscoveries = onlineDiscoveries.take(8),
                        recent = recent,
                        topPlayed = topPlayed,
                        allTracks = allTracks
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = HomeUiState.Error(e.message ?: "Failed to generate recommendations")
                }
            }
        }
    }

    fun loadData() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                repository.refresh()
            } catch (e: Exception) {
                _uiState.value = HomeUiState.Error(e.message ?: "Failed to load home data")
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
