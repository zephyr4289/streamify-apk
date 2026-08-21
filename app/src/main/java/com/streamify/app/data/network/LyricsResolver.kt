package com.streamify.app.data.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.roundToLong

object LyricsResolver {

    private const val USER_AGENT_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val ANDROID_YTM_UA = "com.google.android.apps.youtube.music/7.21.50 (Linux; U; Android 14)"
    private const val YTM_KEY = "AIzaSyC1xlRQImGslL28Q8HqTqD_o-w-r2Q_Z4"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    @Volatile
    private var cachedMxmToken: String? = null

    // =====================================================================
    // 1. UNIVERSAL METADATA SANITIZERS (PLAN 23 / 24)
    // =====================================================================
    private fun cleanTitle(rawTitle: String): String {
        if (rawTitle.isBlank()) return ""
        var title = rawTitle

        // Split compound titles like "Title – Artist" or "Artist - Title"
        val delimiters = listOf(" – ", " — ", " - ", " // ", " | ")
        for (delim in delimiters) {
            if (title.contains(delim)) {
                val parts = title.split(delim)
                if (parts.size == 2) {
                    title = parts[0].trim()
                }
            }
        }

        // Strip parenthetical and bracket noise
        val noiseRegex = Regex(
            """(?i)\((?:official|audio|video|remastered|remaster|radio edit|edit|deluxe|version|feat\.|feat|ft\.|with|bonus|live|acoustic|anniversary|lyrics|lyric video|hd|4k|mv|topic|vevo).*?\)|\[.*?\]"""
        )
        title = noiseRegex.replace(title, "").trim()
        return title.ifBlank { rawTitle.trim() }
    }

    private fun cleanArtist(rawArtist: String): String {
        if (rawArtist.isBlank()) return ""
        var artist = rawArtist
            .replace(Regex("""(?i)(\s*-\s*Topic|\s*VEVO|\s*Official)"""), "")
            .replace(Regex("""(?i)(feat\.|ft\.).*"""), "")
            .split(',')[0]
            .trim()
        return artist.ifBlank { rawArtist.trim() }
    }

    // =====================================================================
    // 2. MAIN PARALLEL RACE & ARBITRATION ENTRYPOINT
    // =====================================================================
    suspend fun fetchSyncedLyrics(
        title: String,
        artist: String,
        durationSec: Int = 0,
        videoId: String = ""
    ): String? = withContext(Dispatchers.IO) {
        val sanitizedTitle = cleanTitle(title)
        val sanitizedArtist = cleanArtist(artist)

        if (sanitizedTitle.isBlank()) return@withContext null

        // Parallel Async Race: YouTube Music ATV (0.00s drift) + Musixmatch Syllable + LRCLIB
        val result = coroutineScope {
            val ytmDeferred = async {
                withTimeoutOrNull(3500L) {
                    fetchYouTubeMusicTimedLyrics(sanitizedTitle, sanitizedArtist, videoId)
                }
            }
            val mxmDeferred = async {
                withTimeoutOrNull(3500L) {
                    fetchMusixmatchSyllableLyrics(sanitizedTitle, sanitizedArtist)
                }
            }
            val lrcDeferred = async {
                withTimeoutOrNull(2500L) {
                    fetchLrclibExact(sanitizedTitle, sanitizedArtist, durationSec)
                }
            }

            val mxmRes = mxmDeferred.await()
            val ytmRes = ytmDeferred.await()
            val lrcRes = lrcDeferred.await()

            // Hierarchy: 
            // 1. Musixmatch Syllable RichSync (Word-by-word karaoke)
            if (!mxmRes.isNullOrBlank() && mxmRes.contains("<")) {
                return@coroutineScope mxmRes
            }
            // 2. YouTube Music TimedLyrics (0.00s Video Sync)
            if (!ytmRes.isNullOrBlank() && ytmRes.contains("[")) {
                return@coroutineScope ytmRes
            }
            // 3. Musixmatch Line-level
            if (!mxmRes.isNullOrBlank() && mxmRes.contains("[")) {
                return@coroutineScope mxmRes
            }
            // 4. LRCLIB Exact
            if (!lrcRes.isNullOrBlank() && lrcRes.contains("[")) {
                return@coroutineScope lrcRes
            }
            // 5. Plain / Fuzzy Fallbacks
            val fallback = raceFuzzyFallback(sanitizedTitle, sanitizedArtist)
            if (!fallback.isNullOrBlank()) {
                if (fallback.contains("[")) return@coroutineScope fallback
                return@coroutineScope synthesizeSyncedLyricsFromPlain(fallback, durationSec)
            }
            return@coroutineScope null
        }

        result
    }

    // =====================================================================
    // 3. YOUTUBE MUSIC CANONICAL ATV SWEEP ENGINE (PLAN 23 / 24)
    // =====================================================================
    private fun fetchYouTubeMusicTimedLyrics(title: String, artist: String, fallbackVid: String = ""): String? {
        try {
            val candidates = resolveTopAtvCandidates(title, artist)
            val candidateList = candidates.toMutableList()
            if (fallbackVid.isNotBlank() && !candidateList.contains(fallbackVid)) {
                candidateList.add(fallbackVid)
            }

            for (vid in candidateList) {
                val timedLrc = tryExtractYtmTimedLyrics(vid)
                if (!timedLrc.isNullOrBlank()) {
                    return timedLrc
                }
            }
        } catch (_: Throwable) {
            // Ignore single provider error
        }
        return null
    }

    private fun resolveTopAtvCandidates(title: String, artist: String): List<String> {
        val ids = mutableListOf<String>()
        try {
            val searchUrl = "https://music.youtube.com/youtubei/v1/search?key=$YTM_KEY&prettyPrint=false"
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID_MUSIC")
                        put("clientVersion", "7.21.50")
                        put("androidSdkVersion", 34)
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("query", "$title $artist")
                put("params", "EgWKAQIIAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D") // Filter: Songs (ATVs)
            }

            val request = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", ANDROID_YTM_UA)
                .header("X-YouTube-Client-Name", "21")
                .header("X-YouTube-Client-Version", "7.21.50")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            NetworkEngine.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return ids
                val body = response.body?.string() ?: return ids
                val json = JSONObject(body)

                // 1. Check musicCardShelfRenderer (Top result card)
                try {
                    val tabs = json.optJSONObject("contents")
                        ?.optJSONObject("tabbedSearchResultsRenderer")
                        ?.optJSONArray("tabs")
                    val contents = tabs?.optJSONObject(0)
                        ?.optJSONObject("tabRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("sectionListRenderer")
                        ?.optJSONArray("contents")

                    val cardShelf = contents?.optJSONObject(0)?.optJSONObject("musicCardShelfRenderer")
                    val topId = cardShelf?.optJSONObject("onTap")
                        ?.optJSONObject("watchEndpoint")
                        ?.optString("videoId", "")
                    if (!topId.isNullOrBlank() && !ids.contains(topId)) {
                        ids.add(topId)
                    }

                    // 2. Dual-Shelf Fallback: Check musicShelfRenderer items (Recommendation 1 of Plan 24)
                    for (cIdx in 0 until (contents?.length() ?: 0)) {
                        val shelf = contents?.optJSONObject(cIdx)?.optJSONObject("musicShelfRenderer")
                        val items = shelf?.optJSONArray("contents")
                        if (items != null) {
                            for (i in 0 until items.length().coerceAtMost(3)) {
                                val item = items.optJSONObject(i)?.optJSONObject("musicResponsiveListItemRenderer")
                                val vid = item?.optJSONObject("playlistItemData")?.optString("videoId", "")
                                    ?: item?.optJSONObject("doubleTapEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId", "")
                                if (!vid.isNullOrBlank() && !ids.contains(vid)) {
                                    ids.add(vid)
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {}

                // Fallback scan if tree was different
                if (ids.isEmpty()) {
                    val matcher = Regex(""""videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""").findAll(body)
                    for (m in matcher) {
                        val vid = m.groupValues[1]
                        if (!ids.contains(vid)) {
                            ids.add(vid)
                            if (ids.size >= 3) break
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return ids.take(3)
    }

    private fun tryExtractYtmTimedLyrics(videoId: String): String? {
        try {
            // Step 1: /next to resolve MPLYt_ browseId
            val nextUrl = "https://music.youtube.com/youtubei/v1/next?key=$YTM_KEY&prettyPrint=false"
            val nextPayload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID_MUSIC")
                        put("clientVersion", "7.21.50")
                        put("androidSdkVersion", 34)
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("videoId", videoId)
                put("isAudioOnly", true)
            }

            val nextReq = Request.Builder()
                .url(nextUrl)
                .header("User-Agent", ANDROID_YTM_UA)
                .header("X-YouTube-Client-Name", "21")
                .header("X-YouTube-Client-Version", "7.21.50")
                .post(nextPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            var lyricBrowseId: String? = null
            NetworkEngine.client.newCall(nextReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)

                val tabs = json.optJSONObject("contents")
                    ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
                    ?.optJSONObject("tabbedRenderer")
                    ?.optJSONObject("watchNextTabbedResultsRenderer")
                    ?.optJSONArray("tabs")
                    ?: json.optJSONObject("contents")
                        ?.optJSONObject("twoColumnBrowseResultsRenderer")
                        ?.optJSONArray("tabs")

                if (tabs != null) {
                    for (i in 0 until tabs.length()) {
                        val tab = tabs.optJSONObject(i)?.optJSONObject("tabRenderer")
                        val endpoint = tab?.optJSONObject("endpoint")?.optJSONObject("browseEndpoint")?.optString("browseId", "") ?: ""
                        val title = tab?.optString("title", "") ?: ""
                        if (title.contains("LYRIC", ignoreCase = true) || endpoint.startsWith("MPLYt_")) {
                            lyricBrowseId = endpoint
                            break
                        }
                    }
                }
            }

            if (lyricBrowseId.isNullOrBlank()) return null

            // Step 2: /browse to extract timedLyricsData model
            val browseUrl = "https://music.youtube.com/youtubei/v1/browse?key=$YTM_KEY&prettyPrint=false"
            val browsePayload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID_MUSIC")
                        put("clientVersion", "7.21.50")
                        put("androidSdkVersion", 34)
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("browseId", lyricBrowseId)
            }

            val browseReq = Request.Builder()
                .url(browseUrl)
                .header("User-Agent", ANDROID_YTM_UA)
                .header("X-YouTube-Client-Name", "21")
                .header("X-YouTube-Client-Version", "7.21.50")
                .post(browsePayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            NetworkEngine.client.newCall(browseReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)

                // Search for timedLyricsData in json tree
                val timedData = findTimedLyricsInJson(json)
                if (timedData != null && timedData.length() > 0) {
                    val sb = StringBuilder()
                    for (i in 0 until timedData.length()) {
                        val item = timedData.optJSONObject(i) ?: continue
                        val cue = item.optJSONObject("cueRange")
                        val startMsStr = cue?.optString("startTimeMilliseconds", "0") ?: "0"
                        val startMs = startMsStr.toLongOrNull() ?: 0L
                        val text = item.optString("lyricLine", "").trim()
                        if (text.isNotBlank()) {
                            val mm = startMs / 60000
                            val ss = (startMs % 60000) / 1000
                            val xx = (startMs % 1000) / 10
                            sb.append(String.format(java.util.Locale.US, "[%02d:%02d.%02d]%s\n", mm, ss, xx, text))
                        }
                    }
                    val lrcResult = sb.toString().trim()
                    if (lrcResult.isNotBlank()) return lrcResult
                }

                // Fallback to static text
                val runs = json.optJSONObject("contents")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")
                    ?.optJSONObject(0)
                    ?.optJSONObject("musicDescriptionShelfRenderer")
                    ?.optJSONObject("description")
                    ?.optJSONArray("runs")

                if (runs != null && runs.length() > 0) {
                    val staticText = runs.optJSONObject(0)?.optString("text", "")
                    if (!staticText.isNullOrBlank()) return staticText
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun findTimedLyricsInJson(obj: Any?): JSONArray? {
        if (obj is JSONObject) {
            if (obj.has("timedLyricsData")) {
                return obj.optJSONArray("timedLyricsData")
            }
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val res = findTimedLyricsInJson(obj.opt(k))
                if (res != null) return res
            }
        } else if (obj is JSONArray) {
            for (i in 0 until obj.length()) {
                val res = findTimedLyricsInJson(obj.opt(i))
                if (res != null) return res
            }
        }
        return null
    }

    // =====================================================================
    // 4. MUSIXMATCH LEAN MACRO & SYLLABLE ENGINE (PLAN 23 / 24)
    // =====================================================================
    private fun getMusixmatchToken(): String {
        cachedMxmToken?.let { return it }
        try {
            val url = "https://apic-desktop.musixmatch.com/ws/1.1/token.get?app_id=web-desktop-app-v1.0"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT_DESKTOP)
                .get()
                .build()

            NetworkEngine.client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JSONObject(body)
                    val token = json.optJSONObject("message")?.optJSONObject("body")?.optString("user_token", "")
                    if (!token.isNullOrBlank() && token != "Upgrade.me") {
                        cachedMxmToken = token
                        return token
                    }
                }
            }
        } catch (_: Throwable) {}
        val fallback = "240228000000000000000000000000"
        cachedMxmToken = fallback
        return fallback
    }

    private fun fetchMusixmatchSyllableLyrics(title: String, artist: String): String? {
        try {
            val token = getMusixmatchToken()
            val qTrack = URLEncoder.encode(title, "UTF-8")
            val qArtist = URLEncoder.encode(artist, "UTF-8")

            // Call 1: macro.subtitles.get (Returns RichSync + Subtitle list in 1 roundtrip)
            val macroUrl = "https://apic-desktop.musixmatch.com/ws/1.1/macro.subtitles.get?" +
                    "format=json&namespace=lyrics_richsynched&subtitle_format=mxm" +
                    "&app_id=web-desktop-app-v1.0&usertoken=$token" +
                    "&q_track=$qTrack&q_artist=$qArtist"

            val req = Request.Builder()
                .url(macroUrl)
                .header("User-Agent", USER_AGENT_DESKTOP)
                .header("Authority", "apic-desktop.musixmatch.com")
                .header("Cookie", "x-mxm-token-id=$token")
                .get()
                .build()

            NetworkEngine.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val macroCalls = json.optJSONObject("message")?.optJSONObject("body")?.optJSONObject("macro_calls")

                // 1. Syllable-Level RichSync
                val richsync = macroCalls?.optJSONObject("track.richsync.get")
                    ?.optJSONObject("message")
                    ?.optJSONObject("body")
                    ?.optJSONObject("richsync")
                val richsyncBody = richsync?.optString("richsync_body", "")

                if (!richsyncBody.isNullOrBlank()) {
                    val syllableLrc = convertMxmRichsyncToSyllableLrc(richsyncBody)
                    if (!syllableLrc.isNullOrBlank()) return syllableLrc
                }

                // 2. Line-Level Subtitle list fallback
                val subList = macroCalls?.optJSONObject("track.subtitles.get")
                    ?.optJSONObject("message")
                    ?.optJSONObject("body")
                    ?.optJSONArray("subtitle_list")

                if (subList != null && subList.length() > 0) {
                    val subBody = subList.optJSONObject(0)?.optJSONObject("subtitle")?.optString("subtitle_body", "")
                    if (!subBody.isNullOrBlank()) {
                        if (subBody.startsWith("[") && subBody.contains("]")) return subBody
                    }
                }
            }

            // Call 2: Query search fallback if strict matcher missed
            val qUnified = URLEncoder.encode("$title $artist", "UTF-8")
            val searchUrl = "https://apic-desktop.musixmatch.com/ws/1.1/track.search?" +
                    "format=json&page_size=1&s_track_rating=desc&f_has_richsync=1" +
                    "&app_id=web-desktop-app-v1.0&usertoken=$token&q=$qUnified"

            val sReq = Request.Builder().url(searchUrl).header("User-Agent", USER_AGENT_DESKTOP).get().build()
            NetworkEngine.client.newCall(sReq).execute().use { sResp ->
                if (!sResp.isSuccessful) return null
                val sBody = sResp.body?.string() ?: return null
                val sJson = JSONObject(sBody)
                val trackList = sJson.optJSONObject("message")?.optJSONObject("body")?.optJSONArray("track_list")
                if (trackList != null && trackList.length() > 0) {
                    val trackId = trackList.optJSONObject(0)?.optJSONObject("track")?.optLong("track_id", 0L) ?: 0L
                    if (trackId > 0) {
                        val richUrl = "https://apic-desktop.musixmatch.com/ws/1.1/track.richsync.get?" +
                                "format=json&subtitle_format=mxm&app_id=web-desktop-app-v1.0&usertoken=$token&track_id=$trackId"
                        val rReq = Request.Builder().url(richUrl).header("User-Agent", USER_AGENT_DESKTOP).get().build()
                        NetworkEngine.client.newCall(rReq).execute().use { rResp ->
                            if (rResp.isSuccessful) {
                                val rBody = rResp.body?.string() ?: ""
                                val rJson = JSONObject(rBody)
                                val rawRich = rJson.optJSONObject("message")?.optJSONObject("body")?.optJSONObject("richsync")?.optString("richsync_body", "")
                                if (!rawRich.isNullOrBlank()) {
                                    return convertMxmRichsyncToSyllableLrc(rawRich)
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * Converts Musixmatch RichSync JSON array to Syllable-Tagged LRC string with explicit rounding (Plan 24 Recommendation 3)
     * Output format: [00:12.45]<00:12.45>Word1 <00:12.80>Word2
     */
    private fun convertMxmRichsyncToSyllableLrc(richsyncJson: String): String? {
        try {
            val array = JSONArray(richsyncJson)
            if (array.length() == 0) return null

            val sb = StringBuilder()
            for (i in 0 until array.length()) {
                val lineObj = array.optJSONObject(i) ?: continue
                val tsSec = lineObj.optDouble("ts", 0.0)
                val lineStartMs = (tsSec * 1000.0).roundToLong()

                val lMin = (lineStartMs / 60000).toInt()
                val lSec = ((lineStartMs % 60000) / 1000).toInt()
                val lMs = ((lineStartMs % 1000) / 10).toInt()

                sb.append(String.format(java.util.Locale.US, "[%02d:%02d.%02d]", lMin, lSec, lMs))

                val wordsArray = lineObj.optJSONArray("l")
                if (wordsArray != null && wordsArray.length() > 0) {
                    for (w in 0 until wordsArray.length()) {
                        val wordObj = wordsArray.optJSONObject(w) ?: continue
                        val wordText = wordObj.optString("c", "")
                        val offsetSec = wordObj.optDouble("o", 0.0)
                        val wordStartMs = lineStartMs + (offsetSec * 1000.0).roundToLong()

                        val wMin = (wordStartMs / 60000).toInt()
                        val wSec = ((wordStartMs % 60000) / 1000).toInt()
                        val wMs = ((wordStartMs % 1000) / 10).toInt()

                        sb.append(String.format(java.util.Locale.US, "<%02d:%02d.%02d>%s", wMin, wSec, wMs, wordText))
                    }
                } else {
                    val lineText = lineObj.optString("x", "")
                    sb.append(lineText)
                }
                sb.append("\n")
            }
            val result = sb.toString().trim()
            return if (result.isNotBlank()) result else null
        } catch (_: Throwable) {
            return null
        }
    }

    // =====================================================================
    // 5. LRCLIB & NETEASE RESILIENT FALLBACKS
    // =====================================================================
    private fun fetchLrclibExact(title: String, artist: String, durationSec: Int): String? {
        try {
            var url = "https://lrclib.net/api/get?track_name=${URLEncoder.encode(title, "UTF-8")}&artist_name=${URLEncoder.encode(artist, "UTF-8")}"
            if (durationSec > 0) {
                url += "&duration=$durationSec"
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT_DESKTOP)
                .get()
                .build()

            NetworkEngine.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val synced = json.optString("syncedLyrics", "")
                if (synced.isNotBlank()) return synced
                val plain = json.optString("plainLyrics", "")
                if (plain.isNotBlank()) return plain
            }
        } catch (_: Exception) {}
        return null
    }

    private suspend fun raceFuzzyFallback(title: String, artist: String): String? = coroutineScope {
        val winnerDeferred = CompletableDeferred<String?>()

        val tasks = listOf(
            async { fetchLrclibFuzzy(title, artist) },
            async { fetchNetEase(title, artist) }
        )

        tasks.forEach { task ->
            task.invokeOnCompletion {
                try {
                    val res = task.getCompleted()
                    if (!res.isNullOrBlank() && (res.contains("[") || res.length > 20)) {
                        winnerDeferred.complete(res)
                    }
                } catch (_: Exception) {}
                if (tasks.all { it.isCompleted } && !winnerDeferred.isCompleted) {
                    winnerDeferred.complete(null)
                }
            }
        }

        val winner = winnerDeferred.await()
        tasks.forEach { if (!it.isCompleted) it.cancel() }
        winner
    }

    private fun fetchLrclibFuzzy(title: String, artist: String): String? {
        try {
            val q = URLEncoder.encode("$title $artist", "UTF-8")
            val url = "https://lrclib.net/api/search?q=$q"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT_DESKTOP)
                .get()
                .build()

            NetworkEngine.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val array = JSONArray(body)
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val synced = item.optString("syncedLyrics", "")
                    if (synced.isNotBlank()) return synced
                }
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val plain = item.optString("plainLyrics", "")
                    if (plain.isNotBlank()) return plain
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun fetchNetEase(title: String, artist: String): String? {
        try {
            val q = URLEncoder.encode("$title $artist", "UTF-8")
            val searchUrl = "https://music.163.com/api/search/get/web?s=$q&type=1&offset=0&total=true&limit=1"

            val reqSearch = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT_DESKTOP)
                .header("Referer", "https://music.163.com/")
                .get()
                .build()

            NetworkEngine.client.newCall(reqSearch).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val root = JSONObject(body)
                val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return null
                if (songs.length() == 0) return null
                val songId = songs.getJSONObject(0).optLong("id", 0L)
                if (songId <= 0) return null

                val lrcUrl = "https://music.163.com/api/song/lyric?os=pc&id=$songId&lv=-1&kv=-1&tv=-1"
                val reqLrc = Request.Builder().url(lrcUrl).header("User-Agent", USER_AGENT_DESKTOP).get().build()
                NetworkEngine.client.newCall(reqLrc).execute().use { lrcResp ->
                    if (!lrcResp.isSuccessful) return null
                    val lrcBody = lrcResp.body?.string() ?: return null
                    val lrcJson = JSONObject(lrcBody)
                    val lrcStr = lrcJson.optJSONObject("lrc")?.optString("lyric", "")
                    if (!lrcStr.isNullOrBlank() && lrcStr.contains("[")) return lrcStr
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun synthesizeSyncedLyricsFromPlain(plainText: String, durationSec: Int): String {
        try {
            val durationMs = if (durationSec > 0) durationSec * 1000 else 180000
            val nativeJson = com.streamify.app.data.NativeBridge.rustAlignAndCompileLyrics(plainText, durationMs, null)
            if (!nativeJson.isNullOrBlank()) {
                val array = JSONArray(nativeJson)
                val sb = StringBuilder()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val startMs = obj.optInt("start_ms", 0)
                    val lineText = obj.optString("line_text", "")
                    val mm = startMs / 60000
                    val ss = (startMs % 60000) / 1000
                    val xx = (startMs % 1000) / 10
                    sb.append(String.format(java.util.Locale.US, "[%02d:%02d.%02d]%s\n", mm, ss, xx, lineText))
                }
                val result = sb.toString().trim()
                if (result.isNotBlank()) return result
            }
        } catch (_: Throwable) {}
        return plainText
    }
}

