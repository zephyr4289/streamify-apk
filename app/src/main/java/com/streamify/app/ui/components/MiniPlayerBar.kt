package com.streamify.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamify.app.data.models.Track
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyShapes
import com.streamify.app.ui.theme.StreamifyType

@Composable
fun MiniPlayerBar(
    track: Track?,
    isPlaying: Boolean,
    progress: Float,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (track == null) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = StreamifyDimens.MiniPlayerMargin)
            .padding(bottom = StreamifyDimens.MiniPlayerMargin)
            .clip(StreamifyShapes.MiniPlayerShape)
            .background(StreamifyColors.BgElevated)
            .clickable(onClick = onExpand)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { /* Snap back */ },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        if (dragAmount > 50) onNext()
                        else if (dragAmount < -50) onPrevious()
                    }
                )
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(StreamifyDimens.MiniPlayerHeight)
                .padding(end = StreamifyDimens.SpaceSM),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = track.coverArtPath,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(StreamifyDimens.MiniPlayerHeight)
                    .padding(StreamifyDimens.SpaceSM)
                    .clip(StreamifyShapes.CardShape)
            )

            Column(modifier = Modifier.weight(1f)) {
                MarqueeText(
                    text = track.title,
                    style = StreamifyType.TitleSmall,
                    color = StreamifyColors.TextMain
                )
                Text(
                    text = track.artist,
                    style = StreamifyType.Caption,
                    color = StreamifyColors.TextSub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            HeartButton(
                isLiked = track.isLiked,
                onToggle = { /* Like */ }
            )

            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = StreamifyColors.TextMain
                )
            }
        }

        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier
                .fillMaxWidth()
                .height(StreamifyDimens.ProgressLineH),
            color = StreamifyColors.TextMain,
            trackColor = StreamifyColors.BgElevated
        )
    }
}
