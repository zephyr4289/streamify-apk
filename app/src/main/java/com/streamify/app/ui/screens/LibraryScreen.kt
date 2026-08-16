package com.streamify.app.ui.screens

import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamify.app.data.PlaylistRepository
import com.streamify.app.data.models.Track
import com.streamify.app.data.remote.SupabaseClient
import com.streamify.app.ui.components.*
import com.streamify.app.ui.theme.*
import com.streamify.app.viewmodel.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun LibraryScreen(
    playerViewModel: PlayerViewModel,
    viewModel: LibraryViewModel = viewModel(),
    ingestionViewModel: IngestionViewModel = viewModel(),
    onTrackClick: (Track, List<Track>) -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val downloadTasks by ingestionViewModel.downloadTasks.collectAsState()
    val currentTrack by playerViewModel.currentTrack.collectAsState()
    val playlists by PlaylistRepository.playlists.collectAsState()
    val user by SupabaseClient.currentUser.collectAsState()

    var selectedFilter by remember { mutableStateOf("Playlists") }
    var isGridView by remember { mutableStateOf(false) }
    var selectedPlaylistId by remember { mutableStateOf<String?>(null) }
    var selectedAlbumName by remember { mutableStateOf<String?>(null) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var selectedOptionsTrack by remember { mutableStateOf<Track?>(null) }

    var showSpotifyImportDialog by remember { mutableStateOf(false) }
    var spotifyUrlInput by remember { mutableStateOf("") }
    val searchViewModel: SearchViewModel = viewModel()

    val jsonFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            searchViewModel.importLocalPlaylistJson(uri, ingestionViewModel, context)
            showSpotifyImportDialog = false
        }
    }

    LaunchedEffect(context) {
        ingestionViewModel.observeDownloads(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.loadLibrary()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.loadLibrary()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showSpotifyImportDialog) {
        AlertDialog(
            onDismissRequest = { showSpotifyImportDialog = false },
            title = { Text("Import Playlist", color = TextMain, style = LocalAppTypography.current.headlineMedium) },
            text = {
                Column {
                    OutlinedTextField(
                        value = spotifyUrlInput,
                        onValueChange = { spotifyUrlInput = it },
                        label = { Text("Spotify Playlist / Track URL", color = TextSecondary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextMain,
                            unfocusedTextColor = TextSecondary,
                            focusedBorderColor = Primary,
                            cursorColor = Primary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = BgSurfaceElevated)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { jsonFilePickerLauncher.launch("application/json") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Primary)
                    ) {
                        Text("Import Local JSON File")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    searchViewModel.importSpotifyPlaylist(spotifyUrlInput, ingestionViewModel, context)
                    showSpotifyImportDialog = false
                    spotifyUrlInput = ""
                }) {
                    Text("Import", color = Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSpotifyImportDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = BgSurfaceElevated
        )
    }

    // Detail Views (Album or Playlist opened)
    if (selectedAlbumName != null) {
        val allTracks = (uiState as? LibraryUiState.Success)?.tracks ?: emptyList()
        AlbumScreen(
            albumName = selectedAlbumName!!,
            allTracks = allTracks,
            playerViewModel = playerViewModel,
            onBack = { selectedAlbumName = null },
            onTrackClick = onTrackClick
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .statusBarsPadding()
    ) {
        // 1. YouTube Music Library Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Library",
                style = LocalAppTypography.current.headlineLarge.copy(fontSize = 24.sp),
                color = TextMain
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showSpotifyImportDialog = true }) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = "Import Spotify",
                        tint = TextMain,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = { enqueueMediaScan(context) }) {
                    Icon(
                        imageVector = Icons.Filled.Sync,
                        contentDescription = "Rescan Storage",
                        tint = TextMain,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // 2. YouTube Music Filter Chips Rail
        YtLibraryFilterChips(
            selectedFilter = selectedFilter,
            onFilterSelected = {
                selectedFilter = it
                selectedFolder = null
                selectedPlaylistId = null
            }
        )

        // 3. Sort & View Mode Selector
        YtSortFilterBar(
            sortLabel = "Recent activity",
            isGridView = isGridView,
            onSortClick = { /* Handle sort */ },
            onToggleView = { isGridView = !isGridView }
        )

        when (val state = uiState) {
            is LibraryUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Primary, strokeWidth = 3.dp)
                }
            }
            is LibraryUiState.Error -> {
                EmptyStateView(
                    title = "Something went wrong",
                    subtitle = state.message,
                    actionText = "Retry",
                    onActionClick = { viewModel.loadLibrary() }
                )
            }
            is LibraryUiState.Success -> {
                val allTracks = state.tracks
                val likedTracks = state.likedTracks

                val albums = remember(allTracks) {
                    allTracks.groupBy { it.album.ifBlank { "Unknown Album" } }
                }
                val artists = remember(allTracks) {
                    allTracks.groupBy { it.artist.ifBlank { "Unknown Artist" } }
                }
                val downloaded = remember(allTracks) {
                    allTracks.filter { it.filepath.isNotBlank() && it.source.contains("download", ignoreCase = true) }
                }
                val folders = remember(allTracks) {
                    allTracks.filter { it.filepath.isNotBlank() }.groupBy {
                        val path = it.filepath
                        val lastSlash = path.lastIndexOf('/')
                        if (lastSlash != -1) path.substring(0, lastSlash) else "Unknown Folder"
                    }
                }

                val screenConfig = LocalScreenConfiguration.current
                val gridColumns = remember(screenConfig.widthDp) {
                    ((screenConfig.widthDp.value / 158f).toInt()).coerceAtLeast(2)
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp)
                ) {
                    when (selectedFilter) {
                        "Playlists" -> {
                            // Pinned Liked Music Hero Card
                            item(key = "pinned_liked_music", contentType = "likedPin") {
                                YtLikedMusicCard(
                                    trackCount = likedTracks.size,
                                    onClick = {
                                        if (likedTracks.isNotEmpty()) {
                                            onTrackClick(likedTracks.first(), likedTracks)
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }

                            if (!isGridView) {
                                items(
                                    items = playlists,
                                    key = { "playlist_${it.id}" },
                                    contentType = { "playlistRow" }
                                ) { playlist ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(56.dp)
                                            .clickable { selectedPlaylistId = playlist.id }
                                            .padding(horizontal = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(BgSurfaceElevated),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.QueueMusic,
                                                contentDescription = "Playlist",
                                                tint = Primary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = playlist.name,
                                                style = LocalAppTypography.current.songTitle.copy(fontSize = 14.sp),
                                                color = TextMain
                                            )
                                            Text(
                                                text = "Playlist • ${playlist.trackIds.size} songs",
                                                style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                                color = TextSecondary
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                CoroutineScope(Dispatchers.Main).launch {
                                                    val file = PlaylistRepository.exportPlaylistToM3U8(playlist.id, allTracks, context)
                                                    if (file != null) {
                                                        Toast.makeText(context, "Exported: ${file.name}", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Share,
                                                contentDescription = "Export",
                                                tint = TextSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            } else {
                                // Responsive Dynamic-Column Grid inside LazyColumn
                                items(
                                    items = playlists.chunked(gridColumns),
                                    key = { "row_${it.first().id}" },
                                    contentType = { "playlistGridRow" }
                                ) { rowPlaylists ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        rowPlaylists.forEach { playlist ->
                                            val playlistTracks = allTracks.filter { it.playlistId == playlist.id }
                                            val firstTrack = playlistTracks.firstOrNull()
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable { selectedPlaylistId = playlist.id }
                                            ) {
                                                Column {
                                                    YtThumbnail(
                                                        url = playlist.customCoverPath ?: firstTrack?.coverArtPath,
                                                        size = 150.dp,
                                                        cornerRadius = 6.dp,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .aspectRatio(1f)
                                                    )
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    Text(
                                                        text = playlist.name,
                                                        style = LocalAppTypography.current.songTitle.copy(fontSize = 13.sp),
                                                        color = TextMain,
                                                        maxLines = 1
                                                    )
                                                    Text(
                                                        text = "Playlist • ${playlistTracks.size} tracks",
                                                        style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
                                                        color = TextSecondary,
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                        }
                                        if (rowPlaylists.size < gridColumns) {
                                            repeat(gridColumns - rowPlaylists.size) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        "Songs" -> {
                            items(
                                items = allTracks,
                                key = { "song_${it.id}" },
                                contentType = { "trackRow" }
                            ) { track ->
                                YtQueueTrackItem(
                                    track = track,
                                    isPlaying = currentTrack?.id == track.id,
                                    showDragHandle = false,
                                    onClick = { onTrackClick(track, allTracks) },
                                    onMoreClick = { selectedOptionsTrack = track }
                                )
                            }
                        }

                        "Albums" -> {
                            if (!isGridView) {
                                items(
                                    items = albums.keys.toList().sorted(),
                                    key = { "album_$it" },
                                    contentType = { "albumRow" }
                                ) { albumName ->
                                    val tracksInAlbum = albums[albumName] ?: emptyList()
                                    val first = tracksInAlbum.firstOrNull()
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(56.dp)
                                            .clickable { selectedAlbumName = albumName }
                                            .padding(horizontal = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        YtThumbnail(
                                            url = first?.coverArtPath,
                                            size = 48.dp,
                                            cornerRadius = 4.dp
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = albumName,
                                                style = LocalAppTypography.current.songTitle.copy(fontSize = 14.sp),
                                                color = TextMain,
                                                maxLines = 1
                                            )
                                            Text(
                                                text = "Album • ${first?.artist ?: "Unknown"} • ${tracksInAlbum.size} songs",
                                                style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                                color = TextSecondary,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            } else {
                                items(
                                    items = albums.keys.toList().sorted().chunked(gridColumns),
                                    key = { "album_row_${it.first()}" },
                                    contentType = { "albumGridRow" }
                                ) { rowAlbums ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        rowAlbums.forEach { albumName ->
                                            val tracksInAlbum = albums[albumName] ?: emptyList()
                                            val first = tracksInAlbum.firstOrNull()
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable { selectedAlbumName = albumName }
                                            ) {
                                                Column {
                                                    YtThumbnail(
                                                        url = first?.coverArtPath,
                                                        size = 150.dp,
                                                        cornerRadius = 6.dp,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .aspectRatio(1f)
                                                    )
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    Text(
                                                        text = albumName,
                                                        style = LocalAppTypography.current.songTitle.copy(fontSize = 13.sp),
                                                        color = TextMain,
                                                        maxLines = 1
                                                    )
                                                    Text(
                                                        text = first?.artist ?: "Unknown Artist",
                                                        style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
                                                        color = TextSecondary,
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                        }
                                        if (rowAlbums.size < gridColumns) {
                                            repeat(gridColumns - rowAlbums.size) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        "Artists" -> {
                            items(
                                items = artists.keys.toList().sorted(),
                                key = { "artist_$it" },
                                contentType = { "artistRow" }
                            ) { artistName ->
                                val tracksByArtist = artists[artistName] ?: emptyList()
                                val first = tracksByArtist.firstOrNull()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .clickable {
                                            if (tracksByArtist.isNotEmpty()) {
                                                onTrackClick(tracksByArtist.first(), tracksByArtist)
                                            }
                                        }
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(BgSurfaceElevated),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        YtThumbnail(
                                            url = first?.coverArtPath,
                                            size = 48.dp,
                                            cornerRadius = 24.dp
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = artistName,
                                            style = LocalAppTypography.current.songTitle.copy(fontSize = 14.sp),
                                            color = TextMain,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "Artist • ${tracksByArtist.size} songs",
                                            style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        }

                        "Downloads" -> {
                            items(
                                items = downloaded,
                                key = { "download_${it.id}" },
                                contentType = { "trackRow" }
                            ) { track ->
                                YtQueueTrackItem(
                                    track = track,
                                    isPlaying = currentTrack?.id == track.id,
                                    showDragHandle = false,
                                    onClick = { onTrackClick(track, downloaded) },
                                    onMoreClick = { selectedOptionsTrack = track }
                                )
                            }
                        }

                        "Device files" -> {
                            items(
                                items = folders.keys.toList().sorted(),
                                key = { "folder_$it" },
                                contentType = { "folderRow" }
                            ) { folderPath ->
                                val count = folders[folderPath]?.size ?: 0
                                val folderName = folderPath.substringAfterLast("/")
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .clickable { selectedFolder = folderPath }
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(BgSurfaceElevated),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Folder,
                                            contentDescription = "Folder",
                                            tint = Primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = folderName,
                                            style = LocalAppTypography.current.songTitle.copy(fontSize = 14.sp),
                                            color = TextMain,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "$count songs • $folderPath",
                                            style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                            color = TextSecondary,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
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

private fun enqueueMediaScan(context: android.content.Context) {
    val workManager = androidx.work.WorkManager.getInstance(context)
    val scanRequest = androidx.work.OneTimeWorkRequestBuilder<com.streamify.app.service.IngestionWorker>()
        .addTag("ingestion_worker")
        .build()
    workManager.enqueueUniqueWork("media_scan", androidx.work.ExistingWorkPolicy.REPLACE, scanRequest)
}
