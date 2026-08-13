package com.streamify.app.data

import com.streamify.app.data.models.Track
import com.streamify.app.data.models.toTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

object TrackRepository {
    
    private val _allTracks = MutableStateFlow<List<Track>>(emptyList())
    val allTracks: StateFlow<List<Track>> = _allTracks.asStateFlow()

    private val _likedTracks = MutableStateFlow<List<Track>>(emptyList())
    val likedTracks: StateFlow<List<Track>> = _likedTracks.asStateFlow()

    suspend fun refresh(): List<Track> = withContext(Dispatchers.IO) {
        val likedIds = NativeBridge.getLikedTracks(1).map { it.id }.toSet()
        val fetchedTracks = NativeBridge.getAllTracks().map { native ->
            native.toTrack().copy(isLiked = likedIds.contains(native.id))
        }
        _allTracks.value = fetchedTracks
        _likedTracks.value = fetchedTracks.filter { it.isLiked }
        fetchedTracks
    }
    
    suspend fun getAllTracks(): List<Track> = withContext(Dispatchers.IO) {
        refresh()
    }
    
    suspend fun searchTracks(query: String): List<Track> = withContext(Dispatchers.IO) {
        val likedIds = NativeBridge.getLikedTracks(1).map { it.id }.toSet()
        NativeBridge.searchTracks(query).map { native ->
            native.toTrack().copy(isLiked = likedIds.contains(native.id))
        }
    }
    
    suspend fun getLikedTracks(userId: Int = 1): List<Track> = withContext(Dispatchers.IO) {
        val liked = NativeBridge.getLikedTracks(userId).map { it.toTrack().copy(isLiked = true) }
        _likedTracks.value = liked
        liked
    }
    
    suspend fun toggleLike(trackId: Int, userId: Int = 1, track: Track? = null): Boolean = withContext(Dispatchers.IO) {
        var targetId = trackId

        // If trackId is invalid (<=0) or missing from SQLite, auto-insert track into DB first
        if (targetId <= 0 && track != null && track.filepath.isNotBlank()) {
            val insertedId = NativeBridge.insertTrack(
                filepath = track.filepath,
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationSec = track.durationSec,
                bpm = track.bpm
            ).toInt()
            if (insertedId > 0) {
                targetId = insertedId
            }
        } else if (targetId <= 0) {
            val found = _allTracks.value.find { it.id == trackId || (track != null && it.filepath == track.filepath) }
            if (found != null && found.filepath.isNotBlank()) {
                val insertedId = NativeBridge.insertTrack(
                    filepath = found.filepath,
                    title = found.title,
                    artist = found.artist,
                    album = found.album,
                    durationSec = found.durationSec,
                    bpm = found.bpm
                ).toInt()
                if (insertedId > 0) {
                    targetId = insertedId
                }
            }
        }

        if (targetId > 0) {
            val result = NativeBridge.toggleLike(userId, targetId)
            refresh()
            result
        } else {
            false
        }
    }

    suspend fun getRecommendations(trackId: Int, recentHistory: IntArray = intArrayOf(), userId: Int = 1, limit: Int = 10): List<Track> = withContext(Dispatchers.IO) {
        val recs = NativeBridge.getRecommendations(trackId, recentHistory, userId, limit)
        if (recs.isEmpty()) return@withContext emptyList()
        val all = getAllTracks().associateBy { it.id }
        recs.mapNotNull { rec -> all[rec.trackId] }
    }

    suspend fun processAudioFile(trackId: Int, filePath: String): Int = withContext(Dispatchers.IO) {
        val result = NativeBridge.processAudioFile(trackId, filePath)
        refresh()
        result
    }

    suspend fun updateTrackCoverArt(trackId: Int, coverArtPath: String): Boolean = withContext(Dispatchers.IO) {
        val result = NativeBridge.updateTrackCoverArt(trackId, coverArtPath)
        refresh()
        result
    }

    suspend fun updateTrackMetadata(trackId: Int, title: String, artist: String, album: String): Boolean = withContext(Dispatchers.IO) {
        val result = NativeBridge.updateTrackMetadata(trackId, title, artist, album)
        refresh()
        result
    }

    suspend fun logPlayEvent(fromTrackId: Int, toTrackId: Int, userId: Int = 1) = withContext(Dispatchers.IO) {
        NativeBridge.logPlayEvent(fromTrackId, toTrackId, userId)
    }

    suspend fun logSkipEvent(fromTrackId: Int, toTrackId: Int, userId: Int = 1) = withContext(Dispatchers.IO) {
        NativeBridge.logSkipEvent(fromTrackId, toTrackId, userId)
    }
}

