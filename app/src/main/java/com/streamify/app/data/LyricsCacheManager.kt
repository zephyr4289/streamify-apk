package com.streamify.app.data

import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.streamify.app.data.models.LyricsData
import com.streamify.app.data.models.LyricsLine
import com.streamify.app.data.models.Track
import com.streamify.app.data.network.LyricsResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance Service-Tier SLYR & LRC binary cache manager.
 * Enforces ByteOrder.nativeOrder(), active currentlyPlayingTrackId pinning, and Bluetooth A2DP compensation.
 */
object LyricsCacheManager {

    @Volatile
    var currentlyPlayingTrackId: Int = -1

    // In-memory pinned cache for active and lookahead tracks (Direct ByteBuffers)
    private val memorySlyrCache = ConcurrentHashMap<String, ByteBuffer>()
    private var currentlyPlayingTrackIdStr: String? = null

    // ═══════════════════════════════════════════════════════════════
    // PHASE 4: PINNED MEMORY STORE & DRIFT CALIBRATION
    // ═══════════════════════════════════════════════════════════════
    fun pinTrack(trackId: String, rawSlyrBytes: ByteArray) {
        currentlyPlayingTrackIdStr = trackId
        val directBuffer = ByteBuffer.allocateDirect(rawSlyrBytes.size).apply {
            order(ByteOrder.nativeOrder())
            put(rawSlyrBytes)
            flip()
        }
        memorySlyrCache[trackId] = directBuffer
    }

    fun getActiveLineIndex(trackId: String, playheadMs: Long): Int {
        val buffer = memorySlyrCache[trackId] ?: return -1
        val rawArray = ByteArray(buffer.remaining())
        val position = buffer.position()
        buffer.get(rawArray)
        buffer.position(position)

        return NativeBridge.findActiveSlyrLine(rawArray, rawArray.size, playheadMs.toInt())
    }

    fun calibrateDrift(vocalEnergy100Hz: FloatArray, lyricOnsets100Hz: FloatArray): Int {
        return NativeBridge.calculateDriftOffset(
            vocalEnergy100Hz, vocalEnergy100Hz.size,
            lyricOnsets100Hz, lyricOnsets100Hz.size
        )
    }

    fun evictUnpinned() {
        memorySlyrCache.keys.removeIf { it != currentlyPlayingTrackIdStr }
    }

    // Bluetooth latency offset cache (milliseconds)
    @Volatile
    private var bluetoothDelayMs: Int = 0

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

    fun getCachedSlyrFile(context: Context, title: String, artist: String): File {
        val lyricsDir = File(context.cacheDir, "lyrics")
        if (!lyricsDir.exists()) lyricsDir.mkdirs()
        val hash = getTrackHash(title, artist)
        return File(lyricsDir, "$hash.slyr")
    }

    /**
     * Updates Bluetooth A2DP latency compensation based on hardware audio routing.
     */
    fun updateBluetoothLatency(context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                if (audioManager.isBluetoothA2dpOn) {
                    bluetoothDelayMs = 120 // Standard A2DP hardware decode/buffer delay
                } else {
                    bluetoothDelayMs = 0
                }
            }
        } catch (e: Exception) {
            bluetoothDelayMs = 0
        }
    }

    fun getBluetoothDelayCompensationMs(): Int = bluetoothDelayMs

    /**
     * Loads or creates a direct memory-mapped SLYR binary buffer for 120 FPS Compose rendering.
     */
    suspend fun getOrLoadSlyrBuffer(context: Context, track: Track): ByteBuffer? = withContext(Dispatchers.IO) {
        val hash = getTrackHash(track.title, track.artist)
        val existing = memorySlyrCache[hash]
        if (existing != null) {
            return@withContext existing.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
        }

        val slyrFile = getCachedSlyrFile(context, track.title, track.artist)
        if (slyrFile.exists() && slyrFile.length() >= 32) {
            try {
                val bytes = slyrFile.readBytes()
                val directBuf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                directBuf.put(bytes)
                directBuf.flip()
                memorySlyrCache[hash] = directBuf
                return@withContext directBuf.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Fallback: fetch text LRC and build SLYR on the fly
        val lrcLines = getOrFetchLyrics(context, track)
        if (lrcLines.isNotEmpty()) {
            val lrcFile = getCachedLyricsFile(context, track.title, track.artist)
            if (lrcFile.exists()) {
                try {
                    val lrcText = lrcFile.readText()
                    val dummyDoc = buildSimpleSlyrBytes(lrcText)
                    if (dummyDoc.isNotEmpty()) {
                        slyrFile.writeBytes(dummyDoc)
                        val directBuf = ByteBuffer.allocateDirect(dummyDoc.size).order(ByteOrder.nativeOrder())
                        directBuf.put(dummyDoc)
                        directBuf.flip()
                        memorySlyrCache[hash] = directBuf
                        return@withContext directBuf.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        null
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

        emptyList()
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

    /**
     * Universally saves adjusted lyrics to local disk LRU cache, companion .lrc (if local),
     * and synchronizes the path with the local SQLite database.
     */
    fun saveLyricsToDiskAndDb(context: Context, track: Track, lrcContent: String) {
        try {
            // 1. Write to local app disk LRU cache
            val cachedFile = getCachedLyricsFile(context, track.title, track.artist)
            cachedFile.writeText(lrcContent)

            // 2. Clear memory SLYR cache for this track so fresh buffer is recomputed
            val hash = getTrackHash(track.title, track.artist)
            memorySlyrCache.remove(hash)
            val slyrFile = getCachedSlyrFile(context, track.title, track.artist)
            if (slyrFile.exists()) slyrFile.delete()

            // 3. Save companion .lrc if local file
            if (track.filepath.isNotBlank() && !track.filepath.startsWith("http")) {
                saveCompanionLyrics(track.filepath, lrcContent)
            }

            // 4. Update SQLite database row if track exists
            if (track.id > 0) {
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    try {
                        TrackRepository.upsertStreamedTrack(
                            track.copy(lyricsPath = cachedFile.absolutePath)
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    /**
     * Purges unpinned track buffers when memory pressure or track changes occur.
     */
    fun trimMemory(activeTrackId: Int, keepHashes: Set<String> = emptySet()) {
        currentlyPlayingTrackId = activeTrackId
        memorySlyrCache.entries.removeIf { (key, _) ->
            !keepHashes.contains(key)
        }
    }

    private fun buildSimpleSlyrBytes(lrcText: String): ByteArray {
        val parsed = LyricsData.parseLrc(lrcText).lines
        if (parsed.isEmpty()) return ByteArray(0)

        val headerSize = 32
        val lineHeaderSize = 16
        val syllableSpanSize = 16

        var totalSyllables = 0
        for (l in parsed) {
            val words = l.text.split(" ").filter { it.isNotBlank() }
            totalSyllables += if (words.isNotEmpty()) words.size else 1
        }

        val totalLineHeadersSize = parsed.size * lineHeaderSize
        val totalSyllableSpansSize = totalSyllables * syllableSpanSize

        val textPoolBytes = mutableListOf<Byte>()
        for (l in parsed) {
            textPoolBytes.addAll(l.text.toByteArray(Charsets.UTF_8).toList())
            textPoolBytes.add(0.toByte())
        }

        val totalRawSize = headerSize + totalLineHeadersSize + totalSyllableSpansSize + textPoolBytes.size
        val paddedSize = (totalRawSize + 15) and 15.inv()

        val buf = ByteBuffer.allocate(paddedSize).order(ByteOrder.nativeOrder())

        // Header (32 bytes)
        buf.put("SLYR".toByteArray(Charsets.US_ASCII))
        buf.putShort(1.toShort()) // version
        buf.putShort(parsed.size.toShort()) // line_count
        buf.putInt(totalSyllables) // syllable_count
        val maxDuration = parsed.lastOrNull()?.timeMs?.toInt() ?: 0
        buf.putInt(maxDuration) // duration_ms
        buf.put(ByteArray(16)) // padding

        // LineHeaders
        var currentSylIdx = 0
        var currentTextOffset = 0
        for (i in parsed.indices) {
            val line = parsed[i]
            val nextTime = if (i + 1 < parsed.size) parsed[i + 1].timeMs.toInt() else line.timeMs.toInt() + 3000
            val words = line.text.split(" ").filter { it.isNotBlank() }
            val sylCount = if (words.isNotEmpty()) words.size else 1

            buf.putInt(line.timeMs.toInt()) // start_ms
            buf.putInt(nextTime) // end_ms
            buf.putShort(currentSylIdx.toShort())
            buf.putShort(sylCount.toShort())
            buf.putInt(currentTextOffset)

            currentSylIdx += sylCount
            currentTextOffset += line.text.toByteArray(Charsets.UTF_8).size + 1
        }

        // SyllableSpans
        for (line in parsed) {
            val words = line.text.split(" ").filter { it.isNotBlank() }
            val lineStart = line.timeMs.toInt()
            val nextTime = lineStart + 3000
            val lineDur = (nextTime - lineStart).coerceAtLeast(500)

            if (words.isNotEmpty()) {
                val step = lineDur / words.size
                var charOffset = 0
                for (w in words) {
                    buf.putInt(lineStart + charOffset * step) // start_ms
                    buf.putInt(lineStart + (charOffset + 1) * step) // end_ms
                    buf.putShort(charOffset.toShort())
                    buf.putShort(w.length.toShort())
                    buf.putInt(0) // flags
                    charOffset += 1
                }
            } else {
                buf.putInt(lineStart)
                buf.putInt(nextTime)
                buf.putShort(0)
                buf.putShort(line.text.length.toShort())
                buf.putInt(0)
            }
        }

        // TextPool
        for (b in textPoolBytes) {
            buf.put(b)
        }

        return buf.array()
    }
}
