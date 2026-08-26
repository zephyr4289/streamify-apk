package com.streamify.app.ui.components

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.streamify.app.ui.theme.ActiveControl
import com.streamify.app.ui.theme.BgSurfaceElevated
import com.streamify.app.ui.theme.Primary
import com.streamify.app.ui.theme.TextSecondary
import kotlinx.coroutines.isActive

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * QUANTUM SONIC TOKEN — ZERO-COMPOSITION FLIGHT RENDERER (Perf Plan v2)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Fixes the start/end 10fps collapse on mid devices. The legacy implementation
 * composed a Surface+AsyncImage+Text subtree ON the launch frame and inserted
 * ImpactBloomCanvas AT the impact frame — composition/layout work colliding
 * with the animation envelope.
 *
 * Now: ONE permanently-composed fullscreen Canvas. Idle = single early-return
 * read (free). Flight + impact bloom = pure DrawPhase invalidation driven by
 * frameTick. NOTHING enters or leaves composition inside the envelope.
 */
@Composable
fun QuantumSonicTokenOverlay(
    controller: QuantumSonicTokenController,
    modifier: Modifier = Modifier
) {
    // Single reusable native paint for the entire particle burst.
    val particlePaint = remember { Paint().apply { isAntiAlias = true } }

    // Permanent physics driver — steps only while a flight is live.
    LaunchedEffect(Unit) {
        var lastFrameNanos = 0L
        while (isActive) {
            androidx.compose.runtime.withFrameNanos { frameTimeNanos ->
                if (lastFrameNanos != 0L &&
                    (controller.stage == TokenStage.FLYING || controller.stage == TokenStage.IMPACT)
                ) {
                    val dt = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.033f)
                    controller.stepSimulation(dt)
                }
                lastFrameNanos = frameTimeNanos
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .zIndex(100f)
            .graphicsLayer {
                val _tick = controller.frameTick
                alpha = when (controller.stage) {
                    TokenStage.IMPACT -> (1f - (controller.impactProgress * 1.4f)).coerceIn(0f, 1f)
                    else -> 1f
                }
            }
    ) {
        val tick = controller.frameTick
        if (!controller.isRenderable || tick == 0L) return@Canvas

        val cardW = controller.cardWidthPx
        val cardH = controller.cardHeightPx
        val x = (controller.posX - cardW / 2f).coerceIn(
            8f,
            (controller.screenWidthPx - cardW - 8f).coerceAtLeast(8f)
        )
        val y = (controller.posY - cardH / 2f).coerceAtLeast(8f)

        // ── IMPACT BLOOM (under the capsule, same draw pass) ────────────────
        if (controller.stage == TokenStage.IMPACT) {
            val p = controller.impactProgress
            val center = if (controller.destination != Offset.Zero) controller.destination
            else Offset(size.width / 2f, size.height - 100f)

            drawCircle(
                color = Primary.copy(alpha = ((1f - p) * 0.85f).coerceIn(0f, 1f)),
                radius = 140.dp.toPx() * p,
                center = center,
                style = Stroke(width = 4.dp.toPx())
            )
            drawCircle(
                color = Color.White.copy(alpha = ((1f - (p * 1.5f)).coerceIn(0f, 1f)) * 0.9f),
                radius = 50.dp.toPx() * (p * 1.2f).coerceAtMost(1f),
                center = center
            )

            val buf = controller.particleBuffer
            val primaryArgb = Primary.toArgb()
            val activeArgb = ActiveControl.toArgb()
            val nc = drawContext.canvas.nativeCanvas
            var i = 0
            while (i < controller.particleCount) {
                val base = i * 6
                val alpha = buf[base + 5]
                if (alpha > 0.01f) {
                    particlePaint.color = if (i % 2 == 0) primaryArgb else activeArgb
                    particlePaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
                    nc.drawCircle(buf[base], buf[base + 1], buf[base + 4] * (1f - p * 0.4f), particlePaint)
                }
                i++
            }
        }

        // ── FLYING CAPSULE ──────────────────────────────────────────────────
        withTransform({
            translate(left = x, top = y)
            scale(
                scaleX = controller.stretchParallel,
                scaleY = controller.stretchPerp,
                pivot = Offset(cardW / 2f, cardH / 2f)
            )
        }) {
            drawRoundRect(
                color = BgSurfaceElevated,
                cornerRadius = CornerRadius(16.dp.toPx()),
                size = Size(cardW, cardH)
            )
            drawRoundRect(
                brush = Brush.sweepGradient(
                    listOf(
                        Color.Transparent,
                        Primary.copy(alpha = 0.9f),
                        ActiveControl,
                        Color.White.copy(alpha = 0.8f),
                        Color.Transparent
                    )
                ),
                cornerRadius = CornerRadius(16.dp.toPx()),
                size = Size(cardW, cardH),
                style = Stroke(width = 2.dp.toPx())
            )

            val artSize = 46.dp.toPx()
            val artX = 12.dp.toPx()
            val artY = (cardH - artSize) / 2f
            clipRect(artX, artY, artX + artSize, artY + artSize) {
                val bmp: Bitmap? = controller.artBitmap
                if (bmp != null) {
                    drawImage(
                        image = bmp.asImageBitmap(),
                        dstOffset = IntOffset(artX.toInt(), artY.toInt()),
                        dstSize = IntSize(artSize.toInt(), artSize.toInt())
                    )
                } else {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.18f),
                        radius = artSize / 2f - 4.dp.toPx(),
                        center = Offset(artX + artSize / 2f, artY + artSize / 2f),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }

            val tp = controller.titlePaint ?: return@withTransform
            val sp = controller.statusPaint ?: return@withTransform
            val nc = drawContext.canvas.nativeCanvas
            val textX = artX + artSize + 12.dp.toPx()

            nc.drawText(controller.trackTitle, textX, cardH / 2f - 6.dp.toPx(), tp)

            val statusText = if (controller.stage == TokenStage.FLYING) "Connecting…" else "Ready"
            sp.color = Primary.toArgb()
            nc.drawText(statusText, textX, cardH / 2f + 14.dp.toPx(), sp)

            if (controller.trackArtist.isNotBlank()) {
                sp.color = TextSecondary.toArgb()
                nc.drawText(
                    " • ${controller.trackArtist}",
                    textX + sp.measureText(statusText),
                    cardH / 2f + 14.dp.toPx(),
                    sp
                )
            }
        }
    }
}
