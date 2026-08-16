package com.streamify.app.ui.components

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

val LocalQuantumController = staticCompositionLocalOf { QuantumSonicTokenController() }
val LocalDockPosition = staticCompositionLocalOf<MutableState<Offset>> { mutableStateOf(Offset.Zero) }

// The Kinetic Morphing Stages
enum class TokenStage { IDLE, LIFTING, LEVITATING, GLIDING, IMPACT, DISSOLVE, DONE }

class QuantumSonicTokenController {
    var stage by mutableStateOf(TokenStage.IDLE)
        private set

    // Origin & Destination coordinates
    var origin by mutableStateOf(Offset.Zero)
        private set
    var destination by mutableStateOf(Offset.Zero)
        private set
    var currentPosition by mutableStateOf(Offset.Zero)
        private set

    // Track visual data
    var trackTitle by mutableStateOf("")
        private set
    var trackArtist by mutableStateOf("")
        private set
    var trackArt by mutableStateOf<String?>(null)
        private set

    // Progress
    var progress by mutableStateOf(0f)
        private set
    var impactProgress by mutableStateOf(0f)
        private set

    // 3D Matrix Transform Values
    var rotationX by mutableStateOf(0f)
        private set
    var rotationY by mutableStateOf(0f)
        private set
    var scaleX by mutableStateOf(1f)
        private set
    var scaleY by mutableStateOf(1f)
        private set

    fun triggerFlight(
        tapOrigin: Offset,
        dockDestination: Offset,
        title: String,
        artist: String = "",
        art: String? = null
    ) {
        origin = tapOrigin
        destination = dockDestination
        currentPosition = tapOrigin
        trackTitle = title
        trackArtist = artist
        trackArt = art
        progress = 0f
        impactProgress = 0f
        scaleX = 1f
        scaleY = 1f
        rotationX = 0f
        rotationY = 0f
        stage = TokenStage.LIFTING
    }

    fun updatePhysics(deltaTimeMs: Long, totalTimeMs: Long) {
        when (stage) {
            TokenStage.LIFTING -> {
                progress = (progress + (deltaTimeMs / 200f)).coerceAtMost(1f)
                val t = progress
                val liftY = -24f * t
                scaleX = 1f + (0.05f * t)
                scaleY = scaleX
                rotationX = -6f * t
                rotationY = 3f * (1f - t)
                currentPosition = origin.copy(y = origin.y + liftY)
                if (t >= 1f) stage = TokenStage.LEVITATING
            }

            TokenStage.LEVITATING -> {
                val t = totalTimeMs / 1000f
                val floatY = -24f + (6f * sin(2 * PI.toFloat() * 1.5f * t))
                rotationY = 3f * cos(2 * PI.toFloat() * 1.5f * t)
                rotationX = -6f + (2f * sin(2 * PI.toFloat() * 1.2f * t))
                currentPosition = origin.copy(y = origin.y + floatY)
            }

            TokenStage.GLIDING -> {
                progress = (progress + (deltaTimeMs / 360f)).coerceAtMost(1f)
                val t = progress
                val easeOut = 1f - (1f - t).pow(3f)

                // Parabolic Bezier trajectory
                val p0 = origin
                val p1 = Offset(
                    origin.x + (destination.x - origin.x) * 0.3f,
                    (origin.y - 180f).coerceAtLeast(60f)
                )
                val p2 = destination

                val oneMinusT = 1f - easeOut
                val x = (oneMinusT * oneMinusT * p0.x) + (2 * oneMinusT * easeOut * p1.x) + (easeOut * easeOut * p2.x)
                val y = (oneMinusT * oneMinusT * p0.y) + (2 * oneMinusT * easeOut * p1.y) + (easeOut * easeOut * p2.y)

                currentPosition = Offset(x, y)

                // Volume-preserving flight stretch
                val flightStretch = (sin(easeOut * PI.toFloat()) * 0.08f)
                scaleX = 1f - flightStretch
                scaleY = 1f + flightStretch
                rotationX = -6f * (1f - easeOut)
                rotationY = 3f * (1f - easeOut) * cos(2 * PI.toFloat() * (totalTimeMs / 1000f))

                if (t >= 1f) {
                    stage = TokenStage.IMPACT
                    impactProgress = 0f
                    com.streamify.app.util.StreamifyHapticEngine.tokenImpact()
                }
            }

            TokenStage.IMPACT -> {
                impactProgress = (impactProgress + (deltaTimeMs / 180f)).coerceAtMost(1f)
                val t = impactProgress
                currentPosition = destination

                // Disney Volume-Preserving Squash and Stretch on landing
                val squashY = when {
                    t < 0.25f -> 1.0f - (0.12f * (t / 0.25f)) // Compress to 0.88
                    t < 0.60f -> 0.88f + (0.18f * ((t - 0.25f) / 0.35f)) // Rebound to 1.06
                    else -> 1.06f - (0.06f * ((t - 0.60f) / 0.40f)) // Settle to 1.00
                }
                scaleY = squashY
                scaleX = 1f + (1f - squashY) * 0.7f // Volume conservation
                rotationX = 0f
                rotationY = 0f

                if (t >= 1f) stage = TokenStage.DISSOLVE
            }

            TokenStage.DISSOLVE -> {
                stage = TokenStage.DONE
            }

            else -> {}
        }
    }

    fun resolveStream() {
        if (stage == TokenStage.LEVITATING || stage == TokenStage.LIFTING) {
            stage = TokenStage.GLIDING
            progress = 0f
        }
    }

    fun reset() {
        stage = TokenStage.IDLE
        progress = 0f
        impactProgress = 0f
        scaleX = 1f
        scaleY = 1f
        rotationX = 0f
        rotationY = 0f
        currentPosition = Offset.Zero
    }
}
