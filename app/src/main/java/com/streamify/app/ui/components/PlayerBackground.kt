package com.streamify.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.streamify.app.ui.models.AmbientPalette
import com.streamify.app.ui.theme.StreamifyColors
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun PlayerBackground(
    palette: AmbientPalette,
    modifier: Modifier = Modifier
) {
    val animatedDominant by animateColorAsState(
        targetValue = palette.dominantColor,
        animationSpec = tween(durationMillis = 800),
        label = "dominantColorAnim"
    )
    val animatedDarkMuted by animateColorAsState(
        targetValue = palette.darkMutedColor,
        animationSpec = tween(durationMillis = 800),
        label = "darkMutedColorAnim"
    )

    androidx.compose.foundation.Canvas(modifier = modifier.fillMaxSize()) {
        // AM-OLED Fluid Mesh: 70% deep black base with radial atmospheric glow
        drawRect(color = Color(0xFF050505))

        val radialGlow = Brush.radialGradient(
            colors = listOf(
                animatedDominant.copy(alpha = 0.45f),
                animatedDarkMuted.copy(alpha = 0.20f),
                Color.Transparent
            ),
            center = Offset(size.width * 0.5f, size.height * 0.35f),
            radius = size.width * 0.85f
        )

        drawCircle(
            brush = radialGlow,
            radius = size.width * 0.85f,
            center = Offset(size.width * 0.5f, size.height * 0.35f)
        )
    }
}

@Composable
fun PlayerBackground(
    dominantColor: Color,
    modifier: Modifier = Modifier
) {
    // 800ms crossfade for color change
    val animatedColor by animateColorAsState(
        targetValue = if (dominantColor == Color.Transparent) StreamifyColors.PlayerDynamicBase else dominantColor,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "PlayerBgColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "GradientRotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Rotation"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(StreamifyColors.BgPlayer)
    ) {
        // Animated rotating multi-stop gradient
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = rotation
                    scaleX = 1.5f
                    scaleY = 1.5f
                }
                .blur(80.dp) // heavy blur to smooth out the gradient
                .background(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            animatedColor,
                            StreamifyColors.BgPlayer,
                            animatedColor.copy(alpha = 0.5f),
                            StreamifyColors.BgPlayer,
                            animatedColor
                        )
                    )
                )
        )
        
        // Dark Scrim for text readability (Bottom 70% to Top)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            StreamifyColors.BgPlayer.copy(alpha = 0.6f),
                            StreamifyColors.BgPlayer
                        ),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY
                    )
                )
        )
        
        // Optional noise texture can be drawn here via drawWithCache, 
        // but skipping literal noise PNG to keep it simple, just using a subtle overlay pattern.
    }
}
