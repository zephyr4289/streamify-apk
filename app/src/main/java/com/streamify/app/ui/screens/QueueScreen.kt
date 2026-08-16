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
    var selectedOptionsTrack by remember { mutableStateOf<Track?>(null) }

    // --- PILLAR 1: Context Menu Back Trapping ---
    BackHandler(enabled = selectedOptionsTrack != null) {
        selectedOptionsTrack = null
    }
    val nowPlaying = playerState.currentTrack
    val queue = playerState.queue
    val listState = rememberLazyListState()

    // 120fps Mathematical Drag Reorder State
    var draggedItemIndex by remember { mutableStateOf<Int?>(null) }
    var draggedItemOffset by remember { mutableStateOf(0f) }
    val itemHeightPx = with(LocalDensity.current) { 56.dp.toPx() }

    val upNext = remember(queue, nowPlaying) {
        if (nowPlaying != null) queue.filter { it.id != nowPlaying.id } else queue
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
            hasQueueItems = upNext.isNotEmpty()
        )

        // 2. Queue LazyColumn
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp) // Protective padding for docked player
        ) {
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

                item(key = "active_${nowPlaying.id}", contentType = "trackRow") {
                    YtQueueTrackItem(
                        track = nowPlaying,
                        isPlaying = playerState.isPlaying,
                        dragOffset = 0f,
                        showDragHandle = false,
                        onClick = { /* Already playing */ },
                        onMoreClick = { selectedOptionsTrack = nowPlaying }
                    )
                }
            }

            // Section B: Up Next Queue Items
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
                    key = { _, track -> track.id },
                    contentType = { _, _ -> "trackRow" }
                ) { index, track ->
                    val isBeingDragged = draggedItemIndex == index

                    YtQueueTrackItem(
                        track = track,
                        isPlaying = false,
                        dragOffset = if (isBeingDragged) draggedItemOffset else 0f,
                        onDragStart = {
                            draggedItemIndex = index
                            draggedItemOffset = 0f
                        },
                        onDragMove = { dragAmount ->
                            draggedItemOffset += dragAmount
                            // 120fps Mathematical Reorder swap calculation
                            val targetIndex = (index + (draggedItemOffset / itemHeightPx).toInt())
                            if (targetIndex != index && targetIndex in upNext.indices) {
                                val fromRealIndex = queue.indexOfFirst { it.id == track.id }
                                val targetTrack = upNext[targetIndex]
                                val toRealIndex = queue.indexOfFirst { it.id == targetTrack.id }
                                if (fromRealIndex != -1 && toRealIndex != -1) {
                                    playerViewModel.reorderQueue(fromRealIndex, toRealIndex)
                                }
                                draggedItemIndex = targetIndex
                                draggedItemOffset -= (targetIndex - index) * itemHeightPx
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
                        onMoreClick = { selectedOptionsTrack = track }
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

    // Context Options Menu Bottom Sheet
    selectedOptionsTrack?.let { track ->
        ContextMenuSheet(
            track = track,
            onDismissRequest = { selectedOptionsTrack = null },
            onLikeClick = {
                playerViewModel.toggleLike(track)
                selectedOptionsTrack = null
            },
            onAddToPlaylistClick = { selectedOptionsTrack = null },
            onAddToQueueClick = {
                playerViewModel.addToQueue(track)
                selectedOptionsTrack = null
            }
        )
    }
}
