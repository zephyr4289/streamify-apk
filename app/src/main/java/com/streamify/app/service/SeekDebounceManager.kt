package com.streamify.app.service

import androidx.media3.exoplayer.ExoPlayer
import com.streamify.app.data.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SeekDebounceManager(
    private val exoPlayer: ExoPlayer
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var pollerJob: Job? = null

    fun start() {
        if (pollerJob != null) return
        pollerJob = scope.launch {
            while (isActive) {
                val pendingSeekMs = NativeBridge.consumePendingSeek(150L)
                if (pendingSeekMs >= 0) {
                    withContext(Dispatchers.Main) {
                        try {
                            exoPlayer.seekTo(pendingSeekMs)
                        } catch (e: Throwable) {
                            // Non-fatal
                        }
                    }
                }
                delay(30)
            }
        }
    }

    fun release() {
        pollerJob?.cancel()
        pollerJob = null
        NativeBridge.resetSeekGuard()
    }
}
