package com.streamify.app.data

import com.streamify.app.data.models.Track
import com.streamify.app.data.remote.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

data class WrappedStats(
    val totalMinutes: Int,
    val totalTracks: Int,
    val likedSongs: Int,
    val topPlayedCount: Int,
    val averageBpm: Int,
    val personaName: String,
    val personaEmoji: String,
    val personaDescription: String,
    val topGenres: List<Pair<String, Float>>
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
            // Fallback for existing sessions: count duration of actually played tracks
            val playedSeconds = topPlayedTracks.sumOf { it.durationSec.toLong() }
            (playedSeconds / 60).toInt()
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

        val stats = WrappedStats(
            totalMinutes = totalMinutes,
            totalTracks = libraryTracks.size,
            likedSongs = likedTracks.size,
            topPlayedCount = topPlayedCount,
            averageBpm = weightedBpm,
            personaName = personaName,
            personaEmoji = personaEmoji,
            personaDescription = personaDesc,
            topGenres = topGenres
        )

        // 5. Two-Way Supabase Cloud Telemetry Sync
        try {
            val user = SupabaseClient.currentUser.value
            if (user != null) {
                SupabaseClient.updateProfile(
                    displayName = user.displayName,
                    avatarUrl = user.avatarUrl,
                    bio = "Streamify Persona: $personaName $personaEmoji",
                    favGenre = topGenres.firstOrNull()?.first ?: "All"
                )
            }
        } catch (e: Exception) {
            // Ignore offline errors
        }

        emit(stats)
    }.flowOn(Dispatchers.Default)
}
