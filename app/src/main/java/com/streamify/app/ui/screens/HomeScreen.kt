package com.streamify.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.streamify.app.data.models.Track
import com.streamify.app.data.remote.SupabaseClient
import com.streamify.app.ui.components.BroadcastBanner
import com.streamify.app.ui.components.FriendActivityCard
import com.streamify.app.ui.components.RecentPlayCard
import com.streamify.app.ui.components.TrackCard
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyShapes
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.util.TimeGreeting
import com.streamify.app.viewmodel.CommunityViewModel
import com.streamify.app.viewmodel.HomeUiState
import com.streamify.app.viewmodel.HomeViewModel
import com.streamify.app.viewmodel.PlayerViewModel

@Composable
fun HomeScreen(
    playerViewModel: PlayerViewModel,
    viewModel: HomeViewModel = viewModel(),
    communityViewModel: CommunityViewModel = viewModel(),
    dominantColor: Color = StreamifyColors.BgBase,
    onTrackClick: (Track, List<Track>) -> Unit,
    onSettingsClick: () -> Unit,
    onNavigateToJam: () -> Unit = {},
    onNavigateToCommunity: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val communityState by communityViewModel.uiState.collectAsState()
    val user by SupabaseClient.currentUser.collectAsState()
    val listState = rememberLazyListState()
    val currentTrack by playerViewModel.currentTrack.collectAsState()

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.loadData()
                communityViewModel.loadCommunityFeed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isVisible = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Collapsing Gradient Header (Parallax)
        val density = LocalDensity.current
        val headerHeightPx = with(density) { 300.dp.toPx() }
        val scrollOffset = if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset else headerHeightPx.toInt()
        val parallaxOffset = -(scrollOffset * 0.5f)
        val alphaFade = (1f - (scrollOffset / headerHeightPx)).coerceIn(0f, 1f)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .offset(y = with(density) { parallaxOffset.toDp() })
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            dominantColor.copy(alpha = 0.6f * alphaFade),
                            StreamifyColors.BgBase
                        )
                    )
                )
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = StreamifyDimens.SpaceLG,
                end = StreamifyDimens.SpaceLG,
                top = StreamifyDimens.SpaceGiant,
                bottom = StreamifyDimens.PlayerBarHeight + StreamifyDimens.SpaceXL
            )
        ) {
            // 1. Top Header with User Profile Avatar & Cloud Sync Pill
            item {
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { 50 }
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(bottom = StreamifyDimens.SpaceMD)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clickable { onNavigateToProfile() }
                                    .padding(vertical = 4.dp)
                            ) {
                                if (user?.avatarUrl?.isNotBlank() == true) {
                                    AsyncImage(
                                        model = user!!.avatarUrl,
                                        contentDescription = "Profile",
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .background(StreamifyColors.PrimaryDark),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            user?.displayName?.take(1)?.uppercase() ?: "S",
                                            style = StreamifyType.HeadlineSmall,
                                            color = StreamifyColors.TextMain
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column {
                                    Text(
                                        text = TimeGreeting.getGreeting(),
                                        style = StreamifyType.HeadlineLarge,
                                        color = StreamifyColors.TextMain
                                    )
                                    Text(
                                        text = user?.displayName ?: "Streamify Cloud",
                                        style = StreamifyType.CaptionBold,
                                        color = StreamifyColors.Primary
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = onNavigateToJam) {
                                    Icon(
                                        Icons.Default.Podcasts,
                                        contentDescription = "Jam",
                                        tint = StreamifyColors.Primary
                                    )
                                }
                                IconButton(onClick = onSettingsClick) {
                                    Icon(
                                        imageVector = Icons.Filled.Settings,
                                        contentDescription = "Settings",
                                        tint = StreamifyColors.TextMain
                                    )
                                }
                            }
                        }

                        // Broadcast Announcement Banner (if any)
                        if (communityState.activeBroadcasts.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            BroadcastBanner(broadcasts = communityState.activeBroadcasts)
                        }
                    }
                }
            }

            // 2. Streamify Jam Hero Banner
            item {
                Surface(
                    color = StreamifyColors.BgCard,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = StreamifyDimens.SpaceLG)
                        .clickable { onNavigateToJam() }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = StreamifyColors.Primary.copy(alpha = 0.2f),
                            shape = CircleShape
                        ) {
                            Icon(
                                Icons.Default.Podcasts,
                                contentDescription = null,
                                tint = StreamifyColors.Primary,
                                modifier = Modifier.padding(10.dp).size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text("Streamify Jam", style = StreamifyType.BodyLargeBold, color = StreamifyColors.TextMain)
                            Text("Listen together in sync with friends", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                        }

                        Surface(
                            color = StreamifyColors.Primary,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                "Live",
                                style = StreamifyType.CaptionBold,
                                color = StreamifyColors.BgBase,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // 3. Friend Listening Activity Shelf
            if (communityState.friendsActivity.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(bottom = StreamifyDimens.SpaceLG)) {
                        Text(
                            "Friend Activity",
                            style = StreamifyType.HeadlineSmall,
                            color = StreamifyColors.TextMain
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(communityState.friendsActivity) { friend ->
                                FriendActivityCard(friend = friend)
                            }
                        }
                    }
                }
            }

            when (val state = uiState) {
                is HomeUiState.Loading -> {
                    item {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceSM)) {
                                repeat(3) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceSM)) {
                                        com.streamify.app.ui.components.ShimmerPlaceholder(
                                            modifier = Modifier.weight(1f).height(56.dp),
                                            shape = StreamifyShapes.CardShape
                                        )
                                        com.streamify.app.ui.components.ShimmerPlaceholder(
                                            modifier = Modifier.weight(1f).height(56.dp),
                                            shape = StreamifyShapes.CardShape
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                is HomeUiState.Error -> {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Text("Error: ${state.message}", color = StreamifyColors.ErrorRed)
                        }
                    }
                }
                is HomeUiState.Success -> {
                    // Recent Grid
                    item {
                        val chunked = state.recent.chunked(2)
                        Column(verticalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceSM)) {
                            for ((rowIndex, row) in chunked.withIndex()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceSM)
                                ) {
                                    for ((colIndex, track) in row.withIndex()) {
                                        AnimatedVisibility(
                                            visible = isVisible,
                                            enter = fadeIn(tween(400, delayMillis = 100 + (rowIndex * 2 + colIndex) * 50)) + slideInVertically(tween(400, delayMillis = 100 + (rowIndex * 2 + colIndex) * 50)) { 50 },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            RecentPlayCard(
                                                title = track.title,
                                                imageUrl = track.coverArtPath,
                                                onClick = { onTrackClick(track, state.allTracks) },
                                                isPlaying = currentTrack?.id == track.id
                                            )
                                        }
                                    }
                                    if (row.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceXXL))
                    }

                    // 0. Hybrid Asymmetric Radar Shelf (Global Crowd + On-Device NEON SIMD)
                    if (state.hybridRecommendations.isNotEmpty()) {
                        item {
                            AnimatedVisibility(
                                visible = isVisible,
                                enter = fadeIn(tween(400, delayMillis = 200)) + slideInVertically(tween(400, delayMillis = 200)) { 50 }
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = "Hybrid Radar ⚡",
                                                style = StreamifyType.HeadlineMedium,
                                                color = StreamifyColors.TextMain
                                            )
                                            Text(
                                                text = "Last.fm Crowd Graph × NEON SIMD Context",
                                                style = StreamifyType.Caption,
                                                color = Color(0xFF00E5FF)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceLG)
                                    ) {
                                        items(state.hybridRecommendations, key = { "hybrid_${it.id}" }) { track ->
                                            TrackCard(
                                                track = track,
                                                onClick = { onTrackClick(track, state.hybridRecommendations) }
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceXXL))
                                }
                            }
                        }
                    }

                    // 1. Session-Aware "Jump Back In • Current Vibe"
                    if (state.sessionRecommendations.isNotEmpty()) {
                        item {
                            AnimatedVisibility(
                                visible = isVisible,
                                enter = fadeIn(tween(400, delayMillis = 250)) + slideInVertically(tween(400, delayMillis = 250)) { 50 }
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = "Jump Back In",
                                                style = StreamifyType.HeadlineMedium,
                                                color = StreamifyColors.TextMain
                                            )
                                            Text(
                                                text = "Session mood tuning",
                                                style = StreamifyType.Caption,
                                                color = StreamifyColors.TextSub
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceLG)
                                    ) {
                                        items(state.sessionRecommendations, key = { it.id }) { track ->
                                            TrackCard(
                                                track = track,
                                                onClick = { onTrackClick(track, state.sessionRecommendations) }
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceXXL))
                                }
                            }
                        }
                    }

                    // Project Chronos Circadian Dayparting Shelf
                    if (state.circadianRecommendations.isNotEmpty()) {
                        item {
                            AnimatedVisibility(
                                visible = isVisible,
                                enter = fadeIn(tween(400, delayMillis = 300)) + slideInVertically(tween(400, delayMillis = 300)) { 50 }
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = state.circadianSlotTitle,
                                                style = StreamifyType.HeadlineMedium,
                                                color = StreamifyColors.TextMain
                                            )
                                            Text(
                                                text = "Dynamic Circadian Rhythm Tuning",
                                                style = StreamifyType.Caption,
                                                color = StreamifyColors.Primary
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceLG)
                                    ) {
                                        items(state.circadianRecommendations, key = { it.id }) { track ->
                                            TrackCard(
                                                track = track,
                                                onClick = { onTrackClick(track, state.circadianRecommendations) }
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceXXL))
                                }
                            }
                        }
                    }

                    // 2. Made For You Shelf
                    if (state.madeForYou.isNotEmpty()) {
                        item {
                            AnimatedVisibility(
                                visible = isVisible,
                                enter = fadeIn(tween(400, delayMillis = 350)) + slideInVertically(tween(400, delayMillis = 350)) { 50 }
                            ) {
                                Column {
                                    Text(
                                        text = "Made For You",
                                        style = StreamifyType.HeadlineMedium,
                                        color = StreamifyColors.TextMain
                                    )
                                    Text(
                                        text = "Personalized ML Recommendations",
                                        style = StreamifyType.Caption,
                                        color = StreamifyColors.TextSub
                                    )
                                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceLG)
                                    ) {
                                        items(state.madeForYou, key = { it.id }) { track ->
                                            TrackCard(
                                                track = track,
                                                onClick = { onTrackClick(track, state.madeForYou) }
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceXXL))
                                }
                            }
                        }
                    }

                    // 3. Community Trending Playlists Hub Shelf
                    if (communityState.communityPlaylists.isNotEmpty()) {
                        item {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "Community Trending Hub",
                                            style = StreamifyType.HeadlineMedium,
                                            color = StreamifyColors.TextMain
                                        )
                                        Text(
                                            text = "Curated playlists by Streamify listeners",
                                            style = StreamifyType.Caption,
                                            color = StreamifyColors.TextSub
                                        )
                                    }
                                    TextButton(onClick = onNavigateToCommunity) {
                                        Text("See All", color = StreamifyColors.Primary, style = StreamifyType.CaptionBold)
                                    }
                                }
                                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceMD)
                                ) {
                                    items(communityState.communityPlaylists) { playlist ->
                                        Surface(
                                            color = StreamifyColors.BgCard,
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier
                                                .width(160.dp)
                                                .clickable { onNavigateToCommunity() }
                                        ) {
                                            Column(modifier = Modifier.padding(10.dp)) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(140.dp)
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(StreamifyColors.BgElevated),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (playlist.coverUrl.isNotBlank()) {
                                                        AsyncImage(
                                                            model = playlist.coverUrl,
                                                            contentDescription = null,
                                                            modifier = Modifier.fillMaxSize()
                                                        )
                                                    } else {
                                                        Icon(Icons.Default.QueueMusic, contentDescription = null, tint = StreamifyColors.Primary, modifier = Modifier.size(40.dp))
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text(playlist.name, style = StreamifyType.BodyMediumBold, color = StreamifyColors.TextMain, maxLines = 1)
                                                Text("by ${playlist.creatorName}", style = StreamifyType.Caption, color = StreamifyColors.Primary, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceXXL))
                            }
                        }
                    }

                    // 4. On Repeat (Heavy Rotation)
                    if (state.topPlayed.isNotEmpty()) {
                        item {
                            AnimatedVisibility(
                                visible = isVisible,
                                enter = fadeIn(tween(400, delayMillis = 400)) + slideInVertically(tween(400, delayMillis = 400)) { 50 }
                            ) {
                                Column {
                                    Text(
                                        text = "On Repeat",
                                        style = StreamifyType.HeadlineMedium,
                                        color = StreamifyColors.TextMain
                                    )
                                    Text(
                                        text = "Your Heavy Rotations",
                                        style = StreamifyType.Caption,
                                        color = StreamifyColors.TextSub
                                    )
                                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceLG)
                                    ) {
                                        items(state.topPlayed, key = { it.id }) { track ->
                                            TrackCard(
                                                track = track,
                                                onClick = { onTrackClick(track, state.topPlayed) }
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceXXL))
                                }
                            }
                        }
                    }

                    // 5. All Tracks
                    if (state.allTracks.isNotEmpty()) {
                        item {
                            AnimatedVisibility(
                                visible = isVisible,
                                enter = fadeIn(tween(400, delayMillis = 500)) + slideInVertically(tween(400, delayMillis = 500)) { 50 }
                            ) {
                                Column {
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
                                                onClick = { onTrackClick(track, state.allTracks) }
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
}
