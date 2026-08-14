package com.streamify.app.data

import android.content.Context
import com.chaquo.python.Python
import com.streamify.app.data.models.LyricsData
import com.streamify.app.data.models.LyricsLine
import com.streamify.app.data.models.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

object LyricsCacheManager {

    private fun getTrackHash(title: String, artist: String): String {
        val input = "$title - $artist".lowercase()
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun getCachedLyricsFile(context: Context, title: String, artist: String): File {
        val lyricsDir = File(context.cacheDir, "lyrics")
        if (!lyricsDir.exists()) lyricsDir.mkdirs()
        val hash = getTrackHash(title, artist)
        return File(lyricsDir, "$hash.lrc")
    }

    suspend fun getOrFetchLyrics(context: Context, track: Track): List<LyricsLine> = withContext(Dispatchers.IO) {
        // 1. Check if track already has explicit lyricsPath
        if (!track.lyricsPath.isNullOrBlank()) {
            val file = File(track.lyricsPath)
            if (file.exists() && file.length() > 0) {
                try {
                    return@withContext LyricsData.parseLrc(file.readText()).lines
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // 2. Check local companion .lrc next to audio file
        if (track.filepath.isNotBlank() && !track.filepath.startsWith("http")) {
            val companionLrc = File(track.filepath.substringBeforeLast(".") + ".lrc")
            if (companionLrc.exists() && companionLrc.length() > 0) {
                try {
                    return@withContext LyricsData.parseLrc(companionLrc.readText()).lines
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // 3. Check Disk LRU Lyrics Cache (0ms load)
        val cachedFile = getCachedLyricsFile(context, track.title, track.artist)
        if (cachedFile.exists() && cachedFile.length() > 0) {
            try {
                return@withContext LyricsData.parseLrc(cachedFile.readText()).lines
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 4. Asynchronously fetch from Python lyrics module and save to disk cache
        try {
            if (Python.isStarted()) {
                val py = Python.getInstance()
                val lyricsModule = py.getModule("download_engine.lyrics")
                val lrcContent = lyricsModule.callAttr("fetch_lyrics", track.title, track.artist, track.durationSec)?.toString()
                if (!lrcContent.isNullOrBlank() && lrcContent != "None") {
                    cachedFile.writeText(lrcContent)
                    return@withContext LyricsData.parseLrc(lrcContent).lines
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext emptyList()
    }
}
