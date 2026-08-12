package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamify.app.ui.components.RecentPlayCard
import com.streamify.app.ui.components.TrackCard
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.util.TimeGreeting
import com.streamify.app.viewmodel.HomeUiState
import com.streamify.app.viewmodel.HomeViewModel

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel(),
    onTrackClick: (Int) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StreamifyColors.BgBase)
            .padding(horizontal = StreamifyDimens.SpaceLG)
    ) {
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceGiant)) // Status bar + padding

        Text(
            text = TimeGreeting.getGreeting(),
            style = StreamifyType.HeadlineLarge,
            color = StreamifyColors.TextMain
        )
        
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceXL))

        when (val state = uiState) {
            is HomeUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = StreamifyColors.Primary)
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
                                            onClick = { onTrackClick(track.id) },
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
                                items(state.recommendations) { track ->
                                    TrackCard(
                                        track = track,
                                        onClick = { onTrackClick(track.id) }
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
                                items(state.allTracks) { track ->
                                    TrackCard(
                                        track = track,
                                        onClick = { onTrackClick(track.id) }
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
