package com.streamify.app.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamify.app.data.models.Track
import com.streamify.app.ui.components.HeartButton
import com.streamify.app.ui.components.MarqueeText
import com.streamify.app.ui.components.PlayerBackground
import com.streamify.app.ui.components.PlayerControls
import com.streamify.app.ui.components.PlayerSeekBar
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyShapes
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.util.DurationFormatter
import kotlinx.coroutines.launch

import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Subtitles

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerSheet(
    track: Track?,
    isPlaying: Boolean,
    progress: Float,
    isShuffleActive: Boolean,
    isRepeatActive: Boolean,
    dominantColor: Color,
    onCollapse: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit,
    onToggleLike: () -> Unit,
    onQueueClick: (() -> Unit)? = null,
    onLyricsClick: (() -> Unit)? = null
) {
    if (track == null) return

    Box(modifier = Modifier.fillMaxSize()) {
        PlayerBackground(dominantColor = dominantColor)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = StreamifyDimens.SpaceXL)
                    .padding(top = 12.dp, bottom = StreamifyDimens.SpaceHuge)
            ) {
                // Pull indicator handle
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(4.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.4f))
                        .align(Alignment.CenterHorizontally)
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onCollapse) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Collapse", tint = StreamifyColors.TextMain)
                    }
                    Text("Now Playing", style = StreamifyType.Caption, color = StreamifyColors.TextMain)
                    IconButton(onClick = { /* More options */ }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Options", tint = StreamifyColors.TextMain)
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Album Art
                Crossfade(targetState = track.coverArtPath, label = "art_crossfade") { artPath ->
                    AsyncImage(
                        model = artPath,
                        contentDescription = "Album Art",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .androidx.compose.ui.draw.shadow(
                                elevation = 24.dp,
                                shape = StreamifyShapes.CardShape,
                                spotColor = dominantColor
                            )
                            .clip(StreamifyShapes.CardShape)
                    )
                }

                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))
                
                // Device Destination Indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Filled.Computer,
                        contentDescription = "Device",
                        tint = StreamifyColors.Primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(StreamifyDimens.SpaceXS))
                    Text(
                        text = "This Device (Local JNI)",
                        style = StreamifyType.Caption,
                        color = StreamifyColors.Primary
                    )
                }

                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceHuge))

                // Track Info & Like
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = StreamifyDimens.SpaceMD)) {
                        MarqueeText(
                            text = track.title,
                            style = StreamifyType.PlayerTitle,
                            color = StreamifyColors.TextMain
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = track.artist,
                                style = StreamifyType.PlayerArtist,
                                color = StreamifyColors.TextSub
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            // Neural Engine Extraction Badge
                            Box(
                                modifier = Modifier
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                    .background(StreamifyColors.Primary.copy(alpha = 0.2f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (track.isProcessed) "⚡ Neural Engine • ${track.bpm.toInt()} BPM" else "✨ AI Analyzed • ${track.bpm.toInt()} BPM",
                                    style = StreamifyType.Caption,
                                    color = StreamifyColors.Primary
                                )
                            }
                        }
                    }
                    HeartButton(
                        isLiked = track.isLiked,
                        onToggle = onToggleLike
                    )
                }

                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceXL))

                // Seekbar
                PlayerSeekBar(
                    progress = progress,
                    onSeek = onSeek
                )
                
                // Time labels
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val currentMs = (progress * track.durationSec * 1000).toLong()
                    Text(
                        text = DurationFormatter.formatMs(currentMs),
                        style = StreamifyType.SeekbarTime,
                        color = StreamifyColors.TextSub
                    )
                    Text(
                        text = DurationFormatter.formatSec(track.durationSec.toLong()),
                        style = StreamifyType.SeekbarTime,
                        color = StreamifyColors.TextSub
                    )
                }

                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))

                // Controls
                PlayerControls(
                    isPlaying = isPlaying,
                    isShuffleActive = isShuffleActive,
                    isRepeatActive = isRepeatActive,
                    onPlayPause = onPlayPause,
                    onSkipNext = onNext,
                    onSkipPrevious = onPrevious,
                    onShuffleToggle = onShuffleToggle,
                    onRepeatToggle = onRepeatToggle
                )

                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))

                // Bottom Action Bar (Queue & Lyrics)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { onQueueClick?.invoke() }) {
                        Icon(
                            imageVector = Icons.Filled.QueueMusic,
                            contentDescription = "Up Next Queue",
                            tint = StreamifyColors.TextSub
                        )
                    }
                    IconButton(onClick = { onLyricsClick?.invoke() }) {
                        Icon(
                            imageVector = Icons.Filled.Subtitles,
                            contentDescription = "Lyrics",
                            tint = StreamifyColors.TextSub
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))
            }
    }
}
