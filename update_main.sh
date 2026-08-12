#!/bin/bash
cat << 'KOTLIN' > app/src/main/java/com/streamify/app/MainActivity.kt
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
import androidx.compose.runtime.remember
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

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { }

            LaunchedEffect(Unit) {
                if (!PermissionHelper.hasPermissions(this@MainActivity)) {
                    permissionLauncher.launch(PermissionHelper.REQUIRED_PERMISSIONS)
                }
            }

            StreamifyTheme {
                val navController = rememberNavController()
                var currentTab by remember { mutableStateOf(BottomTab.HOME) }
                val playerViewModel: PlayerViewModel = viewModel()
                val playerState by playerViewModel.playerState.collectAsState()

                LaunchedEffect(Unit) {
                    playerViewModel.initialize(this@MainActivity)
                }

                val screenHeight = LocalConfiguration.current.screenHeightDp.dp
                val density = LocalDensity.current
                val peekHeight = 140.dp
                val sheetState = rememberStandardBottomSheetState(
                    initialValue = SheetValue.PartiallyExpanded,
                    skipHiddenState = true
                )
                val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

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
                                        onExpand = { /* Let sheet swipe handle expansion */ },
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
                                        dominantColor = androidx.compose.ui.graphics.Color(0xFF1DB954),
                                        onCollapse = { /* Handle collapse via state if needed */ },
                                        onPlayPause = { playerViewModel.togglePlayPause() },
                                        onNext = { playerViewModel.skipNext() },
                                        onPrevious = { playerViewModel.skipPrevious() },
                                        onSeek = { f ->
                                            playerViewModel.seekTo((f * playerState.duration).toLong())
                                        },
                                        onShuffleToggle = { playerViewModel.toggleShuffle() },
                                        onRepeatToggle = { playerViewModel.toggleRepeat() },
                                        onToggleLike = { playerViewModel.toggleLike() },
                                        onQueueClick = { navController.navigate("queue") },
                                        onLyricsClick = { navController.navigate("lyrics") }
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        AppNavGraph(
                            navController = navController,
                            playerViewModel = playerViewModel
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
                                }
                                navController.navigate(route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = innerPadding.calculateBottomPadding())
                        )
                    }
                }
            }
        }
    }
}
KOTLIN
chmod +x update_main.sh
./update_main.sh
