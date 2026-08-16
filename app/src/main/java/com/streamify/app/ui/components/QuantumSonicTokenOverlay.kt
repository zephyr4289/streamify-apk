package com.streamify.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

    // 120 FPS Physics Loop
    LaunchedEffect(controller.stage) {
        var lastFrameTime = System.currentTimeMillis()
        var totalTime = 0L

        while (controller.stage != TokenStage.DONE && controller.stage != TokenStage.IDLE) {
            val now = System.currentTimeMillis()
            val delta = (now - lastFrameTime).coerceAtLeast(1L)
            lastFrameTime = now
            totalTime += delta

            controller.updatePhysics(delta, totalTime)
            delay(8L) // ~120fps tick
        }
        delay(120L) // Settle final impact
        controller.reset()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(100f) // Absolute highest Z-layer
    ) {
        // The Floating 3D Ghost Proxy Card
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = (controller.currentPosition.x - 160.dp.toPx()).toInt().coerceAtLeast(0),
                        y = controller.currentPosition.y.toInt().coerceAtLeast(0)
                    )
                }
                .width(340.dp)
                .height(60.dp)
                .graphicsLayer {
                    this.scaleX = controller.scaleX
                    this.scaleY = controller.scaleY
                    this.rotationX = controller.rotationX
                    this.rotationY = controller.rotationY
                    this.cameraDistance = 16f * density.density
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
                ActiveControl.copy(alpha = 0.8f),
                Primary,
                Color.Transparent
            )
        )
    } else {
        Brush.linearGradient(
            listOf(
                ActiveControl.copy(alpha = 0.5f),
                Primary.copy(alpha = 0.5f)
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

            // Animated Quantum Pulsing Equalizer
            YtActiveEqualizer(
                isPlaying = true,
                barColor = Primary,
                modifier = Modifier.size(width = 16.dp, height = 14.dp)
            )
        }
    }
}
