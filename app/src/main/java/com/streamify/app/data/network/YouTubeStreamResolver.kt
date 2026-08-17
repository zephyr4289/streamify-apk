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

data class ThumbnailDescriptor(
    val primary: String?,
    val secondary: String?,
    val fallbackColorSeed: Int
)

class UnresolvableTrackException(msg: String = "Unable to resolve playable audio stream") : Exception(msg)

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

    // ========================================================================
    // INVARIANT 1: STORAGE GATEKEEPER & IDENTITY SANITIZATION
    // ========================================================================
    fun sanitizeForStorage(rawIdentifier: String, title: String, artist: String): String {
        val trimmed = rawIdentifier.trim()
        if (trimmed.startsWith("/") || trimmed.startsWith("file://")) {
            return trimmed
        }

        val videoId = extractVideoId(trimmed)
        if (videoId != null) {
            return "https://www.youtube.com/watch?v=$videoId"
        }

        if (trimmed.contains("googlevideo.com") || trimmed.contains("search_query=") || trimmed.isBlank()) {
            return "ytsearch:${title.trim()} ${artist.trim()}".trim()
        }

        return trimmed
    }

    fun sanitizeCoverUrl(rawUrl: String?, videoId: String?): String? {
        if (rawUrl.isNullOrBlank()) {
            return videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
        }
        val trimmed = rawUrl.trim()
        return when {
            trimmed.contains("mzstatic.com") -> trimmed.replace(Regex("\\d+x\\d+bb"), "600x600bb")
            trimmed.contains("googleusercontent.com") && !trimmed.contains("=") -> "$trimmed=w544-h544-l90-rj"
            trimmed.contains("googlevideo.com") -> videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
            else -> trimmed
        }
    }

    // ========================================================================
    // INVARIANT 4: 3-TIER IMAGE DEGRADATION DESCRIPTOR
    // ========================================================================
    fun buildThumbnailPipeline(
        rawUrl: String?,
        videoId: String?,
        title: String,
        artist: String
    ): ThumbnailDescriptor {
        val sanitizedPrimary = sanitizeCoverUrl(rawUrl, videoId)
        val secondaryUrl = videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
        val proceduralSeed = (title.trim().lowercase() + artist.trim().lowercase()).hashCode()

        return ThumbnailDescriptor(
            primary = sanitizedPrimary,
            secondary = secondaryUrl,
            fallbackColorSeed = proceduralSeed
        )
    }

    fun extractVideoId(urlOrId: String, fallbackThumbnail: String? = null): String? {
        val trimmed = urlOrId.trim()
        if (trimmed.length == 11 && !trimmed.contains("/") && !trimmed.contains("?") && !trimmed.contains("&") && !trimmed.contains(".")) {
            return trimmed
        }
        val matchWatch = Regex("(?:[?&]v=|youtu\\.be/|/embed/|/live/|^)([a-zA-Z0-9_-]{11})").find(trimmed)
        if (matchWatch != null) return matchWatch.groupValues[1]

        val matchYtImg = Regex("i\\.ytimg\\.com/vi(_webp)?/([a-zA-Z0-9_-]{11})").find(trimmed)
        if (matchYtImg != null) return matchYtImg.groupValues[2]

        // Check fallback thumbnail if provided
        if (!fallbackThumbnail.isNullOrBlank()) {
            val thumbTrimmed = fallbackThumbnail.trim()
            val matchThumbImg = Regex("i\\.ytimg\\.com/vi(_webp)?/([a-zA-Z0-9_-]{11})").find(thumbTrimmed)
            if (matchThumbImg != null) return matchThumbImg.groupValues[2]

            val matchThumbWatch = Regex("(?:[?&]v=|youtu\\.be/|/embed/|/live/|^)([a-zA-Z0-9_-]{11})").find(thumbTrimmed)
            if (matchThumbWatch != null) return matchThumbWatch.groupValues[1]
        }

        return null
    }

    fun getCanonicalWatchUrl(urlOrId: String, fallbackThumbnail: String? = null): String? {
        val videoId = extractVideoId(urlOrId, fallbackThumbnail) ?: return null
        return "https://www.youtube.com/watch?v=$videoId"
    }

    fun parseExpiry(url: String): Long {
        if (url.isBlank()) return 0L
        val expireEpochSec = Regex("[?&]expire=([0-9]+)").find(url)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return 0L
        return expireEpochSec * 1000L
    }

    fun isCdnExpired(url: String, safetyMarginMs: Long = 7_200_000L): Boolean {
        if (url.isBlank() || !url.startsWith("http")) return true
        val expireEpochMs = parseExpiry(url)
        if (expireEpochMs > 0L) {
            return System.currentTimeMillis() >= (expireEpochMs - safetyMarginMs)
        }
        return false
    }

    // ========================================================================
    // INVARIANT 2: UNIFIED JIT 3-TIER STREAM RESOLUTION CASCADE
    // ========================================================================
    suspend fun resolveStreamJit(track: com.streamify.app.data.models.Track): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        // Tier 0: Offline Local File Exists
        if (track.filepath.startsWith("/") || track.filepath.startsWith("file://")) {
            val localFile = java.io.File(track.filepath.removePrefix("file://"))
            if (localFile.exists()) {
                return@withContext Result.success(
                    ResolvedStream(
                        streamUrl = track.filepath,
                        mimeType = "audio/mpeg",
                        bitrate = 320000,
                        durationSec = track.durationSec
                    )
                )
            }
        }

        // 1. Determine Video ID (or search online if missing/unresolved)
        var videoId = extractVideoId(track.filepath, track.coverArtPath)
        if (videoId == null) {
            val cleanQuery = if (track.filepath.startsWith("ytsearch:")) {
                track.filepath.removePrefix("ytsearch:").trim()
            } else {
                "${track.title} ${track.artist}".trim()
            }

            if (cleanQuery.isNotBlank()) {
                val searchMatches = YouTubeMusicSearchApi.search(cleanQuery, maxResults = 2)
                val topMatch = searchMatches.firstOrNull()
                if (topMatch != null) {
                    videoId = extractVideoId(topMatch.url, topMatch.thumbnail)
                }
            }
        }

        if (videoId == null) {
            return@withContext Result.failure(UnresolvableTrackException("No video ID could be found for ${track.title}"))
        }

        // 2. In-Memory LRU Cache with 600s safety margin
        val cached = StreamEdgeCache.getStream(videoId)
        if (cached != null && !isCdnExpired(cached.streamUrl, safetyMarginMs = 600_000L)) {
            return@withContext Result.success(cached)
        }

        // 3. Tier 1: Native HTTP/2 Innertube Client Race (<80ms)
        val nativeResolved = raceClientEndpoints(videoId)
        if (nativeResolved != null && nativeResolved.streamUrl.isNotBlank()) {
            StreamEdgeCache.putStream(videoId, nativeResolved)
            return@withContext Result.success(nativeResolved)
        }

        // 4. Tier 2: Chaquopy Python yt-dlp Subprocess Fallback
        try {
            val pyFallbackResult = PythonEngine.executeFallback(
                moduleName = "download_engine.search",
                function = "get_stream_url",
                args = arrayOf("https://www.youtube.com/watch?v=$videoId")
            ) { pyObj ->
                val strVal = pyObj.toString().trim()
                if (strVal.startsWith("{")) {
                    val jsonObj = JSONObject(strVal)
                    jsonObj.optString("url", "")
                } else {
                    strVal
                }
            }

            val pythonStreamUrl = pyFallbackResult.getOrNull()
            if (!pythonStreamUrl.isNullOrBlank()) {
                val pyResolved = ResolvedStream(
                    streamUrl = pythonStreamUrl,
                    mimeType = "audio/webm",
                    bitrate = 160000,
                    durationSec = track.durationSec
                )
                StreamEdgeCache.putStream(videoId, pyResolved)
                return@withContext Result.success(pyResolved)
            }
        } catch (e: Throwable) {
            // Log & proceed to Tier 3
        }

        // 5. Tier 3: Query YouTube Music Search Query Match and retry
        try {
            val fallbackSearch = YouTubeMusicSearchApi.search("${track.title} ${track.artist}", maxResults = 3)
            for (candidate in fallbackSearch) {
                val candVideoId = extractVideoId(candidate.url, candidate.thumbnail)
                if (candVideoId != null && candVideoId != videoId) {
                    val retryResolved = raceClientEndpoints(candVideoId)
                    if (retryResolved != null && retryResolved.streamUrl.isNotBlank()) {
                        StreamEdgeCache.putStream(candVideoId, retryResolved)
                        return@withContext Result.success(retryResolved)
                    }
                }
            }
        } catch (e: Exception) {
            // Failed tier 3
        }

        return@withContext Result.failure(UnresolvableTrackException("Stream exhaustion for ${track.title} - ${track.artist}"))
    }

    suspend fun resolveStreamUrl(urlOrId: String, fallbackThumbnail: String? = null): ResolvedStream? = withContext(Dispatchers.IO) {
        val dummyTrack = com.streamify.app.data.models.Track(
            id = 0,
            title = "",
            artist = "",
            album = "",
            durationSec = 0,
            filepath = urlOrId,
            coverArtPath = fallbackThumbnail
        )
        resolveStreamJit(dummyTrack).getOrNull()
    }

    suspend fun resolveTrackStream(track: com.streamify.app.data.models.Track): ResolvedStream? = withContext(Dispatchers.IO) {
        resolveStreamJit(track).getOrNull()
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

    private val VIDEO_CLIENT_TARGETS = listOf(
        ClientConfig("ANDROID", "19.09.37", "3"),
        ClientConfig("IOS", "19.09.3", "5"),
        ClientConfig("WEB", "2.20230515.01.00", "1")
    )

    suspend fun resolveVideoStreamUrl(track: com.streamify.app.data.models.Track): ResolvedStream? = withContext(Dispatchers.IO) {
        val videoId = CanonicalSeedResolver.resolveToCanonicalId(track)

        // 1. Zero-RTT Edge Cache Check
        val cached = StreamEdgeCache.getVideoStream(videoId)
        if (cached != null) {
            return@withContext cached
        }

        // 2. Parallel Standard Client Video Stream Racing (ANDROID / IOS / WEB)
        val resolved = raceClientVideoEndpoints(videoId)
        if (resolved != null && resolved.streamUrl.isNotBlank()) {
            StreamEdgeCache.putVideoStream(videoId, resolved)
            return@withContext resolved
        }

        // 3. Tier 2: Chaquopy Python yt-dlp Video Stream Fallback
        try {
            val pyFallbackResult = PythonEngine.executeFallback(
                moduleName = "download_engine.search",
                function = "get_stream_url",
                args = arrayOf("https://www.youtube.com/watch?v=$videoId")
            ) { pyObj ->
                val strVal = pyObj.toString().trim()
                if (strVal.startsWith("{")) {
                    val jsonObj = JSONObject(strVal)
                    jsonObj.optString("url", "")
                } else {
                    strVal
                }
            }

            val pythonStreamUrl = pyFallbackResult.getOrNull()
            if (!pythonStreamUrl.isNullOrBlank()) {
                val pyResolved = ResolvedStream(
                    streamUrl = pythonStreamUrl,
                    mimeType = "video/mp4",
                    bitrate = 1200000,
                    durationSec = track.durationSec
                )
                StreamEdgeCache.putVideoStream(videoId, pyResolved)
                return@withContext pyResolved
            }
        } catch (e: Throwable) {
            // Ignore & return null
        }

        return@withContext null
    }

    suspend fun resolveVideoStreamUrl(urlOrId: String, fallbackThumbnail: String? = null): ResolvedStream? = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(urlOrId, fallbackThumbnail) ?: return@withContext null

        // 1. Zero-RTT Edge Cache Check
        val cached = StreamEdgeCache.getVideoStream(videoId)
        if (cached != null) {
            return@withContext cached
        }

        // 2. Parallel Client Video Stream Racing
        val resolved = raceClientVideoEndpoints(videoId)
        if (resolved != null) {
            StreamEdgeCache.putVideoStream(videoId, resolved)
        }
        return@withContext resolved
    }

    private suspend fun raceClientVideoEndpoints(videoId: String): ResolvedStream? = coroutineScope {
        val winnerDeferred = CompletableDeferred<ResolvedStream?>()

        val jobs = VIDEO_CLIENT_TARGETS.map { config ->
            async(Dispatchers.IO) {
                try {
                    val stream = executeVideoPlayerRequest(videoId, config)
                    if (stream != null && stream.streamUrl.isNotBlank()) {
                        winnerDeferred.complete(stream)
                    }
                } catch (e: Exception) {
                    // Ignore single client failure in race
                }
            }
        }

        jobs.forEach { job ->
            job.invokeOnCompletion {
                if (jobs.all { it.isCompleted } && !winnerDeferred.isCompleted) {
                    winnerDeferred.complete(null)
                }
            }
        }

        val winner = winnerDeferred.await()
        jobs.forEach { if (!it.isCompleted) it.cancel() }
        return@coroutineScope winner
    }

    private fun executeVideoPlayerRequest(videoId: String, config: ClientConfig): ResolvedStream? {
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
                return parseVideoPlayerResponse(root)
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun parseVideoPlayerResponse(root: JSONObject): ResolvedStream? {
        try {
            val playabilityStatus = root.optJSONObject("playabilityStatus")
            val status = playabilityStatus?.optString("status", "")
            if (status != null && !status.equals("OK", ignoreCase = true)) {
                return null
            }

            val streamingData = root.optJSONObject("streamingData") ?: return null
            val durationSec = root.optJSONObject("videoDetails")?.optString("lengthSeconds", "0")?.toIntOrNull() ?: 0

            val formats = streamingData.optJSONArray("formats")
            val progressiveList = mutableListOf<JSONObject>()
            if (formats != null) {
                for (i in 0 until formats.length()) {
                    val f = formats.getJSONObject(i)
                    val url = f.optString("url", "")
                    if (url.isNotBlank()) {
                        progressiveList.add(f)
                    }
                }
            }

            // Prefer itag 22 (720p HD MP4) > itag 18 (360p MP4) > highest quality progressive MP4
            val bestFormat = progressiveList.firstOrNull { it.optInt("itag") == 22 }
                ?: progressiveList.firstOrNull { it.optInt("itag") == 18 }
                ?: progressiveList.firstOrNull()

            if (bestFormat != null) {
                val streamUrl = bestFormat.optString("url", "")
                val mimeType = bestFormat.optString("mimeType", "video/mp4")
                val bitrate = bestFormat.optInt("bitrate", 1200000)
                if (streamUrl.isNotBlank()) {
                    return ResolvedStream(
                        streamUrl = streamUrl,
                        mimeType = mimeType,
                        bitrate = bitrate,
                        durationSec = durationSec
                    )
                }
            }

            // Fallback to adaptive video format if no progressive available
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            if (adaptiveFormats != null) {
                for (i in 0 until adaptiveFormats.length()) {
                    val f = adaptiveFormats.getJSONObject(i)
                    val mime = f.optString("mimeType", "")
                    val streamUrl = f.optString("url", "")
                    if (mime.startsWith("video/") && streamUrl.isNotBlank()) {
                        return ResolvedStream(
                            streamUrl = streamUrl,
                            mimeType = mime,
                            bitrate = f.optInt("bitrate", 800000),
                            durationSec = durationSec
                        )
                    }
                }
            }

            return null
        } catch (e: Exception) {
            return null
        }
    }
}
