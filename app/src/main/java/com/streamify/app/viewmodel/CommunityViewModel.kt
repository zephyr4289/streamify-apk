package com.streamify.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamify.app.data.models.Track
import com.streamify.app.data.remote.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CommunityUiState(
    val communityPlaylists: List<CommunityPlaylist> = emptyList(),
    val friendsActivity: List<FriendActivity> = emptyList(),
    val activeBroadcasts: List<String> = emptyList(),
    val currentTrackComments: List<TrackComment> = emptyList(),
    val isCommentsLoading: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
)

class CommunityViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(CommunityUiState())
    val uiState: StateFlow<CommunityUiState> = _uiState.asStateFlow()

    init {
        loadCommunityFeed()
    }

    fun loadCommunityFeed() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val playlists = SupabaseClient.fetchCommunityPlaylists(limit = 15)
            val friends = SupabaseClient.fetchFriendsActivity()
            val broadcasts = SupabaseClient.fetchActiveBroadcasts()

            _uiState.value = _uiState.value.copy(
                communityPlaylists = playlists,
                friendsActivity = friends,
                activeBroadcasts = broadcasts,
                isLoading = false
            )
        }
    }

    fun loadCommentsForTrack(track: Track?) {
        if (track == null) {
            _uiState.value = _uiState.value.copy(currentTrackComments = emptyList())
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCommentsLoading = true)
            val trackCloudId = "trk_${(track.title + track.artist).hashCode()}"
            val comments = SupabaseClient.fetchTrackComments(trackCloudId)
            _uiState.value = _uiState.value.copy(
                currentTrackComments = comments,
                isCommentsLoading = false
            )
        }
    }

    fun postComment(track: Track?, currentPositionMs: Long, commentText: String, onComplete: (Boolean) -> Unit) {
        if (track == null || commentText.isBlank()) return

        viewModelScope.launch {
            val trackCloudId = "trk_${(track.title + track.artist).hashCode()}"
            val result = SupabaseClient.postTrackComment(trackCloudId, currentPositionMs, commentText.trim())
            result.onSuccess { newComment ->
                val updated = (_uiState.value.currentTrackComments + newComment).sortedBy { it.timestampMs }
                _uiState.value = _uiState.value.copy(currentTrackComments = updated)
                onComplete(true)
            }.onFailure {
                onComplete(false)
            }
        }
    }

    fun submitLyrics(track: Track?, lrcText: String, onComplete: (Boolean) -> Unit) {
        if (track == null || lrcText.isBlank()) return

        viewModelScope.launch {
            val trackCloudId = "trk_${(track.title + track.artist).hashCode()}"
            val result = SupabaseClient.submitSyncedLyrics(trackCloudId, lrcText)
            onComplete(result.isSuccess)
        }
    }
}
