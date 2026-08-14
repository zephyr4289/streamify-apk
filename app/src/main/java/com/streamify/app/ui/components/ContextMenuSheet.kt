package com.streamify.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamify.app.data.PlaylistRepository
import com.streamify.app.data.TrackRepository
import com.streamify.app.data.models.Track
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyShapes
import com.streamify.app.ui.theme.StreamifyType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextMenuSheet(
    track: Track,
    onDismissRequest: () -> Unit,
    onLikeClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onAddToQueueClick: () -> Unit,
    modifier: Modifier = Modifier,
    onGoToArtist: ((String) -> Unit)? = null,
    onGoToAlbum: ((String) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showEditDialog by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    val playlists by PlaylistRepository.playlists.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = StreamifyColors.BgElevated,
        shape = StreamifyShapes.BottomSheet,
        dragHandle = { BottomSheetDefaults.DragHandle(color = StreamifyColors.TextDimmed) }
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(bottom = StreamifyDimens.SpaceXL) // Navigation bar padding
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(StreamifyDimens.SpaceLG),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = track.coverArtPath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(StreamifyShapes.CardShape)
                )
                Spacer(modifier = Modifier.width(StreamifyDimens.SpaceMD))
                Column {
                    Text(
                        text = track.title,
                        style = StreamifyType.TitleLarge,
                        color = StreamifyColors.TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        style = StreamifyType.BodyMedium,
                        color = StreamifyColors.TextSub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(color = StreamifyColors.Divider, thickness = 1.dp)

            // Actions
            ContextActionItem(
                icon = Icons.Filled.Favorite,
                text = if (track.isLiked) "Remove from Liked Songs" else "Like",
                iconTint = if (track.isLiked) StreamifyColors.Primary else StreamifyColors.TextSub,
                onClick = {
                    onLikeClick()
                    onDismissRequest()
                }
            )
            ContextActionItem(
                icon = Icons.Filled.Add,
                text = "Add to Playlist",
                onClick = {
                    showPlaylistDialog = true
                }
            )
            ContextActionItem(
                icon = Icons.Filled.QueueMusic,
                text = "Add to Queue",
                onClick = {
                    onAddToQueueClick()
                    onDismissRequest()
                }
            )
            val context = androidx.compose.ui.platform.LocalContext.current

            ContextActionItem(
                icon = Icons.Filled.Download,
                text = "Download",
                onClick = {
                    com.streamify.app.viewmodel.IngestionViewModel.enqueueDownloadDirect(
                        context = context,
                        url = if (track.filepath.startsWith("http")) track.filepath else "https://www.youtube.com/watch?v=${track.id}",
                        title = track.title,
                        artist = track.artist,
                        album = track.album.ifBlank { "Streamify" }
                    )
                    android.widget.Toast.makeText(context, "Download queued: ${track.title}", android.widget.Toast.LENGTH_SHORT).show()
                    onDismissRequest()
                }
            )
            if (onGoToArtist != null && track.artist.isNotBlank() && track.artist != "Unknown Artist") {
                ContextActionItem(
                    icon = Icons.Filled.Person,
                    text = "Go to Artist",
                    onClick = {
                        onGoToArtist(track.artist)
                        onDismissRequest()
                    }
                )
            }
            if (onGoToAlbum != null && track.album.isNotBlank() && track.album != "Single" && track.album != "Local Import") {
                ContextActionItem(
                    icon = Icons.Filled.Album,
                    text = "Go to Album",
                    onClick = {
                        onGoToAlbum(track.album)
                        onDismissRequest()
                    }
                )
            }
            ContextActionItem(
                icon = Icons.Filled.Share,
                text = "Share",
                onClick = {
                    com.streamify.app.util.TrackShareCard.shareTrack(context, track)
                    onDismissRequest()
                }
            )
            ContextActionItem(
                icon = Icons.Filled.Edit,
                text = "Edit Info",
                onClick = {
                    showEditDialog = true
                }
            )
        }
    }

    if (showEditDialog) {
        var editTitle by remember { mutableStateOf(track.title) }
        var editArtist by remember { mutableStateOf(track.artist) }
        var editAlbum by remember { mutableStateOf(track.album) }
        val coroutineScope = rememberCoroutineScope()

        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Info", color = StreamifyColors.TextMain) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Title") },
                        colors = TextFieldDefaults.colors(focusedContainerColor = StreamifyColors.BgElevated, unfocusedContainerColor = StreamifyColors.BgElevated)
                    )
                    TextField(
                        value = editArtist,
                        onValueChange = { editArtist = it },
                        label = { Text("Artist") },
                        colors = TextFieldDefaults.colors(focusedContainerColor = StreamifyColors.BgElevated, unfocusedContainerColor = StreamifyColors.BgElevated)
                    )
                    TextField(
                        value = editAlbum,
                        onValueChange = { editAlbum = it },
                        label = { Text("Album") },
                        colors = TextFieldDefaults.colors(focusedContainerColor = StreamifyColors.BgElevated, unfocusedContainerColor = StreamifyColors.BgElevated)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            TrackRepository.updateTrackMetadata(track.id, editTitle, editArtist, editAlbum)
                            showEditDialog = false
                            onDismissRequest()
                        }
                    }
                ) { Text("Save", color = StreamifyColors.Primary) }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("Cancel", color = StreamifyColors.TextSub) }
            },
            containerColor = StreamifyColors.BgCard
        )
    }

    if (showPlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showPlaylistDialog = false },
            title = { Text("Add to Playlist", color = StreamifyColors.TextMain) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(onClick = {
                        showPlaylistDialog = false
                        showCreatePlaylistDialog = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "Create", tint = StreamifyColors.Primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Create New Playlist", color = StreamifyColors.Primary)
                    }
                    HorizontalDivider(color = StreamifyColors.Divider)
                    if (playlists.isEmpty()) {
                        Text("No playlists yet.", color = StreamifyColors.TextSub)
                    } else {
                        LazyColumn {
                            items(playlists) { playlist ->
                                Text(
                                    text = playlist.name,
                                    color = StreamifyColors.TextMain,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            PlaylistRepository.addTrackToPlaylist(playlist.id, track.id)
                                            showPlaylistDialog = false
                                            onDismissRequest()
                                        }
                                        .padding(vertical = 12.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlaylistDialog = false }) { Text("Cancel", color = StreamifyColors.TextSub) }
            },
            containerColor = StreamifyColors.BgCard
        )
    }

    if (showCreatePlaylistDialog) {
        var playlistName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = { Text("New Playlist", color = StreamifyColors.TextMain) },
            text = {
                TextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    label = { Text("Playlist Name") },
                    colors = TextFieldDefaults.colors(focusedContainerColor = StreamifyColors.BgElevated, unfocusedContainerColor = StreamifyColors.BgElevated)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (playlistName.isNotBlank()) {
                            PlaylistRepository.createPlaylist(playlistName)
                            showCreatePlaylistDialog = false
                            showPlaylistDialog = true
                        }
                    }
                ) { Text("Create", color = StreamifyColors.Primary) }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistDialog = false }) { Text("Cancel", color = StreamifyColors.TextSub) }
            },
            containerColor = StreamifyColors.BgCard
        )
    }
}

@Composable
private fun ContextActionItem(
    icon: ImageVector,
    text: String,
    iconTint: androidx.compose.ui.graphics.Color = StreamifyColors.TextSub,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = StreamifyDimens.SpaceLG,
                vertical = StreamifyDimens.SpaceMD
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(StreamifyDimens.SpaceLG))
        Text(
            text = text,
            style = StreamifyType.TitleMedium,
            color = StreamifyColors.TextMain
        )
    }
}

