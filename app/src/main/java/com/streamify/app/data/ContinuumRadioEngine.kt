package com.streamify.app.data

import com.streamify.app.data.models.Track
import com.streamify.app.data.network.NetworkEngine
import com.streamify.app.data.network.YouTubeMusicSearchApi
import com.streamify.app.data.network.YouTubeStreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.HashSet
import java.util.zip.GZIPInputStream

val Track.videoId: String
    get() = YouTubeStreamResolver.extractVideoId(filepath, coverArtPath) ?: id.toString()

data class RadioContext(
    val videoId: String,
    val playlistId: String? = null,
    var continuationToken: String? = null
)

object ContinuumRadioEngine {

    private const val INNERTUBE_NEXT_URL = "https://music.youtube.com/youtubei/v1/next"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

    // O(1) De-duplication Sets & Dynamic Diversity Pools
    private val playedVideoIds = Collections.synchronizedSet(HashSet<String>())
    private val playedRootHashes = Collections.synchronizedSet(HashSet<Long>())
    private val playedArtistCounts = Collections.synchronizedMap(HashMap<String, Int>())

    private val _discoveredQueue = MutableStateFlow<List<Track>>(emptyList())
    val discoveredQueue: StateFlow<List<Track>> = _discoveredQueue.asStateFlow()

    private var currentRadioContext: RadioContext? = null

    private val _isFetching = MutableStateFlow(false)
    val isFetching: StateFlow<Boolean> = _isFetching.asStateFlow()

    fun clearRadio() {
        playedVideoIds.clear()
        playedRootHashes.clear()
        playedArtistCounts.clear()
        _discoveredQueue.value = emptyList()
        currentRadioContext = null
    }

    /**
     * Raw Innertube RDAMVM radio crawler with multi-tier fallback used by UniversalCandidateBroker.
     */
    suspend fun fetchRawRadioTracks(canonicalVideoId: String, seedTrack: Track? = null): List<Track> = withContext(Dispatchers.IO) {
        val (candidates, _) = executeNextRequest(canonicalVideoId, null, seedTrack)
        if (candidates.isNotEmpty()) return@withContext candidates

        // Multi-tier fallback 3: If RDAMVM returns empty, query YouTube Music Search Mix
        if (seedTrack != null && seedTrack.title.isNotBlank()) {
            try {
                val query = "${seedTrack.title} ${seedTrack.artist} radio".trim()
                val searchResults = YouTubeMusicSearchApi.search(query, maxResults = 20)
                val dynamicBpm = if (seedTrack.bpm > 0f) seedTrack.bpm else 120f
                return@withContext searchResults.mapNotNull { item ->
                    val vid = YouTubeStreamResolver.extractVideoId(item.url, item.thumbnail) ?: return@mapNotNull null
                    Track(
                        id = -(vid.hashCode()),
                        title = item.title,
                        artist = item.uploader,
                        album = "Streamify Radio",
                        durationSec = item.duration,
                        filepath = "https://www.youtube.com/watch?v=$vid",
                        coverArtPath = item.thumbnail.ifBlank { "https://i.ytimg.com/vi/$vid/hqdefault.jpg" },
                        bpm = dynamicBpm,
                        key = "",
                        lyricsPath = null,
                        source = "online_stream"
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    /**
     * Step 1: Initialize radio from search tap or autoplay
     */
    suspend fun startRadio(seedTrack: Track): List<Track> = withContext(Dispatchers.IO) {
        clearRadio()
        val vId = com.streamify.app.data.network.CanonicalSeedResolver.resolveToCanonicalId(seedTrack)
        if (vId.length != 11) {
            // No verified canonical seed → refuse to build a radio around an
            // arbitrary/different recording.
            return@withContext emptyList()
        }
        playedVideoIds.add(vId)
        val seedHash = FuzzyTitleMatcher.extractRootHash(seedTrack.title)
        if (seedHash != 0L) playedRootHashes.add(seedHash)
        if (seedTrack.artist.isNotBlank()) {
            playedArtistCounts[seedTrack.artist.lowercase().trim()] = 1
        }
        currentRadioContext = RadioContext(videoId = vId, playlistId = "RDAMVM$vId")

        // Fetch initial radio batch (usually 15-25 tracks)
        fetchNextRadioBatch(seedTrack)
    }

    /**
     * Step 2: Autonomous Pagination & De-duplication
     */
    suspend fun fetchNextRadioBatch(seedTrack: Track? = null, limit: Int = 20): List<Track> = withContext(Dispatchers.IO) {
        if (currentRadioContext == null && seedTrack != null) {
            return@withContext startRadio(seedTrack)
        }
        val context = currentRadioContext ?: return@withContext emptyList()
        _isFetching.value = true

        try {
            val (candidates, nextContinuation) = executeNextRequest(context.videoId, context.continuationToken)
            // Update continuation token for infinite pagination
            context.continuationToken = nextContinuation

            val uniqueTracks = mutableListOf<Track>()
            for (track in candidates) {
                val trackVId = track.videoId
                val rootHash = FuzzyTitleMatcher.extractRootHash(track.title)
                val artistKey = track.artist.lowercase().trim()
                val artistCount = playedArtistCounts[artistKey] ?: 0

                // 1. Root Hash collision or Jaccard fuzzy similarity check
                val isVariation = (rootHash != 0L && playedRootHashes.contains(rootHash)) ||
                        uniqueTracks.any { FuzzyTitleMatcher.isSameSongVariation(it.title, it.artist, track.title, track.artist) }

                // 2. Artist Saturation Cap (max 2 songs per artist in the upcoming queue)
                if (!playedVideoIds.contains(trackVId) && !isVariation && artistCount < 2) {
                    playedVideoIds.add(trackVId)
                    if (rootHash != 0L) playedRootHashes.add(rootHash)
                    playedArtistCounts[artistKey] = artistCount + 1
                    uniqueTracks.add(track)
                }
            }

            // Fallback: If pagination returned no unique tracks, query Innertube search mix
            if (uniqueTracks.isEmpty()) {
                val queryTerm = if (seedTrack != null) "${seedTrack.title} ${seedTrack.artist} radio" else "${context.videoId} mix"
                val fallbackResults = YouTubeMusicSearchApi.search(queryTerm, maxResults = 15)
                for (item in fallbackResults) {
                    val fVId = YouTubeStreamResolver.extractVideoId(item.url) ?: item.url
                    val fRootHash = FuzzyTitleMatcher.extractRootHash(item.title)
                    val fArtistKey = item.uploader.lowercase().trim()
                    val fArtistCount = playedArtistCounts[fArtistKey] ?: 0

                    val isVariation = (fRootHash != 0L && playedRootHashes.contains(fRootHash)) ||
                            uniqueTracks.any { FuzzyTitleMatcher.isSameSongVariation(it.title, it.artist, item.title, item.uploader) }

                    if (!playedVideoIds.contains(fVId) && !isVariation && fArtistCount < 2) {
                        playedVideoIds.add(fVId)
                        if (fRootHash != 0L) playedRootHashes.add(fRootHash)
                        playedArtistCounts[fArtistKey] = fArtistCount + 1
                        uniqueTracks.add(
                            Track(
                                id = -(item.url.hashCode()),
                                title = item.title,
                                artist = item.uploader,
                                album = "Streamify Radio",
                                durationSec = item.duration,
                                filepath = if (fVId.length == 11) "https://www.youtube.com/watch?v=$fVId" else item.url,
                                coverArtPath = item.thumbnail.takeIf { it.isNotBlank() },
                                bpm = 120f,
                                key = "",
                                lyricsPath = null,
                                source = "online_stream"
                            )
                        )
                    }
                }
            }

            // Append to live discovered queue
            _discoveredQueue.value = _discoveredQueue.value + uniqueTracks
            uniqueTracks
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        } finally {
            _isFetching.value = false
        }
    }

    /**
     * Step 3: The Predictive Pre-Fetch Trigger (Called by PlayerViewModel)
     */
    suspend fun ensureQueueDepth(currentQueueSize: Int, seedTrack: Track? = null): List<Track> {
        if (currentRadioContext == null && seedTrack != null) {
            return startRadio(seedTrack)
        }
        if (currentQueueSize <= 3) {
            // Queue is running low, fetch the next page silently in background
            return fetchNextRadioBatch(seedTrack)
        }
        return emptyList()
    }

    /**
     * Stateless single radio page fetch — exposes the /next continuation chain
     * to OnlineRadioEngine so ultra-long sessions can chain infinite batches
     * without touching this object's UI-facing radio context.
     */
    suspend fun fetchRadioPage(
        videoId: String,
        continuationToken: String?,
        seedTrack: Track? = null
    ): Pair<List<Track>, String?> = executeNextRequest(videoId, continuationToken, seedTrack)

    private fun executeNextRequest(videoId: String, continuationToken: String?, seedTrack: Track? = null): Pair<List<Track>, String?> {
        // Tier 1: Try ANDROID_MUSIC client
        val androidResult = executeInnertubeNextCall(
            clientName = "ANDROID_MUSIC",
            clientVersion = "6.42.52",
            videoId = videoId,
            continuationToken = continuationToken,
            seedTrack = seedTrack
        )
        if (androidResult.first.isNotEmpty()) {
            return androidResult
        }

        // Tier 2: Fallback to WEB_REMIX client
        return executeInnertubeNextCall(
            clientName = "WEB_REMIX",
            clientVersion = "1.20230515.01.00",
            videoId = videoId,
            continuationToken = continuationToken,
            seedTrack = seedTrack
        )
    }

    private fun executeInnertubeNextCall(
        clientName: String,
        clientVersion: String,
        videoId: String,
        continuationToken: String?,
        seedTrack: Track? = null
    ): Pair<List<Track>, String?> {
        try {
            val isAndroid = clientName == "ANDROID_MUSIC"
            val requestJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", clientName)
                        put("clientVersion", clientVersion)
                        if (isAndroid) put("androidSdkVersion", 34)
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                if (!continuationToken.isNullOrBlank()) {
                    put("continuation", continuationToken)
                } else {
                    put("videoId", videoId)
                    put("playlistId", "RDAMVM$videoId")
                    put("isAudioOnly", true)
                }
            }

            val requestBuilder = Request.Builder()
                .url(INNERTUBE_NEXT_URL)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("User-Agent", if (isAndroid) "com.google.android.apps.youtube.music/6.42.52 (Linux; U; Android 14; en_US) gzip" else USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "gzip, deflate")
            
            if (!isAndroid) {
                requestBuilder.header("Origin", "https://music.youtube.com")
                requestBuilder.header("Referer", "https://music.youtube.com/")
            }

            val request = requestBuilder.post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE)).build()

            NetworkEngine.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return Pair(emptyList(), null)

                val body = response.body ?: return Pair(emptyList(), null)
                val encoding = response.header("Content-Encoding", "")

                val responseBody = if ("gzip".equals(encoding, ignoreCase = true)) {
                    GZIPInputStream(body.byteStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
                } else {
                    body.string()
                }

                val root = JSONObject(responseBody)
                return parseNextResponse(root, seedTrack)
            }
        } catch (e: Exception) {
            return Pair(emptyList(), null)
        }
    }

    private fun parseNextResponse(root: JSONObject, seedTrack: Track? = null): Pair<List<Track>, String?> {
        val tracks = mutableListOf<Track>()
        var nextContinuation: String? = null
        val dynamicBpm = if (seedTrack != null && seedTrack.bpm > 0f) seedTrack.bpm else 120f

        try {
            val candidateNodes = mutableListOf<JSONObject>()
            findJsonObjects(root, "playlistPanelVideoRenderer", candidateNodes)

            for (node in candidateNodes) {
                val videoId = node.optString("videoId", "")
                if (videoId.isBlank()) continue

                val titleObj = node.optJSONObject("title")
                val title = titleObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                    ?: titleObj?.optString("simpleText", "Unknown Track") ?: "Unknown Track"

                val bylineObj = node.optJSONObject("longBylineText") ?: node.optJSONObject("shortBylineText")
                val artist = bylineObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "Unknown Artist")
                    ?: "Unknown Artist"

                val durationText = node.optJSONObject("lengthText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                    ?: node.optJSONObject("lengthText")?.optString("simpleText", "3:30") ?: "3:30"
                val durationSec = parseDurationText(durationText)

                val thumbnailArray = node.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                val thumbnail = if (thumbnailArray != null && thumbnailArray.length() > 0) {
                    thumbnailArray.optJSONObject(thumbnailArray.length() - 1)?.optString("url", "") ?: ""
                } else ""

                tracks.add(
                    Track(
                        id = -(videoId.hashCode()),
                        title = title,
                        artist = artist,
                        album = "Streamify Radio",
                        durationSec = durationSec,
                        filepath = "https://www.youtube.com/watch?v=$videoId",
                        coverArtPath = thumbnail.takeIf { it.isNotBlank() },
                        bpm = dynamicBpm,
                        key = "",
                        lyricsPath = null,
                        source = "online_stream"
                    )
                )
            }

            // Extract continuation token
            val continuationNodes = mutableListOf<JSONObject>()
            findJsonObjects(root, "nextContinuationData", continuationNodes)
            findJsonObjects(root, "nextRadioContinuationData", continuationNodes)

            for (cNode in continuationNodes) {
                val token = cNode.optString("continuation", "")
                if (token.isNotBlank()) {
                    nextContinuation = token
                    break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return Pair(tracks, nextContinuation)
    }

    private fun findJsonObjects(json: Any, targetKey: String, result: MutableList<JSONObject>) {
        when (json) {
            is JSONObject -> {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key == targetKey) {
                        val obj = json.optJSONObject(key)
                        if (obj != null) result.add(obj)
                    } else {
                        val child = json.opt(key)
                        if (child != null) findJsonObjects(child, targetKey, result)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until json.length()) {
                    val child = json.opt(i)
                    if (child != null) findJsonObjects(child, targetKey, result)
                }
            }
        }
    }

    private fun parseDurationText(text: String): Int {
        try {
            val parts = text.trim().split(":")
            if (parts.size == 2) {
                val min = parts[0].toIntOrNull() ?: 0
                val sec = parts[1].toIntOrNull() ?: 0
                return (min * 60) + sec
            } else if (parts.size == 3) {
                val hr = parts[0].toIntOrNull() ?: 0
                val min = parts[1].toIntOrNull() ?: 0
                val sec = parts[2].toIntOrNull() ?: 0
                return (hr * 3600) + (min * 60) + sec
            }
        } catch (e: Exception) {
            // ignore
        }
        return 210
    }

    private fun normalizeSignature(title: String, artist: String): String {
        val cleanTitle = title.lowercase()
            .replace(Regex("\\(.*?\\)|\\[.*?\\]"), "")
            .replace(Regex("[^a-z0-9]"), "")
            .trim()
        val cleanArtist = artist.lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .trim()
        return "$cleanTitle::$cleanArtist"
    }
}
