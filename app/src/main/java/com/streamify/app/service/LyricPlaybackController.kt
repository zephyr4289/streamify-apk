package com.streamify.app.service

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs

class LyricPlaybackController {

    var targetPositionMs by mutableStateOf(0L)
    var userOffsetMs by mutableStateOf(0L)
    var interpolatedPosMs by mutableStateOf(0L)
        private set

    private var velocityMs = 0.0
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
                    return@withFrameNanos
                }

                val dt = ((frameTimeNanos - lastFrameTimeNanos) / 1_000_000.0).coerceIn(1.0, 50.0)
                lastFrameTimeNanos = frameTimeNanos

                val effectiveTarget = targetPositionMs + userOffsetMs
                val diff = (effectiveTarget - interpolatedPosMs).toDouble()

                // Large Seek Discontinuity (>1200ms) -> Snap playhead instantly without spring drag
                if (abs(diff) > 1200.0) {
                    interpolatedPosMs = effectiveTarget
                    velocityMs = 0.0
                } else {
                    // Critically Damped Spring Physics (120 FPS sub-pixel smoothing)
                    val springStiffness = 0.14
                    val dampingFactor = 0.82
                    val springForce = diff * springStiffness
                    velocityMs += springForce
                    velocityMs *= dampingFactor
                    val delta = velocityMs * (dt / 16.67)
                    interpolatedPosMs += delta.toLong()
                }
            }
        }
    }
}
