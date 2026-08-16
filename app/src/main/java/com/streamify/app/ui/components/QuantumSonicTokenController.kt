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

// The 4 Stages of the Kinetic Timeline
enum class TokenStage { IDLE, LIFTING, LEVITATING, GLIDING, IMPACT, DONE }

class QuantumSonicTokenController {
    var stage by mutableStateOf(TokenStage.IDLE)
        private set

    // Origin coordinates (where the user tapped)
    var origin by mutableStateOf(Offset.Zero)
        private set

    // Destination coordinates (the docked mini-player)
    var destination by mutableStateOf(Offset.Zero)
        private set

    // Current position in window during flight
    var currentPosition by mutableStateOf(Offset.Zero)
        private set

    // Track visual data
    var trackTitle by mutableStateOf("")
        private set
    var trackArtist by mutableStateOf("")
        private set
    var trackArt by mutableStateOf<String?>(null)
        private set

    // Animation progress values
    var liftProgress by mutableStateOf(0f)
        private set
    var glideProgress by mutableStateOf(0f)
        private set
    var impactProgress by mutableStateOf(0f)
        private set

    // 3D Physics & GPU Transformation values
    var rotationX by mutableStateOf(0f)
        private set
    var rotationY by mutableStateOf(0f)
        private set
    var translationY by mutableStateOf(0f)
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
        liftProgress = 0f
        glideProgress = 0f
        impactProgress = 0f
        stage = TokenStage.LIFTING
    }

    // 120 FPS Physics tick updater
    fun updatePhysics(deltaTimeMs: Long, totalTimeMs: Long) {
        when (stage) {
            TokenStage.LIFTING -> {
                // 0ms - 250ms: 3D Lift-Off
                liftProgress = (liftProgress + (deltaTimeMs / 250f)).coerceAtMost(1f)
                val t = liftProgress
                translationY = -30f * t
                scaleX = 1f + (0.06f * t)
                scaleY = scaleX
                rotationX = -8f * t
                rotationY = 4f * (1f - t)
                currentPosition = origin.copy(y = origin.y + translationY)
                if (t >= 1f) stage = TokenStage.LEVITATING
            }

            TokenStage.LEVITATING -> {
                // Zero-Gravity Jiggle (While resolving stream)
                val t = totalTimeMs / 1000f
                translationY = -30f + (8f * sin(2 * PI.toFloat() * 1.5f * t))
                rotationY = 4f * cos(2 * PI.toFloat() * 1.5f * t)
                rotationX = -8f + (2f * sin(2 * PI.toFloat() * 1.2f * t))
                currentPosition = origin.copy(y = origin.y + translationY)
            }

            TokenStage.GLIDING -> {
                // Parabolic Bezier Arc Glide (350ms)
                glideProgress = (glideProgress + (deltaTimeMs / 350f)).coerceAtMost(1f)
                val t = glideProgress
                val easeOut = 1f - (1f - t).pow(3f)

                // Quadratic Bezier Curve: P0(origin) -> P1(control) -> P2(destination)
                val p0 = origin
                val p1 = Offset(origin.x, (destination.y - 200f).coerceAtLeast(origin.y))
                val p2 = destination

                val oneMinusT = 1f - easeOut
                val x = (oneMinusT * oneMinusT * p0.x) + (2 * oneMinusT * easeOut * p1.x) + (easeOut * easeOut * p2.x)
                val y = (oneMinusT * oneMinusT * p0.y) + (2 * oneMinusT * easeOut * p1.y) + (easeOut * easeOut * p2.y)

                currentPosition = Offset(x, y)
                scaleX = 1.06f - (0.06f * easeOut)
                scaleY = scaleX
                rotationX = -8f * (1f - easeOut)
                rotationY = 4f * (1f - easeOut) * cos(2 * PI.toFloat() * 1.5f * (totalTimeMs / 1000f))

                if (t >= 1f) {
                    stage = TokenStage.IMPACT
                    impactProgress = 0f
                }
            }

            TokenStage.IMPACT -> {
                // Disney Squash-and-Stretch Volume Preservation (150ms)
                impactProgress = (impactProgress + (deltaTimeMs / 150f)).coerceAtMost(1f)
                val t = impactProgress
                currentPosition = destination

                scaleY = when {
                    t < 0.2f -> 1.0f - (0.10f * (t / 0.2f)) // Drop to 0.90
                    t < 0.5f -> 0.90f + (0.15f * ((t - 0.2f) / 0.3f)) // Bounce to 1.05
                    else -> 1.05f - (0.05f * ((t - 0.5f) / 0.5f)) // Settle to 1.00
                }
                scaleX = 1f + (1f - scaleY) * 0.5f // Volume preservation
                rotationX = 0f
                rotationY = 0f

                if (t >= 1f) stage = TokenStage.DONE
            }

            else -> {}
        }
    }

    fun resolveStream() {
        if (stage == TokenStage.LEVITATING || stage == TokenStage.LIFTING) {
            stage = TokenStage.GLIDING
        }
    }

    fun reset() {
        stage = TokenStage.IDLE
        liftProgress = 0f
        glideProgress = 0f
        impactProgress = 0f
        translationY = 0f
        rotationX = 0f
        rotationY = 0f
        scaleX = 1f
        scaleY = 1f
        currentPosition = Offset.Zero
    }
}
