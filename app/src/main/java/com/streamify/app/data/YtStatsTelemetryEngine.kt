package com.streamify.app.data

import com.streamify.app.data.models.Track
import com.streamify.app.data.remote.SupabaseClient
import com.streamify.app.data.remote.TelemetryPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import org.json.JSONArray
import org.json.JSONObject

data class WrappedStats(
    val totalMinutes: Int,
    val totalTracks: Int,
    val likedSongs: Int,
    val topPlayedCount: Int,
    val averageBpm: Int,
    val personaName: String,
    val personaEmoji: String,
    val personaDescription: String,
    val topGenres: List<Pair<String, Float>>,
    val top5Tracks: List<Track> = emptyList(),
    val topArtists: List<Pair<String, Int>> = emptyList()
)

object YtStatsTelemetryEngine {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _cachedWrappedStats = MutableStateFlow<WrappedStats?>(null)
    val cachedWrappedStats: StateFlow<WrappedStats?> = _cachedWrappedStats.asStateFlow()

    private var secondsSinceLastCloudSync = 0L

    fun initFromContext(context: android.content.Context) {
        try {
            val prefs = context.getSharedPreferences("streamify_playback_telemetry", android.content.Context.MODE_PRIVATE)
            val savedJson = prefs.getString("wrapped_2026_cached_json", null)
            if (!savedJson.isNullOrBlank()) {
                val diskStats = deserializeWrappedStats(savedJson)
                if (diskStats != null) {
                    _cachedWrappedStats.value = diskStats
                }
            }
        } catch (e: Exception) {
            // Safe fallback
        }
    }

    init {
        // Hydrate from persistent disk cache on engine initialization if context is already available
        TrackRepository.appContext?.let { initFromContext(it) }
    }

    fun recordListeningSeconds(seconds: Long) {
        val context = TrackRepository.appContext ?: return
        if (seconds <= 0) return
        try {
            val prefs = context.getSharedPreferences("streamify_playback_telemetry", android.content.Context.MODE_PRIVATE)
            val currentSec = prefs.getLong("total_listened_seconds", 0L)
            val newTotalSec = currentSec + seconds
            prefs.edit().putLong("total_listened_seconds", newTotalSec).apply()

            // Non-destructive in-place RAM & Disk update (Never wipe cache to null!)
            val cur = _cachedWrappedStats.value
            if (cur != null) {
                val updated = cur.copy(totalMinutes = (newTotalSec / 60).toInt())
                _cachedWrappedStats.value = updated
                prefs.edit().putString("wrapped_2026_cached_json", serializeWrappedStats(updated)).apply()
            }

            // Periodic 60s Cloud Telemetry Auto-Sync (Cross-Device Cloud Sync)
            secondsSinceLastCloudSync += seconds
            if (secondsSinceLastCloudSync >= 60L) {
                secondsSinceLastCloudSync = 0L
                engineScope.launch(Dispatchers.IO) {
                    syncCurrentTelemetryToCloud()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun recordTrackPlay(track: Track) {
        val context = TrackRepository.appContext ?: return
        try {
            val prefs = context.getSharedPreferences("streamify_playback_telemetry", android.content.Context.MODE_PRIVATE)
            val sig = "${track.title.trim().lowercase()}_${track.artist.trim().lowercase()}"
            val countJson = prefs.getString("played_tracks_counts_map", "{}") ?: "{}"
            val countObj = JSONObject(countJson)
            val currentPlays = countObj.optInt(sig, 0) + 1
            countObj.put(sig, currentPlays)

            val totalPlays = prefs.getInt("total_plays_count", 0) + 1
            
            val metaJson = prefs.getString("played_tracks_meta_map", "{}") ?: "{}"
            val metaObj = JSONObject(metaJson)
            metaObj.put(sig, JSONObject().apply {
                put("id", track.id)
                put("title", track.title)
                put("artist", track.artist)
                put("album", track.album)
                put("durationSec", track.durationSec)
                put("filepath", track.filepath)
                put("coverArtPath", track.coverArtPath ?: "")
                put("bpm", track.bpm.toDouble())
                put("isLiked", track.isLiked)
            })

            prefs.edit()
                .putString("played_tracks_counts_map", countObj.toString())
                .putString("played_tracks_meta_map", metaObj.toString())
                .putInt("total_plays_count", totalPlays)
                .apply()

            // Trigger non-blocking cloud sync on play event
            engineScope.launch(Dispatchers.IO) {
                syncCurrentTelemetryToCloud()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun mergeCloudTelemetry(cloudSeconds: Long, cloudPlays: Int, cloudTopTrack: String = "") {
        val context = TrackRepository.appContext ?: return
        try {
            val prefs = context.getSharedPreferences("streamify_playback_telemetry", android.content.Context.MODE_PRIVATE)
            val localSeconds = prefs.getLong("total_listened_seconds", 0L)
            val localPlays = prefs.getInt("total_plays_count", 0)

            val mergedSeconds = maxOf(localSeconds, cloudSeconds)
            val mergedPlays = maxOf(localPlays, cloudPlays)

            prefs.edit()
                .putLong("total_listened_seconds", mergedSeconds)
                .putInt("total_plays_count", mergedPlays)
                .apply()

            val cur = _cachedWrappedStats.value
            if (cur != null) {
                val updated = cur.copy(
                    totalMinutes = (mergedSeconds / 60).toInt(),
                    topPlayedCount = mergedPlays
                )
                _cachedWrappedStats.value = updated
                prefs.edit().putString("wrapped_2026_cached_json", serializeWrappedStats(updated)).apply()
            }

            // Two-way sync: If local listening was ahead of cloud, push up merged stats
            if (localSeconds > cloudSeconds || localPlays > cloudPlays) {
                engineScope.launch(Dispatchers.IO) {
                    syncCurrentTelemetryToCloud()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun syncCurrentTelemetryToCloud() = withContext(Dispatchers.IO) {
        try {
            val user = SupabaseClient.currentUser.value ?: return@withContext
            val context = TrackRepository.appContext
            val prefs = context?.getSharedPreferences("streamify_playback_telemetry", android.content.Context.MODE_PRIVATE)
            val realListenedSeconds = prefs?.getLong("total_listened_seconds", 0L) ?: 0L
            val realTotalPlays = prefs?.getInt("total_plays_count", 0) ?: 0

            val topPlayedTracks = getLocalTopPlayedTracks(context, 1)
            val topTrack = topPlayedTracks.firstOrNull()?.let { "${it.title} • ${it.artist}" } ?: ""
            val libraryTracks = TrackRepository.getAllTracks()
            val finalTotalPlays = maxOf(realTotalPlays, libraryTracks.sumOf { it.playCount }, topPlayedTracks.size)

            val validBpms = (topPlayedTracks + libraryTracks).map { it.bpm }.filter { it > 40f && it < 240f }
            val weightedBpm = if (validBpms.isNotEmpty()) validBpms.average().toInt() else 124

            val persona = when {
                weightedBpm >= 130 -> "⚡ Kinetic Pulse Runner"
                weightedBpm in 110..129 -> "🌌 Harmonic Groove Weaver"
                else -> "🌙 Midnight Lofi Dreamer"
            }

            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date())

            SupabaseClient.upsertTelemetry(
                TelemetryPayload(
                    listeningSeconds = realListenedSeconds,
                    totalPlays = finalTotalPlays,
                    topTrack = topTrack,
                    favoriteGenre = user.favoriteGenre.ifBlank { "Electronic & Synthwave" },
                    bio = persona,
                    lastActiveAt = timestamp
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getLocalTopPlayedTracks(context: android.content.Context?, limit: Int): List<Track> {
        if (context == null) return emptyList()
        val result = mutableListOf<Track>()
        try {
            val prefs = context.getSharedPreferences("streamify_playback_telemetry", android.content.Context.MODE_PRIVATE)
            val countJson = prefs.getString("played_tracks_counts_map", "{}") ?: "{}"
            val metaJson = prefs.getString("played_tracks_meta_map", "{}") ?: "{}"
            val countObj = JSONObject(countJson)
            val metaObj = JSONObject(metaJson)

            val sortedKeys = countObj.keys().asSequence().sortedByDescending { countObj.optInt(it, 0) }.take(limit).toList()
            for (key in sortedKeys) {
                val tObj = metaObj.optJSONObject(key)
                if (tObj != null) {
                    result.add(
                        Track(
                            id = tObj.optInt("id", 0),
                            title = tObj.optString("title", ""),
                            artist = tObj.optString("artist", ""),
                            album = tObj.optString("album", ""),
                            durationSec = tObj.optInt("durationSec", 0),
                            filepath = tObj.optString("filepath", ""),
                            coverArtPath = tObj.optString("coverArtPath", ""),
                            bpm = tObj.optDouble("bpm", 120.0).toFloat(),
                            isLiked = tObj.optBoolean("isLiked", false)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    fun computeWrappedStats(forceRefresh: Boolean = false): Flow<WrappedStats> = flow {
        val context = TrackRepository.appContext
        val prefs = context?.getSharedPreferences("streamify_playback_telemetry", android.content.Context.MODE_PRIVATE)

        // Tier 0: Disk Storage Hydration if memory is cold
        if (_cachedWrappedStats.value == null && prefs != null) {
            val savedJson = prefs.getString("wrapped_2026_cached_json", null)
            if (!savedJson.isNullOrBlank()) {
                val diskStats = deserializeWrappedStats(savedJson)
                if (diskStats != null) {
                    _cachedWrappedStats.value = diskStats
                }
            }
        }

        // Tier 1: Instant 0ms RAM / Disk Cache Return if not forcing refresh
        _cachedWrappedStats.value?.takeIf { !forceRefresh }?.let { cached ->
            emit(cached)
            return@flow
        }

        // Tier 2: Hot Dynamic Local Matrix Computation
        val likedTracks = TrackRepository.getLikedTracks()
        val trackedTopPlays = getLocalTopPlayedTracks(context, 20)
        val nativeTopPlays = TrackRepository.getTopPlayedTracks(20)
        val libraryTracks = TrackRepository.getAllTracks()

        val topPlayedTracks = (trackedTopPlays + nativeTopPlays).distinctBy {
            "${it.title.lowercase().trim()}_${it.artist.lowercase().trim()}"
        }.sortedByDescending { it.playCount }

        val realListenedSeconds = prefs?.getLong("total_listened_seconds", 0L) ?: 0L
        val realTotalPlays = prefs?.getInt("total_plays_count", 0) ?: 0

        val totalMinutes = if (realListenedSeconds > 0) {
            (realListenedSeconds / 60).toInt()
        } else {
            val playedSeconds = topPlayedTracks.sumOf { (it.playCount.coerceAtLeast(1) * it.durationSec).toLong() }
            (playedSeconds / 60).toInt()
        }

        // Unified distinct corpus across played songs, liked songs, and library
        val unifiedCorpus = (topPlayedTracks + likedTracks + libraryTracks).distinctBy {
            "${it.title.lowercase().trim()}_${it.artist.lowercase().trim()}"
        }

        val totalTracks = unifiedCorpus.size
        val likedCount = likedTracks.size
        val topPlayedCount = maxOf(realTotalPlays, topPlayedTracks.sumOf { it.playCount }, topPlayedTracks.size)

        // Select Top 5 Songs with strict priority: Real Top Played Tracks
        val top5Songs = if (topPlayedTracks.isNotEmpty()) {
            topPlayedTracks.take(5)
        } else {
            likedTracks.take(5).ifEmpty { libraryTracks.take(5) }
        }


        // Weighted BPM calculation across user's true favorites
        val validBpms = (likedTracks.map { it.bpm } + topPlayedTracks.map { it.bpm } + unifiedCorpus.map { it.bpm })
            .filter { it in 45f..230f }
        val weightedBpm = if (validBpms.isNotEmpty()) {
            validBpms.average().toInt()
        } else {
            124
        }

        val (personaName, personaEmoji, personaDesc) = when {
            weightedBpm >= 130 -> Triple(
                "Kinetic Pulse Runner",
                "⚡",
                "High-Energy Electronic & Rock dominant acoustic profile with dynamic tempo transitions."
            )
            weightedBpm in 110..129 -> Triple(
                "Harmonic Groove Weaver",
                "🌌",
                "Groove Pop, Synthwave, and balanced harmonic frequency clusters."
            )
            else -> Triple(
                "Midnight Lofi Dreamer",
                "🌙",
                "Acoustic, Ambient, and Chill-hop dominant mellow listening signature."
            )
        }

        val genreKeywords = mapOf(
            "Electronic & Synthwave" to listOf("electronic", "synth", "dance", "club", "house", "techno", "edm"),
            "Pop & Modern Hits" to listOf("pop", "hit", "radio", "deluxe", "remix"),
            "Hip-Hop & R&B" to listOf("hip-hop", "rap", "trap", "r&b", "soul", "urban"),
            "Indie & Rock" to listOf("rock", "indie", "alternative", "punk", "metal", "guitar"),
            "Chill Lo-Fi & Ambient" to listOf("chill", "lo-fi", "lofi", "ambient", "sleep", "focus", "piano", "acoustic")
        )

        val genreScores = mutableMapOf<String, Int>()
        genreKeywords.keys.forEach { genreScores[it] = 0 }

        // Double-weight liked songs & top played tracks so user's true favorites shape the genre distribution
        val weightedList = (likedTracks + topPlayedTracks + unifiedCorpus)
        weightedList.forEach { track ->
            val metadata = "${track.title} ${track.artist} ${track.album}".lowercase()
            var matched = false
            for ((genre, keywords) in genreKeywords) {
                if (keywords.any { metadata.contains(it) }) {
                    genreScores[genre] = (genreScores[genre] ?: 0) + 1
                    matched = true
                    break
                }
            }
            if (!matched) {
                genreScores["Pop & Modern Hits"] = (genreScores["Pop & Modern Hits"] ?: 0) + 1
            }
        }

        val totalMatches = genreScores.values.sum().coerceAtLeast(1)
        val topGenres = genreScores.entries
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .take(4)
            .map { it.key to (it.value.toFloat() / totalMatches.toFloat()) }
            .ifEmpty {
                listOf(
                    "Electronic & Synthwave" to 0.45f,
                    "Pop & Modern Hits" to 0.35f,
                    "Chill Lo-Fi & Ambient" to 0.20f
                )
            }

        val topArtists = (likedTracks + topPlayedTracks + unifiedCorpus).groupBy { it.artist.ifBlank { "Unknown Artist" } }
            .mapValues { it.value.size }
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key to it.value }

        val stats = WrappedStats(
            totalMinutes = totalMinutes,
            totalTracks = totalTracks,
            likedSongs = likedCount,
            topPlayedCount = topPlayedCount,
            averageBpm = weightedBpm,
            personaName = personaName,
            personaEmoji = personaEmoji,
            personaDescription = personaDesc,
            topGenres = topGenres,
            top5Tracks = top5Songs,
            topArtists = topArtists
        )

        // Cache in RAM & Disk
        _cachedWrappedStats.value = stats
        prefs?.edit()?.putString("wrapped_2026_cached_json", serializeWrappedStats(stats))?.apply()

        // Emits immediately in <5ms without waiting for network I/O
        emit(stats)

        // Tier 3: Detached Non-Blocking Background Cloud Sync
        engineScope.launch(Dispatchers.IO) {
            try {
                syncCurrentTelemetryToCloud()
            } catch (e: Exception) {
                // Ignore offline errors
            }
        }
    }.flowOn(Dispatchers.Default)

    private fun serializeWrappedStats(stats: WrappedStats): String {
        return JSONObject().apply {
            put("totalMinutes", stats.totalMinutes)
            put("totalTracks", stats.totalTracks)
            put("likedSongs", stats.likedSongs)
            put("topPlayedCount", stats.topPlayedCount)
            put("averageBpm", stats.averageBpm)
            put("personaName", stats.personaName)
            put("personaEmoji", stats.personaEmoji)
            put("personaDescription", stats.personaDescription)

            val genreArr = JSONArray()
            stats.topGenres.forEach { (genre, pct) ->
                genreArr.put(JSONObject().apply {
                    put("genre", genre)
                    put("percentage", pct.toDouble())
                })
            }
            put("topGenres", genreArr)

            val trackArr = JSONArray()
            stats.top5Tracks.forEach { track ->
                trackArr.put(JSONObject().apply {
                    put("id", track.id)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("album", track.album)
                    put("durationSec", track.durationSec)
                    put("filepath", track.filepath)
                    put("coverArtPath", track.coverArtPath ?: "")
                    put("bpm", track.bpm.toDouble())
                    put("isLiked", track.isLiked)
                })
            }
            put("top5Tracks", trackArr)

            val artistArr = JSONArray()
            stats.topArtists.forEach { (artist, count) ->
                artistArr.put(JSONObject().apply {
                    put("artist", artist)
                    put("count", count)
                })
            }
            put("topArtists", artistArr)
        }.toString()
    }

    private fun deserializeWrappedStats(jsonStr: String): WrappedStats? {
        if (jsonStr.isBlank()) return null
        return try {
            val obj = JSONObject(jsonStr)
            val genreList = mutableListOf<Pair<String, Float>>()
            val genreArr = obj.optJSONArray("topGenres")
            if (genreArr != null) {
                for (i in 0 until genreArr.length()) {
                    val g = genreArr.getJSONObject(i)
                    genreList.add(g.getString("genre") to g.getDouble("percentage").toFloat())
                }
            }

            val trackList = mutableListOf<Track>()
            val trackArr = obj.optJSONArray("top5Tracks")
            if (trackArr != null) {
                for (i in 0 until trackArr.length()) {
                    val t = trackArr.getJSONObject(i)
                    trackList.add(
                        Track(
                            id = t.optInt("id", 0),
                            title = t.optString("title", ""),
                            artist = t.optString("artist", ""),
                            album = t.optString("album", ""),
                            durationSec = t.optInt("durationSec", 0),
                            filepath = t.optString("filepath", ""),
                            coverArtPath = t.optString("coverArtPath", "").ifBlank { null },
                            bpm = t.optDouble("bpm", 0.0).toFloat(),
                            isLiked = t.optBoolean("isLiked", true)
                        )
                    )
                }
            }

            val artistList = mutableListOf<Pair<String, Int>>()
            val artistArr = obj.optJSONArray("topArtists")
            if (artistArr != null) {
                for (i in 0 until artistArr.length()) {
                    val a = artistArr.getJSONObject(i)
                    artistList.add(a.getString("artist") to a.getInt("count"))
                }
            }

            WrappedStats(
                totalMinutes = obj.optInt("totalMinutes", 0),
                totalTracks = obj.optInt("totalTracks", 0),
                likedSongs = obj.optInt("likedSongs", 0),
                topPlayedCount = obj.optInt("topPlayedCount", 0),
                averageBpm = obj.optInt("averageBpm", 124),
                personaName = obj.optString("personaName", "Harmonic Groove Weaver"),
                personaEmoji = obj.optString("personaEmoji", "🌌"),
                personaDescription = obj.optString("personaDescription", ""),
                topGenres = genreList,
                top5Tracks = trackList,
                topArtists = artistList
            )
        } catch (e: Exception) {
            null
        }
    }
}
