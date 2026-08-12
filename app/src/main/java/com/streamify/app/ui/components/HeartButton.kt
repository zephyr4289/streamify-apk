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
    val scale = remember { Animatable(1f) }

    val tint by animateColorAsState(
        targetValue = if (isLiked) StreamifyColors.Primary else StreamifyColors.TextSub,
        animationSpec = tween(durationMillis = 200),
        label = "heart_color"
    )

    Icon(
        imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
        contentDescription = "Like",
        tint = tint,
        modifier = modifier
            .scale(scale.value)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onToggle()
                coroutineScope.launch {
                    scale.animateTo(
                        1.3f,
                        animationSpec = tween(150, easing = FastOutSlowInEasing)
                    )
                    scale.animateTo(
                        1f,
                        animationSpec = tween(150, easing = LinearOutSlowInEasing)
                    )
                }
            }
            .padding(8.dp)
    )
}
