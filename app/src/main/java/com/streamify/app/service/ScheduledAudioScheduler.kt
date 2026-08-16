package com.streamify.app.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.streamify.app.data.models.Track
import kotlinx.coroutines.*

class ScheduledAudioScheduler(
    private val player: Player,
    private val precisionProtocol: PrecisionTimeProtocol
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var scheduledJob: Job? = null

    /**
     * Pre-buffers the track and schedules atomic playout at the exact target atomic millisecond.
     */
    fun scheduleAtomicPlayback(
        track: Track,
        targetAtomicTimestampMs: Long,
        startPositionMs: Long = 0L,
        onPlaybackStarted: (() -> Unit)? = null
    ) {
        scheduledJob?.cancel()
        scheduledJob = scope.launch {
            try {
                // 1. Prepare and pre-buffer the track in paused state
                val mediaItem = MediaItem.Builder()
                    .setUri(track.filepath)
                    .setMediaId(track.id.toString())
                    .build()

                player.setMediaItem(mediaItem)
                if (startPositionMs > 0) {
                    player.seekTo(startPositionMs)
                }
                player.prepare()
                player.playWhenReady = false

                // 2. Countdown loop with hybrid delay + tight busy-wait for sub-millisecond precision
                withContext(Dispatchers.Default) {
                    while (isActive) {
                        val currentAtomicTime = precisionProtocol.getSynchronizedClockMs()
                        val timeUntilTarget = targetAtomicTimestampMs - currentAtomicTime

                        if (timeUntilTarget <= 0) {
                            // Target reached: release playback atomically!
                            withContext(Dispatchers.Main) {
                                player.playWhenReady = true
                                player.play()
                                onPlaybackStarted?.invoke()
                            }
                            break
                        } else if (timeUntilTarget <= 20) {
                            // Final 20ms: tight CPU spin (busy-wait) to bypass Android Linux timer granularity
                            // Yield briefly to prevent freezing other threads while maintaining sub-millisecond accuracy
                            Thread.onSpinWait()
                        } else {
                            // Coarse sleep to conserve CPU and battery
                            delay(timeUntilTarget - 18)
                        }
                    }
                }
            } catch (e: Exception) {
                // If scheduled execution fails, fallback to immediate playback
                withContext(Dispatchers.Main) {
                    player.playWhenReady = true
                    player.play()
                }
            }
        }
    }

    fun cancel() {
        scheduledJob?.cancel()
    }
}
