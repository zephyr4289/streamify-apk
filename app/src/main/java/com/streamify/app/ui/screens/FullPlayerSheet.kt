package com.streamify.app.ui.screens

import android.content.res.Configuration
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.streamify.app.data.models.Track
import com.streamify.app.ui.components.HeartButton
import com.streamify.app.ui.components.MarqueeText
import com.streamify.app.ui.components.PlayerBackground
import com.streamify.app.ui.components.PlayerControls
import com.streamify.app.ui.components.PlayerSeekBar
import com.streamify.app.ui.components.TrackCoverArt
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyShapes
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.util.DurationFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerSheet(
    track: Track?,
    isPlaying: Boolean,
    progress: Float,
    isShuffleActive: Boolean,
    isRepeatActive: Boolean,
    dominantColor: Color,
    durationMs: Long = 0L,
    currentPositionMs: Long = 0L,
    onCollapse: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit,
    onToggleLike: () -> Unit,
    onQueueClick: (() -> Unit)? = null,
    onLyricsClick: (() -> Unit)? = null,
    onRadioClick: (() -> Unit)? = null,
    onJamClick: (() -> Unit)? = null,
    isAutoPlayEnabled: Boolean = false,
    onAutoPlayToggle: (() -> Unit)? = null
) {
    if (track == null) return

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showCommentsSheet by remember { mutableStateOf(false) }
    val communityViewModel: com.streamify.app.viewmodel.CommunityViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

    Box(modifier = Modifier.fillMaxSize()) {
        PlayerBackground(dominantColor = dominantColor)

        if (isLandscape) {
            // Adaptive Landscape / Tablet Layout
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = StreamifyDimens.SpaceXL, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Column: Album Art & Header
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    IconButton(
                        onClick = onCollapse,
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Collapse", tint = StreamifyColors.TextMain)
                    }
                    
                    Spacer(modifier = Modifier.weight(1f))

                    TrackCoverArt(
                        coverArtPath = track.coverArtPath,
                        title = track.title,
                        artist = track.artist,
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .aspectRatio(1f)
                            .shadow(24.dp, StreamifyShapes.CardShape, spotColor = dominantColor),
                        shape = StreamifyShapes.CardShape
                    )

                    Spacer(modifier = Modifier.weight(1f))
                }

                // Right Column: Player Controls & Details
                Column(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center
                ) {
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
                            Text(
                                text = track.artist,
                                style = StreamifyType.PlayerArtist,
                                color = StreamifyColors.TextSub
                            )
                        }
                        HeartButton(isLiked = track.isLiked, onToggle = onToggleLike)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    PlayerSeekBar(progress = progress, onSeek = onSeek)
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val totalDurationMs = if (durationMs > 0) durationMs else (track.durationSec.toLong() * 1000L)
                        val currentMs = if (currentPositionMs > 0) currentPositionMs else (progress * totalDurationMs).toLong()
                        Text(text = DurationFormatter.formatMs(currentMs), style = StreamifyType.SeekbarTime, color = StreamifyColors.TextSub)
                        Text(text = DurationFormatter.formatMs(totalDurationMs), style = StreamifyType.SeekbarTime, color = StreamifyColors.TextSub)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

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
                }
            }
        } else {
            // Standard Portrait Mobile Layout
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = StreamifyDimens.SpaceXL)
                    .padding(top = 12.dp, bottom = StreamifyDimens.SpaceHuge)
                    .verticalScroll(rememberScrollState())
            ) {
                // Pull indicator handle
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.4f))
                        .align(Alignment.CenterHorizontally)
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                // Header with Jam and Options
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onCollapse) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Collapse", tint = StreamifyColors.TextMain)
                    }
                    Text("Now Playing", style = StreamifyType.Caption, color = StreamifyColors.TextMain)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onJamClick?.invoke() }) {
                            Icon(Icons.Filled.Share, contentDescription = "Jam", tint = StreamifyColors.Primary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Album Art
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Crossfade(targetState = track.coverArtPath, label = "art_crossfade_portrait") { artPath ->
                    TrackCoverArt(
                        coverArtPath = artPath,
                        title = track.title,
                        artist = track.artist,
                        modifier = Modifier
                            .fillMaxWidth(if (configuration.screenWidthDp > 600) 0.7f else 1f)
                            .aspectRatio(1f)
                            .shadow(
                                elevation = 24.dp,
                                shape = StreamifyShapes.CardShape,
                                spotColor = dominantColor
                            ),
                        shape = StreamifyShapes.CardShape
                    )
                }
                }

                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))
                
                // Dynamic Audio Route Indicator
                val outputDevice by com.streamify.app.service.AudioDeviceManager.currentDevice.collectAsState()
                val isOnline = track.filepath.startsWith("http://") || track.filepath.startsWith("https://") || track.source.contains("online", ignoreCase = true)
                val routingText = if (isOnline) {
                    if (outputDevice.isBluetooth) "Bluetooth • ${outputDevice.name} (HQ Stream)"
                    else "Online Stream • M4A High-Bitrate"
                } else {
                    if (outputDevice.isBluetooth) "Bluetooth • ${outputDevice.name}"
                    else if (outputDevice.isHeadphones) "Headphones • Lossless"
                    else "Phone Speaker • Streamify Engine"
                }
                val routingIcon = if (outputDevice.isBluetooth) Icons.Filled.BluetoothAudio
                else if (outputDevice.isHeadphones) Icons.Filled.Headphones
                else if (isOnline) Icons.Filled.CloudQueue
                else Icons.Filled.Speaker

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = routingIcon,
                        contentDescription = "Device",
                        tint = StreamifyColors.Primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(StreamifyDimens.SpaceXS))
                    Text(
                        text = routingText,
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
                            
                            // Dynamic Neural Engine Extraction Badge / Pending Clock State
                            val isNeuralProcessed = track.isProcessed && track.bpm > 0f
                            Surface(
                                color = if (isNeuralProcessed) StreamifyColors.Primary.copy(alpha = 0.18f) else StreamifyColors.BgElevated,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isNeuralProcessed) {
                                        Icon(
                                            imageVector = Icons.Filled.Bolt,
                                            contentDescription = null,
                                            tint = StreamifyColors.Primary,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "NEURAL • ${track.bpm.toInt()} BPM${if (track.key.isNotBlank()) " • " + track.key else ""}",
                                            style = StreamifyType.Caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                                            color = StreamifyColors.Primary
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Filled.Schedule,
                                            contentDescription = null,
                                            tint = StreamifyColors.TextSub,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Acoustic Signal Processing",
                                            style = StreamifyType.Caption,
                                            color = StreamifyColors.TextSub
                                        )
                                    }
                                }
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
                    val totalDurationMs = if (durationMs > 0) durationMs else (track.durationSec.toLong() * 1000L)
                    val currentMs = if (currentPositionMs > 0) currentPositionMs else (progress * totalDurationMs).toLong()
                    Text(
                        text = DurationFormatter.formatMs(currentMs),
                        style = StreamifyType.SeekbarTime,
                        color = StreamifyColors.TextSub
                    )
                    Text(
                        text = DurationFormatter.formatMs(totalDurationMs),
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

                // Bottom Action Bar (Audio Device Switcher, Queue, Infinity Radio, Lyrics)
                val audioDevice by com.streamify.app.service.AudioDeviceManager.currentDevice.collectAsState()
                val context = androidx.compose.ui.platform.LocalContext.current

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Spotify Connect Audio Device Pill
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { com.streamify.app.service.AudioDeviceManager.openSystemAudioSettings(context) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (audioDevice.isBluetooth) Icons.Filled.BluetoothAudio else Icons.Filled.Speaker,
                            contentDescription = "Audio Output Device",
                            tint = if (audioDevice.isBluetooth) StreamifyColors.Primary else StreamifyColors.TextSub,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = audioDevice.name,
                            style = StreamifyType.Caption,
                            color = if (audioDevice.isBluetooth) StreamifyColors.Primary else StreamifyColors.TextSub,
                            maxLines = 1
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Song Radio (pgvector AI recommendations)
                        IconButton(onClick = { onRadioClick?.invoke() }) {
                            Icon(
                                imageVector = Icons.Filled.AutoAwesome,
                                contentDescription = "Song Radio",
                                tint = StreamifyColors.Primary
                            )
                        }

                        // Comments Sheet Trigger
                        IconButton(onClick = { showCommentsSheet = true }) {
                            Icon(
                                imageVector = Icons.Filled.Send,
                                contentDescription = "Reactions",
                                tint = StreamifyColors.TextSub
                            )
                        }

                        // Queue
                        IconButton(onClick = { onQueueClick?.invoke() }) {
                            Icon(
                                imageVector = Icons.Filled.QueueMusic,
                                contentDescription = "Up Next Queue",
                                tint = StreamifyColors.TextSub
                            )
                        }
                        
                        // Lyrics
                        IconButton(onClick = { onLyricsClick?.invoke() }) {
                            Icon(
                                imageVector = Icons.Filled.Subtitles,
                                contentDescription = "Lyrics",
                                tint = StreamifyColors.TextSub
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCommentsSheet) {
        com.streamify.app.ui.components.CommentsSheet(
            track = track,
            currentPositionMs = currentPositionMs,
            communityViewModel = communityViewModel,
            onSeekTo = { posMs ->
                if (durationMs > 0) onSeek(posMs.toFloat() / durationMs.toFloat())
            },
            onDismiss = { showCommentsSheet = false }
        )
    }
}
