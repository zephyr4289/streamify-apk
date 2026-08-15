package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamify.app.data.models.Track
import com.streamify.app.ui.components.*
import com.streamify.app.ui.theme.*
import com.streamify.app.viewmodel.*
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(
    playerViewModel: PlayerViewModel,
    viewModel: SearchViewModel = viewModel(),
    ingestionViewModel: IngestionViewModel = viewModel(),
    onTrackClick: (Track, List<Track>) -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val currentTrack by playerViewModel.currentTrack.collectAsState()
    val resolvingTrackUrl by viewModel.resolvingTrackUrl.collectAsState()
    val searchHistory by viewModel.searchHistory.collectAsState()
    val searchSuggestions by viewModel.searchSuggestions.collectAsState()

    var query by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") }
    var selectedOptionsTrack by remember { mutableStateOf<Track?>(null) }

    LaunchedEffect(context) {
        viewModel.init(context)
    }

    // 120fps Debounce Engine: Cancels rapid keystroke jobs
    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            delay(150)
            viewModel.search(query)
            viewModel.updateSuggestions(query)
        } else {
            viewModel.updateSuggestions("")
        }
    }

    val genres = remember {
        listOf(
            "Chill & Lofi" to Color(0xFF3EA6FF),
            "Energy & Gym" to Color(0xFFFF0000),
            "Deep Focus" to Color(0xFF8D67AB),
            "Indie & Rock" to Color(0xFFE91E63),
            "Hip-Hop" to Color(0xFFFF9800),
            "Electronic" to Color(0xFF00E676),
            "Pop" to Color(0xFFFF4081),
            "Acoustic & Folk" to Color(0xFF8D6E63)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .statusBarsPadding()
    ) {
        // 1. YouTube Music Omnibar
        YtSearchOmnibar(
            query = query,
            onQueryChange = { query = it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // 2. Search Category Filter Chips (When Query is Active)
        if (query.isNotBlank()) {
            YtSearchFilterChips(
                selectedFilter = selectedFilter,
                onFilterSelected = { selectedFilter = it }
            )
        }

        // Suggestions Rail
        if (query.isNotBlank() && searchSuggestions.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(searchSuggestions) { suggestion ->
                    Surface(
                        color = BgSurfaceElevated,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.clickable {
                            query = suggestion
                            viewModel.search(suggestion)
                            viewModel.updateSuggestions("")
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = suggestion,
                                style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                color = TextMain
                            )
                        }
                    }
                }
            }
        }

        // 3. Main Viewport
        if (query.isBlank()) {
            // ==========================================
            // STATE A: EXPLORE & BROWSE HUB
            // ==========================================
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 120.dp)
            ) {
                // Recent Searches
                if (searchHistory.isNotEmpty()) {
                    item(key = "header_recent") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Recent searches",
                                style = LocalAppTypography.current.headlineMedium.copy(fontSize = 16.sp),
                                color = TextMain
                            )
                            TextButton(onClick = { viewModel.clearHistory() }) {
                                Text(
                                    text = "Clear",
                                    style = LocalAppTypography.current.chipText.copy(fontSize = 12.sp),
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                    items(searchHistory.take(5)) { pastQuery ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    query = pastQuery
                                    viewModel.search(pastQuery)
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = pastQuery,
                                style = LocalAppTypography.current.songTitle.copy(fontSize = 14.sp),
                                color = TextMain
                            )
                        }
                    }
                }

                // Moods & Genres
                item(key = "header_genres", contentType = "header") {
                    YtSectionHeader(
                        title = "Moods & Genres",
                        kicker = "Explore Categories"
                    )
                }

                item(key = "genre_grid", contentType = "genre_grid") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        genres.chunked(2).forEach { rowGenres ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowGenres.forEach { (genreTitle, accentColor) ->
                                    Box(modifier = Modifier.weight(1f)) {
                                        YtGenreCard(
                                            title = genreTitle,
                                            accentColor = accentColor,
                                            onClick = {
                                                query = genreTitle
                                                viewModel.search(genreTitle)
                                            }
                                        )
                                    }
                                }
                                if (rowGenres.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // ==========================================
            // STATE B: SEARCH RESULTS HUB
            // ==========================================
            when (val state = uiState) {
                is SearchUiState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 40.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        CircularProgressIndicator(
                            color = Primary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                is SearchUiState.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 40.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Text(
                            text = state.message,
                            color = Primary,
                            style = LocalAppTypography.current.songArtist
                        )
                    }
                }
                is SearchUiState.Success -> {
                    val localMatches = state.localResults
                    val onlineMatches = state.onlineResults

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 120.dp)
                    ) {
                        // 1. Top Result Hero Card
                        if (localMatches.isNotEmpty() && (selectedFilter == "All" || selectedFilter == "Songs")) {
                            val topHit = localMatches.first()
                            item(key = "header_top_hit", contentType = "header") {
                                YtSectionHeader(title = "Top result")
                            }
                            item(key = "card_top_hit", contentType = "topCard") {
                                YtTopResultCard(
                                    track = topHit,
                                    onPlay = { onTrackClick(topHit, localMatches) }
                                )
                            }
                        }

                        // 2. Library Songs
                        if (localMatches.isNotEmpty() && (selectedFilter == "All" || selectedFilter == "Songs")) {
                            val remainingLocal = if (localMatches.size > 1) localMatches.drop(1) else emptyList()
                            if (remainingLocal.isNotEmpty()) {
                                item(key = "header_local_songs", contentType = "header") {
                                    YtSectionHeader(
                                        title = "From your library",
                                        kicker = "${localMatches.size} Tracks Found"
                                    )
                                }
                                items(
                                    items = remainingLocal,
                                    key = { "local_${it.id}" },
                                    contentType = { "trackRow" }
                                ) { track ->
                                    YtQueueTrackItem(
                                        track = track,
                                        isPlaying = currentTrack?.id == track.id,
                                        showDragHandle = false,
                                        onClick = { onTrackClick(track, localMatches) },
                                        onMoreClick = { selectedOptionsTrack = track }
                                    )
                                }
                            }
                        }

                        // 3. Online Shimmer Loading Indicator
                        if (state.isOnlineLoading) {
                            item(key = "header_online_loading", contentType = "header") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        color = Primary,
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Searching YouTube Music...",
                                        style = LocalAppTypography.current.headlineMedium.copy(fontSize = 15.sp),
                                        color = Primary
                                    )
                                }
                            }
                            items(4) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .padding(horizontal = 16.dp, vertical = 6.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(BgSurfaceElevated)
                                )
                            }
                        }

                        // 4. Online YouTube Results
                        if (onlineMatches.isNotEmpty() && (selectedFilter == "All" || selectedFilter == "Songs" || selectedFilter == "Videos")) {
                            item(key = "header_youtube_results", contentType = "header") {
                                YtSectionHeader(
                                    title = "YouTube Results",
                                    kicker = "Instant Audio Stream"
                                )
                            }
                            items(
                                items = onlineMatches,
                                key = { "online_${it.url}" },
                                contentType = { "trackRow" }
                            ) { onlineTrack ->
                                val isResolving = resolvingTrackUrl == onlineTrack.url
                                val trackModel = Track(
                                    id = 0,
                                    title = onlineTrack.title,
                                    artist = onlineTrack.uploader,
                                    album = "YouTube Music",
                                    durationSec = onlineTrack.duration,
                                    filepath = onlineTrack.url,
                                    coverArtPath = onlineTrack.thumbnail.takeIf { it.isNotBlank() },
                                    bpm = 0f,
                                    key = "",
                                    lyricsPath = null,
                                    source = "online"
                                )

                                YtQueueTrackItem(
                                    track = trackModel,
                                    isPlaying = isResolving || (currentTrack?.filepath == onlineTrack.url),
                                    showDragHandle = false,
                                    onClick = {
                                        viewModel.playOnlineTrack(
                                            onlineTrack,
                                            playerViewModel,
                                            ingestionViewModel,
                                            context
                                        )
                                    },
                                    onMoreClick = { selectedOptionsTrack = trackModel }
                                )
                            }
                        }

                        // 5. Empty Result State
                        if (!state.isOnlineLoading && localMatches.isEmpty() && onlineMatches.isEmpty()) {
                            item(key = "empty_results") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 60.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "No results found for \"$query\"",
                                            style = LocalAppTypography.current.headlineMedium.copy(fontSize = 16.sp),
                                            color = TextMain
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Try searching for a different song or artist",
                                            style = LocalAppTypography.current.songArtist,
                                            color = TextSecondary
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
