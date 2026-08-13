package com.streamify.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.streamify.app.data.NativeBridge
import com.streamify.app.data.TrackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker.Result

class DownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

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

        try {
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

                override fun onError(error: String) {
                    // Log error
                }
            }

            val success = coreModule.callAttr("download_audio", url, outputDir.absolutePath, callback, quality).toBoolean()
            
            var targetPath = completedFilePath
            if (targetPath == null || !File(targetPath).exists()) {
                targetPath = outputDir.listFiles()?.filter { 
                    it.name.endsWith(".mp3") || it.name.endsWith(".m4a") || it.name.endsWith(".webm") || it.name.endsWith(".opus")
                }?.maxByOrNull { it.lastModified() }?.absolutePath
            }

            if (success && targetPath != null && File(targetPath).exists()) {
                // Inject metadata and extract cover art & lyrics path
                val metadataResult = try {
                    metadataModule.callAttr("inject_metadata", targetPath, title, artist, album, null)
                } catch (e: Exception) {
                    null
                }
                
                var durationSec = 0
                var bpm = 120.0f
                var coverArtPath = ""
                var lyricsPath = ""
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
                        if (list.size >= 4) {
                            lyricsPath = list[3].toString()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                // Insert to database using JNI on Dispatchers.IO
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
                    // Run ONNX feature extraction & VectorStore embedding
                    try {
                        NativeBridge.processAudioFile(trackId, targetPath)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    // Auto-assign to 'Streamify' Playlist
                    try {
                        com.streamify.app.data.PlaylistRepository.init(applicationContext)
                        val repo = com.streamify.app.data.PlaylistRepository
                        var playlist = repo.playlists.value.find { it.name.equals("Streamify", ignoreCase = true) }
                        if (playlist == null) {
                            repo.createPlaylist("Streamify", "Downloaded songs on Streamify")
                            playlist = repo.playlists.value.find { it.name.equals("Streamify", ignoreCase = true) }
                        }
                        if (playlist != null) {
                            repo.addTrackToPlaylist(playlist.id, trackId)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    // Scan file with Android MediaStore
                    try {
                        android.media.MediaScannerConnection.scanFile(
                            applicationContext,
                            arrayOf(targetPath),
                            null,
                            null
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    // Refresh repository flow so all UI screens update automatically
                    TrackRepository.refresh()
                }

                Result.success()
            } else {
                Result.failure()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }
}

interface DownloadCallback {
    fun onProgress(percent: String, speed: String, eta: String)
    fun onFinished(filepath: String)
    fun onError(error: String)
}

