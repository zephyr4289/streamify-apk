package com.streamify.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens

@Composable
fun PlayerSeekBar(
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = StreamifyColors.TextMain,
    inactiveColor: Color = StreamifyColors.TextDimmed
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableStateOf(progress) }
    
    val currentProgress = if (isDragging) dragProgress else progress

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(StreamifyDimens.SpaceXL)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        val newProgress = (offset.x / size.width).coerceIn(0f, 1f)
                        onSeek(newProgress)
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragProgress = (offset.x / size.width).coerceIn(0f, 1f)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        dragProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        isDragging = false
                        onSeek(dragProgress)
                    },
                    onDragCancel = {
                        isDragging = false
                    }
                )
            }
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2

        // Inactive Track
        drawLine(
            color = inactiveColor,
            start = Offset(0f, centerY),
            end = Offset(width, centerY),
            strokeWidth = StreamifyDimens.SeekBarHeight.toPx()
        )

        // Active Track
        drawLine(
            color = activeColor,
            start = Offset(0f, centerY),
            end = Offset(width * currentProgress, centerY),
            strokeWidth = StreamifyDimens.SeekBarHeight.toPx()
        )

        // Thumb (Only show when dragging, or always small and grow on drag)
        val thumbRadius = if (isDragging) StreamifyDimens.SeekBarThumb.toPx() else StreamifyDimens.SeekBarThumb.toPx() * 0.5f
        drawCircle(
            color = activeColor,
            radius = thumbRadius,
            center = Offset(width * currentProgress, centerY)
        )
    }
}
