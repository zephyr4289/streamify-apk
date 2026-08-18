package com.streamify.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamify.app.data.TrackRepository
import com.streamify.app.data.models.Track
import com.streamify.app.data.network.StreamEdgeCache
import com.streamify.app.data.network.YouTubeStreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SearchResultType { SONG, VIDEO, ARTIST, ALBUM, PLAYLIST }

data class OnlineSearchResult(
    val title: String,
    val uploader: String,
    val url: String,
    val duration: Int,
    val thumbnail: String,
    val type: SearchResultType = SearchResultType.SONG,
    val subtitle: String? = null,
    val year: String? = null,
    val browseId: String? = null,
    val isExplicit: Boolean = false,
    val isVerified: Boolean = false
)

sealed class SearchUiState {
    object Idle : SearchUiState()
    object Loading : SearchUiState()
    data class Success(
        val localResults: List<Track>,
        val onlineResults: List<OnlineSearchResult>,
        val isOnlineLoading: Boolean = false
    ) : SearchUiState()
    data class Error(val message: String) : SearchUiState()
}

class SearchViewModel(private val repository: TrackRepository = TrackRepository) : ViewModel() {
    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private val _searchSuggestions = MutableStateFlow<List<String>>(emptyList())
    val searchSuggestions: StateFlow<List<String>> = _searchSuggestions.asStateFlow()

    private var searchJob: kotlinx.coroutines.Job? = null
    private var suggestJob: kotlinx.coroutines.Job? = null
    private var speculativePrefetchJob: kotlinx.coroutines.Job? = null
    private val prefetchScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private var prefs: android.content.SharedPreferences? = null
    private var appContext: android.content.Context? = null

    fun updateSuggestions(query: String) {
        suggestJob?.cancel()
        val clean = query.trim()
        if (clean.length < 2) {
            _searchSuggestions.value = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            kotlinx.coroutines.delay(150)
            val list = com.streamify.app.data.network.YouTubeMusicSearchApi.fetchSearchSuggestions(clean)
            _searchSuggestions.value = list
        }
    }

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
        if (prefs == null) {
            prefs = context.getSharedPreferences("search_history", android.content.Context.MODE_PRIVATE)
            val saved = prefs?.getString("history", "") ?: ""
            if (saved.isNotBlank()) {
                _searchHistory.value = saved.split(";;")
            }
        }
    }

    private fun isWifiConnected(context: android.content.Context?): Boolean {
        if (context == null) return false
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
               capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun addQueryToHistory(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        val current = _searchHistory.value.toMutableList()
        current.remove(q)
        current.add(0, q)
        if (current.size > 10) current.removeAt(current.size - 1)
        _searchHistory.value = current
        prefs?.edit()?.putString("history", current.joinToString(";;"))?.apply()
    }

    fun clearHistory() {
        _searchHistory.value = emptyList()
        prefs?.edit()?.remove("history")?.apply()
    }

    private val searchCache = android.util.LruCache<String, List<OnlineSearchResult>>(100)
    private val streamUrlCache = android.util.LruCache<String, Pair<String, Long>>(50)
    private var historyJob: kotlinx.coroutines.Job? = null
    private var streamJob: kotlinx.coroutines.Job? = null

    private val _resolvingTrackUrl = MutableStateFlow<String?>(null)
    val resolvingTrackUrl: StateFlow<String?> = _resolvingTrackUrl.asStateFlow()

    fun search(query: String, filter: String = "All") {
        searchJob?.cancel()
        historyJob?.cancel()

        val cleanQuery = query.trim()
        val searchFilter = com.streamify.app.data.network.SearchFilter.fromLabel(filter)
        val cacheKey = "${filter.lowercase()}:${cleanQuery.lowercase()}"

        if (cleanQuery.isBlank()) {
            _uiState.value = SearchUiState.Idle
            return
        }

        historyJob = viewModelScope.launch {
            kotlinx.coroutines.delay(1000)
            addQueryToHistory(cleanQuery)
        }

        searchJob = viewModelScope.launch {
            // 200ms keystroke debounce to prevent SQLite and network thrashing
            kotlinx.coroutines.delay(200)

            // Signal orchestrator to prioritize search over background AI ingestion
            com.streamify.app.data.NativeBridge.setHighPriorityActive(true)

            // 1. Instantaneous Local Search (Sub-millisecond JNI + Fuzzy Fallback)
            val localResults = if (filter == "All" || filter == "Songs") {
                repository.searchTracks(cleanQuery)
            } else emptyList()

            // 2. Check In-Memory LRU Cache for Instant Online Results (0ms)
            val cachedOnline = searchCache.get(cacheKey)
            if (cachedOnline != null) {
                _uiState.value = SearchUiState.Success(
                    localResults = localResults,
                    onlineResults = cachedOnline,
                    isOnlineLoading = false
                )
                return@launch
            }

            _uiState.value = SearchUiState.Success(
                localResults = localResults,
                onlineResults = emptyList(),
                isOnlineLoading = true
            )

            // 3. Ultra-Fast Sub-100ms Parametric Innertube & Python Search Pipeline
            val onlineResults = withContext(Dispatchers.IO) {
                try {
                    val searchResult: List<OnlineSearchResult> = com.streamify.app.data.network.ResilientMediaRouter.fetchWithFallback<List<OnlineSearchResult>>(
                        timeoutMs = 2500L,
                        primary = {
                            val yt = com.streamify.app.data.network.YouTubeMusicSearchApi.search(cleanQuery, filter = searchFilter, maxResults = 30)
                            if (yt.isNotEmpty()) yt
                            else com.streamify.app.data.network.iTunesSearchApi.search(cleanQuery, maxResults = 25)
                        },
                        fallback = {
                            com.streamify.app.data.network.iTunesSearchApi.search(cleanQuery, maxResults = 25)
                        }
                    ) ?: emptyList()

                    val semanticResults: List<OnlineSearchResult> = if (filter == "All" && com.streamify.app.data.network.SemanticSearchEngine.isSemanticQuery(cleanQuery)) {
                        try {
                            com.streamify.app.data.network.SemanticSearchEngine.resolveMoodQuery(cleanQuery)
                        } catch (e: Exception) {
                            emptyList()
                        }
                    } else emptyList()

                    val combined = if (semanticResults.isNotEmpty()) {
                        (semanticResults + searchResult).distinctBy { it.url.ifBlank { it.title } }
                    } else {
                        searchResult
                    }

                    rankSearchResults(combined, cleanQuery)
                } catch (e: Exception) {
                    emptyList()
                }
            }

            if (onlineResults.isNotEmpty()) {
                searchCache.put(cacheKey, onlineResults)
            }

            _uiState.value = SearchUiState.Success(
                localResults = localResults,
                onlineResults = onlineResults,
                isOnlineLoading = false
            )

            // 4. Zero-Disk Speculative In-Memory URL Pre-Resolver (Zero Disk I/O, Zero Contention)
            if (onlineResults.isNotEmpty()) {
                speculativePrefetchJob?.cancel()
                prefetchScope.coroutineContext.cancelChildren()
                speculativePrefetchJob = prefetchScope.launch {
                    val candidates = onlineResults.take(3)
                    candidates.forEach { candidate ->
                        launch {
                            try {
                                val tempTrack = Track(
                                    id = 0,
                                    title = candidate.title,
                                    artist = candidate.uploader,
                                    filepath = candidate.url,
                                    coverArtPath = candidate.thumbnail
                                )
                                val resolved = YouTubeStreamResolver.resolveStreamJit(tempTrack).getOrNull()
                                if (resolved != null && resolved.streamUrl.isNotBlank()) {
                                    val vidId = YouTubeStreamResolver.extractVideoId(candidate.url, candidate.thumbnail)
                                    if (vidId != null) {
                                        StreamEdgeCache.putStream(vidId, resolved)
                                    }
                                    streamUrlCache.put(candidate.url, Pair(resolved.streamUrl, System.currentTimeMillis()))
                                }
                            } catch (e: Exception) {
                                // Silent fail
                            }
                        }
                    }
                }
            }
        }
    }

    private fun rankSearchResults(results: List<OnlineSearchResult>, query: String): List<OnlineSearchResult> {
        val qLower = query.lowercase().trim()
        return results.sortedWith(
            compareByDescending<OnlineSearchResult> { item ->
                var score = 0.0
                val titleLower = item.title.lowercase()
                val artistLower = item.uploader.lowercase()

                if (item.isVerified) score += 50.0
                if (item.type == SearchResultType.ARTIST && artistLower == qLower) score += 100.0
                if (titleLower == qLower) score += 80.0
                if (artistLower == qLower) score += 60.0

                val simTitle = com.streamify.app.data.FuzzyTitleMatcher.calculateSimilarity(qLower, item.title)
                val simArtist = com.streamify.app.data.FuzzyTitleMatcher.calculateSimilarity(qLower, item.uploader)
                score += maxOf(simTitle, simArtist) * 40.0

                if (item.type == SearchResultType.SONG && item.duration in 90..480) {
                    score += 15.0
                }
                score
            }
        )
    }

    fun playOnlineTrack(
        onlineTrack: OnlineSearchResult,
        allOnlineResults: List<OnlineSearchResult> = emptyList(),
        playerViewModel: com.streamify.app.viewmodel.PlayerViewModel,
        ingestionViewModel: com.streamify.app.viewmodel.IngestionViewModel,
        context: android.content.Context,
        onTrackReady: (() -> Unit)? = null
    ) {
        // Immediately cancel all speculative background prefetches to free network sockets & CPU for active playback
        speculativePrefetchJob?.cancel()
        prefetchScope.coroutineContext.cancelChildren()
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            _resolvingTrackUrl.value = onlineTrack.url
            try {
                // 1. Check if StreamEdgeCache already pre-resolved this candidate (<1ms hit)
                val videoId = YouTubeStreamResolver.extractVideoId(onlineTrack.url, onlineTrack.thumbnail)
                val cached = if (videoId != null) StreamEdgeCache.getStream(videoId) else null
                val directUrl = if (cached != null && !YouTubeStreamResolver.isCdnExpired(cached.streamUrl)) {
                    cached.streamUrl
                } else {
                    val candidateTrack = Track(
                        id = 0,
                        title = onlineTrack.title,
                        artist = onlineTrack.uploader,
                        album = "Online Stream",
                        durationSec = onlineTrack.duration,
                        filepath = onlineTrack.url,
                        coverArtPath = onlineTrack.thumbnail
                    )
                    val resolveResult = YouTubeStreamResolver.resolveStreamJit(candidateTrack)
                    resolveResult.getOrNull()?.streamUrl ?: ""
                }

                if (directUrl.isNotBlank()) {
                    val trackToPlay = Track(
                        id = -(onlineTrack.url.hashCode()),
                        title = onlineTrack.title,
                        artist = onlineTrack.uploader,
                        album = "Online Stream",
                        durationSec = onlineTrack.duration,
                        filepath = directUrl,
                        coverArtPath = onlineTrack.thumbnail,
                        bpm = 0f,
                        key = "",
                        lyricsPath = null,
                        source = "online_stream"
                    )

                    // 2. Play tapped track immediately and kick off Continuum Radio Engine
                    playerViewModel.playFromSearch(trackToPlay, listOf(trackToPlay))
                    withContext(Dispatchers.Main) {
                        onTrackReady?.invoke()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Could not resolve audio stream. Please try another track.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Ignore intentional user cancellation
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _resolvingTrackUrl.value = null
            }
        }
    }

    fun importSpotifyPlaylist(
        url: String,
        ingestionViewModel: com.streamify.app.viewmodel.IngestionViewModel,
        context: android.content.Context
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Resolving Spotify link...", android.widget.Toast.LENGTH_SHORT).show()
                }
                
                val scraped = com.streamify.app.data.remote.PlaylistLinkScraper.scrapePlaylist(url)
                val newPlaylistId = java.util.UUID.randomUUID().toString()
                val playlistName = scraped.name.ifBlank { "Imported Spotify Playlist" }
                val trackIds = mutableListOf<Int>()
                
                val savedQuality = context.getSharedPreferences("audio_settings", android.content.Context.MODE_PRIVATE)
                    .getString("download_quality", "320") ?: "320"
                    
                for (item in scraped.tracks) {
                    val title = item.title
                    val artist = item.artist
                    val album = "Spotify"
                    val coverUrl = item.thumbnailUrl ?: ""
                    
                    // Generate a pseudo ID
                    val pseudoId = -((title + artist).hashCode())
                    trackIds.add(pseudoId)
                    
                    val searchString = "ytsearch1:$title $artist"
                    
                    // Fire and forget background download using ytsearch
                    withContext(Dispatchers.Main) {
                        ingestionViewModel.enqueueDownload(
                            context = context,
                            url = searchString,
                            title = title,
                            artist = artist,
                            album = album,
                            quality = savedQuality
                        )
                    }
                    kotlinx.coroutines.delay(60) // Smooth scheduling prevents WorkManager queue thrashing
                }
                
                if (trackIds.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        val repo = com.streamify.app.data.PlaylistRepository
                        val p = com.streamify.app.data.Playlist(
                            id = newPlaylistId,
                            name = playlistName,
                            description = "Imported from Spotify ($url)",
                            trackIds = trackIds
                        )
                        repo.addPlaylist(p)
                        android.widget.Toast.makeText(context, "Imported ${trackIds.size} tracks. Check Downloads tab.", android.widget.Toast.LENGTH_LONG).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Failed to resolve Spotify link.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Error importing playlist.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun importLocalPlaylistJson(
        uri: android.net.Uri,
        ingestionViewModel: com.streamify.app.viewmodel.IngestionViewModel,
        context: android.content.Context
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Reading local playlist...", android.widget.Toast.LENGTH_SHORT).show()
                }
                val inputStream = context.contentResolver.openInputStream(uri)
                val jsonString = inputStream?.bufferedReader().use { it?.readText() } ?: return@launch
                
                var jsonArray = org.json.JSONArray()
                try {
                    jsonArray = org.json.JSONArray(jsonString)
                } catch (e: Exception) {
                    val jsonObj = org.json.JSONObject(jsonString)
                    val keys = listOf("items", "tracks", "playlist", "songs", "data")
                    for (k in keys) {
                        if (jsonObj.has(k)) {
                            val arr = jsonObj.optJSONArray(k)
                            if (arr != null) {
                                jsonArray = arr
                                break
                            }
                        }
                    }
                }
                
                val newPlaylistId = java.util.UUID.randomUUID().toString()
                var playlistName = "Imported Local JSON"
                val documentFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
                if (documentFile != null && documentFile.name != null) {
                    playlistName = documentFile.name!!.removeSuffix(".json")
                }
                
                val trackIds = mutableListOf<Int>()
                
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(i) ?: continue
                    val tObj = item.optJSONObject("track") ?: item
                    
                    val title = tObj.optString("title", tObj.optString("name", "Unknown"))
                    val albumObj = tObj.optJSONObject("album")
                    val album = albumObj?.optString("name") ?: tObj.optString("album", "Local Import")
                    
                    var artistStr = "Unknown"
                    if (tObj.has("artists")) {
                        val artistsArr = tObj.optJSONArray("artists")
                        if (artistsArr != null && artistsArr.length() > 0) {
                            val artistsList = mutableListOf<String>()
                            for (j in 0 until artistsArr.length()) {
                                val a = artistsArr.optJSONObject(j)
                                if (a != null) artistsList.add(a.optString("name", ""))
                            }
                            artistStr = artistsList.joinToString(", ")
                        }
                    } else if (tObj.has("artist")) {
                        artistStr = tObj.optString("artist")
                    }
                    
                    if (title.isBlank()) continue
                    
                    val pseudoId = -((title + artistStr).hashCode())
                    trackIds.add(pseudoId)
                    val searchString = "ytsearch1:$title $artistStr"
                    
                    withContext(Dispatchers.Main) {
                        ingestionViewModel.enqueueDownload(
                            context = context,
                            url = searchString,
                            title = title,
                            artist = artistStr,
                            album = album,
                            quality = "256"
                        )
                    }
                    kotlinx.coroutines.delay(50)
                }
                
                if (trackIds.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        val repo = com.streamify.app.data.PlaylistRepository
                        val p = com.streamify.app.data.Playlist(
                            id = newPlaylistId,
                            name = playlistName,
                            description = "Imported from local file",
                            trackIds = trackIds
                        )
                        repo.addPlaylist(p)
                        android.widget.Toast.makeText(context, "Imported ${trackIds.size} tracks. Check Downloads tab.", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Failed to parse JSON playlist.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
