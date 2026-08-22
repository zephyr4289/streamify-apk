package com.streamify.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.FavoriteBorder
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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.StrokeCap
import com.streamify.app.viewmodel.PlaybackButtonState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
fun MiniPlayerBar(
    track: Track?,
    isPlaying: Boolean,
    progressFlow: StateFlow<Float>,
    isBuffering: Boolean = false,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onExpand: () -> Unit,
    onToggleLike: (() -> Unit)? = null,
    onSwipeDown: (() -> Unit)? = null,
    alpha: Float = 1f,
    tokenController: QuantumSonicTokenController? = null,
    modifier: Modifier = Modifier
) {
    if (track == null) return
    // Snapshot-backed subscription: reading .value inside the Canvas draw
    // scope below triggers REDRAW-ONLY invalidation per tick.
    val progressState = progressFlow.collectAsState()
    // Always-current callback reference for long-lived pointer detectors.
    val currentOnSwipeDown by androidx.compose.runtime.rememberUpdatedState(onSwipeDown)

    val buttonState = when {
        isBuffering -> PlaybackButtonState.BUFFERING
        isPlaying -> PlaybackButtonState.PLAYING
        else -> PlaybackButtonState.PAUSED
    }

    var isAbsorbing by remember { mutableStateOf(false) }
    if (tokenController != null) {
        LaunchedEffect(tokenController.stage) {
            if (tokenController.stage == TokenStage.IMPACT) {
                isAbsorbing = true
                kotlinx.coroutines.delay(220)
                isAbsorbing = false
            }
        }
    }

    val recoilScaleX by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isAbsorbing) 1.045f else 1.0f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
        ),
        label = "MiniPlayerRecoilX"
    )

    val recoilScaleY by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isAbsorbing) 0.935f else 1.0f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
        ),
        label = "MiniPlayerRecoilY"
    )

    val density = androidx.compose.ui.platform.LocalDensity.current
    val swipeThresholdPx = with(density) { 75.dp.toPx() }
    val dragOffsetX = remember { androidx.compose.animation.core.Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    Surface(
        color = BgSurfaceElevated,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .graphicsLayer {
                this.alpha = alpha
                this.scaleX = recoilScaleX
                this.scaleY = recoilScaleY
                this.translationX = dragOffsetX.value
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onExpand() })
                }
                .pointerInput(Unit) {
                    // Swipe-down dismisses the dock for the current track
                    // (auto-restores when the next track starts).
                    var totalDragY = 0f
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            totalDragY += dragAmount
                        },
                        onDragEnd = {
                            if (totalDragY > 140f) {
                                com.streamify.app.util.StreamifyHapticEngine.tokenImpactDetent()
                                currentOnSwipeDown?.invoke()
                            }
                            totalDragY = 0f
                        },
                        onDragCancel = { totalDragY = 0f }
                    )
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            coroutineScope.launch {
                                val offset = dragOffsetX.value
                                if (offset < -swipeThresholdPx) {
                                    com.streamify.app.util.StreamifyHapticEngine.tokenImpactDetent()
                                    onNext()
                                } else if (offset > swipeThresholdPx) {
                                    com.streamify.app.util.StreamifyHapticEngine.tokenImpactDetent()
                                    onPrevious()
                                }
                                dragOffsetX.animateTo(
                                    targetValue = 0f,
                                    animationSpec = androidx.compose.animation.core.spring(
                                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                        stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                                    )
                                )
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            coroutineScope.launch {
                                dragOffsetX.snapTo(dragOffsetX.value + dragAmount * 0.65f)
                            }
                        }
                    )
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

                // Like / Heart Action
                if (onToggleLike != null) {
                    IconButton(onClick = {
                        com.streamify.app.util.StreamifyHapticEngine.heartbeatFlutter()
                        onToggleLike()
                    }) {
                        Icon(
                            imageVector = if (track.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = if (track.isLiked) "Unlike" else "Like",
                            tint = if (track.isLiked) StreamifyColors.Primary else TextMain,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Play / Pause Action
                IconButton(onClick = onPlayPause) {
                    Box(contentAlignment = Alignment.Center) {
                        AnimatedContent(
                            targetState = buttonState,
                            transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(120)) },
                            label = "MiniPlayerPlayPauseAnimatedContent"
                        ) { state ->
                            when (state) {
                                PlaybackButtonState.BUFFERING -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = TextMain,
                                        strokeWidth = 2.dp,
                                        strokeCap = StrokeCap.Round
                                    )
                                }
                                PlaybackButtonState.PLAYING -> {
                                    Icon(
                                        imageVector = Icons.Filled.Pause,
                                        contentDescription = "Pause",
                                        tint = TextMain,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                PlaybackButtonState.PAUSED -> {
                                    Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = "Play",
                                        tint = TextMain,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                        }
                    }
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
                // Active progress (YouTube Stark White or Red).
                // Snapshot read inside draw: ticks redraw this 2dp strip only.
                val clampedProgress = progressState.value.coerceIn(0f, 1f)
                drawRect(
                    color = ActiveControl,
                    size = Size(width = size.width * clampedProgress, height = size.height)
                )
            }
        }
    }
}
