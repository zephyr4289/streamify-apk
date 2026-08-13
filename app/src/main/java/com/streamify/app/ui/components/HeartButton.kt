package com.streamify.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import com.streamify.app.ui.theme.StreamifyColors
import kotlinx.coroutines.launch

@Composable
fun HeartButton(
    isLiked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scale = remember { Animatable(1f) }
    val rotation = remember { Animatable(0f) }
    val fillProgress = remember { Animatable(if (isLiked) 1f else 0f) }
    val particlesState = remember { Animatable(0f) }

    val tint by animateColorAsState(
        targetValue = if (isLiked) StreamifyColors.Primary else StreamifyColors.TextSub,
        animationSpec = tween(durationMillis = 200),
        label = "heart_color"
    )

    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .androidx.compose.ui.draw.scale(scale.value)
            .androidx.compose.ui.graphics.graphicsLayer { rotationZ = rotation.value }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onToggle()
                if (!isLiked) { // was not liked, now liking
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    coroutineScope.launch {
                        launch {
                            scale.animateTo(1.3f, animationSpec = tween(150, easing = FastOutSlowInEasing))
                            scale.animateTo(1f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
                        }
                        launch {
                            fillProgress.snapTo(0f)
                            fillProgress.animateTo(1f, animationSpec = tween(200, easing = LinearOutSlowInEasing))
                        }
                        launch {
                            particlesState.snapTo(0f)
                            particlesState.animateTo(1f, animationSpec = tween(600, easing = FastOutLinearInEasing))
                        }
                    }
                } else { // was liked, now unliking
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    coroutineScope.launch {
                        launch {
                            rotation.animateTo(8f, animationSpec = tween(50))
                            rotation.animateTo(-8f, animationSpec = tween(50))
                            rotation.animateTo(8f, animationSpec = tween(50))
                            rotation.animateTo(0f, animationSpec = tween(50))
                        }
                        launch {
                            fillProgress.animateTo(0f, animationSpec = tween(150))
                        }
                    }
                }
            }
            .padding(8.dp)
            .androidx.compose.ui.draw.drawBehind {
                val p = particlesState.value
                if (p > 0f && p < 1f) {
                    val radius = size.minDimension / 2f
                    val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
                    for (i in 0 until 12) {
                        val angle = (i * 30) * (Math.PI / 180f)
                        val r = radius + (p * radius * 2f)
                        val drop = p * p * 20.dp.toPx() // gravity fall
                        val x = center.x + (r * kotlin.math.cos(angle)).toFloat()
                        val y = center.y + (r * kotlin.math.sin(angle)).toFloat() + drop
                        val alpha = 1f - p
                        drawCircle(
                            color = StreamifyColors.Primary.copy(alpha = alpha),
                            radius = 3.dp.toPx() * (1f - p),
                            center = androidx.compose.ui.geometry.Offset(x, y)
                        )
                    }
                }
            },
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.FavoriteBorder,
            contentDescription = "Like",
            tint = StreamifyColors.TextSub
        )
        if (fillProgress.value > 0f) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                tint = StreamifyColors.Primary,
                modifier = Modifier.androidx.compose.ui.draw.drawWithContent {
                    val height = size.height
                    androidx.compose.ui.graphics.drawscope.clipRect(
                        top = height - (height * fillProgress.value)
                    ) {
                        this@drawWithContent.drawContent()
                    }
                }
            )
        }
    }
}
