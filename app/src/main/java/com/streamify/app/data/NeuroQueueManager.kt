package com.streamify.app.data

import com.streamify.app.data.models.Track
import com.streamify.app.data.network.YouTubeStreamResolver
import com.streamify.app.data.remote.PlaylistLinkScraper
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Psychological Neuro-Acoustic Queue Manager
 *
 * Coordinates Tri-Engine Candidate Sourcing (Spotify, YouTube, Local Liked) and
 * delegates sub-millisecond scoring to Rust's NeuroQueueEngine with a 5-slot cinematic micro-arc.
 */
object NeuroQueueManager {

    const val STATE_FLOW = 0       // Normal playback (>80% listened): 45% Spotify : 40% YouTube : 15% Liked
    const val STATE_DISTRESS = 1   // Fast skip (<10s): 10% Spotify : 0% YouTube : 90% Liked (Emergency Reset)
    const val STATE_HYPNOSIS = 2   // Passive dwell (3+ consecutive songs): 35% Spotify : 55% YouTube : 10% Liked
    const val STATE_IMPATIENCE = 3 // Scrubbing / Fast-Forward: 50% Spotify : 40% YouTube : 10% Liked (Energy >= 0.75)
    const val STATE_OBSESSION = 4  // Loop / Repeat track: 70% Spotify : 20% YouTube : 10% Liked (Sim >= 0.90)

    @Volatile
    private var currentBrainState = STATE_FLOW

    @Volatile
    private var consecutivePassiveCount = 0

    @Volatile
    private var lastTrackStartTimeMs = 0L

    fun onTrackStarted(track: Track) {
        lastTrackStartTimeMs = System.currentTimeMillis()
    }

    fun onTrackFinished(track: Track, listenedMs: Long, totalDurationMs: Long, isRepeat: Boolean) {
        if (isRepeat) {
            currentBrainState = STATE_OBSESSION
            consecutivePassiveCount = 0
            return
        }

        val fraction = if (totalDurationMs > 0) listenedMs.toFloat() / totalDurationMs else 0f
        if (listenedMs < 10_000L && totalDurationMs >= 30_000L) {
            // Fast Skip (<10s) -> Emergency Reset
            currentBrainState = STATE_DISTRESS
            consecutivePassiveCount = 0
        } else if (fraction >= 0.80f) {
            consecutivePassiveCount++
            currentBrainState = if (consecutivePassiveCount >= 3) STATE_HYPNOSIS else STATE_FLOW
        }
    }

    fun onUserScrubbing() {
        currentBrainState = STATE_IMPATIENCE
        consecutivePassiveCount = 0
    }

    fun getActiveBrainState(): Int = currentBrainState

    /**
     * Synthesizes and scores a 5-slot micro-arc queue using the Tri-Engine pipeline in pure Rust
     */
    suspend fun generateAdaptiveQueue(
        seedTrack: Track,
        likedTracks: List<Track>,
        targetCount: Int = 15
    ): List<Track> = withContext(Dispatchers.IO) {
        val nowSec = System.currentTimeMillis() / 1000L
        val hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        // 1. Parallel Tri-Engine Candidate Fetching
        val spotifyDeferred = async { fetchSpotifyCandidates(seedTrack) }
        val youtubeDeferred = async { fetchYouTubeCandidates(seedTrack) }

        val spotifyTracks = try { spotifyDeferred.await() } catch (_: Exception) { emptyList() }
        val youtubeTracks = try { youtubeDeferred.await() } catch (_: Exception) { emptyList() }

        // 2. Build JSON candidate payloads
        val seedJson = JSONObject().apply {
            put("id", seedTrack.id.toString())
            put("title", seedTrack.title)
            put("artist", seedTrack.artist)
            put("album", seedTrack.album)
            put("source", 0)
            put("bpm", if (seedTrack.bpm > 0f) seedTrack.bpm.toDouble() else 120.0)
            put("key", if (seedTrack.key.isNotBlank()) seedTrack.key else "8A")
            put("energy", 0.7)
            put("valence", 0.7)
            put("acoustic_sim", 1.0)
            put("user_affinity", if (seedTrack.isLiked) 1.0 else 0.5)
            put("last_played_sec", nowSec)
        }.toString()

        val candidatesArray = JSONArray()

        // Source 0: Liked Tracks (Strict Acoustic & Artist Affinity Gated - Max 5 tracks)
        val filteredLiked = likedTracks.filter { t ->
            if (t.id == seedTrack.id || (t.title.equals(seedTrack.title, ignoreCase = true) && t.artist.equals(seedTrack.artist, ignoreCase = true))) {
                false
            } else {
                val sameArtist = t.artist.equals(seedTrack.artist, ignoreCase = true) ||
                                 t.artist.contains(seedTrack.artist, ignoreCase = true) ||
                                 seedTrack.artist.contains(t.artist, ignoreCase = true)
                val bpmDiff = if (seedTrack.bpm > 0f && t.bpm > 0f) kotlin.math.abs(seedTrack.bpm - t.bpm) else 0f
                // Strict Gate: Must share artist OR have tight tempo proximity (<= 15 BPM) to prevent out-of-context vibe clashes
                sameArtist || (seedTrack.bpm > 0f && t.bpm > 0f && bpmDiff <= 15f)
            }
        }.take(8)

        filteredLiked.forEach { t ->
            val sameArtist = t.artist.equals(seedTrack.artist, ignoreCase = true) ||
                             t.artist.contains(seedTrack.artist, ignoreCase = true) ||
                             seedTrack.artist.contains(t.artist, ignoreCase = true)
            val bpmDiff = if (seedTrack.bpm > 0f && t.bpm > 0f) kotlin.math.abs(seedTrack.bpm - t.bpm) else 0f
            val computedSim = if (sameArtist) 0.95 else (0.75 - (bpmDiff / 50.0).coerceIn(0.0, 0.25))

            candidatesArray.put(JSONObject().apply {
                put("id", "liked_${t.id}")
                put("title", t.title)
                put("artist", t.artist)
                put("album", t.album)
                put("source", 0)
                put("bpm", if (t.bpm > 0f) t.bpm.toDouble() else 120.0)
                put("key", if (t.key.isNotBlank()) t.key else "8A")
                put("energy", 0.65)
                put("valence", 0.7)
                put("acoustic_sim", computedSim)
                put("user_affinity", if (sameArtist) 0.85 else 0.40)
                put("last_played_sec", 0L)
            })
        }

        // Source 1: Spotify Candidates (Vibe & Genre continuity)
        spotifyTracks.take(30).forEachIndexed { idx, t ->
            candidatesArray.put(JSONObject().apply {
                put("id", "spotify_${idx}_${t.title.hashCode()}")
                put("title", t.title)
                put("artist", t.artist)
                put("album", t.album)
                put("source", 1)
                put("bpm", if (t.bpm > 0f) t.bpm.toDouble() else (seedTrack.bpm.takeIf { it > 0f } ?: 120f).toDouble())
                put("key", if (t.key.isNotBlank()) t.key else seedTrack.key.ifBlank { "8A" })
                put("energy", 0.75)
                put("valence", 0.70)
                put("acoustic_sim", 0.95)
                put("user_affinity", 0.60)
                put("last_played_sec", 0L)
            })
        }

        // Source 2: YouTube Candidates (Discovery & Seed Continuum)
        youtubeTracks.take(30).forEachIndexed { idx, t ->
            candidatesArray.put(JSONObject().apply {
                put("id", "yt_${idx}_${t.title.hashCode()}")
                put("title", t.title)
                put("artist", t.artist)
                put("album", t.album)
                put("source", 2)
                put("bpm", if (t.bpm > 0f) t.bpm.toDouble() else (seedTrack.bpm.takeIf { it > 0f } ?: 120f).toDouble())
                put("key", if (t.key.isNotBlank()) t.key else seedTrack.key.ifBlank { "8A" })
                put("energy", 0.75)
                put("valence", 0.70)
                put("acoustic_sim", 0.88)
                put("user_affinity", 0.50)
                put("last_played_sec", 0L)
            })
        }


        // 3. Delegate to Rust NeuroQueueEngine via JNI
        val outJson = try {
            NativeBridge.rustGenerateNeuroQueue(
                seedJson = seedJson,
                candidatesJson = candidatesArray.toString(),
                brainState = currentBrainState,
                nowSec = nowSec,
                hourOfDay = hourOfDay,
                targetCount = targetCount
            )
        } catch (_: UnsatisfiedLinkError) {
            null
        } catch (_: Exception) {
            null
        }

        if (!outJson.isNullOrBlank()) {
            try {
                val array = JSONArray(outJson)
                val outList = mutableListOf<Track>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val title = obj.optString("title", "")
                    val artist = obj.optString("artist", "")
                    val album = obj.optString("album", "")
                    val bpm = obj.optDouble("bpm", 120.0).toFloat()
                    val key = obj.optString("key", "8A")

                    outList.add(
                        Track(
                            id = (title + artist).hashCode(),
                            title = title,
                            artist = artist,
                            album = album.ifBlank { "Streamify Radio" },
                            bpm = bpm,
                            key = key,
                            source = "neuro_queue"
                        )
                    )
                }
                if (outList.isNotEmpty()) {
                    return@withContext outList
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Fallback: Interleaved combination
        val fallback = mutableListOf<Track>()
        val all = (likedTracks.take(5) + spotifyTracks.take(5) + youtubeTracks.take(5))
        fallback.addAll(all.distinctBy { (it.title + it.artist).lowercase() }.take(targetCount))
        return@withContext fallback
    }

    private suspend fun fetchSpotifyCandidates(seedTrack: Track): List<Track> = withContext(Dispatchers.IO) {
        try {
            // High-speed embed query using seed track artist search
            val query = "${seedTrack.artist} ${seedTrack.title}".trim()
            val scraped = PlaylistLinkScraper.scrapePlaylist("https://open.spotify.com/search/${java.net.URLEncoder.encode(query, "UTF-8")}")
            scraped.tracks.map { st ->
                Track(
                    id = (st.title + st.artist).hashCode(),
                    title = st.title,
                    artist = st.artist,
                    durationSec = st.durationSec,
                    source = "spotify_vibe"
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchYouTubeCandidates(seedTrack: Track): List<Track> = withContext(Dispatchers.IO) {
        try {
            val vid = YouTubeStreamResolver.extractVideoId(seedTrack.filepath)
            val mixId = if (!vid.isNullOrBlank()) "RDAMVM$vid" else ""
            if (mixId.isNotBlank()) {
                val scraped = PlaylistLinkScraper.scrapePlaylist("https://music.youtube.com/playlist?list=$mixId")
                scraped.tracks.map { st ->
                    Track(
                        id = (st.title + st.artist).hashCode(),
                        title = st.title,
                        artist = st.artist,
                        durationSec = st.durationSec,
                        source = "youtube_discovery"
                    )
                }
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
