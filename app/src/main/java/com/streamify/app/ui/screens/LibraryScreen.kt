package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
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
    onTrackClick: (Int, List<com.streamify.app.data.models.Track>) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val downloadTasks by ingestionViewModel.downloadTasks.collectAsState()
    var selectedOptionsTrack by remember { mutableStateOf<Track?>(null) }
    
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.loadLibrary()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                // Profile Avatar could go here
                Spacer(modifier = Modifier.width(StreamifyDimens.SpaceSM))
                Text(
                    text = "Your Library",
                    style = StreamifyType.HeadlineLarge,
                    color = StreamifyColors.TextMain
                )
            }
            Row {
                IconButton(onClick = { /* Search library */ }) {
                    Icon(Icons.Filled.Search, contentDescription = "Search", tint = StreamifyColors.TextMain)
                }
                IconButton(onClick = { /* Add new */ }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add", tint = StreamifyColors.TextMain)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))

        // Filter Chips (Mock)
        Row(
            modifier = Modifier.padding(horizontal = StreamifyDimens.SpaceLG),
            horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceSM)
        ) {
            FilterChip(
                selected = false,
                onClick = { },
                label = { Text("Playlists", color = StreamifyColors.TextMain) },
                colors = FilterChipDefaults.filterChipColors(containerColor = StreamifyColors.BgCard)
            )
            FilterChip(
                selected = false,
                onClick = { },
                label = { Text("Artists", color = StreamifyColors.TextMain) },
                colors = FilterChipDefaults.filterChipColors(containerColor = StreamifyColors.BgCard)
            )
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
                if (state.likedTracks.isEmpty()) {
                    EmptyStateView(
                        title = "Nothing to see here",
                        subtitle = "Tracks you like will appear here",
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = StreamifyDimens.PlayerBarHeight + StreamifyDimens.SpaceXL)
                    ) {
                        items(state.likedTracks) { track ->
                            TrackListItem(
                                track = track,
                                onClick = { onTrackClick(track.id, (uiState as? LibraryUiState.Success)?.likedTracks ?: emptyList()) },
                                onOptionsClick = { selectedOptionsTrack = track }
                            )
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
