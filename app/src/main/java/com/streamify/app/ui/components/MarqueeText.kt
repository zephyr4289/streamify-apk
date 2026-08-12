package com.streamify.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay

@Composable
fun MarqueeText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = style.color
) {
    val scrollState = remember { ScrollState(0) }
    var shouldAnimate by remember { mutableStateOf(true) }

    LaunchedEffect(key1 = text) {
        scrollState.scrollTo(0)
        while (shouldAnimate) {
            delay(2000) // Pause at start
            if (scrollState.maxValue > 0) {
                scrollState.animateScrollTo(
                    value = scrollState.maxValue,
                    animationSpec = tween(
                        durationMillis = scrollState.maxValue * 30, // Speed
                        easing = LinearEasing
                    )
                )
                delay(2000) // Pause at end
                scrollState.scrollTo(0)
            } else {
                // If it doesn't need to scroll, wait and check again later
                delay(1000)
            }
        }
    }

    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Visible,
        modifier = modifier.horizontalScroll(scrollState, enabled = false)
    )
}
