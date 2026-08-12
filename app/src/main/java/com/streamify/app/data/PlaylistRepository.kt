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
}
