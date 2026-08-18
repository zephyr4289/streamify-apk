package com.streamify.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.streamify.app.ui.theme.*

/**
 * YouTube Music Style Precision Seekbar with iPhone-Style Fluid Kinetic Waveform & Spring Inertia.
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
    FluidWaveformSeekbar(
        progress = progress,
        durationMs = durationMs,
        currentPositionMs = currentPositionMs,
        onSeek = onSeek,
        modifier = modifier,
        activeGlowColor = Primary,
        activeColor = activeColor,
        inactiveColor = trackColor.copy(alpha = 0.40f)
    )
}

