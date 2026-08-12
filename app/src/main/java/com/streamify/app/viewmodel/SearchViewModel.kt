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
    val duration: Int
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

class SearchViewModel(private val repository: TrackRepository = TrackRepository()) : ViewModel() {
    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    fun search(query: String) {
        if (query.isBlank()) {
            _uiState.value = SearchUiState.Idle
            return
        }
        
        viewModelScope.launch {
            _uiState.value = SearchUiState.Loading
            
            try {
                // 1. Local Search (JNI) via TrackRepository
                val localResults = repository.searchTracks(query)
                
                // 2. Online Search (Chaquopy yt-dlp)
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
                                    duration = obj.optInt("duration", 0)
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
            } catch (e: Exception) {
                _uiState.value = SearchUiState.Error(e.message ?: "Search failed")
            }
        }
    }
}
