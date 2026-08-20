package com.streamify.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.streamify.app.data.NativeBridge

@Composable
fun ZeroDragSeekbar(
    modifier: Modifier = Modifier,
    currentPositionMs: Long,
    durationMs: Long,
    activeColor: Color = Color.White,
    inactiveColor: Color = Color(0x33FFFFFF)
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragRatio by remember { mutableFloatStateOf(0f) }

    val safeDuration = durationMs.coerceAtLeast(1L)
    val displayRatio = if (isDragging) {
        dragRatio
    } else {
        (currentPositionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(safeDuration) {
                detectTapGestures { offset ->
                    val ratio = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    val targetMs = (ratio * safeDuration).toLong()
                    NativeBridge.submitSeekRequest(targetMs)
                }
            }
            .pointerInput(safeDuration) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragRatio = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val targetMs = (dragRatio * safeDuration).toLong()
                        NativeBridge.submitSeekRequest(targetMs)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        dragRatio = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val targetMs = (dragRatio * safeDuration).toLong()
                        NativeBridge.submitSeekRequest(targetMs)
                    },
                    onDragEnd = {
                        isDragging = false
                    },
                    onDragCancel = {
                        isDragging = false
                    }
                )
            }
    ) {
        val barHeight = 4.dp.toPx()
        val centerY = (size.height - barHeight) / 2f
        val activeWidth = size.width * displayRatio
        val corner = CornerRadius(barHeight / 2f, barHeight / 2f)

        // Inactive background track
        drawRoundRect(
            color = inactiveColor,
            topLeft = Offset(0f, centerY),
            size = Size(size.width, barHeight),
            cornerRadius = corner
        )

        // Active highlighted track
        if (activeWidth > 0f) {
            drawRoundRect(
                color = activeColor,
                topLeft = Offset(0f, centerY),
                size = Size(activeWidth, barHeight),
                cornerRadius = corner
            )
        }

        // Thumb dot (rendered when dragging or actively scrubbed)
        if (isDragging) {
            drawCircle(
                color = activeColor,
                radius = 6.dp.toPx(),
                center = Offset(activeWidth.coerceIn(6.dp.toPx(), size.width - 6.dp.toPx()), size.height / 2f)
            )
        }
    }
}
