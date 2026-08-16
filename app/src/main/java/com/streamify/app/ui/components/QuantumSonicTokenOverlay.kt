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
import kotlinx.coroutines.delay

@Composable
fun QuantumSonicTokenOverlay(
    controller: QuantumSonicTokenController,
    modifier: Modifier = Modifier
) {
    if (controller.stage == TokenStage.IDLE || controller.stage == TokenStage.DONE) return

    val density = LocalDensity.current

    // 120 FPS Frame-Clock Physics Loop
    LaunchedEffect(controller.stage) {
        var lastFrameTime = System.currentTimeMillis()
        var totalTime = 0L

        while (controller.stage != TokenStage.DONE && controller.stage != TokenStage.IDLE) {
            val now = System.currentTimeMillis()
            val delta = (now - lastFrameTime).coerceAtLeast(1L)
            lastFrameTime = now
            totalTime += delta

            controller.updatePhysics(delta, totalTime)
            delay(8L) // ~120 FPS tick rate
        }
        delay(80L)
        controller.reset()
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .zIndex(100f) // Topmost overlay layer
    ) {
        val screenWidthPx = constraints.maxWidth.toFloat()
        val cardWidthDp = (maxWidth * 0.88f).coerceIn(280.dp, 560.dp)
        val cardWidthPx = with(density) { cardWidthDp.toPx() }
        val cardHeightPx = with(density) { 60.dp.toPx() }

        // Symmetrically center on the current X coordinate, bounded by screen edges
        val xClamped = (controller.currentPosition.x - (cardWidthPx / 2f))
            .coerceIn(8f, (screenWidthPx - cardWidthPx - 8f).coerceAtLeast(8f))
        val yClamped = (controller.currentPosition.y - (cardHeightPx / 2f)).coerceAtLeast(0f)

        // ─── APPLE AIRDROP LUMINESCENCE SHOCKWAVE (Impact Bloom) ───
        if (controller.stage == TokenStage.IMPACT || controller.stage == TokenStage.DISSOLVE) {
            ImpactBloomCanvas(
                center = Offset(controller.destination.x, controller.destination.y),
                progress = controller.impactProgress
            )
        }

        // ─── FLOATING 3D QUANTUM GHOST CARD ───
        Box(
            modifier = Modifier
                .offset { IntOffset(xClamped.toInt(), yClamped.toInt()) }
                .width(cardWidthDp)
                .height(60.dp)
                .graphicsLayer {
                    this.scaleX = controller.scaleX
                    this.scaleY = controller.scaleY
                    this.rotationX = controller.rotationX
                    this.rotationY = controller.rotationY
                    this.transformOrigin = TransformOrigin.Center
                    this.cameraDistance = 16f * density.density
                    // Smoothly fade out at the end of impact
                    this.alpha = if (controller.stage == TokenStage.DISSOLVE) 0.3f else 1f
                }
                .shadow(
                    elevation = if (controller.stage == TokenStage.LEVITATING) 24.dp else 12.dp,
                    shape = RoundedCornerShape(12.dp)
                )
        ) {
            QuantumGhostCard(
                title = controller.trackTitle,
                artist = controller.trackArtist,
                artUrl = controller.trackArt,
                isLevitating = controller.stage == TokenStage.LEVITATING
            )
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

        // Ring 1: High-velocity glow ring (Expands 0 -> 130dp, Alpha 0.85 -> 0.0)
        val glowRadius = 130.dp.toPx() * progress
        val glowAlpha = ((1f - progress) * 0.85f).coerceIn(0f, 1f)

        drawCircle(
            color = ActiveControl.copy(alpha = glowAlpha),
            radius = glowRadius,
            center = safeCenter,
            style = Stroke(width = 6.dp.toPx())
        )

        // Ring 2: Concentrated white-hot core flash (Expands 0 -> 55dp, Alpha 0.95 -> 0.0)
        val coreRadius = 55.dp.toPx() * (progress * 1.3f).coerceAtMost(1f)
        val coreAlpha = ((1f - (progress * 1.5f)).coerceIn(0f, 1f) * 0.95f)

        drawCircle(
            color = Color.White.copy(alpha = coreAlpha),
            radius = coreRadius,
            center = safeCenter
        )
    }
}

@Composable
private fun QuantumGhostCard(
    title: String,
    artist: String,
    artUrl: String?,
    isLevitating: Boolean
) {
    val auraBrush = if (isLevitating) {
        Brush.sweepGradient(
            listOf(
                Color.Transparent,
                ActiveControl.copy(alpha = 0.85f),
                Primary,
                Color.Transparent
            )
        )
    } else {
        Brush.linearGradient(
            listOf(
                ActiveControl.copy(alpha = 0.6f),
                Primary.copy(alpha = 0.6f)
            )
        )
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
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
                size = 44.dp,
                cornerRadius = 6.dp
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = LocalAppTypography.current.songTitle.copy(fontSize = 14.sp),
                    color = TextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (artist.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = artist,
                        style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
