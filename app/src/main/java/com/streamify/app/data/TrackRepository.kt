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
        DatabaseInitializer.ensureInitialized()
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
        val liked = finalTracks.filter { it.isLiked }
        _likedTracks.value = liked
        rebuildIndex(finalTracks, liked)

        // Background Cloud Sync (only sync cloud tracks to avoid local path pollution)
        try {
            com.streamify.app.data.remote.SupabaseClient.syncCloudLikes(cloudTracks)
        } catch (e: Exception) {
            // Ignore offline cloud sync errors
        }

        finalTracks
    }

    private val _cadIdIndex = java.util.concurrent.ConcurrentHashMap<Long, Track>()
    private val _idIndex = java.util.concurrent.ConcurrentHashMap<Int, Track>()
    private val _videoIdIndex = java.util.concurrent.ConcurrentHashMap<String, Track>()
    private val _filepathIndex = java.util.concurrent.ConcurrentHashMap<String, Track>()
    @Volatile private var _likedIds = emptySet<Int>()
    @Volatile private var _likedFnvSet = emptySet<Long>()

    private fun rebuildIndex(tracks: List<Track>, liked: List<Track>) {
        _idIndex.clear()
        _videoIdIndex.clear()
        _filepathIndex.clear()
        _cadIdIndex.clear()

        for (track in tracks) {
            if (track.id > 0) _idIndex[track.id] = track
            if (!track.ytmVideoId.isNullOrBlank()) _videoIdIndex[track.ytmVideoId] = track
            if (track.filepath.isNotBlank()) _filepathIndex[track.filepath] = track
            val hash = FuzzyTitleMatcher.extractRootHash("${track.title}\u0001${track.artist}")
            if (hash != 0L) _cadIdIndex[hash] = track
        }

        _likedIds = liked.map { it.id }.filter { it > 0 }.toSet()
        _likedFnvSet = liked.map { FuzzyTitleMatcher.extractRootHash("${it.title}\u0001${it.artist}") }.filter { it != 0L }.toSet()
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
        val directMatches = NativeBridge.searchTracks(query).map { native ->
            native.toTrack().copy(isLiked = likedIds.contains(native.id))
        }
        if (directMatches.isNotEmpty()) {
            return@withContext directMatches
        }
        // Typo-tolerant fuzzy fallback across in-memory tracks
        val all = _allTracks.value
        val cleanQ = query.trim().lowercase()
        if (cleanQ.length >= 2) {
            val fuzzyMatches = all.mapNotNull { track ->
                val simTitle = com.streamify.app.data.FuzzyTitleMatcher.calculateSimilarity(cleanQ, track.title)
                val simArtist = com.streamify.app.data.FuzzyTitleMatcher.calculateSimilarity(cleanQ, track.artist)
                val maxSim = maxOf(simTitle, simArtist)
                if (maxSim >= 0.65) Pair(track.copy(isLiked = likedIds.contains(track.id)), maxSim) else null
            }.sortedByDescending { it.second }.map { it.first }
            return@withContext fuzzyMatches.take(20)
        }
        emptyList()
    }
    
    suspend fun getLikedTracks(userId: Int = 1): List<Track> = withContext(Dispatchers.IO) {
        val liked = NativeBridge.getLikedTracks(userId).map { it.toTrack().copy(isLiked = true) }
        _likedTracks.value = liked
        _likedIds = liked.map { it.id }.filter { it > 0 }.toSet()
        _likedFnvSet = liked.map { FuzzyTitleMatcher.extractRootHash("${it.title}\u0001${it.artist}") }.filter { it != 0L }.toSet()
        liked
    }
    
    fun isTrackLiked(track: Track): Boolean {
        if (track.id > 0 && _likedIds.contains(track.id)) {
            return true
        }
        val hash = FuzzyTitleMatcher.extractRootHash("${track.title}\u0001${track.artist}")
        if (hash != 0L && _likedFnvSet.contains(hash)) {
            return true
        }
        return _likedTracks.value.any { liked ->
            com.streamify.app.data.FuzzyTitleMatcher.isSameSongVariation(liked.title, liked.artist, track.title, track.artist)
        }
    }

    fun hydrateTrack(track: Track): Track {
        val liked = isTrackLiked(track)
        val matchedInDb = if (track.id > 0) {
            _idIndex[track.id] ?: _allTracks.value.find { it.id == track.id }
        } else {
            // O(1) Fast paths: Direct VideoId -> Filepath -> FNV-1a Root Hash
            val byVid = track.ytmVideoId?.let { _videoIdIndex[it] }
            val byPath = if (track.filepath.isNotBlank()) _filepathIndex[track.filepath] else null
            val rootHash = FuzzyTitleMatcher.extractRootHash("${track.title}\u0001${track.artist}")
            val byHash = if (rootHash != 0L) {
                val candidate = _cadIdIndex[rootHash]
                // 6-second tolerance check to prevent duration poisoning across remix variations
                if (candidate != null && (candidate.durationSec <= 0 || track.durationSec <= 0 || kotlin.math.abs(candidate.durationSec - track.durationSec) <= 6)) {
                    candidate
                } else null
            } else null

            byVid ?: byPath ?: byHash ?: _allTracks.value.find {
                it.id > 0 && (
                    it.filepath == track.filepath ||
                    (it.ytmVideoId != null && track.ytmVideoId != null && it.ytmVideoId == track.ytmVideoId) ||
                    (
                        com.streamify.app.data.FuzzyTitleMatcher.isSameSongVariation(it.title, it.artist, track.title, track.artist) &&
                        // Artist gate: title-only fuzzy matching used to merge
                        // different artists' same-titled songs into one identity.
                        com.streamify.app.data.FuzzyTitleMatcher.artistsMatch(it.artist, track.artist)
                    )
                )
            }
        }

        val resolvedVid = track.ytmVideoId ?: matchedInDb?.ytmVideoId ?: com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(track.filepath, track.coverArtPath)

        return if (matchedInDb != null) {
            track.copy(
                id = matchedInDb.id,
                coverArtPath = if (!matchedInDb.coverArtPath.isNullOrBlank()) matchedInDb.coverArtPath else track.coverArtPath,
                isLiked = liked,
                ytmVideoId = resolvedVid
            )
        } else {
            track.copy(isLiked = liked, ytmVideoId = resolvedVid)
        }
    }

    suspend fun registerStreamedTrack(
        track: Track,
        context: android.content.Context? = null,
        addToDefaultPlaylist: Boolean = false
    ): Track = withContext(Dispatchers.IO) {
        val albumName = if (track.album.isNotBlank() && !track.album.equals("Single", ignoreCase = true)) track.album else "Streamify"

        // 1. Guard against canonical overwrites: prioritize explicit ytmVideoId or extracted ID
        val existingVideoId = track.ytmVideoId?.takeIf { com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(it) != null }
            ?: com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(track.filepath, track.coverArtPath)

        var canonicalPath = if (!existingVideoId.isNullOrBlank()) {
            "https://www.youtube.com/watch?v=$existingVideoId"
        } else {
            var path = com.streamify.app.data.network.YouTubeStreamResolver.sanitizeForStorage(track.filepath, track.title, track.artist, track.coverArtPath)
            if (path.startsWith("ytsearch:") || path.isBlank()) {
                val cid = com.streamify.app.data.network.CanonicalSeedResolver.resolveToCanonicalId(track)
                if (cid.length == 11 && cid != "dQw4w9WgXcQ") {
                    path = "https://www.youtube.com/watch?v=$cid"
                }
            }
            path
        }

        val videoId = existingVideoId ?: com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(canonicalPath, track.coverArtPath)
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
        val isLikedInDb = if (savedId > 0) {
            try {
                val likedIds = NativeBridge.getLikedTracks(1).map { it.id }.toSet()
                likedIds.contains(savedId)
            } catch (e: Exception) {
                isTrackLiked(track)
            }
        } else {
            isTrackLiked(track)
        }

        val updatedTrack = track.copy(
            id = savedId,
            filepath = canonicalPath,
            coverArtPath = sanitizedCover,
            album = albumName,
            source = "online_stream",
            isLiked = isLikedInDb,
            ytmVideoId = videoId
        )

        if (savedId > 0) {
            // 1. Auto-add to "Streamify" playlist only if explicitly requested
            if (addToDefaultPlaylist) {
                try {
                    var streamifyPl = PlaylistRepository.getPlaylists().find { it.name.equals("Streamify", ignoreCase = true) }
                    if (streamifyPl == null) {
                        streamifyPl = PlaylistRepository.createPlaylist("Streamify", "Auto-saved Streamify songs")
                    }
                    PlaylistRepository.addTrackToPlaylist(streamifyPl.id, savedId)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
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

        // Targeted in-memory state patch: Zero full SQLite rescan
        val currentAll = _allTracks.value
        val existingIndex = currentAll.indexOfFirst { it.id == updatedTrack.id || (it.filepath.isNotBlank() && it.filepath == updatedTrack.filepath) }
        val newAll = if (existingIndex >= 0) {
            currentAll.toMutableList().apply { set(existingIndex, updatedTrack) }
        } else {
            currentAll + updatedTrack
        }
        _allTracks.value = newAll
        if (updatedTrack.isLiked) {
            _likedTracks.value = (_likedTracks.value.filter { it.id != updatedTrack.id } + updatedTrack)
        }

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
            val isNowLiked = NativeBridge.toggleLike(userId, targetId)
            val updated = trackObj ?: _allTracks.value.find { it.id == targetId }
            if (updated != null) {
                val patchedTrack = updated.copy(isLiked = isNowLiked)
                val cleanSig = (updated.title.trim().lowercase() + "_" + updated.artist.trim().lowercase())
                val cloudId = "trk_${kotlin.math.abs(cleanSig.hashCode())}"
                
                // Targeted in-memory state patch: Zero JNI DB thrashing
                _allTracks.value = _allTracks.value.map { if (it.id == targetId) patchedTrack else it }
                _likedTracks.value = if (isNowLiked) {
                    (_likedTracks.value.filter { it.id != targetId } + patchedTrack)
                } else {
                    _likedTracks.value.filter { it.id != targetId }
                }

                // Instant asynchronous push to Supabase Cloud
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (isNowLiked) {
                            com.streamify.app.data.remote.SupabaseClient.upsertCloudTrack(patchedTrack)
                            com.streamify.app.data.remote.SupabaseClient.addCloudLike(cloudId)
                        } else {
                            com.streamify.app.data.remote.SupabaseClient.removeCloudLike(cloudId)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            isNowLiked
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
        val existingVideoId = track.ytmVideoId?.takeIf { com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(it) != null }
            ?: com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(track.filepath, track.coverArtPath)

        var canonicalPath = if (!existingVideoId.isNullOrBlank()) {
            "https://www.youtube.com/watch?v=$existingVideoId"
        } else {
            var path = com.streamify.app.data.network.YouTubeStreamResolver.sanitizeForStorage(track.filepath, track.title, track.artist, track.coverArtPath)
            if (path.startsWith("ytsearch:") || path.isBlank()) {
                val cid = com.streamify.app.data.network.CanonicalSeedResolver.resolveToCanonicalId(track)
                if (cid.length == 11 && cid != "dQw4w9WgXcQ") {
                    path = "https://www.youtube.com/watch?v=$cid"
                }
            }
            path
        }

        val videoId = existingVideoId ?: com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(canonicalPath, track.coverArtPath)
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
        val sanitizedTrack = track.copy(id = id, filepath = canonicalPath, coverArtPath = sanitizedCover, ytmVideoId = videoId)
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

    suspend fun getEmergencyComfortTrack(): Track? = withContext(Dispatchers.IO) {
        val liked = getLikedTracks(1)
        if (liked.isNotEmpty()) {
            liked.shuffled().firstOrNull()
        } else {
            getTopPlayedTracks(10).shuffled().firstOrNull()
        }
    }

    fun hardResetState() {
        _allTracks.value = emptyList()
        _likedTracks.value = emptyList()
    }
}

