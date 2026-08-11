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
}
