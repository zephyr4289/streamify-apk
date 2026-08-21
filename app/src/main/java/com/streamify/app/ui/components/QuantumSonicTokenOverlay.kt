package com.streamify.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.streamify.app.data.NativeBridge
import com.streamify.app.ui.theme.*
import kotlinx.coroutines.isActive

@Composable
fun QuantumSonicTokenOverlay(
    controller: QuantumSonicTokenController,
    modifier: Modifier = Modifier
) {
    if (controller.stage == TokenStage.IDLE || controller.stage == TokenStage.DONE) return

    val density = LocalDensity.current

    // Hardware VSYNC-Locked Choreographer Loop (120Hz / 90Hz Display Refresh)
    LaunchedEffect(controller.stage) {
        var lastFrameNanos = 0L

        while (isActive && controller.stage != TokenStage.DONE && controller.stage != TokenStage.IDLE) {
            withFrameNanos { frameTimeNanos ->
                if (lastFrameNanos != 0L) {
                    val dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
                    controller.stepSimulation(dt)
                }
                lastFrameNanos = frameTimeNanos
            }
        }
        controller.reset()
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .zIndex(100f) // Topmost overlay
    ) {
        val screenWidthPx = constraints.maxWidth.toFloat()
        val cardWidthDp = (maxWidth * 0.88f).coerceIn(280.dp, 560.dp)
        val cardWidthPx = with(density) { cardWidthDp.toPx() }
        val cardHeightPx = with(density) { 60.dp.toPx() }

        // ─── 1. APPLE AIRDROP 3D LUMINESCENCE SHOCKWAVE & FLUID PARTICLES ON DOCK IMPACT ───
        if (controller.stage == TokenStage.IMPACT) {
            ImpactBloomCanvas(
                controller = controller
            )
        }

        // ─── 2. FLUID METAMORPHIC AIRDROP CAPSULE (100% GPU RENDER-NODE PHASE) ───
        Box(
            modifier = Modifier
                .width(cardWidthDp)
                .height(60.dp)
                .graphicsLayer {
                    // Subscribe strictly to Draw Phase invalidations via frameTick
                    val _tick = controller.frameTick

                    val xClamped = (controller.posX - (cardWidthPx / 2f))
                        .coerceIn(8f, (screenWidthPx - cardWidthPx - 8f).coerceAtLeast(8f))
                    val yClamped = (controller.posY - (cardHeightPx / 2f)).coerceAtLeast(0f)

                    this.translationX = xClamped
                    this.translationY = yClamped
                    this.scaleX = controller.stretchParallel
                    this.scaleY = controller.stretchPerp
                    this.rotationX = controller.pitchDeg
                    this.rotationY = controller.rollDeg
                    this.transformOrigin = TransformOrigin.Center
                    this.cameraDistance = 18f * density.density

                    // Seamless dissolution upon impact
                    this.alpha = if (controller.stage == TokenStage.IMPACT) {
                        (1f - (controller.impactProgress * 1.4f)).coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                }
        ) {
            AirDropFluidCard(
                title = controller.trackTitle,
                artist = controller.trackArtist,
                artUrl = controller.trackArt,
                isFlying = controller.stage == TokenStage.FLYING
            )
        }
    }
}

@Composable
private fun AirDropFluidCard(
    title: String,
    artist: String,
    artUrl: String?,
    isFlying: Boolean
) {
    val auraBrush = remember(isFlying) {
        Brush.sweepGradient(
            listOf(
                Color.Transparent,
                Primary.copy(alpha = if (isFlying) 0.9f else 0.4f),
                ActiveControl,
                Color.White.copy(alpha = 0.8f),
                Color.Transparent
            )
        )
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = BgSurfaceElevated,
        border = BorderStroke(1.5.dp, auraBrush),
        modifier = Modifier.fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!artUrl.isNullOrBlank()) {
                coil.compose.AsyncImage(
                    model = artUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BgCard),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    style = LocalAppTypography.current.songTitle.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = TextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isFlying) "Connecting..." else "Ready",
                        style = LocalAppTypography.current.caption.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.3.sp
                        ),
                        color = Primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (artist.isNotBlank()) {
                        Text(
                            text = " • $artist",
                            style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ImpactBloomCanvas(
    controller: QuantumSonicTokenController,
    modifier: Modifier = Modifier
) {
    val nativePaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val _tick = controller.frameTick
        val p = controller.impactProgress
        if (p <= 0f || p >= 1f) return@Canvas

        val safeCenter = if (controller.destination != Offset.Zero) controller.destination else Offset(size.width / 2f, size.height - 100f)

        // 1. Primary Phosphor Luminescence Shockwave
        val glowRadius = 140.dp.toPx() * p
        val glowAlpha = ((1f - p) * 0.85f).coerceIn(0f, 1f)
        drawCircle(
            color = Primary.copy(alpha = glowAlpha),
            radius = glowRadius,
            center = safeCenter,
            style = Stroke(width = 4.dp.toPx())
        )

        // 2. Concentrated White-Hot Kinetic Core
        val coreRadius = 50.dp.toPx() * (p * 1.2f).coerceAtMost(1f)
        val coreAlpha = ((1f - (p * 1.5f)).coerceIn(0f, 1f) * 0.9f)
        drawCircle(
            color = Color.White.copy(alpha = coreAlpha),
            radius = coreRadius,
            center = safeCenter
        )

        // 3. Batched 3D Fluid Splash Particles using native Canvas (Zero Compose Paint allocations)
        val buf = controller.particleBuffer
        val primaryArgb = Primary.toArgb()
        val activeArgb = ActiveControl.toArgb()
        val pCount = controller.particleCount

        drawContext.canvas.nativeCanvas.let { canvas ->
            for (i in 0 until pCount) {
                val base = i * 6
                val alpha = buf[base + 5]
                if (alpha > 0.01f) {
                    val px = buf[base + 0]
                    val py = buf[base + 1]
                    val r = buf[base + 4] * (1f - (p * 0.4f))
                    val baseCol = if (i % 2 == 0) primaryArgb else activeArgb
                    nativePaint.color = baseCol
                    nativePaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
                    canvas.drawCircle(px, py, r, nativePaint)
                }
            }
        }
    }
}

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
