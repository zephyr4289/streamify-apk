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
        val hybridRecommendations: List<Track> = emptyList(),
        val sessionRecommendations: List<Track>,
        val circadianRecommendations: List<Track>,
        val circadianSlotTitle: String,
        val madeForYou: List<Track>,
        val onlineDiscoveries: List<Track>,
        val recent: List<Track>,
        val topPlayed: List<Track>,
        val allTracks: List<Track>
    ) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}

class HomeViewModel(
    private val repository: TrackRepository = TrackRepository,
    private val hybridFetcher: com.streamify.app.data.network.HybridGraphFetcher = com.streamify.app.data.network.HybridGraphFetcher()
) : ViewModel() {

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

                // 2. Project Chronos Circadian Recommendation Shelf (V_slot & Time-of-day BPM)
                val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val rawCircadian = try { repository.getCircadianRecommendations(currentHour, 20) } catch (e: Exception) { emptyList() }
                val circadianRecs = ReRanker.reRank(
                    candidates = if (rawCircadian.isNotEmpty()) rawCircadian else allTracks,
                    maxPerArtist = 2,
                    explorationRatio = 0.20f,
                    explorationPool = allTracks,
                    limit = 8
                )
                val slotName = repository.getCircadianSlot(currentHour)
                val slotTitle = when (slotName) {
                    "MORNING" -> "Morning Energy • Wake & Move"
                    "AFTERNOON" -> "Afternoon Flow • Focus & Lo-Fi"
                    "EVENING" -> "Evening Horizon • Golden Hour Unwind"
                    else -> "Late Night Drift • Deep Chill"
                }

                // 3. Multi-Modal Long-Term Profile (V_long)
                val rawLongRecs = try { repository.getLongTermRecommendations(userId = 1, limit = 30) } catch (e: Exception) { emptyList() }
                val madeForYou = ReRanker.reRank(
                    candidates = if (rawLongRecs.isNotEmpty()) rawLongRecs else allTracks,
                    maxPerArtist = 2,
                    explorationRatio = 0.25f,
                    explorationPool = allTracks,
                    limit = 8
                )

                // 4. Top Heavy Rotations
                val topPlayed = try { repository.getTopPlayedTracks(20) } catch (e: Exception) { emptyList() }
                val recent = allTracks.takeLast(6)

                // 5. Multi-Seed Ensemble & Hybrid Radar (Shatters single-genre Phonk loop)
                val distinctSeeds = ReRanker.getDistinctGenreSeeds(sessionRecs.ifEmpty { allTracks }, limit = 3)
                val hybridRecs = if (distinctSeeds.isNotEmpty()) {
                    try {
                        val timeOfDay = com.streamify.app.util.TimeGreeting.getCurrentTimeOfDay()
                        val audioDevice = com.streamify.app.service.AudioDeviceManager.getCurrentDeviceType()
                        val candidates = mutableListOf<Track>()
                        for (seed in distinctSeeds) {
                            val recs = hybridFetcher.getHybridRecommendations(seed, timeOfDay, audioDevice, limit = 5)
                            candidates.addAll(recs)
                        }
                        ReRanker.reRank(
                            candidates = candidates.distinctBy { it.id },
                            maxPerArtist = 2,
                            maxPerTempoCluster = 3,
                            explorationRatio = 0.25f,
                            explorationPool = allTracks,
                            limit = 8
                        )
                    } catch (e: Exception) {
                        emptyList()
                    }
                } else emptyList()

                // 6. Online 2-Hop Graph Discovery Across Diverse Artists
                val topArtists = ReRanker.extractTopArtists(sessionRecs.ifEmpty { madeForYou.ifEmpty { allTracks } }, limit = 3)
                val onlineDiscoveries = mutableListOf<Track>()

                for (artist in topArtists) {
                    val queryResults = try { iTunesSearchApi.search(artist, maxResults = 4) } catch (e: Exception) { emptyList() }
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
                        hybridRecommendations = hybridRecs,
                        sessionRecommendations = sessionRecs,
                        circadianRecommendations = circadianRecs,
                        circadianSlotTitle = slotTitle,
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
