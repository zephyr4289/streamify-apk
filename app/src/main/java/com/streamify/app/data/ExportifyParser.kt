package com.streamify.app.data

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ParsedTrackItem(
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val coverUrl: String
)

data class DiscoveredPlaylistFile(
    val file: File,
    val name: String,
    val trackCount: Int,
    val sizeBytes: Long
)

object ExportifyParser {

    suspend fun discoverLocalPlaylistFiles(): List<DiscoveredPlaylistFile> = withContext(Dispatchers.IO) {
        val foundList = mutableListOf<DiscoveredPlaylistFile>()
        val searchDirs = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File("/storage/emulated/0/Download"),
            File("/sdcard/Download"),
            File("/storage/emulated/0/Music")
        ).filter { it.exists() && it.isDirectory }

        val seenPaths = mutableSetOf<String>()

        for (dir in searchDirs) {
            val jsonFiles = dir.listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) } ?: continue
            for (f in jsonFiles) {
                if (f.absolutePath in seenPaths) continue
                seenPaths.add(f.absolutePath)

                // Check if valid playlist format
                val (playlistName, tracks) = parsePlaylistJson(f)
                if (tracks.isNotEmpty()) {
                    foundList.add(
                        DiscoveredPlaylistFile(
                            file = f,
                            name = playlistName.ifBlank { f.nameWithoutExtension },
                            trackCount = tracks.size,
                            sizeBytes = f.length()
                        )
                    )
                }
            }
        }
        foundList
    }

    suspend fun parsePlaylistJson(file: File): Pair<String, List<ParsedTrackItem>> = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.canRead()) return@withContext Pair("", emptyList())

        try {
            val content = file.readText(Charsets.UTF_8).trim()
            if (content.isBlank()) return@withContext Pair("", emptyList())

            val tracks = mutableListOf<ParsedTrackItem>()
            var playlistName = file.nameWithoutExtension

            if (content.startsWith("[")) {
                // Array of track objects (Exportify / Soundiiz format)
                val jsonArray = JSONArray(content)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i) ?: continue
                    val item = extractTrackFromObject(obj)
                    if (item != null) tracks.add(item)
                }
            } else if (content.startsWith("{")) {
                val root = JSONObject(content)
                playlistName = root.optString("name", root.optString("playlist_name", playlistName))

                val itemsArray = root.optJSONArray("items")
                    ?: root.optJSONArray("tracks")
                    ?: root.optJSONArray("songs")
                    ?: root.optJSONArray("data")

                if (itemsArray != null) {
                    for (i in 0 until itemsArray.length()) {
                        val obj = itemsArray.optJSONObject(i) ?: continue
                        val item = extractTrackFromObject(obj)
                        if (item != null) tracks.add(item)
                    }
                }
            }

            return@withContext Pair(playlistName, tracks)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext Pair("", emptyList())
        }
    }

    private fun extractTrackFromObject(obj: JSONObject): ParsedTrackItem? {
        // Direct format or nested "track" object (Spotify API)
        val targetObj = obj.optJSONObject("track") ?: obj

        val title = targetObj.optString("name", targetObj.optString("title", targetObj.optString("Track Name", ""))).trim()
        if (title.isBlank()) return null

        var artist = targetObj.optString("artist", targetObj.optString("Artist Name(s)", targetObj.optString("Artist", ""))).trim()
        if (artist.isBlank()) {
            val artistsArray = targetObj.optJSONArray("artists")
            if (artistsArray != null && artistsArray.length() > 0) {
                val list = mutableListOf<String>()
                for (j in 0 until artistsArray.length()) {
                    val aObj = artistsArray.optJSONObject(j)
                    val aName = aObj?.optString("name", "") ?: artistsArray.optString(j)
                    if (aName.isNotBlank()) list.add(aName)
                }
                artist = list.joinToString(", ")
            }
        }
        if (artist.isBlank()) artist = "Unknown Artist"

        var album = targetObj.optString("album", targetObj.optString("Album Name", "Streamify")).trim()
        val albumObj = targetObj.optJSONObject("album")
        if (albumObj != null) {
            album = albumObj.optString("name", album)
        }

        var durationSec = 0
        if (targetObj.has("duration_ms")) {
            durationSec = (targetObj.optLong("duration_ms", 0L) / 1000).toInt()
        } else if (targetObj.has("duration")) {
            durationSec = targetObj.optInt("duration", 0)
        } else if (targetObj.has("Duration (ms)")) {
            durationSec = (targetObj.optLong("Duration (ms)", 0L) / 1000).toInt()
        }

        var coverUrl = targetObj.optString("cover_url", targetObj.optString("coverUrl", ""))
        if (coverUrl.isBlank() && albumObj != null) {
            val images = albumObj.optJSONArray("images")
            if (images != null && images.length() > 0) {
                coverUrl = images.getJSONObject(0).optString("url", "")
            }
        }

        return ParsedTrackItem(
            title = title,
            artist = artist,
            album = album.ifBlank { "Streamify" },
            durationSec = durationSec,
            coverUrl = coverUrl
        )
    }
}
