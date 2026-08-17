package com.streamify.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamify.app.data.models.Track
import com.streamify.app.data.remote.StreamifyUpdateManager
import com.streamify.app.data.remote.SupabaseClient
import com.streamify.app.data.remote.UpdateState
import com.streamify.app.ui.components.*
import com.streamify.app.ui.theme.*
import com.streamify.app.util.ApkInstaller
import com.streamify.app.viewmodel.CommunityViewModel
import com.streamify.app.viewmodel.HomeUiState
import com.streamify.app.viewmodel.HomeViewModel
import com.streamify.app.viewmodel.PlayerViewModel

@Composable
fun HomeScreen(
    playerViewModel: PlayerViewModel,
    viewModel: HomeViewModel = viewModel(),
    communityViewModel: CommunityViewModel = viewModel(),
    dominantColor: Color = BgBase,
    onTrackClick: (Track, List<Track>) -> Unit,
    onSettingsClick: () -> Unit,
    onNavigateToJam: () -> Unit = {},
    onNavigateToCommunity: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val communityState by communityViewModel.uiState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val user by SupabaseClient.currentUser.collectAsState()
    val listState = rememberLazyListState()
    val contextMenuController = LocalContextMenuController.current

    var selectedMood by remember { mutableStateOf("All") }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.loadData()
                communityViewModel.loadCommunityFeed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
    ) {
        // 1. YouTube Music Sticky Top App Bar
        YtTopAppBar(
            onSearchClick = { /* Handled via Explore tab / Search */ },
            onAvatarClick = onNavigateToProfile,
            onCastClick = onNavigateToJam,
            avatarUrl = user?.avatarUrl,
            avatarInitial = user?.displayName?.take(1) ?: "S"
        )

        // 2. YouTube Music Sticky Mood Filter Rail
        YtMoodFilterRail(
            selectedMood = selectedMood,
            onMoodSelected = { mood ->
                selectedMood = if (selectedMood == mood) "All" else mood
            }
        )

        // 3. GPU Pull-To-Refresh Discovery Feed Container
        StreamifyPullToRefreshContainer(
            isRefreshing = isRefreshing,
            onRefresh = {
                viewModel.loadData()
                communityViewModel.loadCommunityFeed()
            }
        ) {
            when (val state = uiState) {
                is HomeUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Primary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                is HomeUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Error: ${state.message}",
                            color = Primary,
                            style = LocalAppTypography.current.songArtist
                        )
                    }
                }
                is HomeUiState.Success -> {
                    val allTracks = state.allTracks
                    val displayTracks = when (selectedMood.lowercase()) {
                        "workout" -> allTracks.filter { it.bpm >= 120f || it.bpm == 0f }.ifEmpty { allTracks }
                        "relax", "chill" -> allTracks.filter { (it.bpm in 60f..110f) || it.bpm == 0f }.ifEmpty { allTracks }
                        "focus" -> allTracks.filter { it.bpm in 70f..115f }.ifEmpty { allTracks }
                        "energize" -> allTracks.filter { it.bpm >= 125f }.ifEmpty { allTracks }
                        else -> allTracks
                    }

                    val screenConfig = LocalScreenConfiguration.current
                    val gridColumns = remember(screenConfig.widthDp) {
                        ((screenConfig.widthDp.value / 158f).toInt()).coerceAtLeast(2)
                    }

                    val quickPickCandidates = if (displayTracks.isNotEmpty()) displayTracks else state.sessionRecommendations.ifEmpty { allTracks }
                    val quickPickColumns = remember(quickPickCandidates) {
                        val pool = if (quickPickCandidates.isNotEmpty()) quickPickCandidates else allTracks
                        pool.take(16).chunked(4)
                    }

                    val listenAgainCandidates = remember(state.topPlayed, state.recent) {
                        val combined = (state.topPlayed + state.recent).distinctBy { it.id }
                        if (combined.isNotEmpty()) combined else allTracks
                    }
                    val listenAgainColumns = remember(listenAgainCandidates, gridColumns) {
                        listenAgainCandidates.take(gridColumns * 6).chunked(if (screenConfig.isTablet) 3 else 2)
                    }

                    val supermixPool = remember(state.madeForYou, state.sessionRecommendations) {
                        (state.madeForYou + state.sessionRecommendations).distinctBy { it.id }.ifEmpty { allTracks }
                    }

                    val circadianColumns = remember(state.circadianRecommendations, gridColumns) {
                        state.circadianRecommendations.take(gridColumns * 6).chunked(if (screenConfig.isTablet) 3 else 2)
                    }

                    val hybridColumns = remember(state.hybridRecommendations, gridColumns) {
                        state.hybridRecommendations.take(gridColumns * 4).chunked(if (screenConfig.isTablet) 3 else 2)
                    }

                    val context = LocalContext.current
                    val updateState by StreamifyUpdateManager.updateState.collectAsState()

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 120.dp)
                    ) {
                        // In-App OTA Update Banner
                        if (updateState is UpdateState.UpdateAvailable) {
                            val available = updateState as UpdateState.UpdateAvailable
                            item(key = "card_update_available", contentType = "updateBanner") {
                                UpdateAvailableCard(
                                    updateState = available,
                                    onUpdateClick = {
                                        StreamifyUpdateManager.dispatchUpdate(context, available.buildInfo)
                                    },
                                    onDismissClick = {
                                        StreamifyUpdateManager.dismissUpdate(context, available.buildInfo.buildNumber)
                                    }
                                )
                            }
                        }

                        // Optional Broadcast Banner
                        if (communityState.activeBroadcasts.isNotEmpty()) {
                            item(key = "broadcast_banner") {
                                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    BroadcastBanner(broadcasts = communityState.activeBroadcasts)
                                }
                            }
                        }

                        // SHELF 1: LISTEN AGAIN
                        if (listenAgainColumns.isNotEmpty() && listenAgainColumns.first().isNotEmpty()) {
                            item(key = "header_listen_again") {
                                YtSectionHeader(
                                    title = "Listen Again",
                                    kicker = "Past Heavy Rotations"
                                )
                            }
                            item(key = "grid_listen_again") {
                                YtListenAgainGrid(
                                    columns = listenAgainColumns,
                                    onTrackClick = onTrackClick
                                )
                            }
                        }

                        // SHELF 2: QUICK PICKS
                        if (quickPickColumns.isNotEmpty() && quickPickColumns.first().isNotEmpty()) {
                            item(key = "header_quick_picks") {
                                YtSectionHeader(
                                    title = "Quick Picks",
                                    kicker = if (selectedMood != "All") "$selectedMood Mix" else "Start radio for"
                                )
                            }
                            item(key = "carousel_quick_picks") {
                                YtQuickPicksCarousel(
                                    columns = quickPickColumns,
                                    onTrackClick = onTrackClick
                                )
                            }
                        }

                        // SHELF 3: MY SUPERMIX & DYNAMIC STATIONS
                        if (supermixPool.isNotEmpty()) {
                            item(key = "header_supermix") {
                                YtSectionHeader(
                                    title = "Mixed For You",
                                    kicker = "Personalized Stations"
                                )
                            }
                            item(key = "row_supermix") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(
                                        items = supermixPool.take(8),
                                        key = { "supermix_${it.id}" }
                                    ) { track ->
                                        YtSupermixCard(
                                            title = "${track.artist} Mix",
                                            subtitle = "Continuous station with ${track.title}",
                                            artworkUrl = track.coverArtPath,
                                            onClick = { onTrackClick(track, supermixPool) },
                                            onLongClick = {
                                                com.streamify.app.util.StreamifyHapticEngine.magneticDetent()
                                                contextMenuController.show(track, origin = MenuOrigin.HOME)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // SHELF 4: CIRCADIAN DAYPARTING RHYTHM
                        if (state.circadianRecommendations.isNotEmpty()) {
                            item(key = "header_circadian") {
                                YtSectionHeader(
                                    title = state.circadianSlotTitle,
                                    kicker = "Circadian Acoustic Tuning"
                                )
                            }
                            item(key = "grid_circadian") {
                                YtListenAgainGrid(
                                    columns = circadianColumns,
                                    onTrackClick = onTrackClick
                                )
                            }
                        }

                        // SHELF 5: HYBRID ASYMMETRIC RADAR
                        if (state.hybridRecommendations.isNotEmpty()) {
                            item(key = "header_hybrid") {
                                YtSectionHeader(
                                    title = "Hybrid Radar ⚡",
                                    kicker = "Last.fm Graph × On-Device SIMD"
                                )
                            }
                            item(key = "grid_hybrid") {
                                YtListenAgainGrid(
                                    columns = hybridColumns,
                                    onTrackClick = onTrackClick
                                )
                            }
                        }

                        // SHELF 6: COMMUNITY TRENDING PLAYLISTS
                        if (communityState.communityPlaylists.isNotEmpty()) {
                            item(key = "header_community") {
                                YtSectionHeader(
                                    title = "Community Trending",
                                    kicker = "Curated by Streamify Listeners",
                                    actionText = "See All",
                                    onActionClick = onNavigateToCommunity
                                )
                            }
                            item(key = "row_community") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(communityState.communityPlaylists) { playlist ->
                                        YtSupermixCard(
                                            title = playlist.name,
                                            subtitle = "by ${playlist.creatorName}",
                                            artworkUrl = playlist.coverUrl,
                                            onClick = onNavigateToCommunity
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
}
