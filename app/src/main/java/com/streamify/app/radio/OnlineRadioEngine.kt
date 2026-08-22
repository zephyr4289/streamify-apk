package com.streamify.app.radio

import com.streamify.app.data.ContinuumRadioEngine
import com.streamify.app.data.FuzzyTitleMatcher
import com.streamify.app.data.models.Track
import com.streamify.app.data.network.AntiDriftScoringEngine
import com.streamify.app.data.network.CanonicalSeedResolver
import com.streamify.app.data.network.NetworkEngine
import com.streamify.app.data.network.YouTubeMusicSearchApi
import com.streamify.app.data.network.YouTubeStreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ONLINE RADIO ENGINE — pure Spotify/YouTube-Music queue construction.
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Protocol contract (replaces the legacy UniversalCandidateBroker guts):
 *
 *   S1  YouTube Music RDAMVM radio (label-curated watch-endpoint mix) —
 *       primary source, retried once on empty/failure.
 *   S2  Strict YTM search-mix — only when S1 under-delivers: official/topic
 *       uploaders only, noise-title gates (no covers/lives/remixes/slowed),
 *       duration sanity vs the seed.
 *   S3  Spotify /recommendations (artist-seeded) — bonus diversity when a
 *       Spotify OAuth token exists. Candidates carry Spotify metadata and are
 *       delivered through the verified YTM resolution cascade at play time.
 *
 * INVARIANTS:
 *   • ZERO local ingestion. The user's library, Markov graphs, cloud taste
 *     rows and offline fallbacks can never appear in a built queue.
 *   • Total provider failure returns EMPTY — never silent local substitution.
 *     Callers treat empty as "radio unavailable this cycle".
 */
object OnlineRadioEngine {

    enum class Source { YTM_RADIO, YTM_SEARCH_MIX, SPOTIFY_RECOMMENDATIONS }

    private val _lastBuildSummary = MutableStateFlow("Idle")
    val lastBuildSummary: StateFlow<String> = _lastBuildSummary.asStateFlow()

    private val NOISE_TITLE_REGEX = Regex(
        "(?i)(\\blive\\b|\\bcover\\b|\\bremix\\b|\\bslowed\\b|\\breverb\\b|\\bsped up\\b|\\b8d\\b|" +
                "\\bkaraoke\\b|\\binstrumental\\b|\\breaction\\b|\\btutorial\\b|\\blesson\\b|\\bnightcore\\b)"
    )

    suspend fun fetchCandidates(
        seedTrack: Track,
        activeQueue: List<Track> = emptyList(),
        targetCount: Int = 20
    ): List<Track> = withContext(Dispatchers.IO) {
        val excludedKeys = buildExclusionKeys(activeQueue + seedTrack)
        val pool = LinkedHashMap<String, Track>()   // dedupe key -> track (insertion ordered)

        // ── S1: YTM RDAMVM radio (primary, one retry) ──────────────────────
        val canonicalId = CanonicalSeedResolver.resolveToCanonicalId(seedTrack)
        if (canonicalId.isNotBlank()) {
            val radio = runWithRetry {
                ContinuumRadioEngine.fetchRawRadioTracks(canonicalId, seedTrack)
            }
            absorb(pool, radio, excludedKeys)
        }

        // ── S2: strict search-mix (only when primary under-delivered) ──────
        if (pool.size < targetCount) {
            val strict = fetchStrictSearchMix(seedTrack, want = targetCount - pool.size)
            absorb(pool, strict, excludedKeys)
        }

        // ── S3: Spotify recommendations (bonus diversity, token optional) ──
        if (pool.size < targetCount && seedTrack.artist.isNotBlank()) {
            val spotify = runCatching {
                fetchSpotifyRecommendations(seedTrack, want = targetCount - pool.size)
            }.getOrElse { emptyList() }
            absorb(pool, spotify, excludedKeys)
        }

        // ── Rank & cap ─────────────────────────────────────────────────────
        val ranked = AntiDriftScoringEngine.filterAndRankCandidates(
            candidates = pool.values.toList(),
            seedTrack = seedTrack,
            activeQueue = activeQueue
        ).take(targetCount)

        val sourceMix = ranked.groupBy { it.source }.entries
            .joinToString("+") { "${it.key}:${it.value.size}" }
            .ifBlank { "empty" }
        _lastBuildSummary.value = "Built ${ranked.size}/${targetCount} · $sourceMix"

        ranked
    }

    // ═══════════════ Sources ═══════════════

    /** Official-uploader-gated search mix. Never returns noise variants. */
    private suspend fun fetchStrictSearchMix(seedTrack: Track, want: Int): List<Track> {
        if (want <= 0 || seedTrack.title.isBlank()) return emptyList()
        return try {
            val query = "${seedTrack.title} ${seedTrack.artist} songs"
            val results = YouTubeMusicSearchApi.search(query, maxResults = 15)
            results.mapNotNull { item ->
                val vid = YouTubeStreamResolver.extractVideoId(item.url, item.thumbnail)
                    ?: return@mapNotNull null
                if (!isCleanCandidate(seedTrack, item.title, item.uploader, item.duration)) return@mapNotNull null
                Track(
                    id = -(vid.hashCode()),
                    title = item.title,
                    artist = item.uploader.removeSuffix(" - Topic"),
                    album = "Streamify Radio",
                    durationSec = item.duration,
                    filepath = "https://www.youtube.com/watch?v=$vid",
                    coverArtPath = item.thumbnail.ifBlank { "https://i.ytimg.com/vi/$vid/hqdefault.jpg" },
                    bpm = if (seedTrack.bpm > 0f) seedTrack.bpm else 120f,
                    source = "online_stream",
                    ytmVideoId = vid
                )
            }.take(want.coerceAtLeast(1))
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Spotify taste injection: seeds off the ARTIST (robust — track-level seeds
     * would require a Spotify-side track lookup). Returned tracks have no
     * videoId; play-time resolution flows through the verified YTM cascade.
     */
    private suspend fun fetchSpotifyRecommendations(seedTrack: Track, want: Int): List<Track> {
        if (want <= 0) return emptyList()
        val context = com.streamify.app.data.TrackRepository.appContext ?: return emptyList()
        val token = com.streamify.app.data.remote.SpotifyAuthManager(context).getAccessToken()
            ?: return emptyList()

        // 1. Resolve the seed artist to a Spotify artist id
        val artistId = spotifyArtistId(token, seedTrack.artist) ?: return emptyList()

        // 2. Artist-seeded recommendations
        val recJson = spotifyGet(
            token,
            "https://api.spotify.com/v1/recommendations?limit=${want.coerceAtMost(25)}&seed_artists=$artistId"
        ) ?: return emptyList()

        val tracksArr = recJson.optJSONArray("tracks") ?: return emptyList()
        val out = mutableListOf<Track>()
        for (i in 0 until tracksArr.length()) {
            val t = tracksArr.optJSONObject(i) ?: continue
            val name = t.optString("name", "")
            if (name.isBlank() || NOISE_TITLE_REGEX.containsMatchIn(name)) continue
            val artistName = t.optJSONArray("artists")?.optJSONObject(0)?.optString("name", "") ?: ""
            val album = t.optJSONObject("album")
            val durationMs = t.optLong("duration_ms", 0L)
            val cover = album?.optJSONArray("images")?.optJSONObject(0)?.optString("url", "") ?: ""

            out.add(
                Track(
                    id = -(("sp_" + name + artistName).hashCode()),
                    title = name,
                    artist = artistName,
                    album = album?.optString("name", "Spotify Radio")?.ifBlank { "Spotify Radio" } ?: "Spotify Radio",
                    durationSec = (durationMs / 1000L).toInt(),
                    filepath = "",                      // resolved at play time
                    coverArtPath = cover.ifBlank { null },
                    bpm = if (seedTrack.bpm > 0f) seedTrack.bpm else 120f,
                    source = "spotify_radio"
                )
            )
        }
        return out.take(want)
    }

    private fun spotifyArtistId(token: String, artistName: String): String? {
        return try {
            val encoded = java.net.URLEncoder.encode(artistName, "UTF-8")
            val json = spotifyGet(
                token,
                "https://api.spotify.com/v1/search?q=$encoded&type=artists&limit=1"
            ) ?: return null
            json.optJSONObject("artists")?.optJSONArray("items")
                ?.optJSONObject(0)?.optString("id", "")?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun spotifyGet(token: String, url: String): JSONObject? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .get()
                .build()
            NetworkEngine.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()?.let { JSONObject(it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ═══════════════ Quality gates & bookkeeping ═══════════════

    private fun isCleanCandidate(seed: Track, candidateTitle: String, uploader: String, durationSec: Int): Boolean {
        if (NOISE_TITLE_REGEX.containsMatchIn(candidateTitle)) return false
        val officialish = uploader.contains(seed.artist, ignoreCase = true) ||
                uploader.contains("Topic", ignoreCase = true) ||
                uploader.contains("VEVO", ignoreCase = true) ||
                FuzzyTitleMatcher.artistsMatch(seed.artist, uploader)
        if (!officialish) return false
        if (seed.durationSec > 60 && durationSec > 0) {
            val ratio = durationSec.toFloat() / seed.durationSec.toFloat()
            if (ratio < 0.5f || ratio > 1.9f) return false
        }
        return true
    }

    private fun dedupeKey(track: Track): String {
        val vid = track.ytmVideoId
            ?: YouTubeStreamResolver.extractVideoId(track.filepath, track.coverArtPath)
        if (vid != null) return "v:$vid"
        val rootHash = FuzzyTitleMatcher.extractRootHash(track.title)
        return if (rootHash != 0L) "h:$rootHash" else "s:${trackSig(track)}"
    }

    private fun trackSig(track: Track): String =
        "${track.title.trim().lowercase()}_${track.artist.trim().lowercase()}"

    private fun buildExclusionKeys(tracks: List<Track>): Set<String> =
        tracks.flatMap { t ->
            buildSet {
                add(dedupeKey(t))
                add("s:${trackSig(t)}")
                val h = FuzzyTitleMatcher.extractRootHash(t.title)
                if (h != 0L) add("h:$h")
            }
        }.toSet()

    private fun absorb(
        pool: LinkedHashMap<String, Track>,
        incoming: List<Track>,
        excludedKeys: Set<String>
    ) {
        for (t in incoming) {
            if (t.title.isBlank() || t.artist.isBlank()) continue
            val key = dedupeKey(t)
            if (pool.containsKey(key)) continue
            if (excludedKeys.contains(key)) continue
            pool[key] = t
        }
    }

    private suspend fun runWithRetry(maxAttempts: Int = 2, block: suspend () -> List<Track>): List<Track> {
        var last: List<Track> = emptyList()
        repeat(maxAttempts) { attempt ->
            last = try { block() } catch (_: Exception) { emptyList() }
            if (last.isNotEmpty()) return last
            if (attempt < maxAttempts - 1) delay(600L * (attempt + 1))
        }
        return last
    }
}
