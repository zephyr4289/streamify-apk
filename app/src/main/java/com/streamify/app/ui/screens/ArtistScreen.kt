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
    val contextMenuController = LocalContextMenuController.current

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
                text = artistName,
                style = StreamifyType.HeadlineMedium,
                color = StreamifyColors.TextMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = StreamifyDimens.SpaceGiant * 2)
        ) {
            // Artist Header Hero
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(StreamifyDimens.SpaceMD),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AsyncImage(
                        model = firstTrackWithCover?.coverArtPath,
                        contentDescription = artistName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(180.dp)
                            .clip(CircleShape)
                            .background(StreamifyColors.BgSurfaceElevated)
                    )

                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))

                    Text(
                        text = artistName,
                        style = StreamifyType.HeadlineLarge,
                        color = StreamifyColors.TextMain,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceXS))

                    Text(
                        text = "${artistTracks.size} Songs",
                        style = StreamifyType.BodyMedium,
                        color = StreamifyColors.TextSub
                    )

                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))

                    // Play All & Shuffle Buttons
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
                            shape = CircleShape,
                            modifier = Modifier.padding(end = StreamifyDimens.SpaceSM)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Play All",
                                tint = Color.Black
                            )
                            Spacer(modifier = Modifier.width(StreamifyDimens.SpaceXS))
                            Text("Play", color = Color.Black, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                if (artistTracks.isNotEmpty()) {
                                    val shuffled = artistTracks.shuffled()
                                    onTrackClick(shuffled.first(), shuffled)
                                }
                            },
                            shape = CircleShape,
                            border = BorderStroke(1.dp, StreamifyColors.Primary)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Shuffle,
                                contentDescription = "Shuffle",
                                tint = StreamifyColors.Primary
                            )
                            Spacer(modifier = Modifier.width(StreamifyDimens.SpaceXS))
                            Text("Shuffle", color = StreamifyColors.Primary)
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Popular Songs",
                    style = StreamifyType.TitleLarge,
                    color = StreamifyColors.TextMain,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(
                        horizontal = StreamifyDimens.SpaceMD,
                        vertical = StreamifyDimens.SpaceSM
                    )
                )
            }

            items(artistTracks, key = { it.id }) { track ->
                SwipeableTrackListItem(
                    track = track,
                    isPlaying = currentTrack?.id == track.id,
                    onClick = { onTrackClick(track, artistTracks) },
                    onOptionsClick = { contextMenuController.show(track, origin = MenuOrigin.HOME) },
                    onSwipeQueue = { playerViewModel.addToQueue(track) },
                    onSwipeLike = { playerViewModel.toggleLike(track) }
                )
            }
        }
    }
}
