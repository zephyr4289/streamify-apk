package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamify.app.ui.components.CategoryCard
import com.streamify.app.ui.components.TrackListItem
import com.streamify.app.ui.components.ContextMenuSheet
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyShapes
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.viewmodel.SearchUiState
import com.streamify.app.viewmodel.SearchViewModel

import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.streamify.app.viewmodel.IngestionViewModel

import com.streamify.app.viewmodel.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    playerViewModel: PlayerViewModel,
    viewModel: SearchViewModel = viewModel(),
    ingestionViewModel: IngestionViewModel = viewModel(),
    onTrackClick: (com.streamify.app.data.models.Track, List<com.streamify.app.data.models.Track>) -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var query by remember { mutableStateOf("") }
    var pendingDownloadTrack by remember { mutableStateOf<com.streamify.app.viewmodel.OnlineSearchResult?>(null) }
    var selectedOptionsTrack by remember { mutableStateOf<com.streamify.app.data.models.Track?>(null) }

    // Dialog removed for instant stream

    // Mock Categories
    val categories = listOf(
        Pair("Podcasts", Color(0xFFE13300)),
        Pair("Live Events", Color(0xFF7358FF)),
        Pair("Made For You", Color(0xFF1E3264)),
        Pair("New Releases", Color(0xFFE8115B)),
        Pair("Pop", Color(0xFF148A08)),
        Pair("Hip-Hop", Color(0xFFBC5900))
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StreamifyColors.BgBase)
    ) {
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceGiant)) // Status bar
        
        Text(
            text = "Search",
            style = StreamifyType.HeadlineLarge,
            color = StreamifyColors.TextMain,
            modifier = Modifier.padding(horizontal = StreamifyDimens.SpaceLG)
        )
        
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))

        TextField(
            value = query,
            onValueChange = { 
                query = it
                viewModel.search(it)
            },
            placeholder = { 
                Text("What do you want to listen to?", color = StreamifyColors.TextOnSearch) 
            },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = "Search", tint = StreamifyColors.TextOnSearch)
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = StreamifyColors.BgSearchBar,
                unfocusedContainerColor = StreamifyColors.BgSearchBar,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = StreamifyColors.TextMain,
                unfocusedTextColor = StreamifyColors.TextMain
            ),
            shape = StreamifyShapes.SearchBarShape,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = StreamifyDimens.SpaceLG)
                .height(56.dp)
        )

        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceXL))

        LaunchedEffect(context) {
            viewModel.init(context)
        }
        
        val searchHistory by viewModel.searchHistory.collectAsState()

        when (val state = uiState) {
            is SearchUiState.Idle -> {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = StreamifyDimens.PlayerBarHeight + StreamifyDimens.SpaceXL)
                ) {
                    if (searchHistory.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = StreamifyDimens.SpaceLG),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Recent Searches",
                                    style = StreamifyType.TitleMedium,
                                    color = StreamifyColors.TextMain
                                )
                                TextButton(onClick = { viewModel.clearHistory() }) {
                                    Text("Clear", color = StreamifyColors.TextSub)
                                }
                            }
                        }
                        items(searchHistory) { pastQuery ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { 
                                        query = pastQuery
                                        viewModel.search(pastQuery) 
                                    }
                                    .padding(horizontal = StreamifyDimens.SpaceLG, vertical = StreamifyDimens.SpaceMD),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Search, contentDescription = null, tint = StreamifyColors.TextSub)
                                Spacer(modifier = Modifier.width(StreamifyDimens.SpaceMD))
                                Text(pastQuery, style = StreamifyType.BodyLarge, color = StreamifyColors.TextMain)
                            }
                        }
                        item { Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG)) }
                    }

                    item {
                        Text(
                            text = "Browse all",
                            style = StreamifyType.TitleMedium,
                            color = StreamifyColors.TextMain,
                            modifier = Modifier.padding(horizontal = StreamifyDimens.SpaceLG)
                        )
                        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))
                    }
                    
                    item {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.heightIn(max = 1000.dp), // Quick fix for nested scrolling
                            contentPadding = PaddingValues(
                                start = StreamifyDimens.SpaceLG,
                                end = StreamifyDimens.SpaceLG
                            ),
                            horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceLG),
                            verticalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceLG)
                        ) {
                            items(categories) { category ->
                                CategoryCard(
                                    title = category.first,
                                    backgroundColor = category.second,
                                    onClick = { /* Handle category */ }
                                )
                            }
                        }
                    }
                }
            }
            is SearchUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    CircularProgressIndicator(
                        color = StreamifyColors.Primary,
                        modifier = Modifier.padding(top = StreamifyDimens.SpaceXL)
                    )
                }
            }
            is SearchUiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Text(
                        text = state.message, 
                        color = StreamifyColors.ErrorRed,
                        modifier = Modifier.padding(top = StreamifyDimens.SpaceXL)
                    )
                }
            }
            is SearchUiState.Success -> {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = StreamifyDimens.PlayerBarHeight + StreamifyDimens.SpaceXL)
                ) {
                    if (state.localResults.isNotEmpty()) {
                        item {
                            Text("Your Library", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain, modifier = Modifier.padding(StreamifyDimens.SpaceLG))
                        }
                        items(state.localResults, key = { it.id }) { track ->
                            TrackListItem(
                                track = track,
                                onClick = { onTrackClick(track, (uiState as? SearchUiState.Success)?.localResults ?: emptyList()) },
                                onOptionsClick = { selectedOptionsTrack = track }
                            )
                        }
                    } 
                    if (state.isOnlineLoading) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(StreamifyDimens.SpaceLG),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    color = StreamifyColors.Primary,
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(StreamifyDimens.SpaceMD))
                                Text(
                                    text = "Searching YouTube...",
                                    style = StreamifyType.TitleMedium,
                                    color = StreamifyColors.Primary
                                )
                            }
                        }
                        items(4) {
                            com.streamify.app.ui.components.ShimmerPlaceholder(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .padding(horizontal = StreamifyDimens.SpaceLG, vertical = 6.dp)
                            )
                        }
                    }
                    if (state.onlineResults.isNotEmpty()) {
                        item {
                            Text("YouTube Results", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain, modifier = Modifier.padding(StreamifyDimens.SpaceLG))
                        }
                        items(state.onlineResults, key = { it.url }) { onlineTrack ->
                            val mockTrack = com.streamify.app.data.models.Track(
                                id = 0, title = onlineTrack.title, artist = onlineTrack.uploader,
                                album = "Streamify", durationSec = onlineTrack.duration,
                                filepath = onlineTrack.url, coverArtPath = onlineTrack.thumbnail.takeIf { it.isNotBlank() },
                                bpm = 0f, key = "", lyricsPath = null, source = "online"
                            )
                            TrackListItem(
                                track = mockTrack,
                                onClick = { 
                                    Toast.makeText(context, "Starting Stream & Download...", Toast.LENGTH_SHORT).show()
                                    viewModel.playOnlineTrack(onlineTrack, playerViewModel, ingestionViewModel, context)
                                },
                                onOptionsClick = { selectedOptionsTrack = mockTrack }
                            )
                        }
                    } 
                    if (!state.isOnlineLoading && state.localResults.isEmpty() && state.onlineResults.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(StreamifyDimens.SpaceXL), contentAlignment = Alignment.Center) {
                                Text("No results found for \"$query\"", color = StreamifyColors.TextMain)
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
