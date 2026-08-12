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
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { /* Handle result if needed */ }

            LaunchedEffect(Unit) {
                if (!PermissionHelper.hasPermissions(this@MainActivity)) {
                    permissionLauncher.launch(PermissionHelper.REQUIRED_PERMISSIONS)
                }
            }

            StreamifyTheme {
                val navController = rememberNavController()
                var currentTab by remember { mutableStateOf(BottomTab.HOME) }
                var showFullPlayer by remember { mutableStateOf(false) }

                val playerViewModel: PlayerViewModel = viewModel()
                val playerState by playerViewModel.playerState.collectAsState()

                // Initialize ExoPlayer MediaController binding once
                LaunchedEffect(Unit) {
                    playerViewModel.initialize(this@MainActivity)
                }

                Scaffold(
                    bottomBar = {
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
                            }
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        AppNavGraph(
                            navController = navController,
                            playerViewModel = playerViewModel
                        )

                        // MiniPlayerBar anchored at bottom above nav bar
                        if (playerState.currentTrack != null) {
                            val progress = if (playerState.duration > 0)
                                playerState.currentPosition.toFloat() / playerState.duration.toFloat()
                            else 0f

                            MiniPlayerBar(
                                track = playerState.currentTrack,
                                isPlaying = playerState.isPlaying,
                                progress = progress,
                                onPlayPause = { playerViewModel.togglePlayPause() },
                                onNext = { playerViewModel.skipNext() },
                                onPrevious = { playerViewModel.skipPrevious() },
                                onExpand = { showFullPlayer = true },
                                modifier = Modifier.align(Alignment.BottomCenter)
                            )
                        }
                    }
                }

                // Full Player Sheet
                if (showFullPlayer && playerState.currentTrack != null) {
                    val progress = if (playerState.duration > 0)
                        playerState.currentPosition.toFloat() / playerState.duration.toFloat()
                    else 0f

                    FullPlayerSheet(
                        track = playerState.currentTrack,
                        isPlaying = playerState.isPlaying,
                        progress = progress,
                        isShuffleActive = playerState.isShuffleActive,
                        isRepeatActive = playerState.isRepeatActive,
                        dominantColor = androidx.compose.ui.graphics.Color(0xFF1DB954),
                        onCollapse = { showFullPlayer = false },
                        onPlayPause = { playerViewModel.togglePlayPause() },
                        onNext = { playerViewModel.skipNext() },
                        onPrevious = { playerViewModel.skipPrevious() },
                        onSeek = { fraction ->
                            playerViewModel.seekTo((fraction * playerState.duration).toLong())
                        },
                        onShuffleToggle = { playerViewModel.toggleShuffle() },
                        onRepeatToggle = { playerViewModel.toggleRepeat() },
                        onToggleLike = { playerViewModel.toggleLike() },
                        onQueueClick = {
                            showFullPlayer = false
                            navController.navigate("queue")
                        },
                        onLyricsClick = {
                            showFullPlayer = false
                            navController.navigate("lyrics")
                        }
                    )
                }
            }
        }
    }
}

