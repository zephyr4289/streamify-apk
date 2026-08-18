package com.streamify.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.*

/**
 * High-Performance Glassy Shimmering Brand Badge: "DEVELOPED BY SIREEN"
 *
 * Implements GPU Draw-Phase Shader Translation matching PrismaticSplashScreen.
 * Renders chromatic laser shimmer directly onto character glyphs with zero bounding-box bleed.
 */
@Composable
fun SireenBrandingBadge(
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "sireen_shimmer_transition")
    val shimmerProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_glint_progress"
    )

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val annotatedText = remember {
        buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp
                )
            ) {
                append("DEVELOPED BY ")
            }
            withStyle(
                SpanStyle(
                    color = Primary,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.2.sp
                )
            ) {
                append("SIREEN")
            }
        }
    }

    val textLayout = remember(annotatedText) {
        textMeasurer.measure(
            text = annotatedText,
            style = TextStyle(fontFamily = StreamifyFontFamily)
        )
    }

    val badgeWidthDp = with(density) { textLayout.size.width.toDp() }
    val badgeHeightDp = with(density) { textLayout.size.height.toDp() }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = BgSurfaceElevated.copy(alpha = 0.65f),
        border = BorderStroke(0.8.dp, BorderChip.copy(alpha = 0.55f)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.5.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier.size(width = badgeWidthDp, height = badgeHeightDp)
            ) {
                val width = size.width
                val shimmerX = (shimmerProgress * (width + 200f)) - 100f

                val sweepBrush = Brush.horizontalGradient(
                    colors = listOf(
                        TextSecondary.copy(alpha = 0.50f),
                        Color.White.copy(alpha = 0.90f),
                        Primary,
                        Color(0xFF00E5FF),
                        Color.White,
                        TextSecondary.copy(alpha = 0.50f)
                    ),
                    startX = shimmerX - 70f,
                    endX = shimmerX + 70f
                )

                drawText(
                    textLayoutResult = textLayout,
                    topLeft = Offset.Zero,
                    brush = sweepBrush
                )
            }
        }
    }
}

