package com.streamify.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyShapes

@Composable
fun ShimmerPlaceholder(
    modifier: Modifier = Modifier,
    shape: Shape = StreamifyShapes.CardShape
) {
    val shimmerColors = listOf(
        StreamifyColors.BgCard,
        StreamifyColors.BgCardHover,
        StreamifyColors.BgCard
    )
    
    val transition = rememberInfiniteTransition(label = "shimmer_transition")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )
    
    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 200f, 0f),
        end = Offset(translateAnim, 0f)
    )

    Box(
        modifier = modifier
            .background(brush = brush, shape = shape)
            .fillMaxSize()
    )
}
