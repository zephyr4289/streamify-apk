package com.streamify.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import coil.imageLoader
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.streamify.app.navigation.AppNavGraph
import com.streamify.app.ui.components.BottomNavBar
import com.streamify.app.ui.components.BottomTab
import com.streamify.app.ui.components.MiniPlayerBar
import com.streamify.app.ui.screens.FullPlayerSheet
import com.streamify.app.ui.theme.StreamifyTheme
import com.streamify.app.util.PermissionHelper
import com.streamify.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                if (permissions.values.any { it }) {
                    enqueueMediaScan(this@MainActivity)
                }
            }

            LaunchedEffect(Unit) {
                if (!PermissionHelper.hasPermissions(this@MainActivity)) {
                    permissionLauncher.launch(PermissionHelper.REQUIRED_PERMISSIONS)
                } else {
                    enqueueMediaScan(this@MainActivity)
                }
            }

            StreamifyTheme {
                val navController = rememberNavController()
                var currentTab by remember { mutableStateOf(BottomTab.HOME) }
                val playerViewModel: PlayerViewModel = viewModel()
                val playerState by playerViewModel.playerState.collectAsState()
                val scope = rememberCoroutineScope()
                val context = androidx.compose.ui.platform.LocalContext.current
                
                var targetColor by remember { mutableStateOf(androidx.compose.ui.graphics.Color(0xFF1DB954)) }
                val dominantColor by androidx.compose.animation.animateColorAsState(
                    targetValue = targetColor,
                    animationSpec = androidx.compose.animation.core.tween(1000),
                    label = "dominantColor"
                )

                LaunchedEffect(playerState.currentTrack?.coverArtPath) {
                    val path = playerState.currentTrack?.coverArtPath
                    if (path != null) {
                        val request = coil.request.ImageRequest.Builder(context)
                            .data(path)
                            .allowHardware(false)
                            .build()
                        val result = (coil.Coil.imageLoader(context).execute(request) as? coil.request.SuccessResult)?.drawable
                        val bitmap = (result as? android.graphics.drawable.BitmapDrawable)?.bitmap
                        if (bitmap != null) {
                            androidx.palette.graphics.Palette.from(bitmap).generate { palette ->
                                palette?.dominantSwatch?.rgb?.let { colorInt ->
                                    targetColor = androidx.compose.ui.graphics.Color(colorInt)
                                } ?: palette?.mutedSwatch?.rgb?.let { colorInt ->
                                    targetColor = androidx.compose.ui.graphics.Color(colorInt)
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    playerViewModel.initialize(this@MainActivity)
                    com.streamify.app.data.PlaylistRepository.init(this@MainActivity)
                }

                val screenHeight = LocalConfiguration.current.screenHeightDp.dp
                val density = LocalDensity.current
                val peekHeight = 140.dp
                val sheetState = rememberStandardBottomSheetState(
                    initialValue = SheetValue.PartiallyExpanded,
                    skipHiddenState = true
                )
                val scaffoldState = rememberBottomSheetScaffoldState(
                    bottomSheetState = sheetState,
                    snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
                )

                LaunchedEffect(Unit) {
                    com.streamify.app.viewmodel.UiEventBus.events.collect { event ->
                        when (event) {
                            is com.streamify.app.viewmodel.UiEvent.ShowSnackbar -> {
                                scaffoldState.snackbarHostState.showSnackbar(
                                    message = event.message,
                                    duration = androidx.compose.material3.SnackbarDuration.Short
                                )
                            }
                        }
                    }
                }

                // Calculate physics fraction (0.0 = collapsed, 1.0 = expanded)
                val fraction = try {
                    val offset = sheetState.requireOffset()
                    val maxOffset = with(density) { screenHeight.toPx() }
                    val minOffset = 0f
                    1f - ((offset - minOffset) / (maxOffset - minOffset)).coerceIn(0f, 1f)
                } catch(e: Exception) {
                    if (sheetState.targetValue == SheetValue.Expanded) 1f else 0f
                }

                BottomSheetScaffold(
                    scaffoldState = scaffoldState,
                    sheetPeekHeight = if (playerState.currentTrack != null) peekHeight else 0.dp,
                    snackbarHost = { 
                        androidx.compose.material3.SnackbarHost(hostState = scaffoldState.snackbarHostState) { data ->
                            androidx.compose.material3.Snackbar(
                                snackbarData = data,
                                containerColor = com.streamify.app.ui.theme.StreamifyColors.Primary,
                                contentColor = com.streamify.app.ui.theme.StreamifyColors.BgBase
                            )
                        }
                    },
                    sheetContent = {
                        if (playerState.currentTrack != null) {
                            val progress = if (playerState.duration > 0)
                                playerState.currentPosition.toFloat() / playerState.duration.toFloat()
                            else 0f

                            Box(modifier = Modifier.fillMaxSize()) {
                                // Mini Player (Fades out)
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .alpha((1f - (fraction * 2f)).coerceIn(0f, 1f))
                                ) {
                                    MiniPlayerBar(
                                        track = playerState.currentTrack,
                                        isPlaying = playerState.isPlaying,
                                        progress = progress,
                                        onPlayPause = { playerViewModel.togglePlayPause() },
                                        onNext = { playerViewModel.skipNext() },
                                        onPrevious = { playerViewModel.skipPrevious() },
                                        onExpand = { scope.launch { sheetState.expand() } },
                                        onToggleLike = { playerViewModel.toggleLike() },
                                        modifier = Modifier.align(Alignment.TopCenter)
                                    )
                                }
                                
                                // Full Player (Scales in and fades in)
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .alpha(fraction)
                                        .scale(0.8f + (0.2f * fraction))
                                ) {
                                    FullPlayerSheet(
                                        track = playerState.currentTrack,
                                        isPlaying = playerState.isPlaying,
                                        progress = progress,
                                        isShuffleActive = playerState.isShuffleActive,
                                        isRepeatActive = playerState.isRepeatActive,
                                        dominantColor = dominantColor,
                                        onCollapse = { scope.launch { sheetState.partialExpand() } },
                                        onPlayPause = { playerViewModel.togglePlayPause() },
                                        onNext = { playerViewModel.skipNext() },
                                        onPrevious = { playerViewModel.skipPrevious() },
                                        onSeek = { f ->
                                            playerViewModel.seekTo((f * playerState.duration).toLong())
                                        },
                                        onShuffleToggle = { playerViewModel.toggleShuffle() },
                                        onRepeatToggle = { playerViewModel.toggleRepeat() },
                                        onToggleLike = { playerViewModel.toggleLike() },
                                        onQueueClick = { 
                                            scope.launch { sheetState.partialExpand() }
                                            navController.navigate("queue") 
                                        },
                                        onLyricsClick = { 
                                            scope.launch { sheetState.partialExpand() }
                                            navController.navigate("lyrics") 
                                        },
                                        isAutoPlayEnabled = playerState.isAutoPlayEnabled,
                                        onAutoPlayToggle = { playerViewModel.toggleAutoPlay() }
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        AppNavGraph(
                            navController = navController,
                            playerViewModel = playerViewModel,
                            dominantColor = dominantColor
                        )
                        // Bottom Navigation overlaps scaffold bottom when collapsed
                        BottomNavBar(
                            currentTab = currentTab,
                            onTabSelected = { tab ->
                                currentTab = tab
                                val route = when (tab) {
                                    BottomTab.HOME -> "home"
                                    BottomTab.SEARCH -> "search"
                                    BottomTab.LIBRARY -> "library"
                                    BottomTab.DOWNLOADS -> "downloads"
                                }
                                navController.navigate(route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = innerPadding.calculateBottomPadding())
                        )
                    }
                }
            }
        }
    }
}

private fun enqueueMediaScan(context: android.content.Context) {
    val workManager = androidx.work.WorkManager.getInstance(context)
    val scanRequest = androidx.work.OneTimeWorkRequestBuilder<com.streamify.app.service.IngestionWorker>()
        .addTag("ingestion_worker")
        .build()
    workManager.enqueueUniqueWork("media_scan", androidx.work.ExistingWorkPolicy.KEEP, scanRequest)
}
