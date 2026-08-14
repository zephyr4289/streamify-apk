package com.streamify.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.streamify.app.data.NativeBridge

import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class StreamifyApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100 * 1024 * 1024L) // 100MB LRU disk cache for instantaneous album art loading
                    .build()
            }
            .crossfade(300)
            .respectCacheHeaders(false)
            .build()
    }
    override fun onCreate() {
        super.onCreate()
        
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        
        val dbPath = getDatabasePath("streamify.db").absolutePath
        NativeBridge.initDatabase(dbPath)

        val vectorBinPath = java.io.File(filesDir, "vectors.bin").absolutePath
        NativeBridge.initVectorStore(vectorBinPath)

        // Copy ONNX model from assets to filesDir if needed
        val modelFile = java.io.File(filesDir, "clap_int8.onnx")
        if (!modelFile.exists()) {
            try {
                assets.open("models/clap_int8.onnx").use { input ->
                    java.io.FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (modelFile.exists()) {
            NativeBridge.initAudioPipeline(modelFile.absolutePath)
        }

        com.streamify.app.service.AudioDeviceManager.init(this)
        com.streamify.app.data.remote.SupabaseClient.init(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            val playbackChannel = NotificationChannel(
                "streamify_playback",
                "Playback Settings",
                NotificationManager.IMPORTANCE_LOW
            )
            playbackChannel.description = "Controls for the current playing track"
            
            val downloadChannel = NotificationChannel(
                "streamify_download",
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            downloadChannel.description = "Background download progress"
            
            manager.createNotificationChannel(playbackChannel)
            manager.createNotificationChannel(downloadChannel)
        }
    }
}
