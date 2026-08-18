package com.streamify.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamify.app.data.ReRanker
import com.streamify.app.data.TrackRepository
import com.streamify.app.data.models.Track
import com.streamify.app.data.network.iTunesSearchApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
        val allTracks: List<Track>,
        val trending: List<Track> = emptyList(),
        val heavyRotation: List<Track> = emptyList()
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
            repository.allTracks
                .debounce(500)
                .map { tracks -> tracks.map { it.id }.sorted().hashCode() }
                .distinctUntilChanged()
                .collectLatest { _ ->
                    computeHomeRecommendations(repository.allTracks.value)
                }
        }
        loadData()
    }

    private fun computeHomeRecommendations(allTracks: List<Track>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // =========================================================================
                // PIPELINE A: CLOUD DISCOVERY (ALWAYS ONLINE, NEVER GATED ON SQLITE)
                // =========================================================================
                val cloudSeeds = listOf("Top Hits", "Trending Hits", "Synthwave", "Lo-Fi Beats", "Indie Chill")
                val deferredCloudSeeds = cloudSeeds.map { seed ->
                    async(Dispatchers.IO) {
                        try {
                            val ytResults = com.streamify.app.data.network.YouTubeMusicSearchApi.search(seed, maxResults = 6)
                            if (ytResults.isNotEmpty()) {
                                ytResults.mapNotNull { res ->
                                    val vid = com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(res.url, res.thumbnail) ?: return@mapNotNull null
                                    val trackObj = Track(
                                        id = -(vid.hashCode()),
                                        title = res.title,
                                        artist = res.uploader,
                                        album = "Trending Global",
                                        durationSec = res.duration,
                                        filepath = "https://www.youtube.com/watch?v=$vid",
                                        coverArtPath = res.thumbnail.ifBlank { "https://i.ytimg.com/vi/$vid/hqdefault.jpg" },
                                        bpm = 120f,
                                        key = "C",
                                        lyricsPath = null,
                                        source = "online_stream",
                                        isLiked = repository.isTrackLiked(Track(title = res.title, artist = res.uploader))
                                    )
                                    repository.hydrateTrack(trackObj)
                                }
                            } else {
                                iTunesSearchApi.search(seed, maxResults = 6).map { res ->
                                    val trackObj = Track(
                                        id = kotlin.math.abs(res.title.hashCode()),
                                        title = res.title,
                                        artist = res.uploader,
                                        album = "Trending Global",
                                        durationSec = res.duration,
                                        filepath = "ytsearch:${res.title} ${res.uploader}",
                                        coverArtPath = res.thumbnail,
                                        bpm = 120f,
                                        key = "C",
                                        lyricsPath = null,
                                        source = "online_stream",
                                        isLiked = repository.isTrackLiked(Track(title = res.title, artist = res.uploader))
                                    )
                                    repository.hydrateTrack(trackObj)
                                }
                            }
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                }

                val cloudDiscoveryPool = deferredCloudSeeds.awaitAll().flatten().distinctBy { "${it.title}:::${it.artist}".lowercase() }

                // =========================================================================
                // PIPELINE B: LOCAL PERSONALIZATION (C++ NATIVE SIMD ENGINE)
                // =========================================================================
                val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val rawSessionRecs = try { repository.getSessionRecommendations(30) } catch (e: Exception) { emptyList() }
                val rawCircadian = try { repository.getCircadianRecommendations(currentHour, 20) } catch (e: Exception) { emptyList() }
                val rawLongRecs = try { repository.getLongTermRecommendations(userId = 1, limit = 30) } catch (e: Exception) { emptyList() }
                val topPlayed = try { repository.getTopPlayedTracks(20) } catch (e: Exception) { emptyList() }
                val recent = allTracks.takeLast(6)

                val slotName = repository.getCircadianSlot(currentHour)
                val slotTitle = when (slotName) {
                    "MORNING" -> "Morning Energy • Wake & Move"
                    "AFTERNOON" -> "Afternoon Flow • Focus & Lo-Fi"
                    "EVENING" -> "Evening Horizon • Golden Hour Unwind"
                    else -> "Late Night Drift • Deep Chill"
                }

                // 1. Session Recommendations (EMA V_session)
                val sessionCandidates = if (rawSessionRecs.isNotEmpty()) rawSessionRecs else (allTracks + cloudDiscoveryPool).distinctBy { it.id }
                val sessionRecs = ReRanker.reRank(
                    candidates = sessionCandidates,
                    maxPerArtist = 2,
                    explorationRatio = 0.25f,
                    explorationPool = cloudDiscoveryPool.ifEmpty { allTracks },
                    limit = 12
                )

                // 2. Project Chronos Circadian Recommendation Shelf (V_slot)
                val circadianCandidates = if (rawCircadian.isNotEmpty()) rawCircadian else (cloudDiscoveryPool + allTracks).distinctBy { it.id }
                val circadianRecs = ReRanker.reRank(
                    candidates = circadianCandidates,
                    maxPerArtist = 2,
                    explorationRatio = 0.25f,
                    explorationPool = cloudDiscoveryPool.ifEmpty { allTracks },
                    limit = 12
                )

                // 3. Multi-Modal Long-Term Profile (V_long)
                val longCandidates = if (rawLongRecs.isNotEmpty()) rawLongRecs else (cloudDiscoveryPool + allTracks).distinctBy { it.id }
                val madeForYou = ReRanker.reRank(
                    candidates = longCandidates,
                    maxPerArtist = 2,
                    explorationRatio = 0.30f,
                    explorationPool = cloudDiscoveryPool.ifEmpty { allTracks },
                    limit = 12
                )

                // 4. Multi-Seed Ensemble & Hybrid Radar (Last.fm Graph x On-Device SIMD)
                val distinctSeeds = ReRanker.getDistinctGenreSeeds(sessionRecs.ifEmpty { cloudDiscoveryPool }, limit = 3)
                val semaphore = Semaphore(6)
                val hybridRecs = if (distinctSeeds.isNotEmpty()) {
                    try {
                        val timeOfDay = com.streamify.app.util.TimeGreeting.getCurrentTimeOfDay()
                        val audioDevice = com.streamify.app.service.AudioDeviceManager.getCurrentDeviceType()
                        coroutineScope {
                            val deferredList = distinctSeeds.map { seed ->
                                async(Dispatchers.IO) {
                                    semaphore.withPermit {
                                        withTimeoutOrNull(2500L) {
                                            try {
                                                hybridFetcher.getHybridRecommendations(seed, timeOfDay, audioDevice, limit = 5)
                                            } catch (e: Exception) {
                                                emptyList()
                                            }
                                        } ?: emptyList()
                                    }
                                }
                            }
                            val candidates = deferredList.awaitAll().flatten()
                            ReRanker.reRank(
                                candidates = candidates.distinctBy { it.id },
                                maxPerArtist = 2,
                                maxPerTempoCluster = 3,
                                explorationRatio = 0.30f,
                                explorationPool = cloudDiscoveryPool.ifEmpty { allTracks },
                                limit = 12
                            )
                        }
                    } catch (e: Exception) {
                        emptyList()
                    }
                } else emptyList()

                // 5. Online 2-Hop Graph Discovery Across Diverse Artists (Bounded Parallel Racing)
                val topArtists = ReRanker.extractTopArtists(sessionRecs.ifEmpty { madeForYou.ifEmpty { cloudDiscoveryPool } }, limit = 4)
                val onlineDiscoveries = coroutineScope {
                    val deferredArtists = topArtists.map { artist ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                withTimeoutOrNull(2500L) {
                                    try {
                                        val yt = com.streamify.app.data.network.YouTubeMusicSearchApi.search("$artist top songs", maxResults = 4)
                                        if (yt.isNotEmpty()) {
                                            yt.mapNotNull { item ->
                                                val vid = com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(item.url, item.thumbnail) ?: return@mapNotNull null
                                                val trackObj = Track(
                                                    id = -(vid.hashCode()),
                                                    title = item.title,
                                                    artist = item.uploader,
                                                    album = "Online Discovery",
                                                    durationSec = item.duration,
                                                    filepath = "https://www.youtube.com/watch?v=$vid",
                                                    coverArtPath = item.thumbnail.ifBlank { "https://i.ytimg.com/vi/$vid/hqdefault.jpg" },
                                                    bpm = 120f,
                                                    key = "",
                                                    lyricsPath = null,
                                                    source = "online_stream",
                                                    isLiked = repository.isTrackLiked(Track(title = item.title, artist = item.uploader))
                                                )
                                                repository.hydrateTrack(trackObj)
                                            }
                                        } else {
                                            iTunesSearchApi.search(artist, maxResults = 4).map { item ->
                                                val trackObj = Track(
                                                    id = -(item.title.hashCode()),
                                                    title = item.title,
                                                    artist = item.uploader,
                                                    album = "Online Discovery",
                                                    durationSec = item.duration,
                                                    filepath = "ytsearch:${item.title} ${item.uploader}",
                                                    coverArtPath = item.thumbnail,
                                                    bpm = 0f,
                                                    key = "",
                                                    lyricsPath = null,
                                                    source = "online_stream",
                                                    isLiked = repository.isTrackLiked(Track(title = item.title, artist = item.uploader))
                                                )
                                                repository.hydrateTrack(trackObj)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        emptyList()
                                    }
                                } ?: emptyList()
                            }
                        }
                    }
                    deferredArtists.awaitAll().flatten()
                }

                val finalDisplayPool = if (allTracks.isNotEmpty()) allTracks else cloudDiscoveryPool
                val hydrateList: (List<Track>) -> List<Track> = { list -> list.map { repository.hydrateTrack(it) } }

                withContext(Dispatchers.Main) {
                    _uiState.value = HomeUiState.Success(
                        hybridRecommendations = hydrateList(hybridRecs.ifEmpty { cloudDiscoveryPool.take(8) }),
                        sessionRecommendations = hydrateList(sessionRecs.ifEmpty { cloudDiscoveryPool.take(8) }),
                        circadianRecommendations = hydrateList(circadianRecs.ifEmpty { cloudDiscoveryPool.take(8) }),
                        circadianSlotTitle = slotTitle,
                        madeForYou = hydrateList(madeForYou.ifEmpty { cloudDiscoveryPool.take(8) }),
                        onlineDiscoveries = hydrateList((onlineDiscoveries + cloudDiscoveryPool).distinctBy { "${it.title}:::${it.artist}".lowercase() }.take(12)),
                        recent = hydrateList(recent.ifEmpty { cloudDiscoveryPool.take(6) }),
                        topPlayed = hydrateList(topPlayed.take(8)),
                        allTracks = finalDisplayPool,
                        trending = hydrateList(cloudDiscoveryPool.take(15)),
                        heavyRotation = hydrateList(topPlayed.take(8))
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
