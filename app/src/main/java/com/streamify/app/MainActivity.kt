package com.streamify.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.streamify.app.data.remote.AuthManager
import com.streamify.app.data.remote.AuthState
import com.streamify.app.navigation.AppNavGraph
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import com.streamify.app.ui.components.LocalDockPosition
import com.streamify.app.ui.components.LocalQuantumController
import com.streamify.app.ui.components.MiniPlayerBar
import com.streamify.app.ui.components.QuantumSonicTokenController
import com.streamify.app.ui.components.QuantumSonicTokenOverlay
import com.streamify.app.ui.components.YtBottomNavBar
import com.streamify.app.ui.screens.FullPlayerSheet
import com.streamify.app.ui.screens.PrismaticSplashScreen
import com.streamify.app.ui.screens.YtOnboardingScreen
import com.streamify.app.ui.theme.*
import com.streamify.app.util.PermissionHelper
import com.streamify.app.viewmodel.PlayerViewModel
import com.streamify.app.viewmodel.UiEvent
import com.streamify.app.viewmodel.UiEventBus
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSpotifyCallback(intent)
    }

    private fun handleSpotifyCallback(intent: android.content.Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "streamify" && (uri.host == "callback" || uri.host == "spotify-auth")) {
            val authCode = uri.getQueryParameter("code")
            val error = uri.getQueryParameter("error")
            if (!authCode.isNullOrEmpty()) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    val spotifyAuth = com.streamify.app.data.remote.SpotifyAuthManager(this@MainActivity)
                    val dbPath = getDatabasePath("streamify_universal.db").absolutePath
                    spotifyAuth.handleAuthCallback(authCode, dbPath) { count ->
                        if (count >= 0) {
                            android.widget.Toast.makeText(this@MainActivity, "Spotify connected! Synced $count tracks into your taste profile 🎵", android.widget.Toast.SHORT).show()
                        } else {
                            android.widget.Toast.makeText(this@MainActivity, "Spotify connected successfully! 🎵", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else if (!error.isNullOrEmpty()) {
                android.widget.Toast.makeText(this, "Spotify auth note: $error", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleSpotifyCallback(intent)
        com.streamify.app.data.models.AppMode.initialize(this)

        setContent {
            val audioPrefs = remember { getSharedPreferences("audio_settings", android.content.Context.MODE_PRIVATE) }
            val isLocalEnabled = remember { audioPrefs.getBoolean("enable_local_audio", false) }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                if (permissions.values.any { it } && isLocalEnabled) {
                    enqueueMediaScan(this@MainActivity)
                }
            }

            LaunchedEffect(Unit) {
                if (isLocalEnabled) {
                    if (!PermissionHelper.hasPermissions(this@MainActivity)) {
                        permissionLauncher.launch(PermissionHelper.REQUIRED_PERMISSIONS)
                    } else {
                        enqueueMediaScan(this@MainActivity)
                    }
                }
            }

            val authState by AuthManager.authState.collectAsState()
            LaunchedEffect(authState) {
                val user = com.streamify.app.data.remote.SupabaseClient.currentUser.value
                if (user != null) {
                    com.streamify.app.data.remote.SupabaseClient.startRealtimeSync(user.id)
                } else {
                    com.streamify.app.data.remote.SupabaseClient.stopRealtimeSync()
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

                var isSplashDone by remember { mutableStateOf(false) }

                // Dynamic Full-Player Overlay & Dock State
                var isPlayerExpanded by remember { mutableStateOf(false) }

                // Quantum Sonic Token 3D Physics Engine
                val quantumController = remember { QuantumSonicTokenController() }
                val dockPositionState = remember { mutableStateOf(Offset.Zero) }
                val contextMenuController = remember { com.streamify.app.ui.components.TrackContextMenuController() }

                // --- PILLAR 4: Root Safe Harbor & Double-Back-to-Exit Guard ---
                var lastBackPressedTime by remember { mutableStateOf(0L) }
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route ?: "home"

                BackHandler(enabled = true) {
                    if (isPlayerExpanded) {
                        isPlayerExpanded = false
                    } else if (currentRoute != "home") {
                        navController.navigate("home") {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastBackPressedTime < 2000) {
                            this@MainActivity.finish()
                        } else {
                            lastBackPressedTime = now
                            android.widget.Toast.makeText(this@MainActivity, "Press back again to exit", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                LaunchedEffect(playerState.currentTrack) {
                    if (playerState.currentTrack != null && quantumController.stage == com.streamify.app.ui.components.TokenStage.FLYING) {
                        quantumController.onTrackReady()
                    }
                }

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
                            androidx.palette.graphics.Palette.from(bitmap)
                                .resizeBitmapArea(112 * 112)
                                .generate { palette ->
                                    palette?.dominantSwatch?.rgb?.let { colorInt ->
                                        targetColor = Color(colorInt)
                                    } ?: palette?.mutedSwatch?.rgb?.let { colorInt ->
                                        targetColor = Color(colorInt)
                                    }
                                }
                        }
                    }
                }

                if (!isSplashDone) {
                    PrismaticSplashScreen(
                        onPreWarmComplete = {
                            val prefs = getSharedPreferences("audio_settings", android.content.Context.MODE_PRIVATE)
                            com.streamify.app.service.CrossfadeAudioProcessor.crossfadeDurationMs =
                                (prefs.getFloat("crossfade_val", 0f) * 1000).toLong()
                            AuthManager.init(this@MainActivity)
                            playerViewModel.initialize(this@MainActivity)
                            com.streamify.app.data.PlaylistRepository.init(this@MainActivity)
                            com.streamify.app.data.TrackRepository.getAllTracks()
                            com.streamify.app.data.remote.StreamifyUpdateManager.checkForUpdates(this@MainActivity)
                        },
                        onAnimationComplete = {
                            isSplashDone = true
                        }
                    )
                } else if (authState !is AuthState.Authenticated) {
                    YtOnboardingScreen(
                        onComplete = {
                            // Automatically advances to Authenticated
                        }
                    )
                } else {
                    val snackbarHostState = remember { SnackbarHostState() }

                    LaunchedEffect(Unit) {
                        UiEventBus.events.collect { event ->
                            when (event) {
                                is UiEvent.ShowSnackbar -> {
                                    snackbarHostState.showSnackbar(
                                        message = event.message,
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            }
                        }
                    }

                    val hasTrack = playerState.currentTrack != null

                    // 2. GPU Fade for the Unified Dock during expansion
                    val dockAlpha by animateFloatAsState(
                        targetValue = if (isPlayerExpanded) 0f else 1f,
                        animationSpec = tween(durationMillis = 180),
                        label = "dockAlpha"
                    )

                    val effectiveDuration = if (playerState.duration > 0) playerState.duration else ((playerState.currentTrack?.durationSec?.toLong() ?: 0L) * 1000L)
                    val progress = if (effectiveDuration > 0)
                        (playerState.currentPosition.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
                    else 0f

                    CompositionLocalProvider(
                        LocalQuantumController provides quantumController,
                        LocalDockPosition provides dockPositionState,
                        com.streamify.app.ui.components.LocalContextMenuController provides contextMenuController
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(BgBase)
                        ) {
                            // --- LAYER 1: Master Scaffold & Unified Dock ---
                            Scaffold(
                                snackbarHost = {
                                    SnackbarHost(hostState = snackbarHostState) { data ->
                                        Snackbar(
                                            snackbarData = data,
                                            containerColor = Primary,
                                            contentColor = Color.White
                                        )
                                    }
                                },
                                bottomBar = {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .graphicsLayer { this.alpha = dockAlpha }
                                            .windowInsetsPadding(WindowInsets.navigationBars)
                                            .centerInLargeScreen()
                                            .onGloballyPositioned { coordinates ->
                                                val pos = coordinates.positionInWindow()
                                                dockPositionState.value = Offset(
                                                    pos.x + (coordinates.size.width / 2f),
                                                    pos.y + 28f
                                                )
                                            }
                                    ) {
                                        // Docked Mini-Player (Directly above BottomNav with zero overlap)
                                        AnimatedVisibility(
                                            visible = hasTrack,
                                            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(animationSpec = tween(200)),
                                            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(animationSpec = tween(200))
                                        ) {
                                            MiniPlayerBar(
                                                track = playerState.currentTrack,
                                                isPlaying = playerState.isPlaying,
                                                progress = progress,
                                                isBuffering = playerState.isBuffering,
                                                onPlayPause = { playerViewModel.togglePlayPause() },
                                                onNext = { playerViewModel.skipNext() },
                                                onPrevious = { playerViewModel.skipPrevious() },
                                                onExpand = { isPlayerExpanded = true },
                                                onToggleLike = { playerViewModel.toggleLike() },
                                                tokenController = quantumController
                                            )
                                        }

                                        // Docked Bottom Navigation (100% accessible at all times)
                                        YtBottomNavBar(
                                            currentRoute = currentRoute,
                                            onNavigate = { route ->
                                                navController.navigate(route) {
                                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        )
                                    }
                                },
                                containerColor = BgBase
                            ) { paddingValues ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .windowInsetsPadding(WindowInsets.statusBars)
                                        .padding(paddingValues)
                                        .centerInLargeScreen()
                                ) {
                                    AppNavGraph(
                                        navController = navController,
                                        playerViewModel = playerViewModel,
                                        dominantColor = dominantColor
                                    )
                                }
                            }

                            // --- LAYER 2: Quantum Sonic Token 3D Levitation Overlay ---
                            QuantumSonicTokenOverlay(controller = quantumController)

                            // --- LAYER 2b: Global Track Context Menu Host ---
                            com.streamify.app.ui.components.GlobalTrackContextMenuHost(
                                controller = contextMenuController,
                                playerViewModel = playerViewModel,
                                onGoToArtist = { artist ->
                                    navController.navigate("artist/${android.net.Uri.encode(artist)}")
                                },
                                onGoToAlbum = { album ->
                                    navController.navigate("album/${android.net.Uri.encode(album)}")
                                }
                            )

                            // --- LAYER 3: 120 FPS Spring Full-Player Overlay ---
                            AnimatedVisibility(
                                visible = isPlayerExpanded && hasTrack,
                                enter = slideInVertically(
                                    initialOffsetY = { it },
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                ) + fadeIn(animationSpec = tween(220)),
                                exit = slideOutVertically(
                                    targetOffsetY = { it },
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                ) + fadeOut(animationSpec = tween(220)),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zIndex(10f)
                            ) {
                            FullPlayerSheet(
                                track = playerState.currentTrack,
                                isPlaying = playerState.isPlaying,
                                progress = progress,
                                isBuffering = playerState.isBuffering,
                                durationMs = playerState.duration,
                                currentPositionMs = playerState.currentPosition,
                                isShuffleActive = playerState.isShuffleActive,
                                isRepeatActive = playerState.isRepeatActive,
                                dominantColor = dominantColor,
                                onCollapse = { isPlayerExpanded = false },
                                onPlayPause = { playerViewModel.togglePlayPause() },
                                onNext = { playerViewModel.skipNext() },
                                onPrevious = { playerViewModel.skipPrevious() },
                                onSeek = { f ->
                                    val dur = if (playerState.duration > 0) playerState.duration else ((playerState.currentTrack?.durationSec ?: 0) * 1000L)
                                    if (dur > 0) {
                                        val targetMs = (f * dur).toLong().coerceIn(0L, dur)
                                        playerViewModel.seekTo(targetMs)
                                    }
                                },
                                onShuffleToggle = { playerViewModel.toggleShuffle() },
                                onRepeatToggle = { playerViewModel.toggleRepeat() },
                                onToggleLike = { playerViewModel.toggleLike() },
                                onRadioClick = {
                                    playerViewModel.startSongRadio(playerState.currentTrack)
                                },
                                onJamClick = {
                                    isPlayerExpanded = false
                                    navController.navigate("jam")
                                },
                                onQueueClick = {
                                    isPlayerExpanded = false
                                    navController.navigate("queue")
                                },
                                onLyricsClick = {
                                    isPlayerExpanded = false
                                    navController.navigate("lyrics")
                                },
                                isAutoPlayEnabled = playerState.isAutoPlayEnabled,
                                onAutoPlayToggle = { playerViewModel.toggleAutoPlay() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        try {
            val dbPath = getDatabasePath("streamify_universal.db").absolutePath
            com.streamify.app.data.NativeBridge.shutdown(dbPath)
        } catch (e: Throwable) {
            // Ignore
        }
        super.onDestroy()
    }
}

private fun enqueueMediaScan(context: android.content.Context) {
    val workManager = androidx.work.WorkManager.getInstance(context)
    val scanRequest = androidx.work.OneTimeWorkRequestBuilder<com.streamify.app.service.IngestionWorker>()
        .addTag("ingestion_worker")
        .build()
    workManager.enqueueUniqueWork("media_scan", androidx.work.ExistingWorkPolicy.KEEP, scanRequest)
}
