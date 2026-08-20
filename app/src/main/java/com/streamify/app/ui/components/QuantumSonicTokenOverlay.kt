package com.streamify.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import com.streamify.app.data.NativeBridge

@Composable
fun QuantumSonicTokenOverlay(
    isFlying: Boolean,
    startX: Float,
    startY: Float,
    targetX: Float,
    targetY: Float,
    onFlightComplete: () -> Unit
) {
    if (!isFlying) return

    // 13-Float State Array: [x, y, z, vx, vy, vz, lambda_par, lambda_perp, theta, phi, psi, p, is_alive]
    val stateVector = remember {
        floatArrayOf(
            startX, startY, 0f,       // Initial position [x, y, z]
            15f, -25f, 5f,            // Initial velocity [vx, vy, vz]
            1.0f, 1.0f,               // Strain tensor [lambda_par, lambda_perp]
            0f, 0f, 0f,               // Gimbal Euler angles [theta, phi, psi]
            0f,                       // Progress p
            1.0f                      // is_alive flag
        )
    }

    val frameTrigger = remember { mutableStateOf(0L) }

    LaunchedEffect(isFlying) {
        var lastFrameTimeNanos = 0L
        while (stateVector[12] > 0.5f) {
            withFrameNanos { frameTimeNanos ->
                if (lastFrameTimeNanos != 0L) {
                    val dt = ((frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000.0f).coerceIn(0.001f, 0.016f)
                    
                    // Step C++20 RK4 6-DOF Aerodynamic ODE in Native Assembly
                    NativeBridge.stepAirDropPhysics(stateVector, targetX, targetY, dt)
                    frameTrigger.value = frameTimeNanos
                }
                lastFrameTimeNanos = frameTimeNanos
            }
        }
        onFlightComplete()
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val _tick = frameTrigger.value // Observe VSYNC pulse
        val x = stateVector[0]
        val y = stateVector[1]
        val scaleParallel = stateVector[6]
        val scalePerpendicular = stateVector[7]
        val rotationDeg = stateVector[8]

        // Render token with dynamic squash-and-stretch strain tensor conservation
        rotate(degrees = rotationDeg, pivot = Offset(x, y)) {
            scale(
                scaleX = scaleParallel,
                scaleY = scalePerpendicular,
                pivot = Offset(x, y)
            ) {
                drawCircle(
                    color = Color(0xFF1DB954),
                    radius = 28f,
                    center = Offset(x, y)
                )
                drawCircle(
                    color = Color.White,
                    radius = 12f,
                    center = Offset(x, y)
                )
            }
        }
    }
}
