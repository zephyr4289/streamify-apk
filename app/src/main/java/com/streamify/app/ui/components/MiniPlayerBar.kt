package com.streamify.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamify.app.data.models.Track
import com.streamify.app.ui.theme.*

@Composable
fun MiniPlayerBar(
    track: Track?,
    isPlaying: Boolean,
    progress: Float,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onExpand: () -> Unit,
    onToggleLike: (() -> Unit)? = null,
    alpha: Float = 1f,
    modifier: Modifier = Modifier
) {
    if (track == null) return

    Surface(
        color = BgSurfaceElevated,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .graphicsLayer { this.alpha = alpha }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onExpand() })
                }
                .pointerInput(Unit) {
                    var totalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onDragEnd = {
                            if (totalDrag < -60f) onNext()
                            else if (totalDrag > 60f) onPrevious()
                            totalDrag = 0f
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        totalDrag += dragAmount
                    }
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 48x48 Album Art
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(BgCard),
                    contentAlignment = Alignment.Center
                ) {
                    if (!track.coverArtPath.isNullOrBlank()) {
                        AsyncImage(
                            model = track.coverArtPath,
                            contentDescription = track.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Song Title & Artist Column
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = track.title,
                        style = LocalAppTypography.current.songTitle,
                        color = TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = track.artist,
                        style = LocalAppTypography.current.songArtist,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Play / Pause Action
                IconButton(onClick = onPlayPause) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = TextMain,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Skip Next Action
                IconButton(onClick = onNext) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "Next",
                        tint = TextMain,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // 2dp Micro-Progress Bar (Canvas drawn flush at the very bottom edge)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.BottomCenter)
            ) {
                // Background track
                drawRect(
                    color = Divider,
                    size = size
                )
                // Active progress (YouTube Stark White or Red)
                val clampedProgress = progress.coerceIn(0f, 1f)
                drawRect(
                    color = ActiveControl,
                    size = Size(width = size.width * clampedProgress, height = size.height)
                )
            }
        }
    }
}
