package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import com.streamify.app.data.models.LyricsLine
import com.streamify.app.ui.theme.StreamifyColors
import kotlinx.coroutines.launch

@Composable
fun LyricsScreen(
    lyrics: List<LyricsLine>,
    currentPositionMs: Long,
    dominantColor: androidx.compose.ui.graphics.Color = StreamifyColors.BgBase,
    onSeek: (Long) -> Unit
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    // Find active line
    var activeIndex by remember { mutableStateOf(-1) }
    
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scrollOffsetPx = remember(density) { with(density) { (-250).dp.roundToPx() } } // Center the text roughly in middle
    
    LaunchedEffect(currentPositionMs, lyrics) {
        val index = lyrics.indexOfLast { it.timeMs <= currentPositionMs }
        if (index != activeIndex && index >= 0) {
            activeIndex = index
            coroutineScope.launch {
                listState.animateScrollToItem(index, scrollOffset = scrollOffsetPx)
            }
        }
    }

    // Dynamic gradient background
    val animatedColor by androidx.compose.animation.animateColorAsState(
        targetValue = dominantColor,
        animationSpec = androidx.compose.animation.core.tween(1500)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        animatedColor.copy(alpha = 0.8f),
                        StreamifyColors.BgBase
                    )
                )
            )
            .padding(top = 48.dp, start = 24.dp, end = 24.dp)
    ) {
        if (lyrics.isEmpty()) {
            Text(
                text = "No lyrics available",
                color = StreamifyColors.TextSub,
                modifier = Modifier.padding(top = 32.dp)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 200.dp, bottom = 300.dp), // Lots of padding for centering
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                itemsIndexed(lyrics) { index, line ->
                    val isActive = index == activeIndex
                    val isPast = index < activeIndex
                    
                    val targetScale = if (isActive) 1.2f else 1.0f

                    val targetAlpha = when {
                        isActive -> 1f
                        isPast -> 0.5f
                        else -> 0.3f
                    }
                    val animatedAlpha by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = targetAlpha,
                        animationSpec = androidx.compose.animation.core.tween(400)
                    )
                    
                    val nextLineTimeMs = if (index < lyrics.size - 1) lyrics[index + 1].timeMs else line.timeMs + 5000L
                    
                    val lineProgress = if (isActive && currentPositionMs >= line.timeMs) {
                        val duration = (nextLineTimeMs - line.timeMs).toFloat()
                        if (duration > 0) ((currentPositionMs - line.timeMs) / duration).coerceIn(0f, 1f) else 1f
                    } else if (isPast) 1f else 0f

                    val animatedScale by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = targetScale,
                        animationSpec = androidx.compose.animation.core.spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                        )
                    )
                    
                    val brush = if (isActive || isPast) {
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            0.0f to StreamifyColors.TextMain,
                            (lineProgress - 0.05f).coerceAtLeast(0f) to StreamifyColors.TextMain,
                            (lineProgress + 0.05f).coerceAtMost(1f) to StreamifyColors.TextMain.copy(alpha = 0.3f),
                            1.0f to StreamifyColors.TextMain.copy(alpha = 0.3f)
                        )
                    } else {
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            0.0f to StreamifyColors.TextMain.copy(alpha = animatedAlpha),
                            1.0f to StreamifyColors.TextMain.copy(alpha = animatedAlpha)
                        )
                    }

                    Text(
                        text = line.text,
                        fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                        fontSize = 28.sp,
                        lineHeight = 36.sp,
                        style = androidx.compose.ui.text.TextStyle(brush = brush),
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                this.scaleX = animatedScale
                                this.scaleY = animatedScale
                                this.transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                            }
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null // Remove ripple for seamless feel
                            ) { onSeek(line.timeMs) }
                    )
                }
            }
        }
    }
}
