package com.streamify.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.streamify.app.data.remote.AuthManager
import com.streamify.app.data.remote.AuthState
import com.streamify.app.navigation.AppNavGraph
import com.streamify.app.ui.components.BottomNavBar
import com.streamify.app.ui.components.BottomTab
import com.streamify.app.ui.components.MiniPlayerBar
import com.streamify.app.ui.components.YtBottomNavBar
import com.streamify.app.ui.screens.FullPlayerSheet
import com.streamify.app.ui.screens.YtOnboardingScreen
import com.streamify.app.ui.theme.BgBase
import com.streamify.app.ui.theme.Primary
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyTheme
import com.streamify.app.util.PermissionHelper
import com.streamify.app.viewmodel.PlayerViewModel
import com.streamify.app.viewmodel.UiEvent
import com.streamify.app.viewmodel.UiEventBus
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
                val playerViewModel: PlayerViewModel = viewModel()
                val playerState by playerViewModel.playerState.collectAsState()
                val scope = rememberCoroutineScope()
                val context = LocalContext.current

                var targetColor by remember { mutableStateOf(Color(0xFF212121)) }
                val dominantColor by animateColorAsState(
                    targetValue = targetColor,
                    animationSpec = tween(800),
                    label = "dominantColor"
                )

                val authState by AuthManager.authState.collectAsState()
                var isOnboardingDone by remember { mutableStateOf(AuthManager.hasSeenOnboarding()) }

                LaunchedEffect(playerState.currentTrack?.coverArtPath) {
                    val path = playerState.currentTrack?.coverArtPath
                    if (path != null) {
                        val request = ImageRequest.Builder(context)
                            .data(path)
                            .allowHardware(false)
                            .build()
                        val result = (Coil.imageLoader(context).execute(request) as? SuccessResult)?.drawable
                        val bitmap = (result as? android.graphics.drawable.BitmapDrawable)?.bitmap
                        if (bitmap != null) {
                            androidx.palette.graphics.Palette.from(bitmap).generate { palette ->
                                palette?.dominantSwatch?.rgb?.let { colorInt ->
                                    targetColor = Color(colorInt)
                                } ?: palette?.mutedSwatch?.rgb?.let { colorInt ->
                                    targetColor = Color(colorInt)
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    val prefs = getSharedPreferences("audio_settings", android.content.Context.MODE_PRIVATE)
                    com.streamify.app.service.CrossfadeAudioProcessor.crossfadeDurationMs =
                        (prefs.getFloat("crossfade_val", 0f) * 1000).toLong()
                    AuthManager.init(this@MainActivity)
                    playerViewModel.initialize(this@MainActivity)
                    com.streamify.app.data.PlaylistRepository.init(this@MainActivity)
                }

                if (!isOnboardingDone && authState !is AuthState.Authenticated) {
                    YtOnboardingScreen(
                        onComplete = {
                            isOnboardingDone = true
                        }
                    )
                } else {
                    val configuration = LocalConfiguration.current
                    val density = LocalDensity.current
                    val hasTrack = playerState.currentTrack != null
                    val peekHeight = if (hasTrack) 120.dp else 0.dp // 64dp Docked MiniPlayer + 56dp BottomNav

                    val sheetState = rememberStandardBottomSheetState(
                        initialValue = SheetValue.PartiallyExpanded,
                        skipHiddenState = true
                    )
                    val scaffoldState = rememberBottomSheetScaffoldState(
                        bottomSheetState = sheetState,
                        snackbarHostState = remember { SnackbarHostState() }
                    )

                    LaunchedEffect(Unit) {
                        UiEventBus.events.collect { event ->
                            when (event) {
                                is UiEvent.ShowSnackbar -> {
                                    scaffoldState.snackbarHostState.showSnackbar(
                                        message = event.message,
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            }
                        }
                    }

                    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
                    val peekHeightPx = with(density) { peekHeight.toPx() }

                    // Extreme Performance: derivedStateOf calculation runs only when offset updates, bound to GPU graphicsLayer
                    val sheetFraction by remember {
                        derivedStateOf {
                            try {
                                val offset = sheetState.requireOffset()
                                if (offset.isNaN() || screenHeightPx <= peekHeightPx) {
                                    if (sheetState.targetValue == SheetValue.Expanded) 1f else 0f
                                } else {
                                    1f - ((offset) / (screenHeightPx - peekHeightPx)).coerceIn(0f, 1f)
                                }
                            } catch (e: Exception) {
                                if (sheetState.targetValue == SheetValue.Expanded) 1f else 0f
                            }
                        }
                    }

                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route ?: "home"

                    BottomSheetScaffold(
                        scaffoldState = scaffoldState,
                        sheetPeekHeight = peekHeight,
                        sheetContainerColor = BgBase,
                        containerColor = BgBase,
                        snackbarHost = {
                            SnackbarHost(hostState = scaffoldState.snackbarHostState) { data ->
                                Snackbar(
                                    snackbarData = data,
                                    containerColor = Primary,
                                    contentColor = Color.White
                                )
                            }
                        },
                    sheetContent = {
                        if (hasTrack) {
                            val progress = if (playerState.duration > 0)
                                playerState.currentPosition.toFloat() / playerState.duration.toFloat()
                            else 0f

                            Box(modifier = Modifier.fillMaxSize()) {
                                // Layer 1: Mini Player (Fades out quickly on GPU during drag)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .align(Alignment.TopCenter)
                                        .graphicsLayer {
                                            alpha = (1f - (sheetFraction * 2.0f)).coerceIn(0f, 1f)
                                        }
                                ) {
                                    MiniPlayerBar(
                                        track = playerState.currentTrack,
                                        isPlaying = playerState.isPlaying,
                                        progress = progress,
                                        onPlayPause = { playerViewModel.togglePlayPause() },
                                        onNext = { playerViewModel.skipNext() },
                                        onPrevious = { playerViewModel.skipPrevious() },
                                        onExpand = { scope.launch { sheetState.expand() } },
                                        onToggleLike = { playerViewModel.toggleLike() }
                                    )
                                }

                                // Layer 2: Full Player Sheet (Fades in and scales up on GPU)
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            alpha = (sheetFraction * 1.5f).coerceIn(0f, 1f)
                                            scaleX = 0.90f + (0.10f * sheetFraction)
                                            scaleY = 0.90f + (0.10f * sheetFraction)
                                        }
                                ) {
                                    FullPlayerSheet(
                                        track = playerState.currentTrack,
                                        isPlaying = playerState.isPlaying,
                                        progress = progress,
                                        durationMs = playerState.duration,
                                        currentPositionMs = playerState.currentPosition,
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
                                        onRadioClick = {
                                            playerViewModel.startSongRadio(playerState.currentTrack)
                                        },
                                        onJamClick = {
                                            scope.launch { sheetState.partialExpand() }
                                            navController.navigate("jam")
                                        },
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
                        // Viewport: NavHost (with fixed bottom padding for the 120dp docked shell)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = if (hasTrack) 56.dp else 56.dp)
                        ) {
                            AppNavGraph(
                                navController = navController,
                                playerViewModel = playerViewModel,
                                dominantColor = dominantColor
                            )
                        }

                        // Docked Bottom Navigation Bar (Fades out when FullPlayer expands)
                        YtBottomNavBar(
                            currentRoute = currentRoute,
                            onNavigate = { route ->
                                navController.navigate(route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            alpha = (1f - (sheetFraction * 2.0f)).coerceIn(0f, 1f),
                            modifier = Modifier.align(Alignment.BottomCenter)
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
