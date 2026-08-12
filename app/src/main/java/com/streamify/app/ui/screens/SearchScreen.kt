package com.streamify.app.ui.screens

import androidx.compose.foundation.background
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
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyShapes
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.viewmodel.SearchUiState
import com.streamify.app.viewmodel.SearchViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = viewModel(),
    onTrackClick: (Int, List<com.streamify.app.data.models.Track>) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var query by remember { mutableStateOf("") }

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
                focusedTextColor = StreamifyColors.TextOnSearch,
                unfocusedTextColor = StreamifyColors.TextOnSearch
            ),
            shape = StreamifyShapes.SearchBarShape,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = StreamifyDimens.SpaceLG)
                .height(56.dp)
        )

        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceXL))

        when (val state = uiState) {
            is SearchUiState.Idle -> {
                Text(
                    text = "Browse all",
                    style = StreamifyType.TitleMedium,
                    color = StreamifyColors.TextMain,
                    modifier = Modifier.padding(horizontal = StreamifyDimens.SpaceLG)
                )
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(
                        start = StreamifyDimens.SpaceLG,
                        end = StreamifyDimens.SpaceLG,
                        bottom = StreamifyDimens.PlayerBarHeight + StreamifyDimens.SpaceXL
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
                        items(state.localResults) { track ->
                            TrackListItem(
                                track = track,
                                onClick = { onTrackClick(track.id, (uiState as? SearchUiState.Success)?.localResults ?: emptyList()) },
                                onOptionsClick = { /* Handle options */ }
                            )
                        }
                    } else if (state.onlineResults.isNotEmpty()) {
                        // Display online results here later
                    } else {
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
}
