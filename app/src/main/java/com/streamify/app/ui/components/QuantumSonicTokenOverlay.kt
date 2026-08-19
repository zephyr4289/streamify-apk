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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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
                    // 120 FPS GPU RenderNode Phase (Zero Tree Recomposition, Zero Layout Phase)
                    val tick = controller.frameTick
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
                statusText = controller.telemetryStatus,
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
    statusText: String,
    artUrl: String?,
    isFlying: Boolean
) {
    // Pre-calculated luminescence aura sweep
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
            YtThumbnail(
                url = artUrl,
                size = 46.dp,
                cornerRadius = 8.dp
            )

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
                        text = statusText,
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
    Canvas(modifier = modifier.fillMaxSize()) {
        val tick = controller.frameTick
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

        // 3. Batched 3D Fluid Splash Particles
        val buf = controller.particleBuffer
        for (i in 0 until controller.particleCount) {
            val base = i * 6
            val alpha = buf[base + 5]
            if (alpha > 0.01f) {
                val px = buf[base + 0]
                val py = buf[base + 1]
                val r = buf[base + 4] * (1f - (p * 0.4f))
                val col = if (i % 2 == 0) Primary.copy(alpha = alpha) else ActiveControl.copy(alpha = alpha)
                drawCircle(
                    color = col,
                    radius = r,
                    center = Offset(px, py)
                )
            }
        }
    }
}
