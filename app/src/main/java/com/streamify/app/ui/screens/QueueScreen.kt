package com.streamify.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.data.models.Track
import com.streamify.app.ui.components.ContextMenuSheet
import com.streamify.app.ui.components.LocalContextMenuController
import com.streamify.app.ui.components.MenuOrigin
import com.streamify.app.data.UniversalCandidateBroker
import com.streamify.app.radio.OnlineRadioEngine
import com.streamify.app.ui.components.YtQueueHeader
import com.streamify.app.ui.components.YtQueueTrackItem
import com.streamify.app.ui.theme.*
import com.streamify.app.viewmodel.PlayerViewModel

@Composable
fun QueueScreen(
    playerViewModel: PlayerViewModel,
    onTrackClick: (Int) -> Unit,
    onClose: (() -> Unit)? = null
) {
    val playerState by playerViewModel.playerState.collectAsState()
    val radioBuilding by UniversalCandidateBroker.isFetching.collectAsState()
    val radioSummary by OnlineRadioEngine.lastBuildSummary.collectAsState()
    val contextMenuController = LocalContextMenuController.current
    val nowPlaying = playerState.currentTrack
    val queue = playerState.queue
    val currentIndex = playerState.currentIndex
    val listState = rememberLazyListState()

    // 120fps Mathematical Drag Reorder State
    var draggedItemIndex by remember { mutableStateOf<Int?>(null) }
    var draggedItemOffset by remember { mutableStateOf(0f) }
    val itemHeightPx = with(LocalDensity.current) { 56.dp.toPx() }

    // Distinct Index-Aware Queue Slices
    val playedTracks = remember(queue, currentIndex) {
        if (queue.isNotEmpty() && currentIndex > 0) {
            queue.subList(0, currentIndex.coerceAtMost(queue.size))
        } else {
            emptyList()
        }
    }

    val upNext = remember(queue, currentIndex) {
        if (queue.isNotEmpty() && currentIndex in queue.indices && currentIndex + 1 < queue.size) {
            queue.subList(currentIndex + 1, queue.size)
        } else {
            emptyList()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .statusBarsPadding()
    ) {
        // 1. YouTube Music Queue Header
        YtQueueHeader(
            sourceName = if (nowPlaying != null) "${nowPlaying.artist} Radio" else "Library Queue",
            isAutoplayEnabled = playerState.isAutoPlayEnabled,
            onToggleAutoplay = { playerViewModel.toggleAutoPlay() },
            onClearQueue = { playerViewModel.clearQueue() },
            onClose = { onClose?.invoke() ?: run { /* Pop backstack */ } },
            hasQueueItems = upNext.isNotEmpty() || playedTracks.isNotEmpty()
        )

        // Radio build telemetry (OnlineRadioEngine)
        if (radioBuilding || radioSummary != "Idle") {
            androidx.compose.material3.Surface(
                color = if (radioBuilding) StreamifyColors.Primary.copy(alpha = 0.12f)
                        else StreamifyColors.BgSurfaceElevated,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = if (radioBuilding) "◌ Building online radio…" else "◉ $radioSummary",
                    style = LocalAppTypography.current.songArtist.copy(
                        fontSize = 10.sp, letterSpacing = 0.8.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    ),
                    color = if (radioBuilding) StreamifyColors.Primary else StreamifyColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        }

        // 2. Queue LazyColumn
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp) // Protective padding for docked player
        ) {
            // Section 0: Previously Played Tracks
            if (playedTracks.isNotEmpty()) {
                item(key = "header_played") {
                    Text(
                        text = "PLAYED (${playedTracks.size})",
                        style = LocalAppTypography.current.songArtist.copy(
                            fontSize = 11.sp,
                            letterSpacing = 0.5.sp
                        ),
                        color = TextTertiary.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp)
                    )
                }

                itemsIndexed(
                    items = playedTracks,
                    key = { index, track -> "played_${track.id}_${track.filepath.hashCode()}_${index}" },
                    contentType = { _, _ -> "trackRow" }
                ) { index, track ->
                    YtQueueTrackItem(
                        track = track,
                        isPlaying = false,
                        dragOffset = 0f,
                        showDragHandle = false,
                        onClick = {
                            onTrackClick(track.id)
                            playerViewModel.playTrack(track, queue)
                        },
                        onMoreClick = { contextMenuController.show(track, origin = MenuOrigin.QUEUE) }
                    )
                }
            }

            // Section A: Now Playing Active Track
            if (nowPlaying != null) {
                item(key = "header_now_playing") {
                    Text(
                        text = "NOW PLAYING",
                        style = LocalAppTypography.current.songArtist.copy(
                            fontSize = 11.sp,
                            letterSpacing = 0.5.sp
                        ),
                        color = TextTertiary,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp)
                    )
                }

                item(key = "active_${nowPlaying.id}_${nowPlaying.filepath.hashCode()}", contentType = "trackRow") {
                    YtQueueTrackItem(
                        track = nowPlaying,
                        isPlaying = playerState.isPlaying,
                        dragOffset = 0f,
                        showDragHandle = false,
                        onClick = { /* Already playing */ },
                        onMoreClick = { contextMenuController.show(nowPlaying, origin = MenuOrigin.QUEUE) }
                    )
                }
            }

            // Section B: Up Next Queue Items (Guaranteed zero played song repetition)
            if (upNext.isNotEmpty()) {
                item(key = "header_up_next") {
                    Text(
                        text = "UP NEXT (${upNext.size})",
                        style = LocalAppTypography.current.songArtist.copy(
                            fontSize = 11.sp,
                            letterSpacing = 0.5.sp
                        ),
                        color = TextTertiary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp)
                    )
                }

                itemsIndexed(
                    items = upNext,
                    key = { index, track -> "queue_${track.id}_${track.filepath.hashCode()}_${index}" },
                    contentType = { _, _ -> "trackRow" }
                ) { index, track ->
                    val isBeingDragged = draggedItemIndex == index
                    val dragOffset = if (isBeingDragged) draggedItemOffset else 0f

                    YtQueueTrackItem(
                        track = track,
                        isPlaying = false,
                        dragOffset = dragOffset,
                        showDragHandle = true,
                        onDragStart = {
                            draggedItemIndex = index
                            draggedItemOffset = 0f
                            com.streamify.app.util.StreamifyHapticEngine.queueGrab()
                        },
                        onDragMove = { deltaY ->
                            draggedItemOffset += deltaY
                            val targetIndex = (index + (draggedItemOffset / itemHeightPx).toInt())
                                .coerceIn(0, upNext.size - 1)
                            if (targetIndex != index) {
                                val absFrom = currentIndex + 1 + index
                                val absTo = currentIndex + 1 + targetIndex
                                playerViewModel.reorderQueue(absFrom, absTo)
                                draggedItemIndex = targetIndex
                                draggedItemOffset = 0f
                                com.streamify.app.util.StreamifyHapticEngine.magneticDetent()
                            }
                        },
                        onDragEnd = {
                            draggedItemIndex = null
                            draggedItemOffset = 0f
                        },
                        onClick = {
                            onTrackClick(track.id)
                            playerViewModel.playTrack(track, queue)
                        },
                        onMoreClick = { contextMenuController.show(track, origin = MenuOrigin.QUEUE) }
                    )
                }
            } else if (nowPlaying == null) {
                item(key = "empty_queue") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 80.dp),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        Text(
                            text = "No songs in queue",
                            style = LocalAppTypography.current.songArtist,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}
