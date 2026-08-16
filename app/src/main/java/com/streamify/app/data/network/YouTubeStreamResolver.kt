package com.streamify.app.data.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

data class ResolvedStream(
    val streamUrl: String,
    val mimeType: String,
    val bitrate: Int,
    val durationSec: Int
)

object YouTubeStreamResolver {

    private const val INNERTUBE_PLAYER_URL = "https://www.youtube.com/youtubei/v1/player"
    private const val USER_AGENT_ANDROID = "com.google.android.apps.youtube.music/6.42.52 (Linux; U; Android 14; en_US) gzip"
    private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

    private data class ClientConfig(
        val clientName: String,
        val clientVersion: String,
        val clientNumber: String
    )

    private val CLIENT_TARGETS = listOf(
        ClientConfig("ANDROID_MUSIC", "6.42.52", "21"),
        ClientConfig("ANDROID", "19.09.37", "3"),
        ClientConfig("IOS", "19.09.3", "5"),
        ClientConfig("WEB_REMIX", "1.20230515.01.00", "67")
    )

    fun extractVideoId(urlOrId: String): String? {
        val trimmed = urlOrId.trim()
        if (trimmed.length == 11 && !trimmed.contains("/") && !trimmed.contains("?")) {
            return trimmed
        }
        val matchWatch = Regex("[?&]v=([a-zA-Z0-9_-]{11})").find(trimmed)
        if (matchWatch != null) return matchWatch.groupValues[1]

        val matchShort = Regex("youtu\\.be/([a-zA-Z0-9_-]{11})").find(trimmed)
        if (matchShort != null) return matchShort.groupValues[1]

        val matchEmbed = Regex("/embed/([a-zA-Z0-9_-]{11})").find(trimmed)
        if (matchEmbed != null) return matchEmbed.groupValues[1]

        val matchLive = Regex("/live/([a-zA-Z0-9_-]{11})").find(trimmed)
        if (matchLive != null) return matchLive.groupValues[1]

        return null
    }

    suspend fun resolveStreamUrl(urlOrId: String): ResolvedStream? = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(urlOrId) ?: return@withContext null

        // 1. Zero-RTT Edge Cache Check (0ms Instant Replay)
        val cached = StreamEdgeCache.getStream(videoId)
        if (cached != null) {
            return@withContext cached
        }

        // 2. Parallel Client Racing ("Happy Eyeballs" <80ms)
        val resolved = raceClientEndpoints(videoId)
        if (resolved != null) {
            StreamEdgeCache.putStream(videoId, resolved)
        }
        return@withContext resolved
    }

    suspend fun resolveTrackStream(track: com.streamify.app.data.models.Track): ResolvedStream? = withContext(Dispatchers.IO) {
        // 1. Direct Video ID / URL resolution
        if (track.filepath.isNotBlank()) {
            val videoId = extractVideoId(track.filepath)
            if (videoId != null) {
                val stream = resolveStreamUrl(videoId)
                if (stream != null && stream.streamUrl.isNotBlank()) {
                    return@withContext stream
                }
            }
        }

        // 2. Dynamic Search Resolution for missing/placeholder/expired tracks
        val query = "${track.title} ${track.artist}".trim()
        if (query.isNotBlank()) {
            val results = YouTubeMusicSearchApi.search(query, maxResults = 3)
            val topMatch = results.firstOrNull()
            if (topMatch != null) {
                val videoId = extractVideoId(topMatch.url)
                if (videoId != null) {
                    val stream = resolveStreamUrl(videoId)
                    if (stream != null && stream.streamUrl.isNotBlank()) {
                        return@withContext stream
                    }
                }
            }
        }
        return@withContext null
    }

    private suspend fun raceClientEndpoints(videoId: String): ResolvedStream? = coroutineScope {
        val winnerDeferred = CompletableDeferred<ResolvedStream?>()

        val jobs = CLIENT_TARGETS.map { config ->
            async(Dispatchers.IO) {
                try {
                    val stream = executePlayerRequest(videoId, config)
                    if (stream != null && stream.streamUrl.isNotBlank()) {
                        winnerDeferred.complete(stream)
                    }
                } catch (e: Exception) {
                    // Ignore single client failure in race
                }
            }
        }

        // Complete with null once all children finish if no winner was found
        jobs.forEach { job ->
            job.invokeOnCompletion {
                if (jobs.all { it.isCompleted } && !winnerDeferred.isCompleted) {
                    winnerDeferred.complete(null)
                }
            }
        }

        val winner = winnerDeferred.await()
        // Cancel remaining jobs to conserve bandwidth and CPU
        jobs.forEach { if (!it.isCompleted) it.cancel() }
        return@coroutineScope winner
    }

    private fun executePlayerRequest(videoId: String, config: ClientConfig): ResolvedStream? {
        try {
            val requestJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", config.clientName)
                        put("clientVersion", config.clientVersion)
                        put("androidSdkVersion", 34)
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val request = Request.Builder()
                .url(INNERTUBE_PLAYER_URL)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("User-Agent", USER_AGENT_ANDROID)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "gzip, deflate")
                .header("X-YouTube-Client-Name", config.clientNumber)
                .header("X-YouTube-Client-Version", config.clientVersion)
                .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            NetworkEngine.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null

                val body = response.body ?: return null
                val encoding = response.header("Content-Encoding", "")

                val responseBody = if ("gzip".equals(encoding, ignoreCase = true)) {
                    GZIPInputStream(body.byteStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
                } else {
                    body.string()
                }

                val root = JSONObject(responseBody)
                return parsePlayerResponse(root)
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun parsePlayerResponse(root: JSONObject): ResolvedStream? {
        try {
            val playabilityStatus = root.optJSONObject("playabilityStatus")
            val status = playabilityStatus?.optString("status", "")
            if (status != null && !status.equals("OK", ignoreCase = true)) {
                return null
            }

            val streamingData = root.optJSONObject("streamingData") ?: return null
            val durationSec = root.optJSONObject("videoDetails")?.optString("lengthSeconds", "0")?.toIntOrNull() ?: 0

            val candidateFormats = mutableListOf<JSONObject>()

            // 1. Collect adaptive formats (pure audio streams)
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            if (adaptiveFormats != null) {
                for (i in 0 until adaptiveFormats.length()) {
                    val f = adaptiveFormats.getJSONObject(i)
                    val mime = f.optString("mimeType", "")
                    val streamUrl = f.optString("url", "")
                    if (mime.startsWith("audio/") && streamUrl.isNotBlank()) {
                        candidateFormats.add(f)
                    }
                }
            }

            // 2. Fallback to standard formats
            if (candidateFormats.isEmpty()) {
                val formats = streamingData.optJSONArray("formats")
                if (formats != null) {
                    for (i in 0 until formats.length()) {
                        val f = formats.getJSONObject(i)
                        val streamUrl = f.optString("url", "")
                        if (streamUrl.isNotBlank()) {
                            candidateFormats.add(f)
                        }
                    }
                }
            }

            if (candidateFormats.isEmpty()) return null

            // 3. Perceptual Codec Scoring Matrix (Opus 160k > AAC 128k > Low Bitrate)
            val bestFormat = candidateFormats.maxByOrNull { format ->
                val itag = format.optInt("itag", 0)
                val bitrate = format.optInt("bitrate", format.optInt("averageBitrate", 0))
                val mime = format.optString("mimeType", "")

                when (itag) {
                    251 -> 1000 + (bitrate / 1000) // WebM Opus (160kbps) - Studio Transparent
                    140 -> 850 + (bitrate / 1000)  // MP4 AAC (128kbps) - Universal Compatibility
                    250 -> 800 + (bitrate / 1000)  // WebM Opus (70kbps)
                    249 -> 750 + (bitrate / 1000)  // WebM Opus (50kbps)
                    139 -> 600 + (bitrate / 1000)  // MP4 AAC (48kbps)
                    else -> {
                        if (mime.contains("audio/webm") || mime.contains("opus")) 700 + (bitrate / 1000)
                        else if (mime.contains("audio/mp4") || mime.contains("m4a")) 650 + (bitrate / 1000)
                        else bitrate / 1000
                    }
                }
            } ?: candidateFormats.first()

            val streamUrl = bestFormat.optString("url", "")
            val mimeType = bestFormat.optString("mimeType", "audio/webm")
            val bitrate = bestFormat.optInt("bitrate", bestFormat.optInt("averageBitrate", 160000))

            if (streamUrl.isNotBlank()) {
                return ResolvedStream(
                    streamUrl = streamUrl,
                    mimeType = mimeType,
                    bitrate = bitrate,
                    durationSec = durationSec
                )
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }
}
