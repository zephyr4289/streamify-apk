package com.streamify.app.data

import com.streamify.app.data.models.Track
import com.streamify.app.data.models.toTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TrackRepository {
    
    suspend fun getAllTracks(): List<Track> = withContext(Dispatchers.IO) {
        NativeBridge.getAllTracks().map { it.toTrack() }
    }
    
    suspend fun searchTracks(query: String): List<Track> = withContext(Dispatchers.IO) {
        NativeBridge.searchTracks(query).map { it.toTrack() }
    }
    
    suspend fun getLikedTracks(userId: Int = 1): List<Track> = withContext(Dispatchers.IO) {
        NativeBridge.getLikedTracks(userId).map { it.toTrack().copy(isLiked = true) }
    }
    
    suspend fun toggleLike(trackId: Int, userId: Int = 1): Boolean = withContext(Dispatchers.IO) {
        NativeBridge.toggleLike(userId, trackId)
    }

    suspend fun getRecommendations(trackId: Int, recentHistory: IntArray = intArrayOf(), userId: Int = 1, limit: Int = 10) = withContext(Dispatchers.IO) {
        NativeBridge.getRecommendations(trackId, recentHistory, userId, limit)
    }

    suspend fun logPlayEvent(fromTrackId: Int, toTrackId: Int, userId: Int = 1) = withContext(Dispatchers.IO) {
        NativeBridge.logPlayEvent(fromTrackId, toTrackId, userId)
    }

    suspend fun logSkipEvent(fromTrackId: Int, toTrackId: Int, userId: Int = 1) = withContext(Dispatchers.IO) {
        NativeBridge.logSkipEvent(fromTrackId, toTrackId, userId)
    }
}
