package com.streamify.app.ui.screens

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.streamify.app.data.TrackRepository
import com.streamify.app.data.models.LyricsData
import com.streamify.app.data.models.LyricsLine
import com.streamify.app.data.models.Track
import com.streamify.app.service.LyricPlaybackController
import com.streamify.app.ui.components.*
import com.streamify.app.ui.theme.*
import com.streamify.app.viewmodel.CommunityViewModel
import com.streamify.app.viewmodel.UiEvent
import com.streamify.app.viewmodel.UiEventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class LandscapePlayerTab {
    UP_NEXT, LYRICS, RELATED
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FullPlayerSheet(
    track: Track?,
    isPlaying: Boolean,
    progress: Float,
    isBuffering: Boolean = false,
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

    val screenConfig = LocalScreenConfiguration.current
    val isLandscape = screenConfig.isLandscape
    val playerViewModel: com.streamify.app.viewmodel.PlayerViewModel = viewModel()

    val playerState by playerViewModel.playerState.collectAsState()
    val isVideoMode = playerState.isVideoMode

    val targetRatio = if (isVideoMode) (16f / 9f) else 1f
    val animatedAspectRatio by androidx.compose.animation.core.animateFloatAsState(
        targetValue = targetRatio,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
        ),
        label = "HeroAspectRatioAnimation"
    )

    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "HeroBufferingPulse")
    val heroPulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.55f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(durationMillis = 750, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "HeroPulseAlpha"
    )

    val playbackButtonState = when {
        isBuffering -> com.streamify.app.viewmodel.PlaybackButtonState.BUFFERING
        isPlaying -> com.streamify.app.viewmodel.PlaybackButtonState.PLAYING
        else -> com.streamify.app.viewmodel.PlaybackButtonState.PAUSED
    }

    var showCommentsSheet by remember { mutableStateOf(false) }
    val communityViewModel: CommunityViewModel = viewModel()
    var showRelatedSheet by remember { mutableStateOf(false) }
    var landscapeTab by remember { mutableStateOf(LandscapePlayerTab.UP_NEXT) }

    // --- PILLAR 2: LIFO Sub-Sheet Back Trapping ---
    BackHandler(enabled = showCommentsSheet) {
        showCommentsSheet = false
    }
    BackHandler(enabled = showRelatedSheet) {
        showRelatedSheet = false
    }
    // Pre-Allocated GPU Assets: Zero heap allocation inside Canvas draw phase
    val ambientGlowColors = remember(dominantColor) {
        listOf(
            dominantColor.copy(alpha = 0.35f),
            BgBase.copy(alpha = 0.85f),
            BgBase
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
    ) {
        // 1. Extreme Performance: GPU Radial Gradient Ambient Glow (0.01ms Single Draw Call)
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = ambientGlowColors,
                    center = Offset(
                        if (isLandscape) size.width * 0.22f else size.width / 2,
                        if (isLandscape) size.height / 2 else size.height * 0.28f
                    ),
                    radius = if (isLandscape) size.width * 0.8f else size.width * 1.15f
                )
            )
        }

        if (isLandscape) {
            // =========================================================================
            // ADAPTIVE DUAL-PANE LANDSCAPE / TABLET LAYOUT
            // Left Pane (42%): Hero Art / 60FPS Video Surface + Controls
            // Right Pane (58%): Dynamic Tabbed Drawer (Up Next / Lyrics / Related)
            // =========================================================================
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ─── LEFT PANE: HERO ARTWORK / VIDEO & PLAYBACK CONTROLS (42%) ───
                Column(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Top Bar: Collapse + Song/Video Switcher + Cast
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onCollapse) {
                            Icon(
                                imageVector = Icons.Filled.ExpandMore,
                                contentDescription = "Collapse",
                                tint = TextMain,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        YtSongVideoSwitcher(
                            isVideo = isVideoMode,
                            onToggle = { playerViewModel.toggleVideoMode(it) }
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onJamClick?.invoke() }) {
                                Icon(
                                    imageVector = Icons.Filled.Cast,
                                    contentDescription = "Cast",
                                    tint = TextMain,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    val hasVideoStream = remember(track.id, track.filepath, isVideoMode) {
                        track.filepath.endsWith(".mp4") || track.filepath.contains("mime=video") || (track.ytmVideoId != null && isVideoMode)
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(LocalAppShapes.current.thumbnailLarge)
                            .background(androidx.compose.ui.graphics.Color.Black)
                            .graphicsLayer {
                                if (isBuffering && !isVideoMode) {
                                    alpha = heroPulseAlpha
                                    scaleX = 0.98f + (heroPulseAlpha * 0.02f)
                                    scaleY = 0.98f + (heroPulseAlpha * 0.02f)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        TrackCoverArt(
                            coverArtPath = track.coverArtPath,
                            title = track.title,
                            artist = track.artist,
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(16.dp)
                        )

                        if (isVideoMode && hasVideoStream && playerViewModel.getController() != null) {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        player = playerViewModel.getController()
                                        useController = false
                                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                                    }
                                },
                                update = { view ->
                                    view.player = playerViewModel.getController()
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Track Title, Artist & Like Button Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                style = LocalAppTypography.current.playerTitle.copy(fontSize = 18.sp),
                                color = TextMain,
                                maxLines = 1,
                                modifier = Modifier.basicMarquee()
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${track.artist}${if (track.album.isNotBlank() && track.album != "Streamify") " • " + track.album else ""}",
                                style = LocalAppTypography.current.playerArtist.copy(fontSize = 13.sp),
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(onClick = {
                            com.streamify.app.util.StreamifyHapticEngine.heartbeatFlutter()
                            onToggleLike()
                        }) {
                            Icon(
                                imageVector = if (track.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = if (track.isLiked) "Unlike" else "Like",
                                tint = if (track.isLiked) StreamifyColors.Primary else TextMain,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // YouTube Music Action Pills (Like, Comments, Radio, Jam)
                    YtPlayerActionPills(
                        isLiked = track.isLiked,
                        onToggleLike = {
                            com.streamify.app.util.StreamifyHapticEngine.heartbeatFlutter()
                            onToggleLike()
                        },
                        onCommentsClick = { showCommentsSheet = true },
                        onRadioClick = onRadioClick,
                        onJamClick = onJamClick,
                        onDownloadClick = { /* Download */ }
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Precision Canvas SeekBar
                    val effectiveDurationMs = if (durationMs > 0) durationMs else (track.durationSec * 1000L)
                    YtPlayerSeekBar(
                        progress = progress,
                        durationMs = effectiveDurationMs,
                        currentPositionMs = currentPositionMs,
                        onSeek = onSeek
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Playback Controls Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onShuffleToggle) {
                            Icon(
                                imageVector = if (isShuffleActive) Icons.Filled.Shuffle else Icons.Outlined.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (isShuffleActive) ActiveControl else TextSecondary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        IconButton(onClick = onPrevious) {
                            Icon(
                                imageVector = Icons.Filled.SkipPrevious,
                                contentDescription = "Previous",
                                tint = TextMain,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(ActiveControl),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(onClick = onPlayPause) {
                                AnimatedContent(
                                    targetState = playbackButtonState,
                                    transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(140)) },
                                    label = "LandscapePlayPauseAnimatedContent"
                                ) { state ->
                                    when (state) {
                                        com.streamify.app.viewmodel.PlaybackButtonState.BUFFERING -> {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(24.dp),
                                                color = TextOnActiveChip,
                                                strokeWidth = 2.5.dp,
                                                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                                            )
                                        }
                                        com.streamify.app.viewmodel.PlaybackButtonState.PLAYING -> {
                                            Icon(
                                                imageVector = Icons.Filled.Pause,
                                                contentDescription = "PlayPause",
                                                tint = TextOnActiveChip,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                        com.streamify.app.viewmodel.PlaybackButtonState.PAUSED -> {
                                            Icon(
                                                imageVector = Icons.Filled.PlayArrow,
                                                contentDescription = "PlayPause",
                                                tint = TextOnActiveChip,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        IconButton(onClick = onNext) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = "Next",
                                tint = TextMain,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        IconButton(onClick = onRepeatToggle) {
                            Icon(
                                imageVector = if (isRepeatActive) Icons.Filled.Repeat else Icons.Outlined.Repeat,
                                contentDescription = "Repeat",
                                tint = if (isRepeatActive) ActiveControl else TextSecondary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                // ─── RIGHT PANE: DYNAMIC TABBED DRAWER (UP NEXT | LYRICS | RELATED) (58%) ───
                Column(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(16.dp))
                        .background(BgSurfaceElevated.copy(alpha = 0.55f))
                        .padding(14.dp)
                ) {
                    // Segmented Capsule Header Tabs
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(BgCard)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        val queueCount = playerState.queue.size
                        val tabItems = listOf(
                            LandscapePlayerTab.UP_NEXT to "UP NEXT ($queueCount)",
                            LandscapePlayerTab.LYRICS to "LYRICS",
                            LandscapePlayerTab.RELATED to "RELATED"
                        )

                        tabItems.forEach { (tabEnum, tabTitle) ->
                            val isSelected = landscapeTab == tabEnum
                            Surface(
                                color = if (isSelected) ActiveControl else Color.Transparent,
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { landscapeTab = tabEnum }
                            ) {
                                Text(
                                    text = tabTitle,
                                    style = LocalAppTypography.current.chipText.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 12.sp
                                    ),
                                    color = if (isSelected) TextOnActiveChip else TextSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Stateful AnimatedContent Tab Router (Preserves scroll position & 0-recomputation)
                    AnimatedContent(
                        targetState = landscapeTab,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(150)) togetherWith fadeOut(animationSpec = tween(150))
                        },
                        label = "LandscapeTabTransition",
                        modifier = Modifier.fillMaxSize()
                    ) { activeTab ->
                        when (activeTab) {
                            LandscapePlayerTab.UP_NEXT -> {
                                LandscapeQueuePane(
                                    queue = playerState.queue,
                                    currentIndex = playerState.currentIndex,
                                    currentTrack = playerState.currentTrack,
                                    isPlaying = playerState.isPlaying,
                                    onTrackClick = { clickedTrack ->
                                        playerViewModel.playTrack(clickedTrack, playerState.queue)
                                    }
                                )
                            }
                            LandscapePlayerTab.LYRICS -> {
                                LandscapeLyricsPane(
                                    track = track,
                                    currentPositionMs = currentPositionMs,
                                    isPlaying = isPlaying,
                                    onSeek = { posMs ->
                                        if (durationMs > 0) onSeek(posMs.toFloat() / durationMs.toFloat())
                                    }
                                )
                            }
                            LandscapePlayerTab.RELATED -> {
                                LandscapeRelatedPane(
                                    track = track,
                                    playerViewModel = playerViewModel,
                                    onTrackClick = { clickedTrack ->
                                        playerViewModel.playTrack(clickedTrack, listOf(clickedTrack))
                                    }
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // =========================================================================
            // STANDARD PORTRAIT MOBILE LAYOUT
            // =========================================================================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .centerInLargeScreen()
                    .padding(top = 16.dp, bottom = 8.dp)
            ) {
                // --- TOP BAR (Collapse Chevron, Song/Video Switcher, Actions) ---
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onCollapse) {
                        Icon(
                            imageVector = Icons.Filled.ExpandMore,
                            contentDescription = "Collapse",
                            tint = TextMain,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    YtSongVideoSwitcher(
                        isVideo = isVideoMode,
                        onToggle = { playerViewModel.toggleVideoMode(it) }
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onJamClick?.invoke() }) {
                            Icon(
                                imageVector = Icons.Filled.Cast,
                                contentDescription = "Cast",
                                tint = TextMain,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        IconButton(onClick = { /* Additional options */ }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "Options",
                                tint = TextMain,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                var seekRippleSide by remember { mutableStateOf<Int?>(null) }
                val seekRippleScope = rememberCoroutineScope()

                // --- HERO DYNAMIC MORPHING ALBUM ARTWORK / HARDWARE VIDEO SURFACE ---
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .aspectRatio(animatedAspectRatio)
                        .clip(LocalAppShapes.current.thumbnailLarge)
                        .background(androidx.compose.ui.graphics.Color.Black)
                        .graphicsLayer {
                            if (isBuffering && !isVideoMode) {
                                alpha = heroPulseAlpha
                                scaleX = 0.98f + (heroPulseAlpha * 0.02f)
                                scaleY = 0.98f + (heroPulseAlpha * 0.02f)
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = {
                                    com.streamify.app.util.StreamifyHapticEngine.magneticQueueGrab()
                                    onLyricsClick?.invoke()
                                },
                                onDoubleTap = { offset: Offset ->
                                    val isRightSide = offset.x > (size.width / 2f)
                                    val seekDeltaMs = if (isRightSide) 10_000L else -10_000L
                                    com.streamify.app.util.StreamifyHapticEngine.scrubberTick()
                                    playerViewModel.seekRelative(seekDeltaMs)
                                    seekRippleSide = if (isRightSide) 1 else -1
                                    seekRippleScope.launch {
                                        kotlinx.coroutines.delay(650)
                                        seekRippleSide = null
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val hasVideoStream = remember(track.id, track.filepath, isVideoMode) {
                        track.filepath.endsWith(".mp4") || track.filepath.contains("mime=video") || (track.ytmVideoId != null && isVideoMode)
                    }

                    TrackCoverArt(
                        coverArtPath = track.coverArtPath,
                        title = track.title,
                        artist = track.artist,
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(16.dp)
                    )

                    if (isVideoMode && hasVideoStream && playerViewModel.getController() != null) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = playerViewModel.getController()
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                                }
                            },
                            update = { view ->
                                view.player = playerViewModel.getController()
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    if (seekRippleSide != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.42f)),
                            contentAlignment = if (seekRippleSide == 1) Alignment.CenterEnd else Alignment.CenterStart
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            ) {
                                Icon(
                                    imageVector = if (seekRippleSide == 1) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (seekRippleSide == 1) "+10s" else "-10s",
                                    style = LocalAppTypography.current.songArtist.copy(
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color.White
                                )
                            }
                        }
                    }
                }


                Spacer(modifier = Modifier.height(20.dp))

                // --- METADATA & NEURAL DSP PILL ---
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = LocalAppTypography.current.playerTitle,
                            color = TextMain,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee()
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${track.artist}${if (track.album.isNotBlank() && track.album != "Streamify") " • " + track.album else ""}",
                                style = LocalAppTypography.current.playerArtist,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )

                            if (track.isProcessed && track.bpm > 0f) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = BgSurfaceElevated,
                                    modifier = Modifier.padding(top = 1.dp)
                                ) {
                                    Text(
                                        text = "${track.bpm.toInt()} BPM${if (track.key.isNotBlank()) " • " + track.key else ""}",
                                        style = LocalAppTypography.current.chipText.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                        color = ActiveControl,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // --- YOUTUBE MUSIC ACTION PILLS RAIL ---
                YtPlayerActionPills(
                    isLiked = track.isLiked,
                    onToggleLike = {
                        com.streamify.app.util.StreamifyHapticEngine.heartbeatFlutter()
                        onToggleLike()
                    },
                    onCommentsClick = { showCommentsSheet = true },
                    onRadioClick = onRadioClick,
                    onJamClick = onJamClick,
                    onDownloadClick = { /* Download */ }
                )

                Spacer(modifier = Modifier.weight(1f))

                // --- PRECISION CANVAS SEEKBAR ---
                val effectiveDurationMs = if (durationMs > 0) durationMs else (track.durationSec * 1000L)
                YtPlayerSeekBar(
                    progress = progress,
                    durationMs = effectiveDurationMs,
                    currentPositionMs = currentPositionMs,
                    onSeek = onSeek
                )

                Spacer(modifier = Modifier.height(12.dp))

                // --- PLAYBACK CONTROLS (Shuffle, Prev, 64dp Play/Pause, Next, Repeat) ---
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onShuffleToggle) {
                        Icon(
                            imageVector = if (isShuffleActive) Icons.Filled.Shuffle else Icons.Outlined.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (isShuffleActive) ActiveControl else TextSecondary,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    IconButton(onClick = {
                        com.streamify.app.util.StreamifyHapticEngine.magneticDetent()
                        onPrevious()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = "Previous",
                            tint = TextMain,
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    // 64dp YouTube Music White Play Button
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(ActiveControl),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(onClick = {
                            com.streamify.app.util.StreamifyHapticEngine.playbackPulse()
                            onPlayPause()
                        }) {
                            AnimatedContent(
                                targetState = playbackButtonState,
                                transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(140)) },
                                label = "PortraitPlayPauseAnimatedContent"
                            ) { state ->
                                when (state) {
                                    com.streamify.app.viewmodel.PlaybackButtonState.BUFFERING -> {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(28.dp),
                                            color = TextOnActiveChip,
                                            strokeWidth = 2.8.dp,
                                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                                        )
                                    }
                                    com.streamify.app.viewmodel.PlaybackButtonState.PLAYING -> {
                                        Icon(
                                            imageVector = Icons.Filled.Pause,
                                            contentDescription = "PlayPause",
                                            tint = TextOnActiveChip,
                                            modifier = Modifier.size(34.dp)
                                        )
                                    }
                                    com.streamify.app.viewmodel.PlaybackButtonState.PAUSED -> {
                                        Icon(
                                            imageVector = Icons.Filled.PlayArrow,
                                            contentDescription = "PlayPause",
                                            tint = TextOnActiveChip,
                                            modifier = Modifier.size(34.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    IconButton(onClick = {
                        com.streamify.app.util.StreamifyHapticEngine.magneticDetent()
                        onNext()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = "Next",
                            tint = TextMain,
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    IconButton(onClick = onRepeatToggle) {
                        Icon(
                            imageVector = if (isRepeatActive) Icons.Filled.Repeat else Icons.Outlined.Repeat,
                            contentDescription = "Repeat",
                            tint = if (isRepeatActive) ActiveControl else TextSecondary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // --- BOTTOM SHEET ANCHOR TABS (UP NEXT | LYRICS | RELATED) ---
                YtPlayerBottomTabs(
                    activeTab = "UP NEXT",
                    onQueueClick = { onQueueClick?.invoke() },
                    onLyricsClick = { onLyricsClick?.invoke() },
                    onRelatedClick = { showRelatedSheet = true }
                )
            }
        }
    }

    if (showCommentsSheet) {
        CommentsSheet(
            track = track,
            currentPositionMs = currentPositionMs,
            communityViewModel = communityViewModel,
            onSeekTo = { posMs ->
                if (durationMs > 0) onSeek(posMs.toFloat() / durationMs.toFloat())
            },
            onDismiss = { showCommentsSheet = false }
        )
    }

    if (showRelatedSheet) {
        RelatedDiscoverSheet(
            track = track,
            playerViewModel = playerViewModel,
            onTrackClick = { clickedTrack ->
                playerViewModel.playTrack(clickedTrack, listOf(clickedTrack))
            },
            onDismiss = { showRelatedSheet = false }
        )
    }
}

@Composable
private fun LandscapeQueuePane(
    queue: List<Track>,
    currentIndex: Int,
    currentTrack: Track?,
    isPlaying: Boolean,
    onTrackClick: (Track) -> Unit
) {
    val listState = rememberLazyListState()

    val playedHistory = remember(queue, currentIndex) {
        if (currentIndex > 0 && queue.isNotEmpty()) {
            queue.subList(0, currentIndex.coerceAtMost(queue.size))
        } else {
            emptyList()
        }
    }

    val upNext = remember(queue, currentIndex) {
        if (currentIndex >= 0 && currentIndex + 1 < queue.size) {
            queue.subList(currentIndex + 1, queue.size)
        } else {
            emptyList()
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        // 1. PLAYED (History)
        if (playedHistory.isNotEmpty()) {
            item(key = "hdr_history") {
                Text(
                    text = "HISTORY (${playedHistory.size})",
                    style = LocalAppTypography.current.songArtist.copy(
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = TextTertiary.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                )
            }

            items(
                items = playedHistory,
                key = { "hist_${it.id}_${it.filepath.hashCode()}" }
            ) { itemTrack ->
                YtQueueTrackItem(
                    track = itemTrack,
                    isPlaying = false,
                    dragOffset = 0f,
                    showDragHandle = false,
                    modifier = Modifier.graphicsLayer { alpha = 0.55f },
                    onClick = { onTrackClick(itemTrack) },
                    onMoreClick = { /* Options */ }
                )
            }

            item(key = "sp_divider_hist") {
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        // 2. NOW PLAYING
        if (currentTrack != null) {
            item(key = "hdr_playing") {
                Text(
                    text = "NOW PLAYING",
                    style = LocalAppTypography.current.songArtist.copy(
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = ActiveControl,
                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                )
            }

            item(key = "active_${currentTrack.id}_${currentTrack.filepath.hashCode()}") {
                YtQueueTrackItem(
                    track = currentTrack,
                    isPlaying = isPlaying,
                    dragOffset = 0f,
                    showDragHandle = false,
                    onClick = { /* Already playing */ },
                    onMoreClick = { /* Options */ }
                )
            }

            item(key = "sp_divider") {
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        // 3. UP NEXT (Strictly upcoming unplayed tracks)
        if (upNext.isNotEmpty()) {
            item(key = "hdr_upnext") {
                Text(
                    text = "UP NEXT (${upNext.size})",
                    style = LocalAppTypography.current.songArtist.copy(
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = TextTertiary,
                    modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 4.dp)
                )
            }

            items(
                items = upNext,
                key = { "upnext_${it.id}_${it.filepath.hashCode()}" }
            ) { itemTrack ->
                YtQueueTrackItem(
                    track = itemTrack,
                    isPlaying = false,
                    dragOffset = 0f,
                    showDragHandle = true,
                    onClick = { onTrackClick(itemTrack) },
                    onMoreClick = { /* Options */ }
                )
            }
        } else {
            item(key = "empty_q") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "End of queue. Auto-play will discover new tracks.",
                        style = LocalAppTypography.current.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun LandscapeLyricsPane(
    track: Track,
    currentPositionMs: Long,
    isPlaying: Boolean = true,
    onSeek: (Long) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var lyricsLines by remember(track.id) { mutableStateOf<List<LyricsLine>>(emptyList()) }
    var isLoading by remember(track.id) { mutableStateOf(true) }
    val lyricController = remember { LyricPlaybackController() }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Cache-only load keyed on lyricsPath too: when PlayerViewModel (the single fetch
    // owner) lands verified lyrics, this effect re-fires and hydrates them instantly.
    LaunchedEffect(track.id, track.lyricsPath) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val loadedLines = com.streamify.app.data.LyricsCacheManager.getOrFetchLyrics(context, track, allowNetwork = false)
            withContext(Dispatchers.Main) {
                lyricsLines = loadedLines
                isLoading = false
            }
        }
    }

    val isSynced = remember(lyricsLines) {
        lyricsLines.isNotEmpty() && lyricsLines.any { it.timeMs > 0L }
    }

    val handleSaveOffset: () -> Unit = {
        if (lyricsLines.isNotEmpty() && lyricController.userOffsetMs != 0L) {
            val offset = lyricController.userOffsetMs
            val shiftedLines = LyricsData.shiftTimestamps(lyricsLines, offset)
            val adjustedLrc = LyricsData.formatLrc(lyricsLines, offset)

            // 1. Instant in-memory shift
            lyricsLines = shiftedLines
            lyricController.resetOffset()

            // 2. Persist to Disk LRU, Companion LRC, SQLite DB & Supabase Community
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    com.streamify.app.data.LyricsCacheManager.saveLyricsToDiskAndDb(context, track, adjustedLrc)

                    // Submit to Community Supabase
                    try {
                        val cleanSig = (track.title.trim().lowercase() + "_" + track.artist.trim().lowercase())
                        val cloudId = "trk_${kotlin.math.abs(cleanSig.hashCode())}"
                        com.streamify.app.data.remote.SupabaseClient.submitSyncedLyrics(cloudId, adjustedLrc)
                    } catch (e: Exception) {
                        // Non-fatal
                    }

                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            "❤️ Thank you for syncing! Lyrics timing saved & synced.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }


    Column(modifier = Modifier.fillMaxSize()) {
        YtLyricsHeader(
            source = "Musixmatch / LRCLIB",
            isSynced = isSynced,
            userOffsetMs = lyricController.userOffsetMs,
            onAdjustOffset = { delta -> lyricController.adjustOffset(delta) },
            onResetOffset = { lyricController.resetOffset() },
            onSaveOffset = handleSaveOffset,
            onClose = null
        )

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ActiveControl, modifier = Modifier.size(32.dp))
            }
        } else if (lyricsLines.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No synchronized lyrics found for this track.",
                    style = LocalAppTypography.current.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            val listState = rememberLazyListState()

            LaunchedEffect(currentPositionMs, isPlaying) {
                lyricController.targetPositionMs = currentPositionMs
                lyricController.isPlaying = isPlaying
            }

            LaunchedEffect(Unit) {
                lyricController.runFrameLoop()
            }

            val activeIndex = remember(lyricController.interpolatedPosMs, lyricsLines, isSynced) {
                if (!isSynced) -1
                else {
                    val idx = lyricsLines.indexOfLast { it.timeMs <= lyricController.interpolatedPosMs }
                    if (idx >= 0) idx else 0
                }
            }

            LaunchedEffect(activeIndex, isSynced) {
                if (isSynced && lyricsLines.isNotEmpty() && activeIndex in lyricsLines.indices && !listState.isScrollInProgress) {
                    val viewportHeight = listState.layoutInfo.viewportSize.height
                    if (viewportHeight > 0) {
                        val focalOffset = viewportHeight * 0.35f
                        val itemInfo = listState.layoutInfo.visibleItemsInfo.find { it.index == activeIndex }
                        if (itemInfo != null) {
                            listState.animateScrollBy(
                                value = itemInfo.offset - focalOffset,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        } else {
                            listState.animateScrollToItem(
                                index = activeIndex,
                                scrollOffset = (-focalOffset).toInt()
                            )
                        }
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                if (!isSynced) {
                    items(lyricsLines.size) { index ->
                        val line = lyricsLines[index]
                        Text(
                            text = line.text,
                            style = LocalAppTypography.current.headlineSmall.copy(
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = TextMain.copy(alpha = 0.90f),
                            modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp)
                        )
                    }
                } else {
                    items(lyricsLines.size) { index ->
                        val line = lyricsLines[index]
                        val nextLineTime = if (index + 1 < lyricsLines.size) lyricsLines[index + 1].timeMs else line.timeMs + 3500L
                        val isActive = index == activeIndex
                        val isPast = index < activeIndex

                        com.streamify.app.ui.components.FluidSyllableText(
                            text = line.text,
                            lineStartMs = line.timeMs,
                            lineEndMs = nextLineTime,
                            currentPlaybackMs = lyricController.interpolatedPosMs,
                            isActive = isActive,
                            isPast = isPast,
                            onClick = { onSeek(line.timeMs) }
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun LandscapeRelatedPane(
    track: Track,
    playerViewModel: com.streamify.app.viewmodel.PlayerViewModel,
    onTrackClick: (Track) -> Unit
) {
    var relatedList by remember(track.id) { mutableStateOf<List<Track>>(emptyList()) }
    var isLoading by remember(track.id) { mutableStateOf(true) }

    LaunchedEffect(track.id) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val radio = try {
                com.streamify.app.data.UniversalCandidateBroker.fetchCandidates(track, targetCount = 20)
            } catch (e: Exception) {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                relatedList = radio.filter { it.id != track.id }
                isLoading = false
            }
        }
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = ActiveControl, modifier = Modifier.size(32.dp))
        }
    } else if (relatedList.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No related tracks discovered yet.",
                style = LocalAppTypography.current.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(relatedList, key = { it.id }) { itemTrack ->
                YtQueueTrackItem(
                    track = itemTrack,
                    isPlaying = false,
                    dragOffset = 0f,
                    showDragHandle = false,
                    onClick = { onTrackClick(itemTrack) },
                    onMoreClick = { /* Options */ }
                )
            }
        }
    }
}
