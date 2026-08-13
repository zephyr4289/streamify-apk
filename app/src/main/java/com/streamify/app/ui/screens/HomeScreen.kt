package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamify.app.data.models.Track
import com.streamify.app.ui.components.RecentPlayCard
import com.streamify.app.ui.components.TrackCard
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyShapes
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.util.TimeGreeting
import com.streamify.app.viewmodel.HomeUiState
import com.streamify.app.viewmodel.HomeViewModel

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel(),
    playerViewModel: com.streamify.app.viewmodel.PlayerViewModel,
    dominantColor: androidx.compose.ui.graphics.Color = StreamifyColors.BgBase,
    onTrackClick: (Track, List<Track>) -> Unit,
    onSettingsClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.loadData()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Gradient Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            dominantColor.copy(alpha = 0.4f),
                            StreamifyColors.BgBase
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = StreamifyDimens.SpaceLG)
        ) {
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceGiant)) // Status bar + padding

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = TimeGreeting.getGreeting(),
                style = StreamifyType.HeadlineLarge,
                color = StreamifyColors.TextMain
            )
            androidx.compose.material3.IconButton(onClick = onSettingsClick) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = StreamifyColors.TextMain
                )
            }
        }
        
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceXL))

        when (val state = uiState) {
            is HomeUiState.Loading -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Shimmer Recent Grid
                    Column(verticalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceSM)) {
                        repeat(3) {
                            Row(horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceSM)) {
                                com.streamify.app.ui.components.ShimmerPlaceholder(
                                    modifier = Modifier.weight(1f).height(56.dp),
                                    shape = com.streamify.app.ui.theme.StreamifyShapes.CardShape
                                )
                                com.streamify.app.ui.components.ShimmerPlaceholder(
                                    modifier = Modifier.weight(1f).height(56.dp),
                                    shape = com.streamify.app.ui.theme.StreamifyShapes.CardShape
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceXXL))
                    
                    com.streamify.app.ui.components.ShimmerPlaceholder(
                        modifier = Modifier.width(150.dp).height(32.dp),
                        shape = com.streamify.app.ui.theme.StreamifyShapes.CardShape
                    )
                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))
                    
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceLG)) {
                        items(3) {
                            com.streamify.app.ui.components.ShimmerPlaceholder(
                                modifier = Modifier.width(160.dp).height(200.dp),
                                shape = com.streamify.app.ui.theme.StreamifyShapes.CardShape
                            )
                        }
                    }
                }
            }
            is HomeUiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Error: ${state.message}", color = StreamifyColors.ErrorRed)
                }
            }
            is HomeUiState.Success -> {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = StreamifyDimens.PlayerBarHeight + StreamifyDimens.SpaceXL)
                ) {
                    // Recent Grid (2 columns, 6 items)
                    item {
                        val chunked = state.recent.chunked(2)
                        Column(verticalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceSM)) {
                            for (row in chunked) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceSM)
                                ) {
                                    for (track in row) {
                                        RecentPlayCard(
                                            title = track.title,
                                            imageUrl = track.coverArtPath,
                                            onClick = { onTrackClick(track, (uiState as? HomeUiState.Success)?.allTracks ?: emptyList()) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    if (row.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceXXL))
                    }

                    // Recommendations
                    if (state.recommendations.isNotEmpty()) {
                        item {
                            Text(
                                text = "Made For You",
                                style = StreamifyType.HeadlineMedium,
                                color = StreamifyColors.TextMain
                            )
                            Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceLG)
                            ) {
                                items(state.recommendations, key = { it.id }) { track ->
                                    TrackCard(
                                        track = track,
                                        onClick = { onTrackClick(track, (uiState as? HomeUiState.Success)?.allTracks ?: emptyList()) }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(StreamifyDimens.SpaceXXL))
                        }
                    }

                    // All Tracks
                    if (state.allTracks.isNotEmpty()) {
                        item {
                            Text(
                                text = "Your Library",
                                style = StreamifyType.HeadlineMedium,
                                color = StreamifyColors.TextMain
                            )
                            Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceLG)
                            ) {
                                items(state.allTracks, key = { it.id }) { track ->
                                    TrackCard(
                                        track = track,
                                        onClick = { onTrackClick(track, (uiState as? HomeUiState.Success)?.allTracks ?: emptyList()) }
                                    )
                                }
                            }
                        }
                    }

                    if (state.allTracks.isEmpty() && state.recent.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) {
                                com.streamify.app.ui.components.EmptyStateView(
                                    title = "Your library is empty",
                                    subtitle = "Go to the Search tab to find and download some music!"
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
