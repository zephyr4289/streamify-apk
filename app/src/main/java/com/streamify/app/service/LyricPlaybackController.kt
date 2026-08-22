package com.streamify.app.service

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs

/**
 * 120 FPS lyric clock with per-track persisted sync offsets.
 *
 * L2 fix: bindTrack(key) attaches a stable track identity — offsets persist
 * across screens, surfaces and app restarts via LyricOffsetStore.
 * L3 fix: adjustOffset()/resetOffset() raise snapPending so the next frame
 * applies the nudge INSTANTLY instead of bleeding through the 25%-per-frame
 * smoothing filter (which made ±0.5s taps feel dead).
 */
class LyricPlaybackController {

    var targetPositionMs by mutableStateOf(0L)
    var userOffsetMs by mutableStateOf(0L)
        private set
    var isPlaying by mutableStateOf(false)
    var interpolatedPosMs by mutableStateOf(0L)
        private set

    /** Stable identity of the bound track (LyricOffsetStore.keyOf). */
    var trackKey: String? = null
        private set

    private var lastObservedTargetMs = -1L
    private var lastTargetTimeNanos = 0L
    private var lastFrameTimeNanos = 0L
    private var snapPending = false

    /**
     * Attaches the controller to a track: restores the user's saved offset and
     * routes every subsequent nudge into persistent storage.
     */
    fun bindTrack(key: String?) {
        trackKey = key
        userOffsetMs = LyricOffsetStore.get(key)
        snapPending = true // re-anchor rendering to the restored offset
    }

    fun adjustOffset(deltaMs: Long) {
        userOffsetMs += deltaMs
        snapPending = true
        val key = trackKey ?: return
        LyricOffsetStore.set(key, userOffsetMs)
    }

    fun resetOffset() {
        userOffsetMs = 0L
        snapPending = true
        val key = trackKey ?: return
        LyricOffsetStore.set(key, 0L)
    }

    suspend fun runFrameLoop() {
        while (true) {
            withFrameNanos { frameTimeNanos ->
                if (lastFrameTimeNanos == 0L) {
                    lastFrameTimeNanos = frameTimeNanos
                    lastTargetTimeNanos = frameTimeNanos
                    lastObservedTargetMs = targetPositionMs
                    interpolatedPosMs = targetPositionMs + userOffsetMs
                    return@withFrameNanos
                }

                lastFrameTimeNanos = frameTimeNanos

                // When ExoPlayer reports a new position tick
                if (targetPositionMs != lastObservedTargetMs) {
                    lastObservedTargetMs = targetPositionMs
                    lastTargetTimeNanos = frameTimeNanos
                }

                if (!isPlaying) {
                    interpolatedPosMs = (lastObservedTargetMs + userOffsetMs).coerceAtLeast(0L)
                    snapPending = false
                    return@withFrameNanos
                }

                // Extrapolate real-time clock advancement between 200ms ExoPlayer updates
                val elapsedSinceTargetMs = if (lastTargetTimeNanos > 0L) {
                    ((frameTimeNanos - lastTargetTimeNanos) / 1_000_000.0).coerceIn(0.0, 250.0)
                } else 0.0

                val exactTarget = (lastObservedTargetMs.toDouble() + elapsedSinceTargetMs + userOffsetMs.toDouble()).toLong()
                val diff = exactTarget - interpolatedPosMs

                if (snapPending || abs(diff) > 800L || interpolatedPosMs <= 0L) {
                    // Instant snap on seek, initial load, or a user nudge (L3).
                    interpolatedPosMs = exactTarget
                    snapPending = false
                } else {
                    // Smooth 120 FPS tracking
                    val step = (diff * 0.25).toLong()
                    interpolatedPosMs += if (step != 0L) step else (if (diff > 0) 1L else if (diff < 0) -1L else 0L)
                }
            }
        }
    }
}
