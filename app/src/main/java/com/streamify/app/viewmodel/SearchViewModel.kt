package com.streamify.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chaquo.python.Python
import com.streamify.app.data.TrackRepository
import com.streamify.app.data.models.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OnlineSearchResult(
    val title: String,
    val uploader: String,
    val url: String,
    val duration: Int,
    val thumbnail: String
)

sealed class SearchUiState {
    object Idle : SearchUiState()
    object Loading : SearchUiState()
    data class Success(
        val localResults: List<Track>,
        val onlineResults: List<OnlineSearchResult>
    ) : SearchUiState()
    data class Error(val message: String) : SearchUiState()
}

class SearchViewModel(private val repository: TrackRepository = TrackRepository) : ViewModel() {
    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private var searchJob: kotlinx.coroutines.Job? = null
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: android.content.Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences("search_history", android.content.Context.MODE_PRIVATE)
            val saved = prefs?.getString("history", "") ?: ""
            if (saved.isNotBlank()) {
                _searchHistory.value = saved.split(";;")
            }
        }
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

    fun search(query: String) {
        searchJob?.cancel()

        if (query.isBlank()) {
            _uiState.value = SearchUiState.Idle
            return
        }

        // Add to history after a short delay so typing doesn't spam history
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(1000)
            addQueryToHistory(query)
        }

        searchJob = viewModelScope.launch {
            // 1. Instantaneous Local Search (Sub-millisecond JNI)
            val localResults = repository.searchTracks(query)
            _uiState.value = SearchUiState.Success(
                localResults = localResults,
                onlineResults = emptyList()
            )

            // 2. Debounce & Async Online Search (Chaquopy yt-dlp)
            kotlinx.coroutines.delay(300)
            
            val onlineResults = withContext(Dispatchers.IO) {
                try {
                    val py = Python.getInstance()
                    val searchModule = py.getModule("download_engine.search")
                    val resultJson = searchModule.callAttr("search_youtube", query).toString()
                    val jsonArray = org.json.JSONArray(resultJson)
                    val results = mutableListOf<OnlineSearchResult>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        results.add(
                            OnlineSearchResult(
                                title = obj.optString("title", "Unknown"),
                                uploader = obj.optString("uploader", "Unknown"),
                                url = obj.optString("url", ""),
                                duration = obj.optInt("duration", 0),
                                thumbnail = obj.optString("thumbnail", "")
                            )
                        )
                    }
                    results
                } catch (e: Exception) {
                    e.printStackTrace()
                    emptyList<OnlineSearchResult>()
                }
            }

            _uiState.value = SearchUiState.Success(
                localResults = localResults,
                onlineResults = onlineResults
            )
        }
    }
}
