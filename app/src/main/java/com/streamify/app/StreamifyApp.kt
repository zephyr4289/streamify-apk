package com.streamify.app

import com.streamify.app.util.SLog
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class StreamifyApp : Application(), ImageLoaderFactory {

    companion object {
        /**
         * App-lifetime scope for deferred background initialization.
         * SupervisorJob: one failed initializer never cancels its siblings.
         */
        val applicationScope: kotlinx.coroutines.CoroutineScope =
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
            )
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    // 20%: leaves headroom on 3GB devices where artwork caches
                    // compete with the audio pipeline.
                    .maxSizePercent(0.20)
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

        // 0. SLog FIRST — every subsequent subsystem logs through it.
        //    Installs the crash hook and starts the disk spool.
        com.streamify.app.util.SLog.initialize(this)
        com.streamify.app.util.SLog.logBootBanner(
            "1.0.${com.streamify.app.BuildConfig.VERSION_CODE}"
        )
        com.streamify.app.data.network.YouTubeStreamResolver.appContext = this

        // Remote fleet adaptation: pull release-free client overrides (2KB JSON).
        com.streamify.app.util.FleetConfig.initialize(this)

        // Screen-level lifecycle breadcrumbs for the admin terminal.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private fun name(a: android.app.Activity) = a.javaClass.simpleName
            override fun onActivityCreated(a: android.app.Activity, s: android.os.Bundle?) {
                com.streamify.app.util.SLog.i("LIFECYCLE", "created ${name(a)}")
            }
            override fun onActivityStarted(a: android.app.Activity) {
                com.streamify.app.util.SLog.i("LIFECYCLE", "started ${name(a)}")
            }
            override fun onActivityResumed(a: android.app.Activity) {
                com.streamify.app.util.SLog.i("LIFECYCLE", "resumed ${name(a)}")
            }
            override fun onActivityPaused(a: android.app.Activity) {
                com.streamify.app.util.SLog.i("LIFECYCLE", "paused ${name(a)}")
            }
            override fun onActivityStopped(a: android.app.Activity) {
                com.streamify.app.util.SLog.i("LIFECYCLE", "stopped ${name(a)}")
            }
            override fun onActivitySaveInstanceState(a: android.app.Activity, s: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {
                com.streamify.app.util.SLog.i("LIFECYCLE", "destroyed ${name(a)}")
            }
        })

        // 1. Ensure TrackRepository application context and Telemetry Engine are bound
        com.streamify.app.data.TrackRepository.appContext = this
        com.streamify.app.data.YtStatsTelemetryEngine.initFromContext(this)

        // 2. Ensure database directory exists and initialize asynchronously off the main thread
        try {
            val dbFile = getDatabasePath("streamify.db")
            dbFile.parentFile?.mkdirs()
            applicationScope.launch(Dispatchers.IO) {
                com.streamify.app.data.DatabaseInitializer.startInitialization(dbFile.absolutePath)
            }
        } catch (e: Throwable) {
            SLog.e("StreamifyApp", "Failed to schedule NativeBridge Database init", e)
        }

        // 3+4. COLD-START BUDGET: vector-store mmap and the multi-MB ONNX
        // asset copy + native session creation are NOT needed for the first
        // frame. Deferring them off the main thread removes 0.5–3s of frozen
        // window on eMMC-class devices.
        applicationScope.launch {
            // 3. Ensure files directory exists for VectorStore
            try {
                val vectorBinFile = java.io.File(filesDir, "vectors.bin")
                vectorBinFile.parentFile?.mkdirs()
                NativeBridge.initVectorStore(vectorBinFile.absolutePath)
            } catch (e: Throwable) {
                SLog.e("StreamifyApp", "Failed to initialize NativeBridge VectorStore", e)
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
                        SLog.w("StreamifyApp", "CLAP ONNX model not found in assets, skipping")
                    }
                }
                if (modelFile.exists()) {
                    NativeBridge.initAudioPipeline(modelFile.absolutePath)
                }
            } catch (e: Throwable) {
                SLog.e("StreamifyApp", "Failed to initialize AudioPipeline", e)
            }
        }

        // 5. Initialize device & remote services safely
        try {
            com.streamify.app.service.AudioDeviceManager.init(this)
        } catch (e: Throwable) {
            SLog.e("StreamifyApp", "Failed to initialize AudioDeviceManager", e)
        }

        try {
            com.streamify.app.data.remote.SupabaseClient.init(this)
        } catch (e: Throwable) {
            SLog.e("StreamifyApp", "Failed to initialize SupabaseClient", e)
        }

        try {
            com.streamify.app.service.OnlineTrackProcessor.init(this)
        } catch (e: Throwable) {
            SLog.e("StreamifyApp", "Failed to initialize OnlineTrackProcessor", e)
        }

        try {
            com.streamify.app.util.StreamifyHapticEngine.init(this)
        } catch (e: Throwable) {
            SLog.e("StreamifyApp", "Failed to initialize StreamifyHapticEngine", e)
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

        // Authenticated YouTube resolution: expose the harvested session to
        // the stream resolver (SAPISIDHASH + cookies past the 2026 bot-wall).
        com.streamify.app.data.network.YouTubeStreamResolver.ytSessionProvider = {
            val m = com.streamify.app.data.remote.SpotifyAuthManager(this)
            (m.getYtAuthHeader() ?: "") to (m.getYtRawCookies() ?: "")
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.streamify.app.util.SLog.w("LIFECYCLE", "onTrimMemory level=$level")
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
