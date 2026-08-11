package com.streamify.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SearchResult(
    val title: String,
    val uploader: String,
    val url: String,
    val duration: Int
)

class SearchViewModel : ViewModel() {
    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    fun searchYouTube(query: String) {
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                try {
                    val py = Python.getInstance()
                    val searchModule = py.getModule("download_engine.search")
                    val resultJson = searchModule.callAttr("search_youtube", query).toString()
                    // Basic parsing (In real implementation use Gson/Moshi)
                    // For now returning mock mapping based on JSON logic
                    emptyList<SearchResult>()
                } catch (e: Exception) {
                    emptyList()
                }
            }
            _searchResults.value = results
        }
    }
}
