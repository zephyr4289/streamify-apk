package com.streamify.app.data

import com.streamify.app.data.models.Track
import com.streamify.app.data.remote.SupabaseClient
import com.streamify.app.data.remote.TelemetryPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

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

    fun recordListeningSeconds(seconds: Long) {
        val context = TrackRepository.appContext ?: return
        if (seconds <= 0) return
        try {
            val prefs = context.getSharedPreferences("streamify_playback_telemetry", android.content.Context.MODE_PRIVATE)
            val currentSec = prefs.getLong("total_listened_seconds", 0L)
            prefs.edit().putLong("total_listened_seconds", currentSec + seconds).apply()
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

            val topPlayedTracks = TrackRepository.getTopPlayedTracks(1)
            val topTrack = topPlayedTracks.firstOrNull()?.let { "${it.title} • ${it.artist}" } ?: ""
            val libraryTracks = TrackRepository.getAllTracks()
            val totalPlays = libraryTracks.sumOf { it.playCount }.coerceAtLeast(topPlayedTracks.size)

            val validBpms = libraryTracks.map { it.bpm }.filter { it > 40f && it < 240f }
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
                    totalPlays = totalPlays,
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

    fun computeWrappedStats(): Flow<WrappedStats> = flow {
        // 1. Off-Main-Thread Real Data Gathering
        val libraryTracks = TrackRepository.getAllTracks()
        val likedTracks = TrackRepository.getLikedTracks()
        val topPlayedTracks = TrackRepository.getTopPlayedTracks(10)

        // 2. Real Telemetry Calculations: Read actual accumulated playback time
        val context = TrackRepository.appContext
        val prefs = context?.getSharedPreferences("streamify_playback_telemetry", android.content.Context.MODE_PRIVATE)
        val realListenedSeconds = prefs?.getLong("total_listened_seconds", 0L) ?: 0L

        val totalMinutes = if (realListenedSeconds > 0) {
            (realListenedSeconds / 60).toInt()
        } else {
            // Count duration of actually played tracks
            val playedSeconds = topPlayedTracks.sumOf { it.durationSec.toLong() }
            if (playedSeconds > 0) (playedSeconds / 60).toInt() else 0
        }

        val topPlayedCount = topPlayedTracks.size

        // 3. Mathematical BPM Persona Analysis
        val validBpms = libraryTracks.map { it.bpm }.filter { it > 40f && it < 240f }
        val weightedBpm = if (validBpms.isNotEmpty()) {
            validBpms.average().toInt()
        } else {
            124 // Default balanced tempo
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

        // 4. Real Genre Distribution Analysis
        val genreKeywords = mapOf(
            "Electronic & Synthwave" to listOf("electronic", "synth", "dance", "club", "house", "techno", "edm"),
            "Pop & Modern Hits" to listOf("pop", "hit", "radio", "deluxe", "remix"),
            "Hip-Hop & R&B" to listOf("hip-hop", "rap", "trap", "r&b", "soul", "urban"),
            "Indie & Rock" to listOf("rock", "indie", "alternative", "punk", "metal", "guitar"),
            "Chill Lo-Fi & Ambient" to listOf("chill", "lo-fi", "lofi", "ambient", "sleep", "focus", "piano", "acoustic")
        )

        val genreScores = mutableMapOf<String, Int>()
        genreKeywords.keys.forEach { genreScores[it] = 0 }

        libraryTracks.forEach { track ->
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

        // 5. Top 5 Songs & Top Artists Discovery
        val top5Songs = if (topPlayedTracks.isNotEmpty()) {
            topPlayedTracks.take(5)
        } else {
            libraryTracks.take(5)
        }

        val topArtists = libraryTracks.groupBy { it.artist.ifBlank { "Unknown Artist" } }
            .mapValues { it.value.size }
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key to it.value }

        val stats = WrappedStats(
            totalMinutes = totalMinutes,
            totalTracks = libraryTracks.size,
            likedSongs = likedTracks.size,
            topPlayedCount = topPlayedCount,
            averageBpm = weightedBpm,
            personaName = personaName,
            personaEmoji = personaEmoji,
            personaDescription = personaDesc,
            topGenres = topGenres,
            top5Tracks = top5Songs,
            topArtists = topArtists
        )

        // 6. Two-Way Supabase Cloud Telemetry Sync
        try {
            syncCurrentTelemetryToCloud()
        } catch (e: Exception) {
            // Ignore offline errors
        }

        emit(stats)
    }.flowOn(Dispatchers.Default)
}
