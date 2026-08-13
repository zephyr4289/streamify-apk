package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamify.app.ui.components.EmptyStateView
import com.streamify.app.ui.components.TrackListItem
import com.streamify.app.ui.components.ContextMenuSheet
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.viewmodel.LibraryUiState
import com.streamify.app.viewmodel.LibraryViewModel
import com.streamify.app.data.models.Track
import com.streamify.app.viewmodel.PlayerViewModel

@Composable
fun LibraryScreen(
    playerViewModel: PlayerViewModel,
    viewModel: LibraryViewModel = viewModel(),
    ingestionViewModel: com.streamify.app.viewmodel.IngestionViewModel = viewModel(),
    onTrackClick: (com.streamify.app.data.models.Track, List<com.streamify.app.data.models.Track>) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val downloadTasks by ingestionViewModel.downloadTasks.collectAsState()
    var selectedOptionsTrack by remember { mutableStateOf<Track?>(null) }
    
    var selectedFilter by remember { mutableStateOf(0) } // 0: All, 1: Liked, 2: Downloads, 3: Folders
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    
    var showSpotifyImportDialog by remember { mutableStateOf(false) }
    var spotifyUrlInput by remember { mutableStateOf("") }
    
    val context = androidx.compose.ui.platform.LocalContext.current
    val searchViewModel = androidx.lifecycle.ViewModelProvider(context as androidx.lifecycle.ViewModelStoreOwner)[com.streamify.app.viewmodel.SearchViewModel::class.java]
    
    val jsonFilePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            searchViewModel.importLocalPlaylistJson(uri, ingestionViewModel, context)
            showSpotifyImportDialog = false
        }
    }
    
    LaunchedEffect(context) {
        ingestionViewModel.observeDownloads(context)
    }
    
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
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
            title = { Text("Import Playlist", color = StreamifyColors.TextMain) },
            text = {
                Column {
                    OutlinedTextField(
                        value = spotifyUrlInput,
                        onValueChange = { spotifyUrlInput = it },
                        label = { Text("Spotify URL") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = StreamifyColors.TextMain,
                            unfocusedTextColor = StreamifyColors.TextSub,
                            focusedBorderColor = StreamifyColors.Primary,
                            cursorColor = StreamifyColors.Primary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Divider(color = StreamifyColors.BgCard)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { jsonFilePickerLauncher.launch("application/json") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StreamifyColors.Primary)
                    ) {
                        Text("Import Local JSON File")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val searchViewModel = androidx.lifecycle.ViewModelProvider(context as androidx.lifecycle.ViewModelStoreOwner)[com.streamify.app.viewmodel.SearchViewModel::class.java]
                    searchViewModel.importSpotifyPlaylist(spotifyUrlInput, ingestionViewModel, context)
                    showSpotifyImportDialog = false
                    spotifyUrlInput = ""
                }) {
                    Text("Import", color = StreamifyColors.Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSpotifyImportDialog = false }) {
                    Text("Cancel", color = StreamifyColors.TextSub)
                }
            },
            containerColor = StreamifyColors.BgCard
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StreamifyColors.BgBase)
    ) {
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceGiant)) // Status bar
        
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = StreamifyDimens.SpaceLG),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.width(StreamifyDimens.SpaceSM))
                Text(
                    text = "Your Library",
                    style = StreamifyType.HeadlineLarge,
                    color = StreamifyColors.TextMain
                )
            }
            Row {
                IconButton(onClick = { showSpotifyImportDialog = true }) {
                    Icon(androidx.compose.material.icons.Icons.Filled.Download, contentDescription = "Import Spotify", tint = StreamifyColors.TextMain)
                }
                IconButton(onClick = { enqueueMediaScan(context) }) {
                    Icon(androidx.compose.material.icons.Icons.Filled.Sync, contentDescription = "Rescan Storage", tint = StreamifyColors.TextMain)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))

        // Filter Chips
        androidx.compose.foundation.lazy.LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = StreamifyDimens.SpaceLG),
            horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceSM)
        ) {
            item {
                FilterChip(
                    selected = selectedFilter == 0,
                    onClick = { selectedFilter = 0; selectedFolder = null },
                    label = { Text("All Songs", color = if (selectedFilter == 0) StreamifyColors.BgBase else StreamifyColors.TextMain) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = StreamifyColors.BgCard,
                        selectedContainerColor = StreamifyColors.Primary
                    )
                )
            }
            item {
                FilterChip(
                    selected = selectedFilter == 1,
                    onClick = { selectedFilter = 1; selectedFolder = null },
                    label = { Text("Liked", color = if (selectedFilter == 1) StreamifyColors.BgBase else StreamifyColors.TextMain) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = StreamifyColors.BgCard,
                        selectedContainerColor = StreamifyColors.Primary
                    )
                )
            }
            item {
                FilterChip(
                    selected = selectedFilter == 2,
                    onClick = { selectedFilter = 2; selectedFolder = null },
                    label = { Text("Downloads", color = if (selectedFilter == 2) StreamifyColors.BgBase else StreamifyColors.TextMain) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = StreamifyColors.BgCard,
                        selectedContainerColor = StreamifyColors.Primary
                    )
                )
            }
            item {
                FilterChip(
                    selected = selectedFilter == 3,
                    onClick = { selectedFilter = 3; selectedFolder = null },
                    label = { Text("Streamify", color = if (selectedFilter == 3) StreamifyColors.BgBase else StreamifyColors.TextMain) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = StreamifyColors.BgCard,
                        selectedContainerColor = StreamifyColors.Primary
                    )
                )
            }
            item {
                FilterChip(
                    selected = selectedFilter == 4,
                    onClick = { selectedFilter = 4; selectedFolder = null },
                    label = { Text("Playlists", color = if (selectedFilter == 4) StreamifyColors.BgBase else StreamifyColors.TextMain) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = StreamifyColors.BgCard,
                        selectedContainerColor = StreamifyColors.Primary
                    )
                )
            }
            item {
                FilterChip(
                    selected = selectedFilter == 5,
                    onClick = { selectedFilter = 5; selectedFolder = null },
                    label = { Text("Folders", color = if (selectedFilter == 5) StreamifyColors.BgBase else StreamifyColors.TextMain) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = StreamifyColors.BgCard,
                        selectedContainerColor = StreamifyColors.Primary
                    )
                )
            }
        }
        
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))
        
        if (downloadTasks.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = StreamifyDimens.SpaceLG)
                    .padding(bottom = StreamifyDimens.SpaceLG),
                colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Active Transfers", style = StreamifyType.TitleSmall, color = StreamifyColors.Primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    downloadTasks.forEach { task ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = task.title, style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain, maxLines = 1, modifier = Modifier.weight(1f))
                            Text(text = task.progress, style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                        }
                    }
                }
            }
        }

        when (val state = uiState) {
            is LibraryUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = StreamifyColors.Primary)
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
                val folders = remember(state.tracks) {
                    state.tracks.filter { it.filepath.isNotBlank() }.groupBy {
                        val path = it.filepath
                        val lastSlash = path.lastIndexOf('/')
                        if (lastSlash != -1) path.substring(0, lastSlash) else "Unknown Folder"
                    }
                }
                
                val playlists by com.streamify.app.data.PlaylistRepository.playlists.collectAsState()
                
                if (selectedFilter == 4 && selectedFolder == null) {
                    if (playlists.isEmpty()) {
                        EmptyStateView(
                            title = "No Playlists",
                            subtitle = "Create playlists to group your favorite songs",
                            actionText = "Add from Context Menu",
                            onActionClick = { }
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = StreamifyDimens.PlayerBarHeight + StreamifyDimens.SpaceXL)
                        ) {
                            items(playlists, key = { it.id }) { playlist ->
                                val trackCount = playlist.trackIds.size
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedFolder = playlist.id }
                                        .padding(horizontal = StreamifyDimens.SpaceLG, vertical = StreamifyDimens.SpaceMD),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Filled.QueueMusic,
                                        contentDescription = "Playlist",
                                        tint = StreamifyColors.Primary,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.width(StreamifyDimens.SpaceMD))
                                    Column {
                                        Text(playlist.name, style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                                        Text("$trackCount tracks", style = StreamifyType.BodyMedium, color = StreamifyColors.TextSub)
                                        if (playlist.description.isNotBlank()) {
                                            Text(playlist.description, style = StreamifyType.Caption, color = StreamifyColors.TextSub, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (selectedFilter == 5 && selectedFolder == null) {
                    if (folders.isEmpty()) {
                        EmptyStateView(
                            title = "No folders found",
                            subtitle = "Scan your device storage to add local music folders",
                            actionText = "Scan Device Storage",
                            onActionClick = { enqueueMediaScan(context) }
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = StreamifyDimens.PlayerBarHeight + StreamifyDimens.SpaceXL)
                        ) {
                            items(folders.keys.toList().sorted(), key = { it }) { folderPath ->
                                val folderName = folderPath.substringAfterLast("/")
                                val trackCount = folders[folderPath]?.size ?: 0
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedFolder = folderPath }
                                        .padding(horizontal = StreamifyDimens.SpaceLG, vertical = StreamifyDimens.SpaceMD),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Folder,
                                        contentDescription = "Folder",
                                        tint = StreamifyColors.Primary,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.width(StreamifyDimens.SpaceMD))
                                    Column {
                                        Text(folderName, style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                                        Text("$trackCount tracks", style = StreamifyType.BodyMedium, color = StreamifyColors.TextSub)
                                        Text(folderPath, style = StreamifyType.Caption, color = StreamifyColors.TextSub, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val displayTracks = when (selectedFilter) {
                        1 -> state.likedTracks
                        2 -> state.tracks.filter { it.filepath.isNotBlank() }
                        3 -> state.tracks.filter { 
                            it.album.equals("Streamify", ignoreCase = true) || 
                            it.source.equals("online", ignoreCase = true) ||
                            it.filepath.contains("Streamify", ignoreCase = true)
                        }
                        4 -> {
                            val p = playlists.find { it.id == selectedFolder }
                            if (p != null) {
                                val tm = state.tracks.associateBy { it.id }
                                p.trackIds.mapNotNull { tm[it] }
                            } else emptyList()
                        }
                        5 -> folders[selectedFolder] ?: emptyList()
                        else -> state.tracks
                    }
                    
                    Column {
                        if ((selectedFilter == 4 || selectedFilter == 5) && selectedFolder != null) {
                            val headerTitle = if (selectedFilter == 4) {
                                playlists.find { it.id == selectedFolder }?.name ?: "Playlist"
                            } else {
                                selectedFolder?.substringAfterLast("/") ?: ""
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedFolder = null }
                                    .padding(horizontal = StreamifyDimens.SpaceLG, vertical = StreamifyDimens.SpaceMD),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = StreamifyColors.TextMain)
                                Spacer(modifier = Modifier.width(StreamifyDimens.SpaceMD))
                                Text(headerTitle, style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                            }
                        }
                        
                        if (displayTracks.isEmpty()) {
                            EmptyStateView(
                                title = when (selectedFilter) {
                                    1 -> "No Liked Songs"
                                    2 -> "No Downloaded Songs"
                                    3 -> "No Streamify Songs"
                                    else -> "No Songs Found"
                                },
                                subtitle = "Songs will appear here once added or downloaded."
                            )
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(bottom = StreamifyDimens.PlayerBarHeight + StreamifyDimens.SpaceXL)
                            ) {
                                items(displayTracks, key = { it.id }) { track ->
                                    TrackListItem(
                                        track = track,
                                        onClick = { onTrackClick(track, displayTracks) },
                                        onOptionsClick = { selectedOptionsTrack = track }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    selectedOptionsTrack?.let { track ->
        ContextMenuSheet(
            track = track,
            onDismissRequest = { selectedOptionsTrack = null },
            onLikeClick = { 
                playerViewModel.toggleLike(track, context)
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

