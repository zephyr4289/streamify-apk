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
    // 2. YOUTUBE / YTM PLAYLIST EXTRACTION (3-Tier Stateful Protocol Router)
    // ========================================================================
    private fun scrapeYouTube(url: String): ScrapedPlaylist {
        var playlistId = when {
            url.contains("list=") -> url.substringAfter("list=").substringBefore("&").substringBefore("#")
            else -> ""
        }

        var videoId: String? = when {
            url.contains("v=") -> url.substringAfter("v=").substringBefore("&").substringBefore("#")
            url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?").substringBefore("#")
            else -> null
        }

        if (playlistId.isBlank() && videoId != null) {
            // Convert single track link to automatic artist/track radio mix
            playlistId = "RDAMVM$videoId"
        }

        if (playlistId.isBlank()) {
            throw IllegalArgumentException("Invalid YouTube playlist URL (missing ?list= ID or video ID)")
        }

        var playlistName = "Imported YouTube Playlist"
        val tracks = mutableListOf<ScrapedTrack>()

        // --------------------------------------------------------------------
        // TIER 1: Native HTTP/2 Innertube Protocol Router & Continuation Loop
        // --------------------------------------------------------------------
        try {
            val isMix = playlistId.startsWith("RD") || playlistId.startsWith("RDAMVM") || playlistId.startsWith("RDCLAK")
            val isAlbum = playlistId.startsWith("OLAK5uy_")
            val isMusicHost = url.contains("music.youtube.com")

            val endpoint = if (isMix) {
                "https://music.youtube.com/youtubei/v1/next"
            } else {
                "https://music.youtube.com/youtubei/v1/browse"
            }

            val clientType = if (isMix || isMusicHost) "ANDROID_MUSIC" else "WEB_REMIX"
            val clientVersion = if (clientType == "ANDROID_MUSIC") "6.42.52" else "1.20260101.01.00"

            val contextJson = JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", clientType)
                    put("clientVersion", clientVersion)
                    if (clientType == "ANDROID_MUSIC") {
                        put("androidSdkVersion", 34)
                        put("osName", "Android")
                        put("osVersion", "14")
                    }
                    put("hl", "en")
                    put("gl", "US")
                })
            }

            val requestJson = JSONObject().apply {
                put("context", contextJson)
                if (isMix) {
                    put("playlistId", playlistId)
                    if (videoId != null) put("videoId", videoId)
                    put("isAudioOnly", true)
                } else {
                    val browseId = if (isAlbum || playlistId.startsWith("VL")) playlistId else "VL$playlistId"
                    put("browseId", browseId)
                }
            }

            val request = Request.Builder()
                .url(endpoint)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("User-Agent", if (clientType == "ANDROID_MUSIC") "com.google.android.apps.youtube.music/6.42.52 (Linux; U; Android 14; en_US)" else USER_AGENT)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    if (responseBody.isNotBlank()) {
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

                        // 2. Extract Tracks (musicResponsiveListItemRenderer, playlistVideoRenderer, playlistPanelVideoRenderer)
                        val candidateNodes = mutableListOf<JSONObject>()
                        findJsonObjects(root, "musicResponsiveListItemRenderer", candidateNodes)
                        findJsonObjects(root, "playlistVideoRenderer", candidateNodes)
                        findJsonObjects(root, "playlistPanelVideoRenderer", candidateNodes)

                        for (node in candidateNodes) {
                            val extracted = parseTrackFromNode(node)
                            if (extracted != null && extracted.title.isNotBlank()) {
                                tracks.add(extracted)
                            }
                        }

                        // 3. Extract Continuation Token for Large Playlists (>100 tracks)
                        var continuationToken = extractContinuationToken(root)
                        while (!continuationToken.isNullOrBlank() && tracks.size < 500) {
                            val contPayload = JSONObject().apply {
                                put("context", contextJson)
                            }
                            val contUrl = "$endpoint?continuation=$continuationToken&ctoken=$continuationToken"
                            val contRequest = Request.Builder()
                                .url(contUrl)
                                .header("Content-Type", "application/json; charset=UTF-8")
                                .header("User-Agent", USER_AGENT)
                                .post(contPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                                .build()

                            httpClient.newCall(contRequest).execute().use { contResp ->
                                if (contResp.isSuccessful) {
                                    val contBody = contResp.body?.string() ?: ""
                                    if (contBody.isNotBlank()) {
                                        val contRoot = JSONObject(contBody)
                                        val contNodes = mutableListOf<JSONObject>()
                                        findJsonObjects(contRoot, "musicResponsiveListItemRenderer", contNodes)
                                        findJsonObjects(contRoot, "playlistVideoRenderer", contNodes)
                                        findJsonObjects(contRoot, "playlistPanelVideoRenderer", contNodes)

                                        if (contNodes.isEmpty()) {
                                            continuationToken = null
                                        } else {
                                            for (cNode in contNodes) {
                                                val cTrack = parseTrackFromNode(cNode)
                                                if (cTrack != null && cTrack.title.isNotBlank()) {
                                                    tracks.add(cTrack)
                                                }
                                            }
                                            continuationToken = extractContinuationToken(contRoot)
                                        }
                                    } else {
                                        continuationToken = null
                                    }
                                } else {
                                    continuationToken = null
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // --------------------------------------------------------------------
        // TIER 2: Chaquopy Python yt-dlp Flat Playlist Extractor (O(1) Batched)
        // --------------------------------------------------------------------
        if (tracks.isEmpty()) {
            try {
                if (Python.isStarted()) {
                    val py = Python.getInstance()
                    val searchModule = py.getModule("download_engine.search")
                    val resultJsonStr = searchModule.callAttr("fetch_youtube_playlist", url, 500).toString()
                    if (resultJsonStr.isNotBlank() && resultJsonStr.startsWith("{")) {
                        val pyRoot = JSONObject(resultJsonStr)
                        val pyTitle = pyRoot.optString("title", "")
                        if (pyTitle.isNotBlank() && playlistName == "Imported YouTube Playlist") {
                            playlistName = pyTitle
                        }
                        val pyTracks = pyRoot.optJSONArray("tracks")
                        if (pyTracks != null) {
                            for (i in 0 until pyTracks.length()) {
                                val item = pyTracks.getJSONObject(i)
                                val title = item.optString("title", "")
                                val artist = item.optString("artist", "Unknown Artist")
                                if (title.isNotBlank()) {
                                    tracks.add(ScrapedTrack(title = title.trim(), artist = artist.trim()))
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // --------------------------------------------------------------------
        // TIER 3: Raw HTML ytInitialData RegEx DOM Scraper
        // --------------------------------------------------------------------
        if (tracks.isEmpty()) {
            try {
                val html = fetchUrl(url)
                val regex = Regex("var\\s+ytInitialData\\s*=\\s*(\\{.+?\\});<", RegexOption.DOT_MATCHES_ALL)
                val match = regex.find(html)
                if (match != null) {
                    val rawJson = match.groupValues[1]
                    val htmlRoot = JSONObject(rawJson)
                    val htmlNodes = mutableListOf<JSONObject>()
                    findJsonObjects(htmlRoot, "playlistVideoRenderer", htmlNodes)
                    findJsonObjects(htmlRoot, "musicResponsiveListItemRenderer", htmlNodes)
                    for (hNode in htmlNodes) {
                        val hTrack = parseTrackFromNode(hNode)
                        if (hTrack != null && hTrack.title.isNotBlank()) {
                            tracks.add(hTrack)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (tracks.isEmpty()) {
            throw IllegalArgumentException("Could not extract tracks from this YouTube playlist. Ensure the playlist is public or unlisted.")
        }

        return ScrapedPlaylist(name = playlistName, tracks = tracks)
    }

    private fun parseTrackFromNode(node: JSONObject): ScrapedTrack? {
        var title = ""
        var artist = "Unknown Artist"

        // Format A: YouTube Music flexColumns
        val flexColumns = node.optJSONArray("flexColumns")
        if (flexColumns != null && flexColumns.length() > 0) {
            val col0Runs = flexColumns.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")
            if (col0Runs != null && col0Runs.length() > 0) {
                title = col0Runs.getJSONObject(0).optString("text", "")
            }

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

        // Format B: Standard YouTube playlistVideoRenderer or playlistPanelVideoRenderer
        if (title.isBlank()) {
            val titleObj = node.optJSONObject("title")
            title = titleObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                ?: titleObj?.optString("simpleText", "") ?: ""

            val bylineObj = node.optJSONObject("shortBylineText") ?: node.optJSONObject("longBylineText")
            artist = bylineObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "Unknown Artist")
                ?: "Unknown Artist"
        }

        return if (title.isNotBlank()) ScrapedTrack(title.trim(), artist.trim()) else null
    }

    private fun extractContinuationToken(root: JSONObject): String? {
        val contNodes = mutableListOf<JSONObject>()
        findJsonObjects(root, "nextContinuationData", contNodes)
        findJsonObjects(root, "nextRadioContinuationData", contNodes)
        for (cNode in contNodes) {
            val token = cNode.optString("continuation", "")
            if (token.isNotBlank()) return token
        }
        return null
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
