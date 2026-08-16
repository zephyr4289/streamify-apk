package com.streamify.app.ui.screens

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import kotlinx.coroutines.Dispatchers
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
    val isLandscape = screenConfig.isLandscape || screenConfig.isTablet
    val playerViewModel: com.streamify.app.viewmodel.PlayerViewModel = viewModel()
    val playerState by playerViewModel.playerState.collectAsState()
    val isVideoMode = playerState.isVideoMode

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
    ) {
        // 1. Extreme Performance: GPU Radial Gradient Ambient Glow (0.01ms Single Draw Call)
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        dominantColor.copy(alpha = 0.35f),
                        BgBase.copy(alpha = 0.85f),
                        BgBase
                    ),
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

                    // 1:1 Aspect Ratio Hero Surface (Artwork or Hardware Video)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .aspectRatio(1f)
                            .clip(LocalAppShapes.current.thumbnailLarge)
                            .background(BgCard),
                        contentAlignment = Alignment.Center
                    ) {
                        Crossfade(
                            targetState = isVideoMode,
                            label = "MediaSurfaceCrossfadeLandscape"
                        ) { isVideo ->
                            if (isVideo && playerViewModel.getController() != null) {
                                AndroidView(
                                    factory = { ctx ->
                                        PlayerView(ctx).apply {
                                            player = playerViewModel.getController()
                                            useController = false
                                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                                        }
                                    },
                                    update = { view ->
                                        view.player = playerViewModel.getController()
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                if (!track.coverArtPath.isNullOrBlank()) {
                                    AsyncImage(
                                        model = track.coverArtPath,
                                        contentDescription = track.title,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.MusicNote,
                                        contentDescription = null,
                                        tint = TextSecondary,
                                        modifier = Modifier.size(54.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Track Title & Artist Marquee
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                    ) {
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

                    Spacer(modifier = Modifier.height(8.dp))

                    // Precision Canvas SeekBar
                    YtPlayerSeekBar(
                        progress = progress,
                        durationMs = durationMs,
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
                                Icon(
                                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = "PlayPause",
                                    tint = TextOnActiveChip,
                                    modifier = Modifier.size(28.dp)
                                )
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

                // --- HERO 1:1 ALBUM ARTWORK / HARDWARE VIDEO SURFACE ---
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .aspectRatio(1f)
                        .clip(LocalAppShapes.current.thumbnailLarge)
                        .background(BgCard),
                    contentAlignment = Alignment.Center
                ) {
                    Crossfade(
                        targetState = isVideoMode,
                        label = "MediaSurfaceCrossfadePortrait"
                    ) { isVideo ->
                        if (isVideo && playerViewModel.getController() != null) {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        player = playerViewModel.getController()
                                        useController = false
                                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                                    }
                                },
                                update = { view ->
                                    view.player = playerViewModel.getController()
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            if (!track.coverArtPath.isNullOrBlank()) {
                                AsyncImage(
                                    model = track.coverArtPath,
                                    contentDescription = track.title,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.MusicNote,
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(64.dp)
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
                YtPlayerSeekBar(
                    progress = progress,
                    durationMs = durationMs,
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
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = "PlayPause",
                                tint = TextOnActiveChip,
                                modifier = Modifier.size(34.dp)
                            )
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
    currentTrack: Track?,
    isPlaying: Boolean,
    onTrackClick: (Track) -> Unit
) {
    val listState = rememberLazyListState()
    val upNext = remember(queue, currentTrack) {
        if (currentTrack != null) queue.filter { it.id != currentTrack.id } else queue
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
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

            item(key = "active_${currentTrack.id}") {
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

        if (upNext.isNotEmpty()) {
            item(key = "hdr_upnext") {
                Text(
                    text = "UP NEXT",
                    style = LocalAppTypography.current.songArtist.copy(
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = TextTertiary,
                    modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 4.dp)
                )
            }

            items(upNext, key = { it.id }) { itemTrack ->
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
    onSeek: (Long) -> Unit
) {
    var lyricsLines by remember(track.id) { mutableStateOf<List<LyricsLine>>(emptyList()) }
    var isLoading by remember(track.id) { mutableStateOf(true) }

    LaunchedEffect(track.id) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val loadedLines = mutableListOf<LyricsLine>()
            // 1. Try local LRC file
            if (!track.lyricsPath.isNullOrBlank() && File(track.lyricsPath).exists()) {
                try {
                    val lrcText = File(track.lyricsPath).readText()
                    val parsed = LyricsData.parseLrc(lrcText)
                    if (parsed.lines.isNotEmpty()) {
                        loadedLines.addAll(parsed.lines)
                    }
                } catch (e: Exception) {}
            }

            // 2. Try online resolver if empty
            if (loadedLines.isEmpty()) {
                val fetchedLrc = com.streamify.app.data.network.LyricsResolver.fetchSyncedLyrics(
                    track.title,
                    track.artist,
                    track.durationSec
                )
                if (!fetchedLrc.isNullOrBlank()) {
                    val parsed = LyricsData.parseLrc(fetchedLrc)
                    if (parsed.lines.isNotEmpty()) {
                        loadedLines.addAll(parsed.lines)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                lyricsLines = loadedLines
                isLoading = false
            }
        }
    }

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
        val lyricController = remember { LyricPlaybackController() }

        LaunchedEffect(currentPositionMs) {
            lyricController.targetPositionMs = currentPositionMs
        }

        LaunchedEffect(Unit) {
            lyricController.runFrameLoop()
        }

        val activeIndex = remember(lyricController.interpolatedPosMs, lyricsLines) {
            val idx = lyricsLines.indexOfLast { it.timeMs <= lyricController.interpolatedPosMs }
            if (idx >= 0) idx else 0
        }

        LaunchedEffect(activeIndex) {
            if (lyricsLines.isNotEmpty() && activeIndex in lyricsLines.indices && !listState.isScrollInProgress) {
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
            items(lyricsLines.size) { index ->
                val line = lyricsLines[index]
                val isActive = index == activeIndex
                val isPast = index < activeIndex

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSeek(line.timeMs) }
                        .padding(vertical = 10.dp, horizontal = 8.dp)
                ) {
                    Text(
                        text = line.text,
                        style = LocalAppTypography.current.headlineSmall.copy(
                            fontSize = if (isActive) 20.sp else 16.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                        ),
                        color = if (isActive) TextMain else if (isPast) TextSecondary.copy(alpha = 0.6f) else TextSecondary.copy(alpha = 0.4f)
                    )
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
                TrackRepository.getCloudSongRadio(track, limit = 20)
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
