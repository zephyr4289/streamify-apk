package com.streamify.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamify.app.data.TrackRepository
import com.streamify.app.data.models.Track
import com.streamify.app.ui.theme.*
import com.streamify.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelatedDiscoverSheet(
    track: Track?,
    playerViewModel: PlayerViewModel,
    onTrackClick: (Track) -> Unit,
    onDismiss: () -> Unit
) {
    if (track == null) return

    val scope = rememberCoroutineScope()
    var relatedTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var artistTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    val allTracks by TrackRepository.allTracks.collectAsState()

    LaunchedEffect(track) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val radio = try {
                TrackRepository.getCloudSongRadio(track, limit = 20)
            } catch (e: Exception) {
                emptyList()
            }

            val moreByArtist = allTracks.filter {
                it.artist.contains(track.artist, ignoreCase = true) && it.id != track.id
            }.take(10)

            withContext(Dispatchers.Main) {
                relatedTracks = radio.filter { it.id != track.id }
                artistTracks = moreByArtist
                isLoading = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgElevated,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextDimmed) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .padding(horizontal = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Related",
                        style = LocalAppTypography.current.headlineMedium.copy(fontSize = 18.sp),
                        color = TextMain
                    )
                    Text(
                        text = "Discoveries based on ${track.title}",
                        style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextMain)
                }
            }

            // Action: Start Full Radio Mix (Seamless)
            Button(
                onClick = {
                    playerViewModel.startSongRadio(track)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Icon(Icons.Filled.Radio, contentDescription = null, tint = Color.Black)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start ${track.title} Radio", style = LocalAppTypography.current.chipText.copy(fontWeight = FontWeight.Bold), color = Color.Black)
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Primary, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. More from Artist
                    if (artistTracks.isNotEmpty()) {
                        item {
                            Text(
                                text = "More from ${track.artist}",
                                style = LocalAppTypography.current.headlineMedium.copy(fontSize = 15.sp),
                                color = TextMain
                            )
                        }

                        item {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(artistTracks) { artTrack ->
                                    Column(
                                        modifier = Modifier
                                            .width(120.dp)
                                            .clickable {
                                                onTrackClick(artTrack)
                                                onDismiss()
                                            }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(120.dp)
                                                .clip(LocalAppShapes.current.thumbnailMedium)
                                                .background(BgCard)
                                        ) {
                                            AsyncImage(
                                                model = artTrack.coverArtPath,
                                                contentDescription = artTrack.title,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = artTrack.title,
                                            style = LocalAppTypography.current.songTitle.copy(fontSize = 12.sp),
                                            color = TextMain,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = artTrack.artist,
                                            style = LocalAppTypography.current.songArtist.copy(fontSize = 10.sp),
                                            color = TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 2. Similar Songs & Acoustic Radio Matches
                    if (relatedTracks.isNotEmpty()) {
                        item {
                            Text(
                                text = "Similar Songs",
                                style = LocalAppTypography.current.headlineMedium.copy(fontSize = 15.sp),
                                color = TextMain
                            )
                        }

                        items(relatedTracks) { relTrack ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onTrackClick(relTrack)
                                        onDismiss()
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(BgCard)
                                ) {
                                    AsyncImage(
                                        model = relTrack.coverArtPath,
                                        contentDescription = relTrack.title,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = relTrack.title,
                                        style = LocalAppTypography.current.songTitle.copy(fontSize = 13.sp),
                                        color = TextMain,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${relTrack.artist}${if (relTrack.bpm > 0f) " • ${relTrack.bpm.toInt()} BPM" else ""}",
                                        style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
                                        color = TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                IconButton(onClick = {
                                    scope.launch {
                                        playerViewModel.addToQueue(listOf(relTrack))
                                    }
                                }) {
                                    Icon(
                                        imageVector = Icons.Filled.PlaylistAdd,
                                        contentDescription = "Add to Queue",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    } else if (artistTracks.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No related tracks found for this song",
                                    style = LocalAppTypography.current.songArtist,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
