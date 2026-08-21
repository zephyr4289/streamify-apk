package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.data.PlaylistRepository
import com.streamify.app.data.models.Track
import com.streamify.app.ui.components.ContextMenuSheet
import com.streamify.app.ui.components.LocalContextMenuController
import com.streamify.app.ui.components.MenuOrigin
import com.streamify.app.ui.components.StreamifyPullToRefreshContainer
import com.streamify.app.ui.components.YtPlaylistHeroHeader
import com.streamify.app.ui.components.YtQueueTrackItem
import com.streamify.app.ui.theme.*
import com.streamify.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    playlistName: String,
    playlistDescription: String,
    playlistTracks: List<Track>,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit
) {
    var isRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    StreamifyPullToRefreshContainer(
        isRefreshing = isRefreshing,
        onRefresh = {
            coroutineScope.launch {
                isRefreshing = true
                try {
                    PlaylistRepository.refresh()
                } catch (e: Exception) {}
                delay(400)
                isRefreshing = false
            }
        }
    ) {
        AlbumScreen(
            albumName = playlistName,
            allTracks = playlistTracks,
            playerViewModel = playerViewModel,
            onBack = onBack,
            onTrackClick = onTrackClick,
            explicitTracks = playlistTracks,
            headerTitle = playlistName,
            headerSubtitle = if (playlistDescription.isNotBlank()) playlistDescription else "${playlistTracks.size} songs",
            playlistId = playlistId
        )
    }
}

@Composable
fun AlbumScreen(
    albumName: String,
    allTracks: List<Track>,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit,
    explicitTracks: List<Track>? = null,
    headerTitle: String? = null,
    headerSubtitle: String? = null,
    playlistId: String? = null
) {
    val context = LocalContext.current
    val albumTracks = remember(albumName, allTracks, explicitTracks) {
        explicitTracks ?: allTracks.filter { it.album.equals(albumName, ignoreCase = true) }
    }
    val displayTitle = headerTitle ?: albumName
    val currentTrack by playerViewModel.currentTrack.collectAsState()
    val contextMenuController = LocalContextMenuController.current
    var isRadioDiscoveryMode by rememberSaveable { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(displayTitle) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    val firstTrack = albumTracks.firstOrNull()

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 120fps GPU Parallax Calculator
    val scrollOffset by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex == 0) {
                listState.firstVisibleItemScrollOffset.toFloat()
            } else {
                1000f // Fully collapsed
            }
        }
    }

    if (showRenameDialog && playlistId != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Playlist", color = TextMain, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Playlist Name") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = BorderChip,
                        cursorColor = Primary,
                        focusedTextColor = TextMain,
                        unfocusedTextColor = TextMain
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) {
                        PlaylistRepository.renamePlaylist(playlistId, renameText)
                    }
                    showRenameDialog = false
                }) {
                    Text("Save", color = Primary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = BgSurfaceElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showDeleteConfirmDialog && playlistId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Playlist?", color = TextMain, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = {
                Text(
                    "Are you sure you want to delete \"$displayTitle\"? Songs will remain in your library.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    PlaylistRepository.deletePlaylist(playlistId)
                    showDeleteConfirmDialog = false
                    onBack()
                }) {
                    Text("Delete", color = androidx.compose.ui.graphics.Color(0xFFFF453A), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = BgSurfaceElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .statusBarsPadding()
    ) {
        // Top Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextMain,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (explicitTracks != null) "Playlist" else "Album",
                    style = LocalAppTypography.current.headlineMedium.copy(fontSize = 18.sp),
                    color = TextMain
                )
            }

            if (playlistId != null && playlistId != "liked_songs") {
                Box {
                    IconButton(onClick = { showOptionsMenu = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "Playlist Options",
                            tint = TextMain,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showOptionsMenu,
                        onDismissRequest = { showOptionsMenu = false },
                        modifier = Modifier.background(BgSurfaceElevated)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename Playlist", color = TextMain) },
                            onClick = {
                                showOptionsMenu = false
                                renameText = displayTitle
                                showRenameDialog = true
                            },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null, tint = TextMain) }
                        )
                        DropdownMenuItem(
                            text = { Text("Export to M3U8", color = TextMain) },
                            onClick = {
                                showOptionsMenu = false
                                coroutineScope.launch {
                                    val file = PlaylistRepository.exportPlaylistToM3U8(playlistId, albumTracks, context)
                                    if (file != null) {
                                        android.widget.Toast.makeText(context, "Exported: ${file.name}", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null, tint = TextMain) }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete Playlist", color = androidx.compose.ui.graphics.Color(0xFFFF453A)) },
                            onClick = {
                                showOptionsMenu = false
                                showDeleteConfirmDialog = true
                            },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = androidx.compose.ui.graphics.Color(0xFFFF453A)) }
                        )
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp) // Protect docked player
        ) {
            // 1. Parallax Collapsing Hero Header
            item(key = "album_hero_header", contentType = "heroHeader") {
                val displaySubtitle = headerSubtitle ?: "Album • ${firstTrack?.artist ?: "Unknown Artist"} • ${albumTracks.size} songs"
                YtPlaylistHeroHeader(
                    title = displayTitle,
                    subtitle = displaySubtitle,
                    artworkUrl = firstTrack?.coverArtPath,
                    scrollOffset = scrollOffset,
                    isRadioDiscoveryMode = isRadioDiscoveryMode,
                    onToggleRadioDiscoveryMode = { isRadioDiscoveryMode = !isRadioDiscoveryMode },
                    onPlay = {
                        if (albumTracks.isNotEmpty()) {
                            if (isRadioDiscoveryMode) {
                                playerViewModel.playTrack(albumTracks.first(), listOf(albumTracks.first()), autoHydrateRadio = true)
                            } else {
                                onTrackClick(albumTracks.first(), albumTracks)
                            }
                        }
                    },
                    onShuffle = {
                        if (albumTracks.isNotEmpty()) {
                            val shuffled = albumTracks.shuffled()
                            if (isRadioDiscoveryMode) {
                                playerViewModel.playTrack(shuffled.first(), listOf(shuffled.first()), autoHydrateRadio = true)
                            } else {
                                onTrackClick(shuffled.first(), shuffled)
                            }
                        }
                    },
                    onExportM3u = {
                        CoroutineScope(Dispatchers.Main).launch {
                            val file = PlaylistRepository.exportPlaylistToM3U8("album_$albumName", albumTracks, context)
                            if (file != null) {
                                android.widget.Toast.makeText(context, "Exported: ${file.name}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
            }

            // 2. Tracklist Items
            items(
                items = albumTracks,
                key = { "album_track_${it.id}" },
                contentType = { "trackRow" }
            ) { track ->
                YtQueueTrackItem(
                    track = track,
                    isPlaying = currentTrack?.id == track.id,
                    showDragHandle = false,
                    onClick = {
                        if (isRadioDiscoveryMode) {
                            playerViewModel.playTrack(track, listOf(track), autoHydrateRadio = true)
                        } else {
                            onTrackClick(track, albumTracks)
                        }
                    },
                    onMoreClick = {
                        contextMenuController.show(
                            track = track,
                            origin = if (playlistId != null) com.streamify.app.ui.components.MenuOrigin.PLAYLIST else com.streamify.app.ui.components.MenuOrigin.HOME,
                            playlistId = playlistId
                        )
                    }
                )
            }
        }
    }
}
