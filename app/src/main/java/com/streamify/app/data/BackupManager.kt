package com.streamify.app.data

import android.content.Context
import android.os.Environment
import com.streamify.app.data.models.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BackupManager {

    suspend fun exportLibraryBackup(context: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            val allTracks = TrackRepository.getAllTracks()
            val likedIds = NativeBridge.getLikedTracks(1).map { it.id }
            val playlists = PlaylistRepository.getPlaylists()

            val rootJson = JSONObject().apply {
                put("version", 1)
                put("exportTimestamp", System.currentTimeMillis())
                put("date", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))

                // Liked IDs
                val likedArray = JSONArray()
                likedIds.forEach { likedArray.put(it) }
                put("likedTrackIds", likedArray)

                // Tracks
                val tracksArray = JSONArray()
                allTracks.forEach { track ->
                    tracksArray.put(JSONObject().apply {
                        put("id", track.id)
                        put("title", track.title)
                        put("artist", track.artist)
                        put("album", track.album)
                        put("durationSec", track.durationSec)
                        put("filepath", track.filepath)
                        put("bpm", track.bpm.toDouble())
                        put("source", track.source)
                        put("isLiked", track.isLiked)
                    })
                }
                put("tracks", tracksArray)

                // Playlists
                val playlistsArray = JSONArray()
                playlists.forEach { p ->
                    playlistsArray.put(JSONObject().apply {
                        put("id", p.id)
                        put("name", p.name)
                        put("description", p.description)
                        val tIds = JSONArray()
                        p.trackIds.forEach { tIds.put(it) }
                        put("trackIds", tIds)
                    })
                }
                put("playlists", playlistsArray)
            }

            val backupDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Streamify")
            if (!backupDir.exists()) backupDir.mkdirs()

            val backupFile = File(backupDir, "streamify_backup_${System.currentTimeMillis()}.json")
            backupFile.writeText(rootJson.toString(2))

            Result.success(backupFile.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun importLibraryBackup(context: Context, jsonString: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(jsonString)
            var importedCount = 0

            if (root.has("tracks")) {
                val tracksArray = root.getJSONArray("tracks")
                for (i in 0 until tracksArray.length()) {
                    val t = tracksArray.getJSONObject(i)
                    val filepath = t.optString("filepath", "")
                    val title = t.optString("title", "Unknown")
                    val artist = t.optString("artist", "Unknown")
                    val album = t.optString("album", "Streamify")
                    val duration = t.optInt("durationSec", 0)
                    val bpm = t.optDouble("bpm", 120.0).toFloat()
                    val isLiked = t.optBoolean("isLiked", false)

                    if (filepath.isNotBlank()) {
                        val trackId = NativeBridge.insertTrack(filepath, title, artist, album, duration, bpm)
                        if (isLiked && trackId > 0) {
                            NativeBridge.toggleLike(1, trackId.toInt())
                        }
                        importedCount++
                    }
                }
            }

            if (root.has("playlists")) {
                val playlistsArray = root.getJSONArray("playlists")
                for (i in 0 until playlistsArray.length()) {
                    val p = playlistsArray.getJSONObject(i)
                    val id = p.optString("id", java.util.UUID.randomUUID().toString())
                    val name = p.optString("name", "Restored Playlist")
                    val desc = p.optString("description", "Restored from backup")
                    val tIds = mutableListOf<Int>()
                    val tIdsArray = p.optJSONArray("trackIds")
                    if (tIdsArray != null) {
                        for (j in 0 until tIdsArray.length()) {
                            tIds.add(tIdsArray.getInt(j))
                        }
                    }
                    PlaylistRepository.addPlaylist(Playlist(id, name, desc, tIds))
                }
            }

            TrackRepository.refresh()
            Result.success(importedCount)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
}
