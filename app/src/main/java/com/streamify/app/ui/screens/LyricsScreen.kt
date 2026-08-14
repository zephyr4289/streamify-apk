package com.streamify.app.ui.screens

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.data.models.LyricsLine
import com.streamify.app.ui.theme.StreamifyColors
import kotlinx.coroutines.launch

@Composable
fun LyricsScreen(
    lyrics: List<LyricsLine>,
    currentPositionMs: Long,
    dominantColor: Color = StreamifyColors.BgBase,
    onSeek: (Long) -> Unit
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var activeIndex by remember { mutableStateOf(-1) }
    val density = LocalDensity.current
    val scrollOffsetPx = remember(density) { with(density) { (-220).dp.roundToPx() } }

    LaunchedEffect(currentPositionMs, lyrics) {
        val index = lyrics.indexOfLast { it.timeMs <= currentPositionMs }
        if (index != activeIndex && index >= 0) {
            activeIndex = index
            coroutineScope.launch {
                listState.animateScrollToItem(index, scrollOffset = scrollOffsetPx)
            }
        }
    }

    val animatedColor by animateColorAsState(
        targetValue = dominantColor,
        animationSpec = tween(1500),
        label = "bgColor"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        animatedColor.copy(alpha = 0.85f),
                        StreamifyColors.BgBase
                    )
                )
            )
            .padding(top = 48.dp, start = 20.dp, end = 20.dp)
    ) {
        if (lyrics.isEmpty()) {
            Text(
                text = "Looking for synchronized lyrics...",
                color = StreamifyColors.TextSub,
                modifier = Modifier.padding(top = 32.dp)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 180.dp, bottom = 320.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                itemsIndexed(lyrics) { index, line ->
                    val isActive = index == activeIndex
                    val isPast = index < activeIndex

                    val targetScale = if (isActive) 1.15f else 0.95f
                    val targetAlpha = when {
                        isActive -> 1.0f
                        isPast -> 0.45f
                        else -> 0.25f
                    }

                    val animatedScale by animateFloatAsState(
                        targetValue = targetScale,
                        animationSpec = spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                        ),
                        label = "scale"
                    )

                    val animatedAlpha by animateFloatAsState(
                        targetValue = targetAlpha,
                        animationSpec = tween(300),
                        label = "alpha"
                    )

                    val nextLineTimeMs = if (index < lyrics.size - 1) lyrics[index + 1].timeMs else line.timeMs + 5000L
                    val lineProgress = if (isActive && currentPositionMs >= line.timeMs) {
                        val duration = (nextLineTimeMs - line.timeMs).toFloat()
                        if (duration > 0) ((currentPositionMs - line.timeMs) / duration).coerceIn(0f, 1f) else 1f
                    } else if (isPast) 1f else 0f

                    val brush = if (isActive || isPast) {
                        Brush.horizontalGradient(
                            0.0f to StreamifyColors.TextMain,
                            (lineProgress - 0.04f).coerceAtLeast(0f) to StreamifyColors.TextMain,
                            (lineProgress + 0.04f).coerceAtMost(1f) to StreamifyColors.TextMain.copy(alpha = 0.35f),
                            1.0f to StreamifyColors.TextMain.copy(alpha = 0.35f)
                        )
                    } else {
                        Brush.horizontalGradient(
                            0.0f to StreamifyColors.TextMain.copy(alpha = animatedAlpha),
                            1.0f to StreamifyColors.TextMain.copy(alpha = animatedAlpha)
                        )
                    }

                    Text(
                        text = line.text,
                        fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                        fontSize = 26.sp,
                        lineHeight = 34.sp,
                        style = TextStyle(brush = brush),
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = animatedScale
                                scaleY = animatedScale
                                transformOrigin = TransformOrigin(0f, 0.5f)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    renderEffect = if (!isActive) {
                                        android.graphics.RenderEffect.createBlurEffect(
                                            5f, 5f, android.graphics.Shader.TileMode.CLAMP
                                        ).asComposeRenderEffect()
                                    } else null
                                }
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onSeek(line.timeMs) }
                    )
                }
            }
        }
    }
}
