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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
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
    // 1. SPOTIFY PLAYLIST EXTRACTION (High-Speed Anonymous API + Pagination)
    // ========================================================================
    private fun scrapeSpotify(url: String): ScrapedPlaylist {
        val playlistId = when {
            url.contains("playlist/") -> url.substringAfter("playlist/").substringBefore("?").substringBefore("/")
            url.contains("album/") -> url.substringAfter("album/").substringBefore("?").substringBefore("/")
            url.startsWith("spotify:playlist:") -> url.substringAfter("spotify:playlist:")
            else -> ""
        }
        if (playlistId.isBlank()) throw IllegalArgumentException("Invalid Spotify playlist URL")

        val tracks = mutableListOf<ScrapedTrack>()
        var playlistName = "Imported Spotify Playlist"

        // Tier 1: Spotify Web Player Anonymous Token & Official Paginated Web API
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
                        var nextUrl: String? = "https://api.spotify.com/v1/playlists/$playlistId?fields=name,tracks.items(track(name,artists(name))),tracks.next"

                        while (!nextUrl.isNullOrBlank() && tracks.size < 500) {
                            val apiReq = Request.Builder()
                                .url(nextUrl)
                                .header("Authorization", "Bearer $token")
                                .header("User-Agent", USER_AGENT)
                                .build()

                            httpClient.newCall(apiReq).execute().use { apiResp ->
                                if (apiResp.isSuccessful) {
                                    val root = JSONObject(apiResp.body?.string() ?: "")
                                    if (root.has("name")) {
                                        playlistName = root.optString("name", playlistName)
                                    }

                                    val tracksObj = root.optJSONObject("tracks") ?: root
                                    val items = tracksObj.optJSONArray("items")
                                    if (items != null) {
                                        for (i in 0 until items.length()) {
                                            val item = items.getJSONObject(i)
                                            val trackObj = item.optJSONObject("track") ?: item
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
                                    nextUrl = tracksObj.optString("next", "").takeIf { it.isNotBlank() }
                                } else {
                                    nextUrl = null
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
                    val jsonArray = org.json.JSONArray(resultJson)
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
    // 2. YOUTUBE / YTM PLAYLIST EXTRACTION (Universal Innertube & Deep FlexColumns)
    // ========================================================================
    private fun scrapeYouTube(url: String): ScrapedPlaylist {
        val playlistId = when {
            url.contains("list=") -> url.substringAfter("list=").substringBefore("&").substringBefore("#")
            else -> ""
        }
        if (playlistId.isBlank()) throw IllegalArgumentException("Invalid YouTube playlist URL (missing ?list= ID)")

        val tracks = mutableListOf<ScrapedTrack>()
        var playlistName = "Imported YouTube Playlist"

        try {
            val isMusicHost = url.contains("music.youtube.com")
            val clientName = if (isMusicHost) "WEB_REMIX" else "WEB"
            val clientVersion = if (isMusicHost) "1.20240301.01.00" else "2.20240301.00.00"
            val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"

            val requestJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", clientName)
                        put("clientVersion", clientVersion)
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("browseId", browseId)
            }

            val endpoint = if (isMusicHost) {
                "https://music.youtube.com/youtubei/v1/browse"
            } else {
                "https://www.youtube.com/youtubei/v1/browse"
            }

            val request = Request.Builder()
                .url(endpoint)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("User-Agent", USER_AGENT)
                .header("Origin", if (isMusicHost) "https://music.youtube.com" else "https://www.youtube.com")
                .header("Referer", if (isMusicHost) "https://music.youtube.com/" else "https://www.youtube.com/")
                .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    // OkHttp transparently decompresses GZIP responses; read body string directly
                    val responseBody = response.body?.string() ?: return ScrapedPlaylist(playlistName, tracks)
                    val root = JSONObject(responseBody)

                    // 1. Extract Playlist Title from Header
                    val headerNodes = mutableListOf<JSONObject>()
                    findJsonObjects(root, "musicDetailHeaderRenderer", headerNodes)
                    findJsonObjects(root, "playlistHeaderRenderer", headerNodes)
                    for (hNode in headerNodes) {
                        val titleRuns = hNode.optJSONObject("title")?.optJSONArray("runs")
                        val headerTitle = titleRuns?.optJSONObject(0)?.optString("text")
                            ?: hNode.optJSONObject("title")?.optString("simpleText", "")
                        if (!headerTitle.isNullOrBlank()) {
                            playlistName = headerTitle
                            break
                        }
                    }

                    // 2. Extract Tracks using deep flexColumns traversal (YouTube Music) and playlistVideoRenderer (YouTube)
                    val candidateNodes = mutableListOf<JSONObject>()
                    findJsonObjects(root, "musicResponsiveListItemRenderer", candidateNodes)
                    findJsonObjects(root, "playlistVideoRenderer", candidateNodes)

                    for (node in candidateNodes) {
                        var title = ""
                        var artist = "Unknown Artist"

                        // Check A: YouTube Music (musicResponsiveListItemRenderer with flexColumns)
                        val flexColumns = node.optJSONArray("flexColumns")
                        if (flexColumns != null && flexColumns.length() > 0) {
                            // Column 0: Title
                            val col0Runs = flexColumns.optJSONObject(0)
                                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                ?.optJSONObject("text")
                                ?.optJSONArray("runs")
                            if (col0Runs != null && col0Runs.length() > 0) {
                                title = col0Runs.getJSONObject(0).optString("text", "")
                            }

                            // Column 1: Artist
                            if (flexColumns.length() > 1) {
                                val col1Runs = flexColumns.optJSONObject(1)
                                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                    ?.optJSONObject("text")
                                    ?.optJSONArray("runs")
                                if (col1Runs != null && col1Runs.length() > 0) {
                                    artist = col1Runs.getJSONObject(0).optString("text", "Unknown Artist")
                                }
                            }
                        }

                        // Check B: Standard YouTube (playlistVideoRenderer)
                        if (title.isBlank()) {
                            val titleObj = node.optJSONObject("title")
                            title = titleObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                                ?: titleObj?.optString("simpleText", "") ?: ""

                            val bylineObj = node.optJSONObject("shortBylineText") ?: node.optJSONObject("longBylineText")
                            artist = bylineObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "Unknown Artist")
                                ?: "Unknown Artist"
                        }

                        if (title.isNotBlank()) {
                            tracks.add(ScrapedTrack(title = title.trim(), artist = artist.trim()))
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
