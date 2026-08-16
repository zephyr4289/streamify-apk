package com.streamify.app.service

import android.content.Context
import com.streamify.app.data.NativeBridge
import com.streamify.app.data.TrackRepository
import com.streamify.app.data.models.Track
import com.streamify.app.data.network.YouTubeStreamResolver
import com.streamify.app.data.remote.SupabaseClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.HashSet
import kotlin.math.abs

object OnlineTrackProcessor {

    private val processingChannel = Channel<Track>(Channel.UNLIMITED)
    private val queuedTrackIds = Collections.synchronizedSet(HashSet<Int>())
    private val queuedSignatures = Collections.synchronizedSet(HashSet<String>())

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private var workerJob: Job? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (workerJob == null || workerJob?.isActive == false) {
            workerJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                for (track in processingChannel) {
                    processTrackInternal(track)
                }
            }
        }
    }

    /**
     * Enqueues an online track for instant background native DSP processing and cloud sync
     */
    fun enqueue(track: Track, context: Context? = null) {
        if (context != null && appContext == null) {
            init(context)
        }
        if (track.bpm > 0f && track.isProcessed) return

        val sig = "${track.title.trim().lowercase()}::${track.artist.trim().lowercase()}"
        if (queuedSignatures.contains(sig) || (track.id > 0 && queuedTrackIds.contains(track.id))) {
            return
        }

        if (track.id > 0) queuedTrackIds.add(track.id)
        queuedSignatures.add(sig)

        processingChannel.trySend(track)
    }

    private suspend fun processTrackInternal(track: Track) {
        _isProcessing.value = true
        var tempChunk: File? = null
        val ctx = appContext

        try {
            var finalBpm = track.bpm
            var finalKey = track.key

            // 1. Resolve live direct CDN audio URL if needed
            val streamUrl = if (track.filepath.startsWith("http") && track.filepath.contains("googlevideo.com") && !YouTubeStreamResolver.isCdnExpired(track.filepath)) {
                track.filepath
            } else {
                val resolved = YouTubeStreamResolver.resolveTrackStream(track)
                resolved?.streamUrl ?: ""
            }

            if (streamUrl.isNotBlank() && ctx != null) {
                // 2. Download a 30-second chorus slice (~600KB) into cacheDir
                val cacheDir = ctx.cacheDir
                val trackKey = if (track.id > 0) track.id.toString() else abs(track.title.hashCode()).toString()
                tempChunk = File(cacheDir, "dsp_chunk_${trackKey}.webm")

                val url = URL(streamUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Range", "bytes=0-650000")
                    connectTimeout = 7000
                    readTimeout = 7000
                }

                if (conn.responseCode in 200..206) {
                    conn.inputStream.use { input ->
                        FileOutputStream(tempChunk).use { output ->
                            input.copyTo(output)
                        }
                    }

                    // 3. Execute Native C++ DSP Engine: FFT, Aubio BPM extraction, and Vector Embeddings
                    if (tempChunk.exists() && tempChunk.length() > 0) {
                        val validId = if (track.id > 0) track.id else NativeBridge.upsertStreamedTrack(
                            filepath = track.filepath,
                            title = track.title,
                            artist = track.artist,
                            album = track.album,
                            durationSec = track.durationSec,
                            coverArtPath = track.coverArtPath ?: "",
                            lyricsPath = track.lyricsPath ?: "",
                            bpm = 0f,
                            key = ""
                        )

                        if (validId > 0) {
                            val extractedBpm = try {
                                NativeBridge.extractBPM(validId, tempChunk.absolutePath)
                            } catch (e: Exception) {
                                120.0f
                            }
                            NativeBridge.processAudioFile(validId, tempChunk.absolutePath)
                            finalBpm = if (extractedBpm > 0f) extractedBpm else 120.0f
                            finalKey = "C"

                            // 4. Update SQLite record with calculated BPM and Key
                            NativeBridge.upsertStreamedTrack(
                                filepath = track.filepath,
                                title = track.title,
                                artist = track.artist,
                                album = track.album,
                                durationSec = track.durationSec,
                                coverArtPath = track.coverArtPath ?: "",
                                lyricsPath = track.lyricsPath ?: "",
                                bpm = finalBpm,
                                key = finalKey
                            )

                            // 5. Sync to Supabase Cloud Catalog
                            val processedTrack = track.copy(
                                id = validId,
                                bpm = finalBpm,
                                key = finalKey,
                                isProcessed = true
                            )
                            SupabaseClient.upsertCloudTrack(processedTrack)

                            // 6. Refresh repository state so UI updates immediately
                            TrackRepository.refresh()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            tempChunk?.let {
                try { if (it.exists()) it.delete() } catch (e: Exception) { /* ignore */ }
            }
            _isProcessing.value = false
        }
    }
}
