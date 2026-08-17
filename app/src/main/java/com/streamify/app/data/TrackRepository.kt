package com.streamify.app.data

import com.streamify.app.data.models.Track
import com.streamify.app.data.models.toTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object TrackRepository {
    var appContext: android.content.Context? = null

    private val _allTracks = MutableStateFlow<List<Track>>(emptyList())
    val allTracks: StateFlow<List<Track>> = _allTracks.asStateFlow()
    val trackFlow: StateFlow<List<Track>> = allTracks

    private val _localTracks = MutableStateFlow<List<Track>>(emptyList())
    val localTracks: StateFlow<List<Track>> = _localTracks.asStateFlow()

    private val _likedTracks = MutableStateFlow<List<Track>>(emptyList())
    val likedTracks: StateFlow<List<Track>> = _likedTracks.asStateFlow()

    suspend fun refresh(): List<Track> = withContext(Dispatchers.IO) {
        val prefs = appContext?.getSharedPreferences("audio_settings", android.content.Context.MODE_PRIVATE)
        val isLocalAudioEnabled = prefs?.getBoolean("enable_local_audio", false) ?: false

        val likedIds = NativeBridge.getLikedTracks(1).map { it.id }.toSet()
        val allNative = NativeBridge.getAllTracks().map { native ->
            native.toTrack().copy(isLiked = likedIds.contains(native.id))
        }

        val (localOnly, cloudTracks) = allNative.partition { it.source == "local" }
        _localTracks.value = localOnly

        val finalTracks = if (isLocalAudioEnabled) allNative else cloudTracks
        _allTracks.value = finalTracks
        _likedTracks.value = finalTracks.filter { it.isLiked }

        // Background Cloud Sync (only sync cloud tracks to avoid local path pollution)
        try {
            com.streamify.app.data.remote.SupabaseClient.syncCloudLikes(cloudTracks)
        } catch (e: Exception) {
            // Ignore offline cloud sync errors
        }

        finalTracks
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
    
    suspend fun registerStreamedTrack(track: Track, context: android.content.Context? = null): Track = withContext(Dispatchers.IO) {
        val albumName = if (track.album.isNotBlank() && !track.album.equals("Single", ignoreCase = true)) track.album else "Streamify"
        val canonicalPath = com.streamify.app.data.network.YouTubeStreamResolver.sanitizeForStorage(track.filepath, track.title, track.artist)
        val videoId = com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(canonicalPath, track.coverArtPath)
        val sanitizedCover = com.streamify.app.data.network.YouTubeStreamResolver.sanitizeCoverUrl(track.coverArtPath, videoId)

        val validId = NativeBridge.upsertStreamedTrack(
            filepath = canonicalPath,
            title = track.title,
            artist = track.artist,
            album = albumName,
            durationSec = track.durationSec,
            coverArtPath = sanitizedCover ?: "",
            lyricsPath = track.lyricsPath ?: "",
            bpm = track.bpm,
            key = track.key
        )

        val savedId = if (validId > 0) validId else track.id
        val updatedTrack = track.copy(
            id = savedId,
            filepath = canonicalPath,
            coverArtPath = sanitizedCover,
            album = albumName,
            source = "online_stream"
        )

        if (savedId > 0) {
            // 1. Auto-add to "Streamify" playlist
            try {
                var streamifyPl = PlaylistRepository.getPlaylists().find { it.name.equals("Streamify", ignoreCase = true) }
                if (streamifyPl == null) {
                    streamifyPl = PlaylistRepository.createPlaylist("Streamify", "Auto-saved Streamify songs")
                }
                PlaylistRepository.addTrackToPlaylist(streamifyPl.id, savedId)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 2. High-speed zero-audio text embedding for instant AI indexing
            val ctx = context ?: appContext
            if (ctx != null) {
                try {
                    val textEngine = com.streamify.app.service.TextEmbeddingEngine.getInstance(ctx)
                    val embedding = textEngine.generateEmbedding("${track.artist} - ${track.title} [${albumName}]")
                    NativeBridge.updateTrackEmbedding(savedId, embedding)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 3. Instant Push to Supabase Cloud
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    com.streamify.app.data.remote.SupabaseClient.upsertCloudTrack(updatedTrack)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 4. Enqueue to OnlineTrackProcessor for autonomous background native C++ DSP processing
            if (updatedTrack.bpm <= 0f || !updatedTrack.isProcessed) {
                com.streamify.app.service.OnlineTrackProcessor.enqueue(updatedTrack, ctx)
            }
        }

        refresh()
        updatedTrack
    }

    suspend fun toggleLike(trackId: Int, userId: Int = 1, track: Track? = null): Boolean = withContext(Dispatchers.IO) {
        var targetId = trackId
        val trackObj = track ?: _allTracks.value.find { it.id == trackId }

        // Guarantee DB row exists before adding to user_liked_songs
        if (targetId <= 0 || _allTracks.value.none { it.id == targetId }) {
            if (trackObj != null) {
                val registered = registerStreamedTrack(trackObj)
                targetId = registered.id
            }
        }

        if (targetId > 0) {
            val result = NativeBridge.toggleLike(userId, targetId)
            val updated = trackObj ?: _allTracks.value.find { it.id == targetId }
            if (updated != null) {
                val cleanSig = (updated.title.trim().lowercase() + "_" + updated.artist.trim().lowercase())
                val cloudId = "trk_${kotlin.math.abs(cleanSig.hashCode())}"
                
                // Instant asynchronous push to Supabase Cloud
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (result) {
                            com.streamify.app.data.remote.SupabaseClient.upsertCloudTrack(updated)
                            com.streamify.app.data.remote.SupabaseClient.addCloudLike(cloudId)
                        } else {
                            com.streamify.app.data.remote.SupabaseClient.removeCloudLike(cloudId)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
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

    suspend fun updateTrack(track: Track): Boolean = updateTrackMetadata(track.id, track.title, track.artist, track.album)

    suspend fun logPlayEvent(fromTrackId: Int, toTrackId: Int, userId: Int = 1) = withContext(Dispatchers.IO) {
        NativeBridge.logPlayEvent(fromTrackId, toTrackId, userId)
    }

    suspend fun logSkipEvent(fromTrackId: Int, toTrackId: Int, userId: Int = 1) = withContext(Dispatchers.IO) {
        NativeBridge.logSkipEvent(fromTrackId, toTrackId, userId)
    }

    suspend fun upsertStreamedTrack(track: Track): Int = withContext(Dispatchers.IO) {
        val canonicalPath = com.streamify.app.data.network.YouTubeStreamResolver.sanitizeForStorage(track.filepath, track.title, track.artist)
        val videoId = com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(canonicalPath, track.coverArtPath)
        val sanitizedCover = com.streamify.app.data.network.YouTubeStreamResolver.sanitizeCoverUrl(track.coverArtPath, videoId)

        val id = NativeBridge.upsertStreamedTrack(
            filepath = canonicalPath,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSec = track.durationSec,
            coverArtPath = sanitizedCover ?: "",
            lyricsPath = track.lyricsPath ?: "",
            bpm = track.bpm,
            key = track.key
        )
        val sanitizedTrack = track.copy(id = id, filepath = canonicalPath, coverArtPath = sanitizedCover)
        // Also mirror to Supabase cloud catalog asynchronously
        try {
            com.streamify.app.data.remote.SupabaseClient.upsertCloudTrack(sanitizedTrack)
        } catch (e: Exception) {
            // Ignore
        }
        refresh()
        id
    }

    suspend fun recordTrackPlay(trackId: Int): Boolean = withContext(Dispatchers.IO) {
        NativeBridge.recordTrackPlay(trackId)
    }

    suspend fun getTopPlayedTracks(limit: Int = 20): List<Track> = withContext(Dispatchers.IO) {
        val nativeTracks = NativeBridge.getTopPlayedTracks(limit)
        nativeTracks.map { it.toTrack() }
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

    suspend fun logEngagementEvent(trackId: Int, durationSec: Int, completionRatio: Float, hourOfDay: Int): Boolean = withContext(Dispatchers.IO) {
        if (trackId > 0) {
            NativeBridge.logEngagementEvent(trackId, durationSec, completionRatio, hourOfDay)
        } else false
    }

    suspend fun getCircadianRecommendations(hourOfDay: Int, limit: Int = 20): List<Track> = withContext(Dispatchers.IO) {
        val recs = NativeBridge.getCircadianRecommendations(hourOfDay, limit)
        if (recs.isEmpty()) return@withContext emptyList()
        val all = getAllTracks().associateBy { it.id }
        recs.mapNotNull { rec -> all[rec.trackId] }
    }

    fun getCircadianSlot(hourOfDay: Int): String {
        return NativeBridge.getCircadianSlot(hourOfDay)
    }

    suspend fun logHookTelemetry(trackId: Int, favoriteSeekMs: Long, lyricsDwellSec: Int, volumeFlare: Int): Boolean = withContext(Dispatchers.IO) {
        if (trackId > 0) {
            NativeBridge.logHookTelemetry(trackId, favoriteSeekMs, lyricsDwellSec, volumeFlare)
        } else false
    }

    suspend fun recordTrackCooccurrence(trackAId: Int, trackBId: Int): Boolean = withContext(Dispatchers.IO) {
        if (trackAId > 0 && trackBId > 0 && trackAId != trackBId) {
            NativeBridge.recordTrackCooccurrence(trackAId, trackBId)
        } else false
    }

    suspend fun getFavoriteSeekMs(trackId: Int): Long = withContext(Dispatchers.IO) {
        if (trackId > 0) NativeBridge.getFavoriteSeekMs(trackId) else 0L
    }

    suspend fun getCooccurrenceRecommendations(trackId: Int, limit: Int = 10): List<Track> = withContext(Dispatchers.IO) {
        val ids = NativeBridge.getCooccurrenceRecommendations(trackId, limit)
        if (ids.isEmpty()) return@withContext emptyList()
        val all = getAllTracks().associateBy { it.id }
        ids.toList().mapNotNull { all[it] }
    }

    suspend fun getCloudSongRadio(seedTrack: Track, limit: Int = 25): List<Track> = withContext(Dispatchers.IO) {
        val candidates = com.streamify.app.data.network.CandidateAggregator.aggregateCandidates(seedTrack, limit = 100)
        com.streamify.app.data.ReRanker.scoreAndRankCandidates(
            candidates = candidates,
            seedTrack = seedTrack,
            limit = limit
        )
    }

    fun hardResetState() {
        _allTracks.value = emptyList()
        _likedTracks.value = emptyList()
    }
}

