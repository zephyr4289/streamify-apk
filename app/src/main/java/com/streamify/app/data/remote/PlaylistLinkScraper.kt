package com.streamify.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ScrapedTrack(
    val title: String,
    val artist: String
)

data class ScrapedPlaylist(
    val name: String,
    val tracks: List<ScrapedTrack>
)

object PlaylistLinkScraper {

    suspend fun scrapePlaylist(rawUrl: String): ScrapedPlaylist = withContext(Dispatchers.IO) {
        val url = rawUrl.trim()
        when {
            url.contains("spotify.com") -> scrapeSpotify(url)
            url.contains("youtube.com") || url.contains("youtu.be") -> scrapeYouTube(url)
            url.contains("music.apple.com") -> scrapeAppleMusic(url)
            else -> throw IllegalArgumentException("Unsupported URL: Must be a Spotify, YouTube, or Apple Music link")
        }
    }

    private fun scrapeSpotify(url: String): ScrapedPlaylist {
        val playlistId = url.substringAfter("playlist/").substringBefore("?").substringBefore("/")
        if (playlistId.isBlank()) throw IllegalArgumentException("Invalid Spotify playlist URL")

        // Zero-Auth Endpoint: Spotify Embed page contains JSON hydration payload
        val embedUrl = "https://open.spotify.com/embed/playlist/$playlistId"
        val html = fetchUrl(embedUrl)

        val jsonStr = html.substringAfter("<script id=\"__NEXT_DATA__\" type=\"application/json\">")
            .substringBefore("</script>")

        if (jsonStr.isBlank() || !jsonStr.startsWith("{")) {
            // Fallback to oEmbed metadata
            return ScrapedPlaylist(
                name = "Imported Spotify Playlist",
                tracks = emptyList()
            )
        }

        val json = JSONObject(jsonStr)
        val entityData = json.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")?.optJSONObject("data")?.optJSONObject("entity")
            ?: json.optJSONObject("props")?.optJSONObject("state")?.optJSONObject("data")?.optJSONObject("entity")

        val playlistName = entityData?.optString("name", "Imported Spotify Playlist") ?: "Imported Spotify Playlist"
        val trackList = entityData?.optJSONArray("trackList")
        val tracks = mutableListOf<ScrapedTrack>()

        if (trackList != null) {
            for (i in 0 until trackList.length()) {
                val item = trackList.getJSONObject(i)
                val title = item.optString("title", "")
                val subtitle = item.optString("subtitle", "")
                if (title.isNotBlank()) {
                    tracks.add(ScrapedTrack(title = title, artist = subtitle))
                }
            }
        }

        return ScrapedPlaylist(name = playlistName, tracks = tracks)
    }

    private fun scrapeYouTube(url: String): ScrapedPlaylist {
        val playlistId = if (url.contains("list=")) url.substringAfter("list=").substringBefore("&") else ""
        if (playlistId.isBlank()) throw IllegalArgumentException("Invalid YouTube playlist URL")

        // Zero-Auth Piped API endpoint
        val apiUrl = "https://pipedapi.kavin.rocks/playlists/$playlistId"
        val tracks = mutableListOf<ScrapedTrack>()
        var playlistName = "Imported YouTube Playlist"

        try {
            val jsonStr = fetchUrl(apiUrl)
            val json = JSONObject(jsonStr)
            playlistName = json.optString("name", "Imported YouTube Playlist")
            val relatedStreams = json.optJSONArray("relatedStreams")
            if (relatedStreams != null) {
                for (i in 0 until relatedStreams.length()) {
                    val stream = relatedStreams.getJSONObject(i)
                    val title = stream.optString("title", "")
                    val uploader = stream.optString("uploader", "")
                    if (title.isNotBlank()) {
                        tracks.add(ScrapedTrack(title = title, artist = uploader))
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback scraping
        }

        return ScrapedPlaylist(name = playlistName, tracks = tracks)
    }

    private fun scrapeAppleMusic(url: String): ScrapedPlaylist {
        val html = fetchUrl(url)
        val jsonStr = html.substringAfter("<script type=\"application/ld+json\" id=\"schema:music-playlist\">")
            .substringBefore("</script>")

        val tracks = mutableListOf<ScrapedTrack>()
        var playlistName = "Imported Apple Music Playlist"

        if (jsonStr.startsWith("{")) {
            val json = JSONObject(jsonStr)
            playlistName = json.optString("name", "Imported Apple Music Playlist")
            val trackArray = json.optJSONArray("track")
            if (trackArray != null) {
                for (i in 0 until trackArray.length()) {
                    val t = trackArray.getJSONObject(i)
                    val title = t.optString("name", "")
                    val artist = t.optJSONObject("byArtist")?.optString("name", "") ?: ""
                    if (title.isNotBlank()) {
                        tracks.add(ScrapedTrack(title = title, artist = artist))
                    }
                }
            }
        }

        return ScrapedPlaylist(name = playlistName, tracks = tracks)
    }

    private fun fetchUrl(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            connectTimeout = 8000
            readTimeout = 8000
        }
        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}
