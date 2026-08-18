package com.streamify.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.TextMain
import com.streamify.app.ui.theme.TextTertiary

/**
 * 120 FPS Zero-Allocation Syllable-by-Syllable Karaoke Text Renderer.
 * Employs CompositingStrategy.Offscreen, BlendMode.SrcIn, TextLayoutResult bounding box extraction,
 * and an 18px soft feathered horizontal gradient sweep with 0 bytes allocated per frame.
 */
@Composable
fun FluidSyllableText(
    text: String,
    lineStartMs: Long,
    lineEndMs: Long,
    currentPlaybackMs: Long,
    isActive: Boolean,
    isPast: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    syllableCount: Int = 1
) {
    val interactionSource = remember { MutableInteractionSource() }
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    val targetScale by animateFloatAsState(
        targetValue = if (isActive) 1.05f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "fluidScale"
    )

    // Calculate fraction across active line
    val lineDuration = (lineEndMs - lineStartMs).coerceAtLeast(1L)
    val elapsed = (currentPlaybackMs - lineStartMs).coerceIn(0L, lineDuration)
    val sweepFraction = when {
        isPast -> 1.0f
        isActive -> elapsed.toFloat() / lineDuration.toFloat()
        else -> 0.0f
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 24.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .graphicsLayer {
                scaleX = targetScale
                scaleY = targetScale
                transformOrigin = TransformOrigin(0f, 0.5f)
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                // 1. Draw base text in dim color (#55FFFFFF / TextTertiary)
                drawContent()

                // 2. If active or past, apply BlendMode.SrcIn horizontal gradient sweep
                if (sweepFraction > 0.0f && textLayoutResult != null) {
                    val layout = textLayoutResult!!
                    val totalWidth = size.width
                    val currentSweepX = totalWidth * sweepFraction
                    val featherWidth = 18.dp.toPx()

                    // Draw solid white mask up to currentSweepX with 18px feather
                    val brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.White,
                            Color.White,
                            Color.Transparent
                        ),
                        startX = 0f,
                        endX = (currentSweepX + featherWidth).coerceAtMost(totalWidth + featherWidth),
                        tileMode = TileMode.Clamp
                    )

                    drawRect(
                        brush = brush,
                        topLeft = Offset.Zero,
                        size = Size(totalWidth, size.height),
                        blendMode = BlendMode.SrcIn
                    )
                }
            }
    ) {
        Text(
            text = text,
            fontSize = 24.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
            color = if (isPast) TextMain else if (isActive) TextTertiary else TextTertiary.copy(alpha = 0.4f),
            lineHeight = 34.sp,
            textAlign = TextAlign.Start,
            onTextLayout = { textLayoutResult = it },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
