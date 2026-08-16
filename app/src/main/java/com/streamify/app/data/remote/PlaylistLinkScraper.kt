package com.streamify.app.data.remote

import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

data class ScrapedTrack(
    val title: String,
    val artist: String
)

data class ScrapedPlaylist(
    val name: String,
    val tracks: List<ScrapedTrack>
)

object PlaylistLinkScraper {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    suspend fun scrapePlaylist(rawUrl: String): ScrapedPlaylist = withContext(Dispatchers.IO) {
        val url = rawUrl.trim()
        when {
            url.contains("spotify.com") || url.startsWith("spotify:") -> scrapeSpotify(url)
            url.contains("youtube.com") || url.contains("youtu.be") -> scrapeYouTube(url)
            url.contains("music.apple.com") -> scrapeAppleMusic(url)
            else -> throw IllegalArgumentException("Unsupported URL: Must be a Spotify, YouTube, or Apple Music link")
        }
    }

    // ========================================================================
    // 1. SPOTIFY PLAYLIST EXTRACTION (Anonymous Web API + Chaquopy Fallback)
    // ========================================================================
    private fun scrapeSpotify(url: String): ScrapedPlaylist {
        val playlistId = url.substringAfter("playlist/").substringBefore("?").substringBefore("/")
        if (playlistId.isBlank()) throw IllegalArgumentException("Invalid Spotify playlist URL")

        val tracks = mutableListOf<ScrapedTrack>()
        var playlistName = "Imported Spotify Playlist"

        // Tier 1: Spotify Web Player Anonymous Token & Official API
        try {
            val tokenReq = Request.Builder()
                .url("https://open.spotify.com/get_access_token?reason=transport&productType=web_player")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(tokenReq).execute().use { tokenResp ->
                if (tokenResp.isSuccessful) {
                    val tokenJson = JSONObject(tokenResp.body?.string() ?: "")
                    val token = tokenJson.optString("accessToken", "")
                    if (token.isNotBlank()) {
                        val apiReq = Request.Builder()
                            .url("https://api.spotify.com/v1/playlists/$playlistId")
                            .header("Authorization", "Bearer $token")
                            .header("User-Agent", USER_AGENT)
                            .build()

                        httpClient.newCall(apiReq).execute().use { apiResp ->
                            if (apiResp.isSuccessful) {
                                val root = JSONObject(apiResp.body?.string() ?: "")
                                playlistName = root.optString("name", "Imported Spotify Playlist")
                                val items = root.optJSONObject("tracks")?.optJSONArray("items")
                                if (items != null) {
                                    for (i in 0 until items.length()) {
                                        val trackObj = items.getJSONObject(i).optJSONObject("track") ?: continue
                                        val title = trackObj.optString("name", "")
                                        val artists = trackObj.optJSONArray("artists")
                                        val artist = if (artists != null && artists.length() > 0) {
                                            artists.getJSONObject(0).optString("name", "Unknown Artist")
                                        } else "Unknown Artist"
                                        if (title.isNotBlank()) {
                                            tracks.add(ScrapedTrack(title = title, artist = artist))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Tier 2: Chaquopy Python Extraction Fallback
        if (tracks.isEmpty()) {
            try {
                if (Python.isStarted()) {
                    val py = Python.getInstance()
                    val spotifyModule = py.getModule("download_engine.spotify")
                    val resultJson = spotifyModule.callAttr("fetch_spotify_metadata_from_url", url).toString()
                    val jsonArray = JSONArray(resultJson)
                    for (i in 0 until jsonArray.length()) {
                        val item = jsonArray.getJSONObject(i)
                        val title = item.optString("title", "")
                        val artist = item.optString("artist", "")
                        if (title.isNotBlank()) {
                            tracks.add(ScrapedTrack(title = title, artist = artist))
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return ScrapedPlaylist(name = playlistName, tracks = tracks)
    }

    // ========================================================================
    // 2. YOUTUBE / YTM PLAYLIST EXTRACTION (Official Innertube Browse API)
    // ========================================================================
    private fun scrapeYouTube(url: String): ScrapedPlaylist {
        val playlistId = if (url.contains("list=")) url.substringAfter("list=").substringBefore("&") else ""
        if (playlistId.isBlank()) throw IllegalArgumentException("Invalid YouTube playlist URL")

        val tracks = mutableListOf<ScrapedTrack>()
        var playlistName = "Imported YouTube Playlist"

        try {
            val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
            val requestJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20230515.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("browseId", browseId)
            }

            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/browse")
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("User-Agent", USER_AGENT)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body ?: return ScrapedPlaylist(playlistName, tracks)
                    val encoding = response.header("Content-Encoding", "")

                    val responseBody = if ("gzip".equals(encoding, ignoreCase = true)) {
                        GZIPInputStream(body.byteStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    } else {
                        body.string()
                    }

                    val root = JSONObject(responseBody)
                    val candidateNodes = mutableListOf<JSONObject>()
                    findJsonObjects(root, "musicResponsiveListItemRenderer", candidateNodes)
                    findJsonObjects(root, "playlistVideoRenderer", candidateNodes)

                    for (node in candidateNodes) {
                        val titleObj = node.optJSONObject("title")
                        val title = titleObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                            ?: titleObj?.optString("simpleText", "") ?: ""

                        val bylineObj = node.optJSONObject("longBylineText") ?: node.optJSONObject("shortBylineText")
                        val artist = bylineObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "Unknown Artist")
                            ?: "Unknown Artist"

                        if (title.isNotBlank()) {
                            tracks.add(ScrapedTrack(title = title, artist = artist))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return ScrapedPlaylist(name = playlistName, tracks = tracks)
    }

    // ========================================================================
    // 3. APPLE MUSIC PLAYLIST EXTRACTION (Universal JSON-LD Parser)
    // ========================================================================
    private fun scrapeAppleMusic(url: String): ScrapedPlaylist {
        val tracks = mutableListOf<ScrapedTrack>()
        var playlistName = "Imported Apple Music Playlist"

        try {
            val html = fetchUrl(url)
            val scriptRegex = Regex("<script\\s+type=\"application/ld\\+json\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            val matches = scriptRegex.findAll(html)

            for (m in matches) {
                val jsonStr = m.groupValues[1].trim()
                if (jsonStr.startsWith("{")) {
                    val json = JSONObject(jsonStr)
                    val type = json.optString("@type", "")
                    if (type.contains("MusicPlaylist", ignoreCase = true) || type.contains("MusicAlbum", ignoreCase = true)) {
                        playlistName = json.optString("name", "Imported Apple Music Playlist")
                        val trackArray = json.optJSONArray("track")
                        if (trackArray != null) {
                            for (i in 0 until trackArray.length()) {
                                val t = trackArray.getJSONObject(i)
                                val title = t.optString("name", "")
                                val artist = t.optJSONObject("byArtist")?.optString("name", "")
                                    ?: t.optString("byArtist", "Unknown Artist")
                                if (title.isNotBlank()) {
                                    tracks.add(ScrapedTrack(title = title, artist = artist))
                                }
                            }
                        }
                        if (tracks.isNotEmpty()) break
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return ScrapedPlaylist(name = playlistName, tracks = tracks)
    }

    private fun fetchUrl(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = 8000
            readTimeout = 8000
        }
        return connection.inputStream.bufferedReader().use { it.readText() }
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
}
