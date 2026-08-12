package com.streamify.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.streamify.app.ui.animations.cardPressEffect
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens

@Composable
fun PlayerControls(
    isPlaying: Boolean,
    isShuffleActive: Boolean,
    isRepeatActive: Boolean,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onShuffleToggle) {
            Icon(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = "Shuffle",
                tint = if (isShuffleActive) StreamifyColors.Shuffle else StreamifyColors.TextSub,
                modifier = Modifier.size(StreamifyDimens.ShuffleButtonSize)
            )
        }

        IconButton(onClick = onSkipPrevious) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = "Previous",
                tint = StreamifyColors.TextMain,
                modifier = Modifier.size(StreamifyDimens.SkipButtonSize)
            )
        }

        Box(
            modifier = Modifier
                .size(StreamifyDimens.PlayButtonSize)
                .clip(CircleShape)
                .background(StreamifyColors.TextMain)
                .cardPressEffect(onClick = onPlayPause)
                .padding(StreamifyDimens.SpaceMD),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = StreamifyColors.BgBase,
                modifier = Modifier.size(32.dp)
            )
        }

        IconButton(onClick = onSkipNext) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "Next",
                tint = StreamifyColors.TextMain,
                modifier = Modifier.size(StreamifyDimens.SkipButtonSize)
            )
        }

        IconButton(onClick = onRepeatToggle) {
            Icon(
                imageVector = Icons.Filled.Repeat,
                contentDescription = "Repeat",
                tint = if (isRepeatActive) StreamifyColors.Shuffle else StreamifyColors.TextSub,
                modifier = Modifier.size(StreamifyDimens.ShuffleButtonSize)
            )
        }
    }
}
