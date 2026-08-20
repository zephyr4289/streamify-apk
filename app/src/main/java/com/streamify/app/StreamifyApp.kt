package com.streamify.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.streamify.app.data.NativeBridge

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
        
        // 1. Ensure TrackRepository application context and Telemetry Engine are bound
        com.streamify.app.data.TrackRepository.appContext = this
        com.streamify.app.data.YtStatsTelemetryEngine.initFromContext(this)

        // 2. Ensure database directory exists before C++ sqlite3_open_v2
        try {
            val dbFile = getDatabasePath("streamify.db")
            dbFile.parentFile?.mkdirs()
            NativeBridge.initDatabase(dbFile.absolutePath)
        } catch (e: Throwable) {
            android.util.Log.e("StreamifyApp", "Failed to initialize NativeBridge Database", e)
        }

        // 3. Ensure files directory exists for VectorStore
        try {
            val vectorBinFile = java.io.File(filesDir, "vectors.bin")
            vectorBinFile.parentFile?.mkdirs()
            NativeBridge.initVectorStore(vectorBinFile.absolutePath)
        } catch (e: Throwable) {
            android.util.Log.e("StreamifyApp", "Failed to initialize NativeBridge VectorStore", e)
        }

        // 4. Copy ONNX model from assets if present
        try {
            val modelFile = java.io.File(filesDir, "clap_int8.onnx")
            if (!modelFile.exists()) {
                try {
                    assets.open("models/clap_int8.onnx").use { input ->
                        java.io.FileOutputStream(modelFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Throwable) {
                    android.util.Log.w("StreamifyApp", "CLAP ONNX model not found in assets, skipping")
                }
            }
            if (modelFile.exists()) {
                NativeBridge.initAudioPipeline(modelFile.absolutePath)
            }
        } catch (e: Throwable) {
            android.util.Log.e("StreamifyApp", "Failed to initialize AudioPipeline", e)
        }

        // 5. Initialize device & remote services safely
        try {
            com.streamify.app.service.AudioDeviceManager.init(this)
        } catch (e: Throwable) {
            android.util.Log.e("StreamifyApp", "Failed to initialize AudioDeviceManager", e)
        }

        try {
            com.streamify.app.data.remote.SupabaseClient.init(this)
        } catch (e: Throwable) {
            android.util.Log.e("StreamifyApp", "Failed to initialize SupabaseClient", e)
        }

        try {
            com.streamify.app.service.OnlineTrackProcessor.init(this)
        } catch (e: Throwable) {
            android.util.Log.e("StreamifyApp", "Failed to initialize OnlineTrackProcessor", e)
        }

        try {
            com.streamify.app.util.StreamifyHapticEngine.init(this)
        } catch (e: Throwable) {
            android.util.Log.e("StreamifyApp", "Failed to initialize StreamifyHapticEngine", e)
        }

        try {
            com.streamify.app.service.LibrarySyncWorker.schedulePeriodicSync(this)
        } catch (e: Throwable) {
            // Non-blocking
        }

        try {
            com.streamify.app.service.ThermalGovernorManager.init(this)
        } catch (e: Throwable) {
            // Non-blocking
        }

        createNotificationChannels()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            com.streamify.app.service.ThermalGovernorManager.handleLowMemory(this)
        }
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
