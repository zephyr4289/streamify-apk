package com.streamify.app.data.network

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

data class ResolvedStream(
    val streamUrl: String,
    val mimeType: String,
    val bitrate: Int,
    val durationSec: Int,
    /** YouTube's own loudness measurement for this exact stream (dB, rel −14 LUFS ref). */
    val loudnessDb: Float? = null
)

data class ThumbnailDescriptor(
    val primary: String?,
    val secondary: String?,
    val fallbackColorSeed: Int
)

class UnresolvableTrackException(msg: String = "Unable to resolve playable audio stream") : Exception(msg)

object YouTubeStreamResolver {

    private const val INNERTUBE_PLAYER_URL = "https://www.youtube.com/youtubei/v1/player"

    // AUTHENTICATED RESOLUTION HOOK (2026 bot-wall): set once at app start.
    // Returns (sapisidhashHeader, rawCookies). When present, both player
    // executors attach Authorization/Cookie/X-Origin — the only combination
    // that still returns adaptiveFormats.
    @Volatile
    var ytSessionProvider: (() -> Pair<String, String>)? = null

    private fun attachYtSession(builder: Request.Builder) {
        val session = try { ytSessionProvider?.invoke() } catch (_: Throwable) { null } ?: return
        val (auth, cookies) = session
        if (!auth.isNullOrBlank()) {
            builder.header(
                "Authorization",
                if (auth.startsWith("SAPISIDHASH")) auth else "SAPISIDHASH $auth"
            )
            builder.header("X-Origin", "https://music.youtube.com")
        }
        if (!cookies.isNullOrBlank()) {
            builder.header("Cookie", cookies)
        }
    }

    object ResolverPolicy {
        @Volatile var useNegativeCache = false
        @Volatile var useCircuitBreaker = false
        @Volatile var strikeLockEnabled = false
    }

    private const val SIGNATURE_TIMESTAMP = 19850

    private const val USER_AGENT_ANDROID = "com.google.android.apps.youtube.music/6.42.52 (Linux; U; Android 14; en_US) gzip"
    private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

    private data class ClientConfig(
        val clientName: String,
        val clientVersion: String,
        val clientNumber: String,
        val userAgent: String,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
        val osName: String? = null,
        val osVersion: String? = null,
        val origin: String? = null,
        val referer: String? = null,
        // Zero-token philosophy (probe-verified 2026-08-24): bare ANDROID/IOS return
        // direct adaptive formats for LICENSED content, while SAPISIDHASH+Cookie on
        // those same requests triggers "Sign in to confirm you're not a bot".
        // Session attach stays available per-profile for opt-in diagnostics only.
        val attachSession: Boolean = false
    )

    private val CLIENT_TARGETS = listOf(
        // 1. Android App: 100% verified direct playback on official/topic music videos
        // Fingerprints restored from 6ffa6c4 — the last build with zero-token audio
        // resolution solving fast. 7a1c9e4 regressed these to stale 19.x clients,
        // which YouTube answers without adaptiveFormats.
        ClientConfig(
            clientName = "ANDROID",
            clientVersion = "21.26.364",
            clientNumber = "3",
            userAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
            osName = "Android",
            osVersion = "11"
        ),
        // 2. Meta Quest / Android VR: Verified unencrypted direct Opus & AAC CDN streams
        ClientConfig(
            clientName = "ANDROID_VR",
            clientVersion = "1.60.19",
            clientNumber = "28",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Quest 3) AppleWebKit/537.36 (KHTML, like Gecko) OculusBrowser/33.0.0.19.46.568453472 SamsungBrowser/4.0 Chrome/122.0.6261.139 Mobile VR Safari/537.36",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            osName = "Android",
            osVersion = "12"
        ),
        // 3. Native iOS YouTube App
        ClientConfig(
            clientName = "IOS",
            clientVersion = "21.26.4",
            clientNumber = "5",
            userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            osName = "iPhone",
            osVersion = "18.3.2.22D82"
        )
    )

    // ========================================================================
    // INVARIANT 1: STORAGE GATEKEEPER & IDENTITY SANITIZATION
    // ========================================================================
    fun sanitizeForStorage(rawIdentifier: String, title: String, artist: String, fallbackCover: String? = null): String {
        val trimmed = rawIdentifier.trim()
        if (trimmed.startsWith("/") || trimmed.startsWith("file://")) {
            return trimmed
        }

        val videoId = extractVideoId(trimmed, fallbackCover)
        if (videoId != null) {
            return "https://www.youtube.com/watch?v=$videoId"
        }

        if (trimmed.contains("googlevideo.com") || trimmed.contains("search_query=") || trimmed.isBlank()) {
            return "ytsearch:${title.trim()} ${artist.trim()}".trim()
        }

        return trimmed
    }


    fun sanitizeCoverUrl(rawUrl: String?, videoId: String?): String? {
        val effectiveVid = videoId ?: extractVideoId(rawUrl ?: "")
        if (rawUrl.isNullOrBlank()) {
            return effectiveVid?.let { "https://i.ytimg.com/vi/$it/maxresdefault.jpg" }
        }
        val trimmed = rawUrl.trim()
        return when {
            trimmed.contains("mzstatic.com") -> trimmed.replace(Regex("\\d+x\\d+bb"), "1400x1400bb")
            trimmed.contains("googleusercontent.com") || trimmed.contains("ggpht.com") -> {
                val cleanBase = trimmed.substringBefore("=")
                "$cleanBase=w1200-h1200-l90-rj"
            }
            trimmed.contains("googlevideo.com") -> effectiveVid?.let { "https://i.ytimg.com/vi/$it/maxresdefault.jpg" }
            trimmed.contains("ytimg.com") || trimmed.contains("vi_webp") -> {
                val vid = Regex("(?<=/vi/|/vi_webp/)[a-zA-Z0-9_-]{11}").find(trimmed)?.value ?: effectiveVid
                if (vid != null) {
                    "https://i.ytimg.com/vi/$vid/maxresdefault.jpg"
                } else {
                    trimmed
                }
            }
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
        val vid = videoId ?: extractVideoId(rawUrl ?: "")
        val secondaryUrl = vid?.let { "https://i.ytimg.com/vi/$it/hq720.jpg" }
        val proceduralSeed = (title.trim().lowercase() + artist.trim().lowercase()).hashCode()

        return ThumbnailDescriptor(
            primary = sanitizedPrimary,
            secondary = secondaryUrl,
            fallbackColorSeed = proceduralSeed
        )
    }

    private val YT_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")
    private val YT_URL_REGEX = Regex("(?:[?&]v=|/v/|youtu\\.be/|/embed/|/shorts/|/live/|^)([a-zA-Z0-9_-]{11})")
    private val YT_THUMBNAIL_REGEX = Regex("(?:vi|vi_webp)/([a-zA-Z0-9_-]{11})")

    fun extractIdFromThumbnail(thumbnailUrl: String?): String? {
        if (thumbnailUrl.isNullOrBlank()) return null
        return YT_THUMBNAIL_REGEX.find(thumbnailUrl)?.groupValues?.getOrNull(1)
    }

    fun extractVideoId(urlOrId: String?, fallbackThumbnail: String? = null): String? {
        if (urlOrId.isNullOrBlank()) return extractIdFromThumbnail(fallbackThumbnail)
        val cleanInput = urlOrId.trim()
        if (cleanInput.startsWith("ytsearch:") || cleanInput.startsWith("online://")) {
            return extractIdFromThumbnail(fallbackThumbnail)
        }
        // 1. Direct 11-character video ID
        if (YT_ID_REGEX.matches(cleanInput)) {
            return cleanInput
        }
        // 2. Standard YouTube URL patterns
        val match = YT_URL_REGEX.find(cleanInput)?.groupValues?.getOrNull(1)
        if (match != null && YT_ID_REGEX.matches(match)) {
            return match
        }
        // 3. Fallback to thumbnail URL if input is a CDN URL (googlevideo.com) or custom scheme
        return extractIdFromThumbnail(fallbackThumbnail)
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

    // 4-Hour Rule: Refreshes CDN streams before expiration with a 2-hour safety window
    fun isCdnExpired(url: String, safetyMarginMs: Long = 7_200_000L): Boolean {
        if (url.isBlank() || !url.startsWith("http")) return true
        val expireEpochMs = parseExpiry(url)
        if (expireEpochMs > 0L) {
            return System.currentTimeMillis() >= (expireEpochMs - safetyMarginMs)
        }
        return false
    }

    // ========================================================================
    // INVARIANT 2: UNIFIED JIT STREAM RESOLUTION CASCADE (sol1.2.3)
    // ========================================================================
    suspend fun resolveStreamJit(track: com.streamify.app.data.models.Track, forceFresh: Boolean = false): Result<ResolvedStream> = withContext(Dispatchers.IO) {
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
        var videoId = track.ytmVideoId?.takeIf { YT_ID_REGEX.matches(it) }
            ?: extractVideoId(track.filepath, track.coverArtPath)
        var wasUnpinnedSearch = false
        if (videoId == null) {
            val cleanQuery = if (track.filepath.startsWith("ytsearch:")) {
                wasUnpinnedSearch = true
                track.filepath.removePrefix("ytsearch:").trim()
            } else {
                "${track.title} ${track.artist}".trim()
            }

            if (cleanQuery.isNotBlank()) {
                val searchMatches = YouTubeMusicSearchApi.search(cleanQuery, maxResults = 5)
                val topMatch = searchMatches.firstOrNull()
                if (topMatch != null) {
                    videoId = extractVideoId(topMatch.url)
                }
            }
        }

        if (videoId == null) {
            android.util.Log.e("LadderTrace", "❌ RESOLUTION FAILED for ${track.title} (No videoId found)")
            return@withContext Result.failure(UnresolvableTrackException("No video ID could be found for ${track.title}"))
        }

        // Canonical Pinning: Lock resolved immutable Video ID in DB
        if (wasUnpinnedSearch && track.id > 0) {
            val canonicalWatchUrl = "https://www.youtube.com/watch?v=$videoId"
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    com.streamify.app.data.TrackRepository.upsertStreamedTrack(
                        track.copy(filepath = canonicalWatchUrl, ytmVideoId = videoId)
                    )
                } catch (_: Exception) {}
            }
        }

        // 2. In-Memory LRU Cache with 4-Hour safety margin
        if (!forceFresh) {
            val cached = StreamEdgeCache.getStream(videoId)
            if (cached != null && !isCdnExpired(cached.streamUrl, safetyMarginMs = 600_000L)) {
                ConnectionWarmer.preWarmCDN(cached.streamUrl)
                return@withContext Result.success(cached)
            }
        } else {
            StreamEdgeCache.evictStream(videoId)
        }

        // 3. Tier 1 / R1: Native HTTP/2 Innertube Multi-Client Race (<80ms)
        val skipR1 = ResolverPolicy.useNegativeCache && NegativeResultCache.isWalled(videoId)
        var r1Verdict = "SKIPPED"
        var nativeResolved: ResolvedStream? = null
        if (!skipR1) {
            nativeResolved = raceClientEndpoints(videoId)
            if (nativeResolved != null && nativeResolved.streamUrl.isNotBlank()) {
                StreamEdgeCache.putStream(videoId, nativeResolved)
                ConnectionWarmer.preWarmCDN(nativeResolved.streamUrl)
                NegativeResultCache.clear(videoId)
                r1Verdict = "HIT"
                android.util.Log.d("LadderTrace", "vid=$videoId r1=HIT r2:cands=0 postGate=0 -> R1_SUCCESS (${track.title})")
                return@withContext Result.success(nativeResolved)
            } else {
                r1Verdict = "MISS"
                if (ResolverPolicy.useNegativeCache) {
                    NegativeResultCache.markWalled(videoId)
                }
            }
        }

        // 4. Tier 2 / R2: Alternate-upload resolver with Topic normalization and unknown-pass gates
        var altSearchSize = 0
        var postGateSize = 0
        try {
            val altSearch = YouTubeMusicSearchApi.search("${track.title} ${track.artist}", maxResults = 8)
            altSearchSize = altSearch.size

            val candidates = altSearch.mapNotNull { c ->
                val cid = extractVideoId(c.url, c.thumbnail) ?: return@mapNotNull null
                if (cid == videoId) return@mapNotNull null

                // Unknown (<=0) always passes. Gates reject only PROVABLE mismatches.
                val durationOk = track.durationSec <= 0 || c.duration <= 0 || kotlin.math.abs(track.durationSec - c.duration) <= 5
                if (!durationOk) return@mapNotNull null

                val titleSim = com.streamify.app.data.FuzzyTitleMatcher.calculateSimilarity(track.title.lowercase(), c.title.lowercase())
                if (titleSim < 0.3f) return@mapNotNull null

                val normTrackArtist = track.artist.removeSuffix(" - Topic").removeSuffix(" - VEVO").trim().lowercase()
                val normCandArtist = c.uploader.removeSuffix(" - Topic").removeSuffix(" - VEVO").trim().lowercase()
                val artistSim = com.streamify.app.data.FuzzyTitleMatcher.calculateSimilarity(normTrackArtist, normCandArtist)

                val compositeScore = (titleSim * 0.6f) + (artistSim * 0.4f)
                Triple(cid, compositeScore, c)
            }.sortedByDescending { it.second }.take(3)
            postGateSize = candidates.size

            for ((candVideoId, score, cand) in candidates) {
                val retryResolved = raceClientEndpoints(candVideoId)
                if (retryResolved != null && retryResolved.streamUrl.isNotBlank()) {
                    StreamEdgeCache.putStream(candVideoId, retryResolved)
                    ConnectionWarmer.preWarmCDN(retryResolved.streamUrl)
                    NegativeResultCache.clear(candVideoId)
                    NegativeResultCache.clear(videoId)
                    android.util.Log.d("LadderTrace", "vid=$videoId r1=$r1Verdict r2:cands=$altSearchSize postGate=$postGateSize -> R2_HIT ($candVideoId, score=${"%.2f".format(score)})")

                    if (track.id > 0) {
                        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                            try {
                                com.streamify.app.data.TrackRepository.upsertStreamedTrack(
                                    track.copy(
                                        filepath = "https://www.youtube.com/watch?v=$candVideoId",
                                        ytmVideoId = candVideoId
                                    )
                                )
                            } catch (_: Exception) {}
                        }
                    }
                    return@withContext Result.success(retryResolved)
                }
            }
        } catch (e: Exception) {
            // R2 failed
        }

        android.util.Log.e("LadderTrace", "vid=$videoId r1=$r1Verdict r2:cands=$altSearchSize postGate=$postGateSize -> EXHAUSTED (${track.title})")
        return@withContext Result.failure(UnresolvableTrackException("Stream exhaustion for ${track.title} - ${track.artist}"))
    }

    suspend fun resolveStreamUrl(urlOrId: String, fallbackThumbnail: String? = null, forceFresh: Boolean = false): ResolvedStream? = withContext(Dispatchers.IO) {
        val dummyTrack = com.streamify.app.data.models.Track(
            id = 0,
            title = "",
            artist = "",
            album = "",
            durationSec = 0,
            filepath = urlOrId,
            coverArtPath = fallbackThumbnail
        )
        resolveStreamJit(dummyTrack, forceFresh = forceFresh).getOrNull()
    }

    suspend fun resolveTrackStream(track: com.streamify.app.data.models.Track, forceFresh: Boolean = false): ResolvedStream? = withContext(Dispatchers.IO) {
        resolveStreamJit(track, forceFresh = forceFresh).getOrNull()
    }

    private suspend fun raceClientEndpoints(videoId: String): ResolvedStream? = coroutineScope {
        val winnerDeferred = CompletableDeferred<ResolvedStream?>()

        // Per-race playability status collector. Tripping decisions are AGGREGATE:
        // a single client reporting LOGIN_REQUIRED (e.g. ANDROID_VR on licensed
        // content) must never poison the breaker while ANDROID/IOS win the race.
        val statuses: MutableSet<String> =
            java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

        val jobs = CLIENT_TARGETS.map { config ->
            async(Dispatchers.IO) {
                try {
                    val stream = executePlayerRequest(videoId, config, statuses)
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
        if (winner == null && statuses.isNotEmpty() && statuses.all { it != "OK" }) {
            // The entire race agreed on a wall/death status — this ID is genuinely gated.
            android.util.Log.w("YouTubeStreamResolver", "Race uniformly walled for $videoId: $statuses")
            StreamifyCircuitBreaker.tripHard(videoId)
        }
        return@coroutineScope winner
    }

    private fun executePlayerRequest(
        videoId: String,
        config: ClientConfig,
        statuses: MutableCollection<String>? = null
    ): ResolvedStream? {
        try {
            val clientJson = JSONObject().apply {
                put("clientName", config.clientName)
                put("clientVersion", config.clientVersion)
                put("hl", "en")
                put("gl", "US")
                config.deviceMake?.let { put("deviceMake", it) }
                config.deviceModel?.let { put("deviceModel", it) }
                config.osName?.let { put("osName", it) }
                config.osVersion?.let { put("osVersion", it) }
                if (config.clientName.contains("ANDROID", ignoreCase = true)) {
                    put("androidSdkVersion", 34)
                }
            }

            val requestJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", clientJson)
                })
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                put("playbackContext", JSONObject().apply {
                    put("contentPlaybackContext", JSONObject().apply {
                        // STATIC, client-coupled STS -- do NOT make this dynamic.
                        // Control experiment (2026-08-24, same phone/network):
                        // legacy build sending 19850 resolves everything while HEAD
                        // sending the live web STS (20683) was uniformly rejected --
                        // an STS far newer than the client fingerprint's era reads
                        // as a bot signal to the player endpoint.
                        put("signatureTimestamp", SIGNATURE_TIMESTAMP)
                        put("html5Preference", "HTML5_PREF_WANTS")
                    })
                })
            }

            val reqBuilder = Request.Builder()
                .url(INNERTUBE_PLAYER_URL)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("User-Agent", config.userAgent)
                .header("Accept", "*/*")
                .header("X-YouTube-Client-Name", config.clientNumber)
                .header("X-YouTube-Client-Version", config.clientVersion)
                .also { if (config.attachSession) attachYtSession(it) }
                .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))

            if (!config.origin.isNullOrBlank()) {
                reqBuilder.header("Origin", config.origin)
            }
            if (!config.referer.isNullOrBlank()) {
                reqBuilder.header("Referer", config.referer)
            }

            val request = reqBuilder.build()

            // OkHttp Network Transport (Shared Connection Pool + Android OS DNS/IPv6/VPN)
            NetworkEngine.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null

                val rawBytes = response.body?.bytes() ?: return null
                if (rawBytes.isEmpty()) return null

                val verdict = gate(rawBytes, statuses)
                return when (verdict) {
                    is Verdict.Ok -> {
                        if (verdict.formats.isNotEmpty()) {
                            verdict.formats.maxByOrNull { f ->
                                val mime = f.mimeType
                                if (mime.contains("opus")) 300_000 + f.bitrate
                                else if (mime.contains("mp4a") || mime.contains("m4a")) 200_000 + f.bitrate
                                else f.bitrate
                            } ?: verdict.formats.first()
                        } else {
                            null
                        }
                    }
                    is Verdict.Gated -> null
                }
            }
        } catch (e: Exception) {
            return null
        }
    }

    sealed interface Verdict {
        data class Ok(val formats: List<ResolvedStream>, val cipheredCount: Int) : Verdict
        data class Gated(val status: String, val reason: String) : Verdict
    }

    fun gate(rawBytes: ByteArray, statuses: MutableCollection<String>? = null): Verdict {
        // Pure Kotlin Innertube JSON Parser (Single Parser, Single Policy, Direct-URL only)
        return kotlinParseVerdict(rawBytes, statuses)
    }

    private fun kotlinParseVerdict(
        rawBytes: ByteArray,
        statuses: MutableCollection<String>? = null
    ): Verdict {
        try {
            val root = JSONObject(String(rawBytes, Charsets.UTF_8))
            val playabilityStatus = root.optJSONObject("playabilityStatus")
            val status = playabilityStatus?.optString("status", "")
            val reason = playabilityStatus?.optString("reason", "") ?: ""

            if (status != null && !status.equals("OK", ignoreCase = true) && status.isNotBlank()) {
                statuses?.add(status.uppercase())
                return Verdict.Gated(status, reason)
            }

            val streamingData = root.optJSONObject("streamingData") ?: return Verdict.Gated("NO_STREAMING_DATA", "")
            val durationSec = root.optJSONObject("videoDetails")?.optString("lengthSeconds", "0")?.toIntOrNull() ?: 0

            val candidateFormats = mutableListOf<ResolvedStream>()
            var cipheredCount = 0

            fun processFormats(arr: JSONArray?) {
                if (arr == null) return
                for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    val mime = f.optString("mimeType", "")
                    val isAudio = mime.startsWith("audio/")
                    val streamUrl = extractUrlFromFormat(f)
                    val isCipher = f.has("signatureCipher") || f.has("cipher")

                    if (isAudio && streamUrl.isNotBlank()) {
                        val bitrate = f.optInt("bitrate", f.optInt("averageBitrate", 160000))
                        fun fmtLoud(obj: JSONObject): Float? {
                            obj.optDouble("loudnessDb", Double.NaN).takeIf { !it.isNaN() }?.let { return it.toFloat() }
                            obj.optJSONObject("volumeNormalizationInfo")?.optDouble("loudnessDb", Double.NaN)?.takeIf { !it.isNaN() }?.let { return it.toFloat() }
                            return null
                        }
                        val loudness = fmtLoud(f)
                            ?: root.optJSONObject("playerConfig")?.optJSONObject("audioConfig")
                                ?.optDouble("loudnessDb", Double.NaN)?.takeIf { !it.isNaN() }?.toFloat()
                        candidateFormats.add(
                            ResolvedStream(
                                streamUrl = streamUrl,
                                mimeType = mime,
                                bitrate = bitrate,
                                durationSec = durationSec,
                                loudnessDb = loudness
                            )
                        )
                    } else if (isAudio && isCipher) {
                        cipheredCount++
                    }
                }
            }

            processFormats(streamingData.optJSONArray("adaptiveFormats"))
            processFormats(streamingData.optJSONArray("formats"))

            return Verdict.Ok(candidateFormats, cipheredCount)
        } catch (e: Exception) {
            return Verdict.Gated("PARSE_ERROR", e.message ?: "")
        }
    }

    private fun extractUrlFromFormat(format: JSONObject): String {
        val directUrl = format.optString("url", "")
        if (directUrl.isNotBlank()) {
            return directUrl
        }

        // Check for signatureCipher or cipher
        val cipher = format.optString("signatureCipher", format.optString("cipher", ""))
        if (cipher.isNotBlank()) {
            try {
                val params = cipher.split("&").associate { param ->
                    val pair = param.split("=", limit = 2)
                    if (pair.size == 2) {
                        pair[0] to java.net.URLDecoder.decode(pair[1], "UTF-8")
                    } else {
                        "" to ""
                    }
                }
                val rawUrl = params["url"]
                if (!rawUrl.isNullOrBlank()) {
                    val sig = params["s"]
                    val sp = params["sp"] ?: "sig"
                    return if (!sig.isNullOrBlank()) {
                        if (rawUrl.contains("?")) "$rawUrl&$sp=$sig" else "$rawUrl?$sp=$sig"
                    } else {
                        rawUrl
                    }
                }
            } catch (e: Exception) {
                // Ignore cipher parsing error
            }
        }
        return ""
    }


    private val VIDEO_CLIENT_TARGETS = listOf(
        ClientConfig(
            clientName = "ANDROID",
            clientVersion = "21.26.364",
            clientNumber = "3",
            userAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
            osName = "Android",
            osVersion = "11"
        ),
        ClientConfig(
            clientName = "ANDROID_VR",
            clientVersion = "1.60.19",
            clientNumber = "28",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Quest 3) AppleWebKit/537.36 (KHTML, like Gecko) OculusBrowser/33.0.0.19.46.568453472 SamsungBrowser/4.0 Chrome/122.0.6261.139 Mobile VR Safari/537.36",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            osName = "Android",
            osVersion = "12"
        ),
        ClientConfig(
            clientName = "IOS",
            clientVersion = "21.26.4",
            clientNumber = "5",
            userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            osName = "iPhone",
            osVersion = "18.3.2.22D82"
        )
    )

    suspend fun resolveVideoStreamUrl(track: com.streamify.app.data.models.Track): ResolvedStream? = withContext(Dispatchers.IO) {
        // 1. Strict Primary: Use track.ytmVideoId if valid, then extract from filepath/coverArt
        val directId = track.ytmVideoId?.takeIf { YT_ID_REGEX.matches(it) }
            ?: extractVideoId(track.filepath, track.coverArtPath)

        val videoId = if (!directId.isNullOrBlank() && YT_ID_REGEX.matches(directId)) {
            directId
        } else {
            CanonicalSeedResolver.resolveToCanonicalId(track).takeIf { it.matches(YT_ID_REGEX) }
        } ?: return@withContext null

        // 1. Zero-RTT Edge Cache Check
        val cached = StreamEdgeCache.getVideoStream(videoId)
        if (cached != null) {
            return@withContext cached
        }


        // 2. Parallel Standard Client Video Stream Racing (ANDROID_VR / IOS / TV)
        val resolved = raceClientVideoEndpoints(videoId)
        if (resolved != null && resolved.streamUrl.isNotBlank()) {
            StreamEdgeCache.putVideoStream(videoId, resolved)
            return@withContext resolved
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
            val clientJson = JSONObject().apply {
                put("clientName", config.clientName)
                put("clientVersion", config.clientVersion)
                put("hl", "en")
                put("gl", "US")
                config.deviceMake?.let { put("deviceMake", it) }
                config.deviceModel?.let { put("deviceModel", it) }
                config.osName?.let { put("osName", it) }
                config.osVersion?.let { put("osVersion", it) }
                if (config.clientName.contains("ANDROID", ignoreCase = true)) {
                    put("androidSdkVersion", 34)
                }
            }

            val requestJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", clientJson)
                })
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                put("playbackContext", JSONObject().apply {
                    put("contentPlaybackContext", JSONObject().apply {
                        put("signatureTimestamp", currentSignatureTimestamp())
                        put("html5Preference", "HTML5_PREF_WANTS")
                    })
                })
            }

            val reqBuilder = Request.Builder()
                .url(INNERTUBE_PLAYER_URL)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("User-Agent", config.userAgent)
                .header("Accept", "*/*")
                .header("X-YouTube-Client-Name", config.clientNumber)
                .header("X-YouTube-Client-Version", config.clientVersion)
                .also { if (config.attachSession) attachYtSession(it) }
                .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))

            if (!config.origin.isNullOrBlank()) {
                reqBuilder.header("Origin", config.origin)
            }
            if (!config.referer.isNullOrBlank()) {
                reqBuilder.header("Referer", config.referer)
            }

            val request = reqBuilder.build()

            NetworkEngine.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                val responseBody = body.string()

                if (responseBody.isBlank()) return null
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
                    val url = extractUrlFromFormat(f)
                    if (url.isNotBlank()) {
                        f.put("extractedUrl", url)
                        progressiveList.add(f)
                    }
                }
            }

            // 1. Check progressive formats (itag 22 = 720p HD MP4 with AAC, itag 18 = 360p)
            val bestProgressive = progressiveList.firstOrNull { it.optInt("itag") == 22 }
                ?: progressiveList.firstOrNull { it.optInt("itag") == 18 }
                ?: progressiveList.firstOrNull()

            if (bestProgressive != null && bestProgressive.optInt("itag") == 22) {
                val streamUrl = bestProgressive.optString("extractedUrl", bestProgressive.optString("url", ""))
                val mimeType = bestProgressive.optString("mimeType", "video/mp4")
                val bitrate = bestProgressive.optInt("bitrate", 2500000)
                if (streamUrl.isNotBlank()) {
                    return ResolvedStream(
                        streamUrl = streamUrl,
                        mimeType = mimeType,
                        bitrate = bitrate,
                        durationSec = durationSec
                    )
                }
            }

            // 2. Check adaptive video streams for 1080p Full HD (itag 137/248) or 720p HD (itag 136/247)
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            val adaptiveVideoList = mutableListOf<JSONObject>()
            if (adaptiveFormats != null) {
                for (i in 0 until adaptiveFormats.length()) {
                    val f = adaptiveFormats.getJSONObject(i)
                    val mime = f.optString("mimeType", "")
                    if (mime.startsWith("video/")) {
                        val url = extractUrlFromFormat(f)
                        if (url.isNotBlank()) {
                            f.put("extractedUrl", url)
                            adaptiveVideoList.add(f)
                        }
                    }
                }
            }

            val bestAdaptiveVideo = adaptiveVideoList.firstOrNull { it.optInt("itag") == 137 } // 1080p MP4
                ?: adaptiveVideoList.firstOrNull { it.optInt("itag") == 248 } // 1080p WebM
                ?: adaptiveVideoList.firstOrNull { it.optInt("itag") == 136 } // 720p MP4
                ?: adaptiveVideoList.firstOrNull { it.optInt("itag") == 247 } // 720p WebM
                ?: adaptiveVideoList.maxByOrNull { it.optInt("bitrate", 0) }

            if (bestAdaptiveVideo != null) {
                val streamUrl = bestAdaptiveVideo.optString("extractedUrl", bestAdaptiveVideo.optString("url", ""))
                val mime = bestAdaptiveVideo.optString("mimeType", "video/mp4")
                val bitrate = bestAdaptiveVideo.optInt("bitrate", 4500000)
                if (streamUrl.isNotBlank()) {
                    return ResolvedStream(
                        streamUrl = streamUrl,
                        mimeType = mime,
                        bitrate = bitrate,
                        durationSec = durationSec
                    )
                }
            }

            // 3. Fallback to progressive MP4
            if (bestProgressive != null) {
                val streamUrl = bestProgressive.optString("extractedUrl", bestProgressive.optString("url", ""))
                val mimeType = bestProgressive.optString("mimeType", "video/mp4")
                val bitrate = bestProgressive.optInt("bitrate", 1200000)
                if (streamUrl.isNotBlank()) {
                    return ResolvedStream(
                        streamUrl = streamUrl,
                        mimeType = mimeType,
                        bitrate = bitrate,
                        durationSec = durationSec
                    )
                }
            }

            return null
        } catch (e: Exception) {
            return null
        }
    }
}
