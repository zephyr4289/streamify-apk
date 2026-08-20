package com.streamify.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.streamify.app.ui.theme.*
import com.streamify.app.util.DurationFormatter
import kotlinx.coroutines.launch

/**
 * Clean, Responsive YouTube Music Style Player Seekbar with Universal Tap & Drag Scrubbing.
 */
@Composable
fun YtPlayerSeekBar(
    progress: Float,
    durationMs: Long,
    currentPositionMs: Long,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = ActiveControl,
    trackColor: Color = Divider
) {
    val scope = rememberCoroutineScope()
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    val currentProgress = if (isDragging) dragProgress else progress.coerceIn(0f, 1f)
    val currentOnSeek by rememberUpdatedState(onSeek)

    // Hardware-Accelerated Animatable Thumb Physics
    val thumbScale = remember { Animatable(1f) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            scope.launch {
                                thumbScale.animateTo(
                                    targetValue = 1.8f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                            }
                            tryAwaitRelease()
                            scope.launch {
                                thumbScale.animateTo(
                                    targetValue = 1f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                            }
                        },
                        onTap = { offset ->
                            val target = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            com.streamify.app.util.StreamifyHapticEngine.scrubberTick()
                            currentOnSeek(target)
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            dragProgress = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            com.streamify.app.util.StreamifyHapticEngine.scrubberTick()
                            scope.launch {
                                thumbScale.animateTo(
                                    targetValue = 2.0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val newProgress = (dragProgress + (dragAmount.x / size.width.toFloat())).coerceIn(0f, 1f)
                            val prevStep = (dragProgress * 30).toInt()
                            val newStep = (newProgress * 30).toInt()
                            if (prevStep != newStep) {
                                com.streamify.app.util.StreamifyHapticEngine.scrubberTick()
                            }
                            dragProgress = newProgress
                        },
                        onDragEnd = {
                            val finalTarget = dragProgress
                            isDragging = false
                            currentOnSeek(finalTarget)
                            scope.launch {
                                thumbScale.animateTo(
                                    targetValue = 1f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                            scope.launch {
                                thumbScale.animateTo(
                                    targetValue = 1f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                            }
                        }
                    )
                }
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center)
            ) {
                val trackHeight = 3.dp.toPx()
                val yPos = size.height / 2

                // Background Inactive Track
                drawLine(
                    color = trackColor,
                    start = Offset(0f, yPos),
                    end = Offset(size.width, yPos),
                    strokeWidth = trackHeight,
                    cap = StrokeCap.Round
                )

                // Active Progress Track
                val activeEndX = size.width * currentProgress
                if (activeEndX > 0f) {
                    drawLine(
                        color = activeColor,
                        start = Offset(0f, yPos),
                        end = Offset(activeEndX, yPos),
                        strokeWidth = trackHeight,
                        cap = StrokeCap.Round
                    )
                }

                // Ambient Halo Glow when dragging
                if (thumbScale.value > 1.2f) {
                    drawCircle(
                        color = activeColor.copy(alpha = 0.25f),
                        radius = 16.dp.toPx() * (thumbScale.value / 2.0f),
                        center = Offset(activeEndX, yPos)
                    )
                }

                // Spring Magnified Thumb
                val thumbRadius = 5.dp.toPx() * thumbScale.value
                drawCircle(
                    color = activeColor,
                    radius = thumbRadius,
                    center = Offset(activeEndX, yPos)
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Time Labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val totalDuration = if (durationMs > 0) durationMs else 1000L
            val currentPos = if (isDragging) (dragProgress * totalDuration).toLong() else currentPositionMs

            Text(
                text = DurationFormatter.formatMs(currentPos),
                style = LocalAppTypography.current.seekbarTime,
                color = TextSecondary
            )
            Text(
                text = DurationFormatter.formatMs(totalDuration),
                style = LocalAppTypography.current.seekbarTime,
                color = TextSecondary
            )
        }
    }
}




