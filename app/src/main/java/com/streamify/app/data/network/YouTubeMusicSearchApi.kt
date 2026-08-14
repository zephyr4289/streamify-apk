package com.streamify.app.data.network

import com.streamify.app.viewmodel.OnlineSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object YouTubeMusicSearchApi {

    private const val INNERTUBE_MUSIC_SEARCH_URL = "https://music.youtube.com/youtubei/v1/search"
    private const val INNERTUBE_YT_SEARCH_URL = "https://www.youtube.com/youtubei/v1/search"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

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

    private fun executeInnertubeSearch(query: String, endpointUrl: String, isMusic: Boolean, maxResults: Int): List<OnlineSearchResult> {
        try {
            val url = URL(endpointUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 4000
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "gzip, deflate")
                if (isMusic) {
                    setRequestProperty("Origin", "https://music.youtube.com")
                    setRequestProperty("Referer", "https://music.youtube.com/")
                } else {
                    setRequestProperty("Origin", "https://www.youtube.com")
                    setRequestProperty("Referer", "https://www.youtube.com/")
                }
            }

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

            conn.outputStream.use { os ->
                os.write(requestJson.toString().toByteArray(Charsets.UTF_8))
                os.flush()
            }

            if (conn.responseCode != 200) {
                return emptyList()
            }

            val inputStream = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
                GZIPInputStream(conn.inputStream)
            } else {
                conn.inputStream
            }

            val responseBody = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { it.readText() }
            val root = JSONObject(responseBody)
            return parseInnertubeResponse(root, maxResults)
        } catch (e: Exception) {
            e.printStackTrace()
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
