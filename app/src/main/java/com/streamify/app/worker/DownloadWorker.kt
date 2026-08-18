package com.streamify.app.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.chaquo.python.Python
import com.streamify.app.data.NativeBridge
import com.streamify.app.data.NativeMetadataTagger
import com.streamify.app.data.PlaylistRepository
import com.streamify.app.data.TrackRepository
import com.streamify.app.data.network.ParallelStreamDownloader
import com.streamify.app.data.network.YouTubeStreamResolver
import com.streamify.app.service.LosslessRemuxer
import com.streamify.app.viewmodel.UiEvent
import com.streamify.app.viewmodel.UiEventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        // Hard-cap background DSP across all DownloadWorkers to 1 thread to protect audio decoding
        private val dspDispatcher = Dispatchers.IO.limitedParallelism(1)
    }

    private val notificationId = 12345

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(inputData.getString("title") ?: "Downloading Track")
    }

    private fun createForegroundInfo(title: String): ForegroundInfo {
        val channelId = "download_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Downloads"
            val channel = NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_LOW)
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(title)
            .setContentText("Downloading from source...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val url = inputData.getString("url") ?: return@withContext Result.failure()
        val title = inputData.getString("title") ?: "Unknown"
        val artist = inputData.getString("artist") ?: "Unknown"
        var album = inputData.getString("album") ?: "Streamify"
        if (album.isBlank() || album == "Unknown" || album == "Downloads") {
            album = "Streamify"
        }
        val quality = inputData.getString("quality") ?: "320"

        val musicDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC), "Streamify")
        if (!musicDir.exists()) {
            try { musicDir.mkdirs() } catch (e: Exception) { e.printStackTrace() }
        }
        val outputDir = if (musicDir.exists()) musicDir else File(applicationContext.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "Streamify")
        if (!outputDir.exists()) outputDir.mkdirs()

        // -------------------------------------------------------------
        // FAST-PATH: Pure Kotlin Native Hermes Downloader (<3s, Zero Python)
        // -------------------------------------------------------------
        var fastPathSuccess = false
        try {
            val videoId = if (url.contains("v=")) url.substringAfter("v=").substringBefore("&") else url.substringAfterLast("/")
            val streamResult = YouTubeStreamResolver.resolveStreamUrl(videoId)

            if (streamResult != null && streamResult.streamUrl.isNotBlank()) {
                val tempRaw = File(applicationContext.cacheDir, "hermes_${System.currentTimeMillis()}.raw")
                val downloader = ParallelStreamDownloader()
                
                val downloadOk = downloader.download(streamResult.streamUrl, tempRaw) { percent, speed, eta ->
                    setProgressAsync(workDataOf(
                        "progress" to percent,
                        "speed" to speed,
                        "eta" to eta
                    ))
                }

                if (downloadOk && tempRaw.exists() && tempRaw.length() > 0) {
                    val targetFile = LosslessRemuxer.prepareTargetFile(outputDir, title, artist, streamResult.mimeType)
                    if (LosslessRemuxer.remuxLossless(tempRaw, targetFile)) {
                        // Tag with iTunes 1400x1400 Retina art and lyrics
                        val taggedAssets = NativeMetadataTagger.tagAndExtractAssets(targetFile, title, artist)

                        val trackId = NativeBridge.insertTrack(
                            filepath = targetFile.absolutePath,
                            title = title,
                            artist = artist,
                            album = album,
                            durationSec = 0,
                            bpm = 120.0f
                        ).toInt()

                        if (trackId > 0) {
                            if (taggedAssets.coverArtPath.isNotBlank()) {
                                NativeBridge.updateTrackCoverArt(trackId, taggedAssets.coverArtPath)
                            }
                            
                            // Background AI Feature extraction (Throttled single-core execution with buffer gate)
                            withContext(dspDispatcher) {
                                try {
                                    while (com.streamify.app.service.PlaybackService.isBuffering.value) {
                                        kotlinx.coroutines.delay(200)
                                    }
                                    NativeBridge.processAudioFile(trackId, targetFile.absolutePath)
                                } catch (e: Exception) {}
                            }

                            // Add to Streamify playlist
                            try {
                                PlaylistRepository.init(applicationContext)
                                var playlist = PlaylistRepository.playlists.value.find { it.name.equals("Streamify", ignoreCase = true) }
                                if (playlist == null) {
                                    PlaylistRepository.createPlaylist("Streamify", "Downloaded songs on Streamify")
                                    playlist = PlaylistRepository.playlists.value.find { it.name.equals("Streamify", ignoreCase = true) }
                                }
                                if (playlist != null) {
                                    PlaylistRepository.addTrackToPlaylist(playlist.id, trackId)
                                }
                            } catch (e: Exception) {}

                            // Scan MediaStore
                            try {
                                android.media.MediaScannerConnection.scanFile(
                                    applicationContext,
                                    arrayOf(targetFile.absolutePath),
                                    null,
                                    null
                                )
                            } catch (e: Exception) {}

                            TrackRepository.refresh()
                            UiEventBus.emitEvent(UiEvent.ShowSnackbar("Saved Lossless Track ($title)"))
                            fastPathSuccess = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            fastPathSuccess = false
        }

        if (fastPathSuccess) {
            return@withContext Result.success()
        }

        // -------------------------------------------------------------
        // FALLBACK: Python yt-dlp Core
        // -------------------------------------------------------------
        try {
            if (Python.isStarted()) {
                val py = Python.getInstance()
                val coreModule = py.getModule("download_engine.core")
                val metadataModule = py.getModule("download_engine.metadata")
                
                var completedFilePath: String? = null

                val callback = object : DownloadCallback {
                    override fun onProgress(percent: String, speed: String, eta: String) {
                        setProgressAsync(workDataOf(
                            "progress" to percent,
                            "speed" to speed,
                            "eta" to eta
                        ))
                    }

                    override fun onFinished(filepath: String) {
                        completedFilePath = filepath
                    }

                    override fun onError(error: String) {}
                }

                val success = coreModule.callAttr("download_audio", url, outputDir.absolutePath, callback, quality).toBoolean()
                
                var targetPath = completedFilePath
                if (targetPath == null || !File(targetPath).exists()) {
                    targetPath = outputDir.listFiles()?.filter { 
                        it.name.endsWith(".mp3") || it.name.endsWith(".m4a") || it.name.endsWith(".webm") || it.name.endsWith(".opus")
                    }?.maxByOrNull { it.lastModified() }?.absolutePath
                }

                if (targetPath != null && File(targetPath).exists()) {
                    val metadataResult = try {
                        metadataModule.callAttr("inject_metadata", targetPath, title, artist, album, null)
                    } catch (e: Exception) {
                        null
                    }
                    
                    var durationSec = 0
                    var bpm = 120.0f
                    var coverArtPath = ""
                    if (metadataResult != null) {
                        try {
                            val list = metadataResult.asList()
                            if (list.size >= 2) {
                                durationSec = list[0].toInt()
                                bpm = list[1].toFloat()
                            }
                            if (list.size >= 3) {
                                coverArtPath = list[2].toString()
                            }
                        } catch (e: Exception) {}
                    }
                    
                    val trackId = NativeBridge.insertTrack(
                        filepath = targetPath,
                        title = title,
                        artist = artist,
                        album = album,
                        durationSec = durationSec,
                        bpm = bpm
                    ).toInt()

                    if (trackId > 0) {
                        if (coverArtPath.isNotBlank()) {
                            NativeBridge.updateTrackCoverArt(trackId, coverArtPath)
                        }
                        // Background AI Feature extraction (Throttled single-core execution with buffer gate)
                        withContext(dspDispatcher) {
                            try {
                                while (com.streamify.app.service.PlaybackService.isBuffering.value) {
                                    kotlinx.coroutines.delay(200)
                                }
                                NativeBridge.processAudioFile(trackId, targetPath)
                            } catch (e: Exception) {}
                        }

                        try {
                            val lyrics = com.streamify.app.data.network.LyricsResolver.fetchSyncedLyrics(title, artist)
                            if (!lyrics.isNullOrBlank()) {
                                com.streamify.app.data.LyricsCacheManager.saveCompanionLyrics(targetPath, lyrics)
                            }
                        } catch (e: Exception) {}

                        try {
                            PlaylistRepository.init(applicationContext)
                            var playlist = PlaylistRepository.playlists.value.find { it.name.equals("Streamify", ignoreCase = true) }
                            if (playlist == null) {
                                PlaylistRepository.createPlaylist("Streamify", "Downloaded songs on Streamify")
                                playlist = PlaylistRepository.playlists.value.find { it.name.equals("Streamify", ignoreCase = true) }
                            }
                            if (playlist != null) {
                                PlaylistRepository.addTrackToPlaylist(playlist.id, trackId)
                            }
                        } catch (e: Exception) {}

                        try {
                            android.media.MediaScannerConnection.scanFile(
                                applicationContext,
                                arrayOf(targetPath),
                                null,
                                null
                            )
                        } catch (e: Exception) {}

                        TrackRepository.refresh()
                        UiEventBus.emitEvent(UiEvent.ShowSnackbar("Saved to Streamify Library ($title)"))
                    }
                    return@withContext Result.success()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        Result.failure()
    }
}

interface DownloadCallback {
    fun onProgress(percent: String, speed: String, eta: String)
    fun onFinished(filepath: String)
    fun onError(error: String)
}
