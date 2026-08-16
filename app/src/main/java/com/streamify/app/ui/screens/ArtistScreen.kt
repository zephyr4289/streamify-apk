package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
fun ArtistScreen(
    artistName: String,
    allTracks: List<Track>,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit
) {
    val artistTracks = remember(artistName, allTracks) {
        allTracks.filter { it.artist.contains(artistName, ignoreCase = true) }
    }
    val currentTrack by playerViewModel.currentTrack.collectAsState()
    var selectedOptionsTrack by remember { mutableStateOf<Track?>(null) }

    val firstTrackWithCover = artistTracks.find { !it.coverArtPath.isNullOrBlank() }

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
                text = "Artist",
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
                            .size(160.dp)
                            .clip(CircleShape)
                            .background(StreamifyColors.BgElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        TrackCoverArt(
                            coverArtPath = firstTrackWithCover?.coverArtPath,
                            title = "",
                            artist = artistName,
                            modifier = Modifier.fillMaxSize(),
                            shape = CircleShape
                        )
                    }

                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))

                    Text(
                        text = artistName,
                        style = StreamifyType.HeadlineLarge,
                        color = StreamifyColors.TextMain
                    )

                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceXS))

                    Text(
                        text = "${artistTracks.size} tracks available",
                        style = StreamifyType.BodyMedium,
                        color = StreamifyColors.TextSub
                    )

                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))

                    // Play & Shuffle Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                if (artistTracks.isNotEmpty()) {
                                    onTrackClick(artistTracks.first(), artistTracks)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = StreamifyColors.Primary),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.Black)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Play", style = StreamifyType.TitleSmall, color = Color.Black)
                        }

                        Spacer(modifier = Modifier.width(StreamifyDimens.SpaceMD))

                        OutlinedButton(
                            onClick = {
                                if (artistTracks.isNotEmpty()) {
                                    val shuffled = artistTracks.shuffled()
                                    onTrackClick(shuffled.first(), shuffled)
                                }
                            },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StreamifyColors.TextMain),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(Icons.Filled.Shuffle, contentDescription = null, tint = StreamifyColors.TextMain)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Shuffle", style = StreamifyType.TitleSmall, color = StreamifyColors.TextMain)
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Songs",
                    style = StreamifyType.TitleMedium,
                    color = StreamifyColors.TextMain,
                    modifier = Modifier.padding(horizontal = StreamifyDimens.SpaceLG, vertical = StreamifyDimens.SpaceMD)
                )
            }

            items(artistTracks, key = { it.id }) { track ->
                SwipeableTrackListItem(
                    track = track,
                    isPlaying = currentTrack?.id == track.id,
                    onClick = { onTrackClick(track, artistTracks) },
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
            onPlayNextClick = {
                playerViewModel.playNext(track)
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
