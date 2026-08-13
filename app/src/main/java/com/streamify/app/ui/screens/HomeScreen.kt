import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
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
import com.streamify.app.viewmodel.PlayerViewModel

@Composable
fun HomeScreen(
    playerViewModel: PlayerViewModel,
    viewModel: HomeViewModel = viewModel(),
    dominantColor: Color = StreamifyColors.BgBase,
    onTrackClick: (Track, List<Track>) -> Unit,
    onSettingsClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val currentTrack by playerViewModel.currentTrack.collectAsState()

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
            item {
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { 50 }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = StreamifyDimens.SpaceXL),
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

                    // Recommendations
                    if (state.recommendations.isNotEmpty()) {
                        item {
                            AnimatedVisibility(
                                visible = isVisible,
                                enter = fadeIn(tween(400, delayMillis = 300)) + slideInVertically(tween(400, delayMillis = 300)) { 50 }
                            ) {
                                Column {
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
                                                onClick = { onTrackClick(track, state.allTracks) }
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceXXL))
                                }
                            }
                        }
                    }

                    // All Tracks
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
