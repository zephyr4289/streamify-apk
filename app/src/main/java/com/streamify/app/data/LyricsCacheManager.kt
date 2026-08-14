package com.streamify.app.data

import android.content.Context
import com.streamify.app.data.models.LyricsData
import com.streamify.app.data.models.LyricsLine
import com.streamify.app.data.models.Track
import com.streamify.app.data.network.LyricsResolver
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
                    val parsed = LyricsData.parseLrc(file.readText()).lines
                    if (parsed.isNotEmpty()) return@withContext parsed
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
                    val parsed = LyricsData.parseLrc(companionLrc.readText()).lines
                    if (parsed.isNotEmpty()) return@withContext parsed
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // 3. Check Disk LRU Lyrics Cache (0ms load)
        val cachedFile = getCachedLyricsFile(context, track.title, track.artist)
        if (cachedFile.exists() && cachedFile.length() > 0) {
            try {
                val parsed = LyricsData.parseLrc(cachedFile.readText()).lines
                if (parsed.isNotEmpty()) return@withContext parsed
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 4. Asynchronously fetch from Native Kotlin Multi-Provider LyricsResolver (<100ms)
        try {
            val lrcContent = LyricsResolver.fetchSyncedLyrics(track.title, track.artist, track.durationSec)
            if (!lrcContent.isNullOrBlank()) {
                cachedFile.writeText(lrcContent)

                // If this is a local track, write companion .lrc
                if (track.filepath.isNotBlank() && !track.filepath.startsWith("http")) {
                    saveCompanionLyrics(track.filepath, lrcContent)
                }

                return@withContext LyricsData.parseLrc(lrcContent).lines
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext emptyList()
    }

    fun saveCompanionLyrics(audioFilePath: String, lrcContent: String) {
        try {
            if (audioFilePath.isBlank() || audioFilePath.startsWith("http")) return
            val companionFile = File(audioFilePath.substringBeforeLast(".") + ".lrc")
            companionFile.writeText(lrcContent)
        } catch (e: Exception) {
            // Ignore filesystem write error
        }
    }
}
