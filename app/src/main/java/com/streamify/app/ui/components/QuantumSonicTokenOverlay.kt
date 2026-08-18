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

        // ─── 1. APPLE AIRDROP LUMINESCENCE SHOCKWAVE ON DOCK IMPACT ───
        if (controller.stage == TokenStage.IMPACT) {
            ImpactBloomCanvas(
                center = Offset(controller.destination.x, controller.destination.y),
                progress = controller.impactProgress
            )
        }

        // ─── 2. FLUID METAMORPHIC AIRDROP CAPSULE ───
        Box(
            modifier = Modifier
                .offset {
                    val xClamped = (controller.currentPosition.x - (cardWidthPx / 2f))
                        .coerceIn(8f, (screenWidthPx - cardWidthPx - 8f).coerceAtLeast(8f))
                    val yClamped = (controller.currentPosition.y - (cardHeightPx / 2f)).coerceAtLeast(0f)
                    IntOffset(xClamped.toInt(), yClamped.toInt())
                }
                .width(cardWidthDp)
                .height(60.dp)
                .graphicsLayer {
                    // Aerodynamic velocity-aligned stretch & volume conservation
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
                .shadow(
                    elevation = if (controller.stage == TokenStage.FLYING) 24.dp else 8.dp,
                    shape = RoundedCornerShape(16.dp),
                    spotColor = Primary.copy(alpha = 0.5f),
                    ambientColor = ActiveControl.copy(alpha = 0.35f)
                )
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
    // Dynamic luminescence aura sweep
    val auraBrush = Brush.sweepGradient(
        listOf(
            Color.Transparent,
            Primary.copy(alpha = if (isFlying) 0.9f else 0.4f),
            ActiveControl,
            Color.White.copy(alpha = 0.8f),
            Color.Transparent
        )
    )

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
    center: Offset,
    progress: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val safeCenter = if (center != Offset.Zero) center else Offset(size.width / 2f, size.height - 100f)

        // Ring 1: Primary Phosphor Luminescence Shockwave
        val glowRadius = 140.dp.toPx() * progress
        val glowAlpha = ((1f - progress) * 0.9f).coerceIn(0f, 1f)

        drawCircle(
            color = Primary.copy(alpha = glowAlpha),
            radius = glowRadius,
            center = safeCenter,
            style = Stroke(width = 5.dp.toPx())
        )

        // Ring 2: Concentrated white-hot kinetic core
        val coreRadius = 60.dp.toPx() * (progress * 1.2f).coerceAtMost(1f)
        val coreAlpha = ((1f - (progress * 1.6f)).coerceIn(0f, 1f) * 0.95f)

        drawCircle(
            color = Color.White.copy(alpha = coreAlpha),
            radius = coreRadius,
            center = safeCenter
        )
    }
}
