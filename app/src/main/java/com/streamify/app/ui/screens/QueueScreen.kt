package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamify.app.data.models.Track
import com.streamify.app.ui.components.ContextMenuSheet
import com.streamify.app.ui.components.TrackListItem
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.viewmodel.PlayerViewModel

@Composable
fun QueueScreen(
    playerViewModel: PlayerViewModel,
    onTrackClick: (Int) -> Unit
) {
    val playerState by playerViewModel.playerState.collectAsState()
    var selectedOptionsTrack by remember { mutableStateOf<Track?>(null) }
    val nowPlaying = playerState.currentTrack
    val upNext = playerState.queue

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StreamifyColors.BgBase)
            .padding(top = StreamifyDimens.SpaceGiant)
    ) {
        Text(
            text = "Now Playing",
            style = StreamifyType.HeadlineMedium,
            color = StreamifyColors.TextMain,
            modifier = Modifier.padding(horizontal = StreamifyDimens.SpaceLG, vertical = StreamifyDimens.SpaceMD)
        )

        if (nowPlaying != null) {
            TrackListItem(
                track = nowPlaying,
                isPlaying = true,
                onClick = { },
                onOptionsClick = { selectedOptionsTrack = nowPlaying }
            )
        }

        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = StreamifyDimens.SpaceLG, vertical = StreamifyDimens.SpaceMD),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Next In Queue (${upNext.size})",
                style = StreamifyType.HeadlineMedium,
                color = StreamifyColors.TextMain
            )
            if (upNext.isNotEmpty()) {
                androidx.compose.material3.TextButton(onClick = { playerViewModel.clearQueue() }) {
                    Text("Clear", style = StreamifyType.TitleSmall, color = StreamifyColors.TextSub)
                }
            }
        }

        com.streamify.app.ui.components.ReorderableList(
            items = upNext,
            onMove = { from, to -> playerViewModel.reorderQueue(from, to) },
            contentPadding = PaddingValues(bottom = StreamifyDimens.PlayerBarHeight + StreamifyDimens.SpaceXL)
        ) { index, track, isDragging ->
            val elevation = if (isDragging) 8.dp else 0.dp
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (isDragging) StreamifyColors.BgCard else androidx.compose.ui.graphics.Color.Transparent,
                shadowElevation = elevation
            ) {
                com.streamify.app.ui.components.SwipeableTrackListItem(
                    track = track,
                    isPlaying = false,
                    onClick = { onTrackClick(track.id) },
                    onOptionsClick = { selectedOptionsTrack = track },
                    onSwipeQueue = null,
                    onSwipeLike = { playerViewModel.removeFromQueue(track.id) }
                )
            }
        }
    }

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
