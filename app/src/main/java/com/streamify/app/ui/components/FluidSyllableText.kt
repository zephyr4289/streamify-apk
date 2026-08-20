package com.streamify.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Paint
import android.graphics.Typeface

@Composable
fun FluidSyllableText(
    text: String,
    progressFraction: Float, // [0.0f - 1.0f] computed from active SLYR syllable timeline
    isActiveLine: Boolean,
    baseColor: Color = Color(0x66FFFFFF),
    highlightColor: Color = Color(0xFFFFFFFF),
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val fontSizePx = with(density) { 24.sp.toPx() }

    // Reusable text paint allocated once outside the draw loop (0 heap GC)
    val textPaint = remember {
        Paint().apply {
            isAntiAlias = true
            textSize = fontSizePx
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .graphicsLayer {
                // Isolate sweep compositing into a dedicated GPU texture layer
                compositingStrategy = CompositingStrategy.Offscreen
            }
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val textY = canvasHeight / 2f + (fontSizePx / 3f)

        // 1. Draw Base Un-highlighted Text
        textPaint.color = if (isActiveLine) baseColor.hashCode() else 0x33FFFFFF
        drawContext.canvas.nativeCanvas.drawText(text, 0f, textY, textPaint)

        if (isActiveLine && progressFraction > 0.0f) {
            val textWidth = textPaint.measureText(text)
            val currentSweepX = (textWidth * progressFraction).coerceIn(0f, textWidth)

            // 2. Draw Active Swept Mask Layer with Zero-Alloc Clip Boundary
            clipRect(
                left = 0f,
                top = 0f,
                right = currentSweepX,
                bottom = canvasHeight
            ) {
                textPaint.color = highlightColor.hashCode()
                drawContext.canvas.nativeCanvas.drawText(text, 0f, textY, textPaint)
            }
        }
    }
}
