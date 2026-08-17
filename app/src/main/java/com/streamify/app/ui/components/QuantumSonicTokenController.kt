package com.streamify.app.ui.components

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import com.streamify.app.data.NativeBridge
import kotlin.math.*

val LocalQuantumController = staticCompositionLocalOf { QuantumSonicTokenController() }
val LocalDockPosition = staticCompositionLocalOf<MutableState<Offset>> { mutableStateOf(Offset.Zero) }

enum class TokenStage { IDLE, FLYING, IMPACT, DONE }

class QuantumSonicTokenController {
    var stage by mutableStateOf(TokenStage.IDLE)
        private set

    // Origin & Destination coordinates
    var origin by mutableStateOf(Offset.Zero)
        private set
    var destination by mutableStateOf(Offset.Zero)
        private set
    var initialDistance by mutableStateOf(1f)
        private set

    // Real-time Kinetic Physics Properties
    var currentPosition by mutableStateOf(Offset.Zero)
        private set
    var stretchParallel by mutableStateOf(1f)
        private set
    var stretchPerp by mutableStateOf(1f)
        private set
    var rotationRad by mutableStateOf(0f)
        private set
    var pitchDeg by mutableStateOf(0f)
        private set
    var rollDeg by mutableStateOf(0f)
        private set
    var impactProgress by mutableStateOf(0f)
        private set

    // Track metadata
    var trackTitle by mutableStateOf("")
        private set
    var trackArtist by mutableStateOf("")
        private set
    var trackArt by mutableStateOf<String?>(null)
        private set

    // Direct 13-Float Zero-Allocation JNI Buffer
    // 0: x, 1: y, 2: z, 3: vx, 4: vy, 5: vz, 6: stretch_parallel, 7: stretch_perp, 8: rotation_rad, 9: pitch_deg, 10: roll_deg, 11: impact_progress, 12: is_docked
    private val physicsBuffer = FloatArray(13)

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

        val dx = dockDestination.x - tapOrigin.x
        val dy = dockDestination.y - tapOrigin.y
        initialDistance = max(1f, sqrt(dx * dx + dy * dy))

        // Initial launch velocity slightly upward and outward
        physicsBuffer[0] = tapOrigin.x
        physicsBuffer[1] = tapOrigin.y
        physicsBuffer[2] = 0f
        physicsBuffer[3] = 0f
        physicsBuffer[4] = -120f // Initial upward pop
        physicsBuffer[5] = 0f
        physicsBuffer[6] = 1f // stretch_parallel
        physicsBuffer[7] = 1f // stretch_perp
        physicsBuffer[8] = 0f // rotation_rad
        physicsBuffer[9] = 0f // pitch_deg
        physicsBuffer[10] = 0f // roll_deg
        physicsBuffer[11] = 0f // impact_progress
        physicsBuffer[12] = 0f // is_docked

        stretchParallel = 1f
        stretchPerp = 1f
        rotationRad = 0f
        pitchDeg = 0f
        rollDeg = 0f
        impactProgress = 0f
        stage = TokenStage.FLYING
    }

    /**
     * Advances simulation by dt (seconds) using C++ RK4 Native Kernel
     * with automatic fallback if native bridge encounters issues.
     */
    fun stepSimulation(dt: Float) {
        if (stage == TokenStage.IDLE || stage == TokenStage.DONE) return

        val safeDt = dt.coerceIn(0.001f, 0.05f)

        try {
            NativeBridge.stepAirDropPhysics(
                inOutBuffer = physicsBuffer,
                targetX = destination.x,
                targetY = destination.y,
                initialDist = initialDistance,
                dt = safeDt
            )
        } catch (e: Throwable) {
            // Pure Kotlin fallback simulator matching exact C++ RK4 algorithm
            stepKotlinRK4(safeDt)
        }

        currentPosition = Offset(physicsBuffer[0], physicsBuffer[1])
        stretchParallel = physicsBuffer[6]
        stretchPerp = physicsBuffer[7]
        rotationRad = physicsBuffer[8]
        pitchDeg = physicsBuffer[9]
        rollDeg = physicsBuffer[10]
        impactProgress = physicsBuffer[11]

        val isDocked = physicsBuffer[12] > 0.5f
        if (isDocked && stage == TokenStage.FLYING) {
            stage = TokenStage.IMPACT
            com.streamify.app.util.StreamifyHapticEngine.tokenImpact()
        }

        if (stage == TokenStage.IMPACT && impactProgress >= 1f) {
            stage = TokenStage.DONE
        }
    }

    private fun stepKotlinRK4(dt: Float) {
        if (physicsBuffer[12] > 0.5f) {
            if (physicsBuffer[11] < 1f) {
                physicsBuffer[11] = min(1f, physicsBuffer[11] + (dt / 0.180f))
                val t = physicsBuffer[11]
                val squashY = when {
                    t < 0.25f -> 1f - (0.12f * (t / 0.25f))
                    t < 0.60f -> 0.88f + (0.18f * ((t - 0.25f) / 0.35f))
                    else -> 1.06f - (0.06f * ((t - 0.60f) / 0.40f))
                }
                physicsBuffer[7] = squashY
                physicsBuffer[6] = 1f / max(0.01f, squashY)
            }
            return
        }

        var x = physicsBuffer[0]
        var y = physicsBuffer[1]
        var vx = physicsBuffer[3]
        var vy = physicsBuffer[4]

        fun computeAccel(px: Float, py: Float, pvx: Float, pvy: Float): Pair<Float, Float> {
            val dx = destination.x - px
            val dy = destination.y - py
            val dist = sqrt(dx * dx + dy * dy)
            if (dist < 1f) return Pair(0f, 0f)

            var fx = 180f * dx - 24f * pvx
            var fy = 180f * dy - 24f * pvy

            if (initialDistance > 1f) {
                val liftMag = 450f * sin((dist / initialDistance).coerceIn(0f, 1f) * PI.toFloat())
                fx += (-dy / dist) * liftMag
                fy += ( dx / dist) * liftMag
            }
            return Pair(fx, fy)
        }

        // k1
        val (ax1, ay1) = computeAccel(x, y, vx, vy)
        val k1_vx = ax1 * dt
        val k1_vy = ay1 * dt
        val k1_x = vx * dt
        val k1_y = vy * dt

        // k2
        val (ax2, ay2) = computeAccel(x + 0.5f * k1_x, y + 0.5f * k1_y, vx + 0.5f * k1_vx, vy + 0.5f * k1_vy)
        val k2_vx = ax2 * dt
        val k2_vy = ay2 * dt
        val k2_x = (vx + 0.5f * k1_vx) * dt
        val k2_y = (vy + 0.5f * k1_vy) * dt

        // k3
        val (ax3, ay3) = computeAccel(x + 0.5f * k2_x, y + 0.5f * k2_y, vx + 0.5f * k2_vx, vy + 0.5f * k2_vy)
        val k3_vx = ax3 * dt
        val k3_vy = ay3 * dt
        val k3_x = (vx + 0.5f * k2_vx) * dt
        val k3_y = (vy + 0.5f * k2_vy) * dt

        // k4
        val (ax4, ay4) = computeAccel(x + k3_x, y + k3_y, vx + k3_vx, vy + k3_vy)
        val k4_vx = ax4 * dt
        val k4_vy = ay4 * dt
        val k4_x = (vx + k3_vx) * dt
        val k4_y = (vy + k3_vy) * dt

        x += (k1_x + 2f * k2_x + 2f * k3_x + k4_x) / 6f
        y += (k1_y + 2f * k2_y + 2f * k3_y + k4_y) / 6f
        vx += (k1_vx + 2f * k2_vx + 2f * k3_vx + k4_vx) / 6f
        vy += (k1_vy + 2f * k2_vy + 2f * k3_vy + k4_vy) / 6f

        val remDist = sqrt((destination.x - x).pow(2) + (destination.y - y).pow(2))
        if (remDist < 16f) {
            physicsBuffer[12] = 1f
            physicsBuffer[0] = destination.x
            physicsBuffer[1] = destination.y
            physicsBuffer[3] = 0f
            physicsBuffer[4] = 0f
            physicsBuffer[11] = 0f
            return
        }

        val speed = sqrt(vx * vx + vy * vy)
        val stretchPar = 1f + 0.35f * tanh(speed / 1200f)
        val stretchPrp = 1f / stretchPar

        physicsBuffer[0] = x
        physicsBuffer[1] = y
        physicsBuffer[3] = vx
        physicsBuffer[4] = vy
        physicsBuffer[6] = stretchPar
        physicsBuffer[7] = stretchPrp
        physicsBuffer[8] = atan2(vy, vx)
        physicsBuffer[9] = (-vy * 0.035f).coerceIn(-15f, 15f)
        physicsBuffer[10] = (vx * 0.035f).coerceIn(-12f, 12f)
    }

    fun reset() {
        stage = TokenStage.IDLE
    }
}
