package com.streamify.app.data

import com.streamify.app.data.models.Track
import com.streamify.app.data.models.toTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

object TrackRepository {
    var appContext: android.content.Context? = null

    private val _allTracks = MutableStateFlow<List<Track>>(emptyList())
    val allTracks: StateFlow<List<Track>> = _allTracks.asStateFlow()
    val trackFlow: StateFlow<List<Track>> = allTracks

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
    
    suspend fun getTracksByIds(ids: List<Int>): List<Track> = withContext(Dispatchers.IO) {
        val tracks = _allTracks.value.ifEmpty { refresh() }
        val idSet = ids.toSet()
        val foundTracks = tracks.filter { it.id in idSet }.associateBy { it.id }
        ids.mapNotNull { foundTracks[it] }
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
        val trackObj = track ?: _allTracks.value.find { it.id == trackId }

        // Guarantee DB row exists before adding to user_liked_songs
        if (targetId <= 0 || _allTracks.value.none { it.id == targetId }) {
            if (trackObj != null) {
                val path = trackObj.filepath.ifBlank { "online://${(trackObj.title + trackObj.artist).hashCode()}" }
                val insertedId = NativeBridge.insertTrack(
                    filepath = path,
                    title = trackObj.title,
                    artist = trackObj.artist,
                    album = trackObj.album.ifBlank { "Streamify" },
                    durationSec = trackObj.durationSec,
                    bpm = trackObj.bpm
                ).toInt()
                if (insertedId > 0) {
                    targetId = insertedId
                    if (!trackObj.coverArtPath.isNullOrBlank()) {
                        NativeBridge.updateTrackCoverArt(insertedId, trackObj.coverArtPath!!)
                    }
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

    suspend fun upsertStreamedTrack(track: Track): Int = withContext(Dispatchers.IO) {
        val id = NativeBridge.upsertStreamedTrack(
            filepath = track.filepath,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSec = track.durationSec,
            coverArtPath = track.coverArtPath ?: "",
            lyricsPath = track.lyricsPath ?: "",
            bpm = track.bpm,
            key = track.key
        )
        refresh()
        id
    }

    suspend fun recordTrackPlay(trackId: Int): Boolean = withContext(Dispatchers.IO) {
        NativeBridge.recordTrackPlay(trackId)
    }

    suspend fun getTopPlayedTracks(limit: Int = 20): List<Track> = withContext(Dispatchers.IO) {
        val nativeTracks = NativeBridge.getTopPlayedTracks(limit)
        nativeTracks.map { it.toDomain(false) }
    }

    suspend fun updateSessionVector(trackId: Int, alpha: Float = 0.45f) = withContext(Dispatchers.IO) {
        if (trackId > 0) {
            NativeBridge.updateSessionVector(trackId, alpha)
        }
    }

    suspend fun getSessionRecommendations(limit: Int = 50): List<Track> = withContext(Dispatchers.IO) {
        val recs = NativeBridge.getSessionRecommendations(limit)
        if (recs.isEmpty()) return@withContext emptyList()
        val all = getAllTracks().associateBy { it.id }
        recs.mapNotNull { rec -> all[rec.trackId] }
    }

    suspend fun getLongTermRecommendations(userId: Int = 1, limit: Int = 50): List<Track> = withContext(Dispatchers.IO) {
        val recs = NativeBridge.getLongTermRecommendations(userId, limit)
        if (recs.isEmpty()) return@withContext emptyList()
        val all = getAllTracks().associateBy { it.id }
        recs.mapNotNull { rec -> all[rec.trackId] }
    }
}

