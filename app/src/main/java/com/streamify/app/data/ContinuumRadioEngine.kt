package com.streamify.app.data

import com.streamify.app.data.models.Track
import com.streamify.app.data.network.NetworkEngine
import com.streamify.app.data.network.YouTubeMusicSearchApi
import com.streamify.app.data.network.YouTubeStreamResolver
import com.streamify.app.viewmodel.OnlineSearchResult
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Collections
import java.util.LinkedHashSet
import java.util.zip.GZIPInputStream

object ContinuumRadioEngine {

    private const val INNERTUBE_NEXT_URL = "https://music.youtube.com/youtubei/v1/next"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

    // O(1) Session History Deduplication Ring Buffer (The Echo Chamber Killer)
    private val playedVideoIds = Collections.synchronizedSet(LinkedHashSet<String>())
    private val playedSignatures = Collections.synchronizedSet(LinkedHashSet<String>())
    private const val MAX_HISTORY_CAPACITY = 200

    private var activeRadioSeedVideoId: String? = null
    private var activeContinuationToken: String? = null

    private val _isFetching = MutableStateFlow(false)
    val isFetching: StateFlow<Boolean> = _isFetching.asStateFlow()

    /**
     * Records a track into the O(1) session history ring buffer
     */
    fun recordTrackPlayed(track: Track) {
        val videoId = YouTubeStreamResolver.extractVideoId(track.filepath)
        if (videoId != null) {
            synchronized(playedVideoIds) {
                if (playedVideoIds.size >= MAX_HISTORY_CAPACITY) {
                    val first = playedVideoIds.iterator().next()
                    playedVideoIds.remove(first)
                }
                playedVideoIds.add(videoId)
            }
        }

        val sig = normalizeSignature(track.title, track.artist)
        if (sig.isNotBlank()) {
            synchronized(playedSignatures) {
                if (playedSignatures.size >= MAX_HISTORY_CAPACITY) {
                    val first = playedSignatures.iterator().next()
                    playedSignatures.remove(first)
                }
                playedSignatures.add(sig)
            }
        }
    }

    /**
     * Initializes or resets a new Radio continuum session based on seed track
     */
    fun resetRadioSession(seedTrack: Track) {
        activeRadioSeedVideoId = YouTubeStreamResolver.extractVideoId(seedTrack.filepath)
        activeContinuationToken = null
        recordTrackPlayed(seedTrack)
    }

    /**
     * Asynchronously fetches the next batch of infinite radio tracks using recursive Innertube continuation tokens
     */
    suspend fun fetchNextRadioBatch(seedTrack: Track, limit: Int = 15): List<Track> = withContext(Dispatchers.IO) {
        _isFetching.value = true
        try {
            val videoId = activeRadioSeedVideoId ?: YouTubeStreamResolver.extractVideoId(seedTrack.filepath) ?: "dQw4w9WgXcQ"
            recordTrackPlayed(seedTrack)

            // 1. Execute Innertube /next request
            val (candidates, nextContinuation) = executeNextRequest(videoId, activeContinuationToken)
            if (nextContinuation != null) {
                activeContinuationToken = nextContinuation
            }

            // 2. O(1) Deduplication against session history
            val freshCandidates = mutableListOf<Track>()
            for (candidate in candidates) {
                val cVideoId = YouTubeStreamResolver.extractVideoId(candidate.filepath)
                val cSig = normalizeSignature(candidate.title, candidate.artist)

                val isDuplicateVideo = cVideoId != null && playedVideoIds.contains(cVideoId)
                val isDuplicateSig = cSig.isNotBlank() && playedSignatures.contains(cSig)

                if (!isDuplicateVideo && !isDuplicateSig) {
                    freshCandidates.add(candidate)
                    if (cVideoId != null) playedVideoIds.add(cVideoId)
                    if (cSig.isNotBlank()) playedSignatures.add(cSig)
                }
            }

            // 3. Fallback: If continuation was empty or returned only duplicates, query Last.fm / Innertube search
            if (freshCandidates.isEmpty()) {
                val fallbackResults = YouTubeMusicSearchApi.search("${seedTrack.artist} ${seedTrack.title} mix", maxResults = limit)
                for (item in fallbackResults) {
                    val fVideoId = YouTubeStreamResolver.extractVideoId(item.url)
                    val fSig = normalizeSignature(item.title, item.uploader)
                    if (fVideoId != null && !playedVideoIds.contains(fVideoId) && !playedSignatures.contains(fSig)) {
                        playedVideoIds.add(fVideoId)
                        if (fSig.isNotBlank()) playedSignatures.add(fSig)
                        freshCandidates.add(
                            Track(
                                id = -(item.url.hashCode()),
                                title = item.title,
                                artist = item.uploader,
                                album = "Streamify Radio",
                                durationSec = item.duration,
                                filepath = item.url,
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

            freshCandidates.take(limit)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        } finally {
            _isFetching.value = false
        }
    }

    private fun executeNextRequest(videoId: String, continuationToken: String?): Pair<List<Track>, String?> {
        try {
            val requestJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20230515.01.00")
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

            val request = Request.Builder()
                .url(INNERTUBE_NEXT_URL)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "gzip, deflate")
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

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
                return parseNextResponse(root)
            }
        } catch (e: Exception) {
            return Pair(emptyList(), null)
        }
    }

    private fun parseNextResponse(root: JSONObject): Pair<List<Track>, String?> {
        val tracks = mutableListOf<Track>()
        var nextContinuation: String? = null

        try {
            // Find playlistPanelVideoRenderer items across tabs / continuations
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
                        bpm = 120f,
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
