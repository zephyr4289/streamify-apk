package com.streamify.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.data.models.LyricsLine
import com.streamify.app.ui.theme.*

@Composable
fun YtSyllableLine(
    line: LyricsLine,
    isActive: Boolean,
    currentTimeMs: () -> Long,
    dominantColor: Color = Primary,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val interactionSource = remember { MutableInteractionSource() }

    // 1. Pre-compute text layout (Runs only when line text changes, not on time ticks)
    val textStyle = TextStyle(
        fontSize = 24.sp,
        fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
        fontFamily = StreamifyFontFamily,
        lineHeight = 34.sp
    )

    val layoutResult = remember(line.text, isActive) {
        textMeasurer.measure(
            text = AnnotatedString(line.text),
            style = textStyle,
            overflow = TextOverflow.Visible
        )
    }

    // 2. Micro-scale spring pop on line activation
    val scaleAnim = remember { Animatable(1f) }

    LaunchedEffect(isActive) {
        if (isActive) {
            scaleAnim.snapTo(0.96f)
            scaleAnim.animateTo(
                targetValue = 1.04f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
            scaleAnim.animateTo(
                targetValue = 1.0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        } else {
            scaleAnim.animateTo(
                targetValue = 1.0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
    }

    val lineHeightDp = with(androidx.compose.ui.platform.LocalDensity.current) {
        layoutResult.size.height.toDp()
    }

    // 3. Draw-Phase GPU Canvas (Reads currentTimeMs lambda at 120 FPS with zero recomposition)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp, horizontal = 24.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onTap
            )
            .graphicsLayer {
                scaleX = scaleAnim.value
                scaleY = scaleAnim.value
                this.alpha = if (isActive) 1f else 0.35f
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(lineHeightDp.coerceAtLeast(36.dp))
        ) {
            val currentTime = currentTimeMs()

            // If line is inactive or in future, draw static dimmed text
            if (!isActive || currentTime < line.timeMs) {
                drawText(layoutResult, color = TextTertiary)
                return@Canvas
            }

            // AMBIENT VOCAL BLOOM (Behind active text)
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        dominantColor.copy(alpha = 0.25f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.35f, size.height / 2f),
                    radius = size.width * 0.65f
                )
            )

            // Calculate Sub-Pixel Sweep Offset (clipX)
            var clipX = 0f
            val syllables = line.syllables

            if (syllables.isNotEmpty()) {
                var previousEndOffset = 0f

                for (syllable in syllables) {
                    val syllableWidth = measureSyllableWidth(layoutResult, syllable.text, line.text, previousEndOffset)
                    previousEndOffset += syllableWidth

                    if (currentTime >= syllable.endMs) {
                        // Syllable has been completely sung
                        clipX += syllableWidth
                    } else if (currentTime in syllable.startMs..syllable.endMs) {
                        // Active Syllable: Calculate vocal sweep percentage
                        val duration = (syllable.endMs - syllable.startMs).coerceAtLeast(1L).toFloat()
                        val progress = ((currentTime - syllable.startMs).toFloat() / duration).coerceIn(0f, 1f)
                        clipX += syllableWidth * progress
                        break
                    } else {
                        // Future syllable
                        break
                    }
                }
            } else {
                // Fallback for standard line LRC: smooth 3.5s linear sweep
                val progress = ((currentTime - line.timeMs).toFloat() / 3500f).coerceIn(0f, 1f)
                clipX = layoutResult.size.width * progress
            }

            // LAYER 1: Dormant Graphite Text
            drawText(layoutResult, color = TextTertiary)

            // LAYER 2: Clipped Stark White Text (The Real-Time Karaoke Illumination)
            clipRect(left = 0f, top = 0f, right = clipX, bottom = size.height) {
                drawText(layoutResult, color = TextMain)
            }
        }
    }
}

// Sub-pixel syllable width calculation using font kerning positions
private fun measureSyllableWidth(
    layout: TextLayoutResult,
    syllableText: String,
    fullLineText: String,
    startSearchOffset: Float
): Float {
    if (syllableText.isEmpty()) return 0f
    val startIndex = fullLineText.indexOf(syllableText)
    if (startIndex == -1) return 0f
    val endIndex = (startIndex + syllableText.length).coerceAtMost(fullLineText.length)

    val startX = layout.getHorizontalPosition(startIndex, true)
    val endX = layout.getHorizontalPosition(endIndex, true)
    return (endX - startX).coerceAtLeast(0f)
}
