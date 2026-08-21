package com.streamify.app.ui.components

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
    var origin = Offset.Zero
        private set
    var destination = Offset.Zero
        private set
    var initialDistance: Float = 1f
        private set

    // Raw High-Performance Primitive Registers (Zero Recomposition Overhead)
    var posX: Float = 0f
    var posY: Float = 0f
    var stretchParallel: Float = 1f
    var stretchPerp: Float = 1f
    var rotationRad: Float = 0f
    var pitchDeg: Float = 0f
    var rollDeg: Float = 0f
    var impactProgress: Float = 0f

    // Frame-tick signal for lambda draw phase (Skipping recomposition)
    var frameTick by mutableLongStateOf(0L)
        private set

    // Track metadata (updated once per flight)
    var trackTitle by mutableStateOf("")
        private set
    var trackArtist by mutableStateOf("")
        private set
    var trackArt by mutableStateOf<String?>(null)
        private set
    var telemetryStatus by mutableStateOf("Connecting to Streamify...")
        private set

    private var flightTime: Float = 0f

    // Direct 14-Float Zero-Allocation JNI Buffer
    // 0: x, 1: y, 2: z, 3: vx, 4: vy, 5: vz, 6: stretch_parallel, 7: stretch_perp, 8: rotation_rad, 9: pitch_deg, 10: roll_deg, 11: impact_progress, 12: is_docked, 13: is_ready_to_dock
    private val physicsBuffer = FloatArray(14)

    // Adaptive Fluid Splashing Particles: Scaled dynamically based on hardware capabilities (Plan 25)
    val particleCount: Int = when {
        Runtime.getRuntime().availableProcessors() >= 8 -> 64
        Runtime.getRuntime().availableProcessors() >= 6 -> 48
        else -> 32
    }
    val particleBuffer = FloatArray(64 * 6)
    private var particlesSpawned = false


    fun triggerFlight(
        tapOrigin: Offset,
        dockDestination: Offset,
        title: String,
        artist: String = "",
        art: String? = null
    ) {
        origin = tapOrigin
        destination = dockDestination
        posX = tapOrigin.x
        posY = tapOrigin.y
        trackTitle = title
        trackArtist = artist
        trackArt = art
        flightTime = 0f
        telemetryStatus = "Connecting to Streamify..."
        particlesSpawned = false

        val dx = dockDestination.x - tapOrigin.x
        val dy = dockDestination.y - tapOrigin.y
        initialDistance = max(1f, sqrt(dx * dx + dy * dy))

        // Initial launch velocity slightly upward and outward
        physicsBuffer[0] = tapOrigin.x
        physicsBuffer[1] = tapOrigin.y
        physicsBuffer[2] = 0f
        physicsBuffer[3] = 0f
        physicsBuffer[4] = -80f // Gentle initial upward pop
        physicsBuffer[5] = 0f
        physicsBuffer[6] = 1f // stretch_parallel
        physicsBuffer[7] = 1f // stretch_perp
        physicsBuffer[8] = 0f // rotation_rad
        physicsBuffer[9] = 0f // pitch_deg
        physicsBuffer[10] = 0f // roll_deg
        physicsBuffer[11] = 0f // impact_progress
        physicsBuffer[12] = 0f // is_docked
        physicsBuffer[13] = 0f // is_ready_to_dock

        stretchParallel = 1f
        stretchPerp = 1f
        rotationRad = 0f
        pitchDeg = 0f
        rollDeg = 0f
        impactProgress = 0f
        frameTick++
        stage = TokenStage.FLYING
    }

    /**
     * Called when the audio stream resolution completes and the track begins playing.
     * Triggers the final impact touchdown and bloom animation.
     */
    fun onTrackReady() {
        physicsBuffer[13] = 1f
        telemetryStatus = "Coupling audio pipeline..."
        if (stage == TokenStage.FLYING) {
            val dx = destination.x - posX
            val dy = destination.y - posY
            val dist = sqrt(dx * dx + dy * dy)
            if (dist < 60f) {
                physicsBuffer[12] = 1f
                physicsBuffer[11] = 0f
            }
        }
    }

    /**
     * Advances simulation by dt seconds (RK4 integration).
     * Dispatches directly to Native C++ engine if loaded,
     * with automatic fallback if native bridge encounters issues.
     */
    fun stepSimulation(dt: Float) {
        if (stage == TokenStage.IDLE || stage == TokenStage.DONE) return

        val safeDt = dt.coerceIn(0.001f, 0.05f)
        flightTime += safeDt

        val newStatus = when {
            stage == TokenStage.IMPACT || stage == TokenStage.DONE -> "Coupled • Ready"
            physicsBuffer[13] > 0.5f -> "Coupling audio pipeline..."
            flightTime > 1.0f -> "Resolving audio stream..."
            flightTime > 0.35f -> "Loading audio pipeline..."
            else -> "Connecting to Streamify..."
        }
        if (telemetryStatus != newStatus) {
            telemetryStatus = newStatus
        }

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

        posX = physicsBuffer[0]
        posY = physicsBuffer[1]
        stretchParallel = physicsBuffer[6]
        stretchPerp = physicsBuffer[7]
        rotationRad = physicsBuffer[8]
        pitchDeg = physicsBuffer[9]
        rollDeg = physicsBuffer[10]
        impactProgress = physicsBuffer[11]

        val isDocked = physicsBuffer[12] > 0.5f
        if (isDocked && stage == TokenStage.FLYING) {
            stage = TokenStage.IMPACT
            telemetryStatus = "Coupled • Ready"
            spawnFluidParticles()
            com.streamify.app.util.StreamifyHapticEngine.tokenImpact()
        }

        if (stage == TokenStage.IMPACT) {
            updateFluidParticles(safeDt)
            if (impactProgress >= 1f) {
                stage = TokenStage.DONE
            }
        }

        frameTick++
    }

    private fun spawnFluidParticles() {
        if (particlesSpawned) return
        particlesSpawned = true
        val rand = java.util.Random(System.currentTimeMillis())
        for (i in 0 until particleCount) {
            val base = i * 6
            val angle = (rand.nextFloat() * 2f * PI.toFloat())
            val speed = 120f + rand.nextFloat() * 380f
            particleBuffer[base + 0] = destination.x + (rand.nextFloat() - 0.5f) * 40f // x
            particleBuffer[base + 1] = destination.y + (rand.nextFloat() - 0.5f) * 15f // y
            particleBuffer[base + 2] = cos(angle) * speed // vx
            particleBuffer[base + 3] = sin(angle) * speed * 0.5f - 80f // vy (slight upward boost)
            particleBuffer[base + 4] = 2.5f + rand.nextFloat() * 4.5f // radius
            particleBuffer[base + 5] = 0.95f // alpha
        }
    }

    private fun updateFluidParticles(dt: Float) {
        val gravity = 320f
        for (i in 0 until particleCount) {
            val base = i * 6
            particleBuffer[base + 0] += particleBuffer[base + 2] * dt
            particleBuffer[base + 1] += particleBuffer[base + 3] * dt + 0.5f * gravity * dt * dt
            particleBuffer[base + 3] += gravity * dt
            particleBuffer[base + 5] = (particleBuffer[base + 5] - dt * 2.8f).coerceAtLeast(0f)
        }
    }

    private fun stepKotlinRK4(dt: Float) {
        if (physicsBuffer[12] > 0.5f) {
            if (physicsBuffer[11] < 1f) {
                physicsBuffer[11] = min(1f, physicsBuffer[11] + (dt / 0.220f))
                val t = physicsBuffer[11]
                val squashY = when {
                    t < 0.25f -> 1f - (0.15f * (t / 0.25f))
                    t < 0.60f -> 0.85f + (0.22f * ((t - 0.25f) / 0.35f))
                    else -> 1.07f - (0.07f * ((t - 0.60f) / 0.40f))
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
        val isReadyToDock = physicsBuffer[13] > 0.5f

        fun computeAccel(px: Float, py: Float, pvx: Float, pvy: Float): Pair<Float, Float> {
            val dx = destination.x - px
            val dy = destination.y - py
            val dist = sqrt(dx * dx + dy * dy)
            if (dist < 1f) return Pair(0f, 0f)

            // Balanced critically-damped spring physics for continuous, organic fluid flight
            val k = 13.5f
            val c = 8.2f

            var fx = k * dx - c * pvx
            var fy = k * dy - c * pvy

            if (initialDistance > 1f) {
                val progress = (1f - (dist / initialDistance)).coerceIn(0f, 1f)
                val liftMag = 45f * sin(progress * PI.toFloat())
                fx += (-dy / dist) * liftMag * 0.35f
                fy += ( dx / dist) * liftMag * 0.35f
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
        if (remDist < 20f) {
            physicsBuffer[0] = destination.x
            physicsBuffer[1] = destination.y
            physicsBuffer[3] = 0f
            physicsBuffer[4] = 0f
            if (isReadyToDock) {
                physicsBuffer[12] = 1f
                physicsBuffer[11] = 0f
                return
            }
        }

        val speed = sqrt(vx * vx + vy * vy)
        val stretchPar = 1f + 0.25f * tanh(speed / 800f)
        val stretchPrp = 1f / stretchPar

        physicsBuffer[0] = x
        physicsBuffer[1] = y
        physicsBuffer[3] = vx
        physicsBuffer[4] = vy
        physicsBuffer[6] = stretchPar
        physicsBuffer[7] = stretchPrp
        physicsBuffer[8] = atan2(vy, vx)
        physicsBuffer[9] = (-vy * 0.025f).coerceIn(-12f, 12f)
        physicsBuffer[10] = (vx * 0.025f).coerceIn(-10f, 10f)
    }

    fun reset() {
        stage = TokenStage.IDLE
    }
}

