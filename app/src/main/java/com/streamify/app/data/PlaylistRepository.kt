package com.streamify.app.data

import android.content.Context
import com.streamify.app.data.models.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val trackIds: List<Int> = emptyList()
)

object PlaylistRepository {
    private var playlistFile: File? = null

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    fun getPlaylists(): List<Playlist> = _playlists.value

    fun init(context: Context) {
        if (playlistFile != null) return
        playlistFile = File(context.filesDir, "playlists.json")
        loadPlaylists()
    }

    private fun loadPlaylists() {
        val file = playlistFile ?: return
        if (!file.exists()) {
            _playlists.value = emptyList()
            return
        }
        try {
            val jsonText = file.readText()
            val jsonArray = JSONArray(jsonText)
            val list = mutableListOf<Playlist>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val trackIdsArray = obj.getJSONArray("trackIds")
                val trackIds = mutableListOf<Int>()
                for (j in 0 until trackIdsArray.length()) {
                    trackIds.add(trackIdsArray.getInt(j))
                }
                list.add(
                    Playlist(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        description = obj.optString("description", ""),
                        trackIds = trackIds
                    )
                )
            }
            _playlists.value = list
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun savePlaylists() {
        try {
            val jsonArray = JSONArray()
            _playlists.value.forEach { playlist ->
                val obj = JSONObject()
                obj.put("id", playlist.id)
                obj.put("name", playlist.name)
                obj.put("description", playlist.description)
                val trackIdsArray = JSONArray()
                playlist.trackIds.forEach { trackIdsArray.put(it) }
                obj.put("trackIds", trackIdsArray)
                jsonArray.put(obj)
            }
            val file = playlistFile ?: return
            file.writeText(jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun createPlaylist(name: String, description: String = "") {
        val newPlaylist = Playlist(name = name, description = description)
        _playlists.value = _playlists.value + newPlaylist
        savePlaylists()
    }

    fun addPlaylist(playlist: Playlist) {
        _playlists.value = _playlists.value + playlist
        savePlaylists()
    }

    fun deletePlaylist(id: String) {
        _playlists.value = _playlists.value.filter { it.id != id }
        savePlaylists()
    }

    fun addTrackToPlaylist(playlistId: String, trackId: Int) {
        _playlists.value = _playlists.value.map {
            if (it.id == playlistId && !it.trackIds.contains(trackId)) {
                it.copy(trackIds = it.trackIds + trackId)
            } else it
        }
        savePlaylists()
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: Int) {
        _playlists.value = _playlists.value.map {
            if (it.id == playlistId) {
                it.copy(trackIds = it.trackIds.filter { id -> id != trackId })
            } else it
        }
        savePlaylists()
    }

    suspend fun getTracksForPlaylist(playlistId: String, allTracks: List<Track>): List<Track> = withContext(Dispatchers.Default) {
        val playlist = _playlists.value.find { it.id == playlistId } ?: return@withContext emptyList()
        val trackMap = allTracks.associateBy { it.id }
        playlist.trackIds.mapNotNull { trackMap[it] }
    }

    suspend fun importAndLinkPlaylist(
        playlistName: String,
        importedTracks: List<ParsedTrackItem>
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (importedTracks.isEmpty()) return@withContext Pair(0, 0)
        
        var playlist = _playlists.value.find { it.name.equals(playlistName, ignoreCase = true) }
        if (playlist == null) {
            playlist = createPlaylist(playlistName, "Imported from universal playlist source")
        }

        var linkedCount = 0
        var queuedCount = 0

        for (item in importedTracks) {
            val localTrackId = NativeBridge.findFuzzyTrackMatch(item.title, item.artist)
            if (localTrackId > 0) {
                addTrackToPlaylist(playlist.id, localTrackId)
                linkedCount++
            } else {
                // Enqueue missing track for background download
                try {
                    val searchUrl = "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode("${item.title} ${item.artist}", "UTF-8")
                    com.streamify.app.viewmodel.IngestionViewModel.enqueueDownloadDirect(
                        url = searchUrl,
                        title = item.title,
                        artist = item.artist,
                        album = item.album,
                        quality = "320"
                    )
                    queuedCount++
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        Pair(linkedCount, queuedCount)
    }

    suspend fun exportPlaylistToM3U8(playlistId: String, allTracks: List<Track>, context: Context): File? = withContext(Dispatchers.IO) {
        val playlist = _playlists.value.find { it.id == playlistId } ?: return@withContext null
        val trackMap = allTracks.associateBy { it.id }
        val playlistTracks = playlist.trackIds.mapNotNull { trackMap[it] }
        if (playlistTracks.isEmpty()) return@withContext null

        try {
            val exportDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC), "Streamify/Playlists")
            if (!exportDir.exists()) exportDir.mkdirs()

            val safeName = playlist.name.replace(Regex("[^a-zA-Z0-9_\\-\\s]"), "").trim().ifBlank { "playlist" }
            val m3u8File = File(exportDir, "$safeName.m3u8")

            val sb = StringBuilder()
            sb.append("#EXTM3U\n")
            sb.append("#PLAYLIST:${playlist.name}\n\n")

            for (t in playlistTracks) {
                sb.append("#EXTINF:${t.durationSec},${t.artist} - ${t.title}\n")
                
                // Compute relative path for universal car & portable USB playback
                val audioFile = File(t.filepath)
                val relativePath = try {
                    if (audioFile.exists() && audioFile.parentFile != null) {
                        exportDir.toURI().relativize(audioFile.toURI()).path
                    } else {
                        t.filepath
                    }
                } catch (e: Exception) {
                    t.filepath
                }
                sb.append("$relativePath\n\n")
            }

            m3u8File.writeText(sb.toString(), Charsets.UTF_8)
            m3u8File
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
