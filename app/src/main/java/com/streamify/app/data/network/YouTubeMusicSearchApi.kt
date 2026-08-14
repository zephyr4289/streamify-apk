package com.streamify.app.data.network

import com.streamify.app.viewmodel.OnlineSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

object YouTubeMusicSearchApi {

    private const val INNERTUBE_MUSIC_SEARCH_URL = "https://music.youtube.com/youtubei/v1/search"
    private const val INNERTUBE_YT_SEARCH_URL = "https://www.youtube.com/youtubei/v1/search"
    private const val SUGGEST_URL = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q="
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

    suspend fun search(query: String, maxResults: Int = 25): List<OnlineSearchResult> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        // 1. Try YouTube Music Search (General without strict params filter)
        val musicResults = executeInnertubeSearch(cleanQuery, INNERTUBE_MUSIC_SEARCH_URL, isMusic = true, maxResults = maxResults)
        if (musicResults.isNotEmpty()) {
            return@withContext musicResults
        }

        // 2. Fallback to standard YouTube Innertube search for maximum coverage
        val ytResults = executeInnertubeSearch(cleanQuery, INNERTUBE_YT_SEARCH_URL, isMusic = false, maxResults = maxResults)
        return@withContext ytResults
    }

    suspend fun fetchSearchSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.length < 2) return@withContext emptyList()

        try {
            val url = SUGGEST_URL + URLEncoder.encode(clean, "UTF-8")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()

            NetworkEngine.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()

                // Response format: ["query", ["suggestion1", "suggestion2", ...]]
                val jsonArray = JSONArray(body)
                if (jsonArray.length() >= 2) {
                    val suggestionsArray = jsonArray.optJSONArray(1) ?: return@withContext emptyList()
                    val suggestions = mutableListOf<String>()
                    for (i in 0 until suggestionsArray.length()) {
                        val item = suggestionsArray.optString(i, "").trim()
                        if (item.isNotBlank() && !suggestions.contains(item)) {
                            suggestions.add(item)
                        }
                    }
                    return@withContext suggestions.take(6)
                }
            }
        } catch (e: Exception) {
            // Ignore suggestion network exceptions
        }
        return@withContext emptyList()
    }

    private fun executeInnertubeSearch(query: String, endpointUrl: String, isMusic: Boolean, maxResults: Int): List<OnlineSearchResult> {
        try {
            val clientName = if (isMusic) "WEB_REMIX" else "WEB"
            val clientVersion = if (isMusic) "1.20230515.01.00" else "2.20230515.01.00"

            val requestJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", clientName)
                        put("clientVersion", clientVersion)
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("query", query)
            }

            val request = Request.Builder()
                .url(endpointUrl)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "gzip, deflate")
                .header("Origin", if (isMusic) "https://music.youtube.com" else "https://www.youtube.com")
                .header("Referer", if (isMusic) "https://music.youtube.com/" else "https://www.youtube.com/")
                .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            NetworkEngine.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()

                val body = response.body ?: return emptyList()
                val encoding = response.header("Content-Encoding", "")

                val responseBody = if ("gzip".equals(encoding, ignoreCase = true)) {
                    GZIPInputStream(body.byteStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
                } else {
                    body.string()
                }

                val root = JSONObject(responseBody)
                return parseInnertubeResponse(root, maxResults)
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private fun parseInnertubeResponse(root: JSONObject, maxResults: Int): List<OnlineSearchResult> {
        val results = mutableListOf<OnlineSearchResult>()
        try {
            findMusicItems(root, results, maxResults)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return results
    }

    private fun findMusicItems(obj: Any?, list: MutableList<OnlineSearchResult>, maxResults: Int) {
        if (list.size >= maxResults || obj == null) return

        when (obj) {
            is JSONObject -> {
                if (obj.has("musicResponsiveListItemRenderer")) {
                    val item = parseMusicItem(obj.getJSONObject("musicResponsiveListItemRenderer"))
                    if (item != null && list.none { it.url == item.url }) {
                        list.add(item)
                        if (list.size >= maxResults) return
                    }
                } else if (obj.has("videoRenderer")) {
                    val item = parseVideoRenderer(obj.getJSONObject("videoRenderer"))
                    if (item != null && list.none { it.url == item.url }) {
                        list.add(item)
                        if (list.size >= maxResults) return
                    }
                } else if (obj.has("compactVideoRenderer")) {
                    val item = parseVideoRenderer(obj.getJSONObject("compactVideoRenderer"))
                    if (item != null && list.none { it.url == item.url }) {
                        list.add(item)
                        if (list.size >= maxResults) return
                    }
                } else {
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        findMusicItems(obj.opt(key), list, maxResults)
                        if (list.size >= maxResults) return
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until obj.length()) {
                    findMusicItems(obj.opt(i), list, maxResults)
                    if (list.size >= maxResults) return
                }
            }
        }
    }

    private fun parseMusicItem(itemObj: JSONObject): OnlineSearchResult? {
        try {
            var videoId = ""
            var title = "Unknown"
            var uploader = "Unknown"
            var duration = 0
            var thumbnail = ""

            // 1. Extract Video ID
            if (itemObj.has("playlistItemData")) {
                videoId = itemObj.optJSONObject("playlistItemData")?.optString("videoId", "") ?: ""
            }
            if (videoId.isBlank()) {
                val ep = itemObj.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
                videoId = ep?.optString("videoId", "") ?: ""
            }
            if (videoId.isBlank()) {
                val overlay = itemObj.optJSONObject("overlay")?.optJSONObject("musicItemThumbnailOverlayRenderer")
                val playNav = overlay?.optJSONObject("content")?.optJSONObject("musicPlayButtonRenderer")?.optJSONObject("playNavigationEndpoint")?.optJSONObject("watchEndpoint")
                videoId = playNav?.optString("videoId", "") ?: ""
            }

            if (videoId.isBlank()) return null

            // 2. Extract Flex Columns (Title, Artist, Duration)
            val flexColumns = itemObj.optJSONArray("flexColumns")
            if (flexColumns != null && flexColumns.length() > 0) {
                // Column 0: Title
                val col0 = flexColumns.optJSONObject(0)
                val runs0 = col0?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.optJSONArray("runs")
                if (runs0 != null && runs0.length() > 0) {
                    title = runs0.getJSONObject(0).optString("text", "Unknown")
                }

                // Column 1: Artist, Album, Duration
                if (flexColumns.length() > 1) {
                    val col1 = flexColumns.optJSONObject(1)
                    val runs1 = col1?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.optJSONArray("runs")
                    if (runs1 != null && runs1.length() > 0) {
                        uploader = runs1.getJSONObject(0).optString("text", "Unknown")

                        for (j in 1 until runs1.length()) {
                            val textVal = runs1.getJSONObject(j).optString("text", "").trim()
                            if (textVal.contains(":")) {
                                duration = parseDurationToSeconds(textVal)
                            }
                        }
                    }
                }
            }

            // 3. Extract High-Res Thumbnail
            val thumbObj = itemObj.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")
            val thumbsArray = thumbObj?.optJSONArray("thumbnails")
            if (thumbsArray != null && thumbsArray.length() > 0) {
                val lastThumb = thumbsArray.getJSONObject(thumbsArray.length() - 1)
                val rawUrl = lastThumb.optString("url", "")
                thumbnail = upgradeThumbnailResolution(rawUrl)
            }

            if (thumbnail.isBlank()) {
                thumbnail = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            }

            return OnlineSearchResult(
                title = title,
                uploader = uploader,
                url = "https://www.youtube.com/watch?v=$videoId",
                duration = duration,
                thumbnail = thumbnail
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun parseVideoRenderer(videoObj: JSONObject): OnlineSearchResult? {
        try {
            val videoId = videoObj.optString("videoId", "")
            if (videoId.isBlank()) return null

            var title = "Unknown"
            val titleObj = videoObj.optJSONObject("title")
            val titleRuns = titleObj?.optJSONArray("runs")
            if (titleRuns != null && titleRuns.length() > 0) {
                title = titleRuns.getJSONObject(0).optString("text", "Unknown")
            } else if (titleObj?.has("simpleText") == true) {
                title = titleObj.optString("simpleText", "Unknown")
            }

            var uploader = "Unknown"
            val ownerObj = videoObj.optJSONObject("ownerText") ?: videoObj.optJSONObject("shortBylineText")
            val ownerRuns = ownerObj?.optJSONArray("runs")
            if (ownerRuns != null && ownerRuns.length() > 0) {
                uploader = ownerRuns.getJSONObject(0).optString("text", "Unknown")
            }

            var duration = 0
            val lengthObj = videoObj.optJSONObject("lengthText")
            val lengthText = lengthObj?.optString("simpleText", "") ?: ""
            if (lengthText.isNotBlank()) {
                duration = parseDurationToSeconds(lengthText)
            }

            var thumbnail = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            val thumbObj = videoObj.optJSONObject("thumbnail")
            val thumbsArray = thumbObj?.optJSONArray("thumbnails")
            if (thumbsArray != null && thumbsArray.length() > 0) {
                val lastThumb = thumbsArray.getJSONObject(thumbsArray.length() - 1)
                val rawUrl = lastThumb.optString("url", "")
                if (rawUrl.isNotBlank()) {
                    thumbnail = upgradeThumbnailResolution(rawUrl)
                }
            }

            return OnlineSearchResult(
                title = title,
                uploader = uploader,
                url = "https://www.youtube.com/watch?v=$videoId",
                duration = duration,
                thumbnail = thumbnail
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun upgradeThumbnailResolution(url: String): String {
        if (url.isBlank()) return ""
        if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) {
            return url.replace(Regex("=w\\d+-h\\d+.*"), "=w544-h544-l90-rj")
        }
        return url
    }

    private fun parseDurationToSeconds(durationStr: String): Int {
        return try {
            val parts = durationStr.split(":").map { it.trim().toIntOrNull() ?: 0 }
            if (parts.size == 2) {
                parts[0] * 60 + parts[1]
            } else if (parts.size == 3) {
                parts[0] * 3600 + parts[1] * 60 + parts[2]
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }
}
