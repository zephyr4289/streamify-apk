package com.streamify.app.service

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs

class LyricPlaybackController {

    var targetPositionMs by mutableStateOf(0L)
    var userOffsetMs by mutableStateOf(0L)
    var isPlaying by mutableStateOf(false)
    var interpolatedPosMs by mutableStateOf(0L)
        private set

    private var lastObservedTargetMs = -1L
    private var lastTargetTimeNanos = 0L
    private var lastFrameTimeNanos = 0L

    fun adjustOffset(deltaMs: Long) {
        userOffsetMs += deltaMs
    }

    fun resetOffset() {
        userOffsetMs = 0L
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
                    return@withFrameNanos
                }

                // Extrapolate real-time clock advancement between 200ms ExoPlayer updates
                val elapsedSinceTargetMs = if (lastTargetTimeNanos > 0L) {
                    ((frameTimeNanos - lastTargetTimeNanos) / 1_000_000.0).coerceIn(0.0, 250.0)
                } else 0.0

                val exactTarget = (lastObservedTargetMs.toDouble() + elapsedSinceTargetMs + userOffsetMs.toDouble()).toLong()
                val diff = exactTarget - interpolatedPosMs

                if (abs(diff) > 800L || interpolatedPosMs <= 0L) {
                    // Instant snap on seek or initial load
                    interpolatedPosMs = exactTarget
                } else {
                    // Smooth 120 FPS tracking
                    val step = (diff * 0.25).toLong()
                    interpolatedPosMs += if (step != 0L) step else (if (diff > 0) 1L else if (diff < 0) -1L else 0L)
                }
            }
        }
    }
}

