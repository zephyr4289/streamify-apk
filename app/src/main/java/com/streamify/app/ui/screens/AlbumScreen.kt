package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.streamify.app.data.models.Track
import com.streamify.app.ui.components.SwipeableTrackListItem
import com.streamify.app.ui.components.TrackCoverArt
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.viewmodel.PlayerViewModel

@Composable
fun AlbumScreen(
    albumName: String,
    allTracks: List<Track>,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit
) {
    val albumTracks = remember(albumName, allTracks) {
        allTracks.filter { it.album.equals(albumName, ignoreCase = true) }
    }
    val currentTrack by playerViewModel.currentTrack.collectAsState()
    var selectedOptionsTrack by remember { mutableStateOf<Track?>(null) }

    val firstTrack = albumTracks.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StreamifyColors.BgBase)
    ) {
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceGiant))

        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = StreamifyDimens.SpaceMD),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = StreamifyColors.TextMain
                )
            }
            Spacer(modifier = Modifier.width(StreamifyDimens.SpaceSM))
            Text(
                text = "Album",
                style = StreamifyType.TitleMedium,
                color = StreamifyColors.TextSub
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            // Header
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(StreamifyDimens.SpaceLG),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(StreamifyColors.BgElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        TrackCoverArt(
                            coverArtPath = firstTrack?.coverArtPath,
                            title = albumName,
                            artist = firstTrack?.artist ?: "",
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))

                    Text(
                        text = albumName,
                        style = StreamifyType.HeadlineLarge,
                        color = StreamifyColors.TextMain,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "${firstTrack?.artist ?: "Unknown Artist"} • ${albumTracks.size} songs",
                        style = StreamifyType.BodyMedium,
                        color = StreamifyColors.TextSub
                    )

                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceMD)
                    ) {
                        Button(
                            onClick = {
                                if (albumTracks.isNotEmpty()) {
                                    onTrackClick(albumTracks.first(), albumTracks)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = StreamifyColors.Primary),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.Black)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Play", color = Color.Black, style = StreamifyType.TitleSmall)
                        }

                        IconButton(
                            onClick = {
                                if (albumTracks.isNotEmpty()) {
                                    val shuffled = albumTracks.shuffled()
                                    onTrackClick(shuffled.first(), shuffled)
                                }
                            }
                        ) {
                            Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle", tint = StreamifyColors.TextSub)
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Tracks",
                    style = StreamifyType.TitleMedium,
                    color = StreamifyColors.TextMain,
                    modifier = Modifier.padding(horizontal = StreamifyDimens.SpaceLG, vertical = StreamifyDimens.SpaceMD)
                )
            }

            items(albumTracks, key = { it.id }) { track ->
                SwipeableTrackListItem(
                    track = track,
                    isPlaying = currentTrack?.id == track.id,
                    onClick = { onTrackClick(track, albumTracks) },
                    onOptionsClick = { selectedOptionsTrack = track },
                    onSwipeQueue = { playerViewModel.addToQueue(track) },
                    onSwipeLike = { playerViewModel.toggleLike(track) }
                )
            }
        }
    }

    selectedOptionsTrack?.let { track ->
        com.streamify.app.ui.components.ContextMenuSheet(
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
