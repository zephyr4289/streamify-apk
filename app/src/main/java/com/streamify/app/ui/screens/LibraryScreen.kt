package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamify.app.ui.components.EmptyStateView
import com.streamify.app.ui.components.TrackListItem
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.viewmodel.LibraryUiState
import com.streamify.app.viewmodel.LibraryViewModel

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = viewModel(),
    onTrackClick: (Int) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

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
                                onClick = { onTrackClick(track.id) },
                                onOptionsClick = { /* Handle options */ }
                            )
                        }
                    }
                }
            }
        }
    }
}
