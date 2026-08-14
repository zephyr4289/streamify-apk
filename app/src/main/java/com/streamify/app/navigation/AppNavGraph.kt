package com.streamify.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.streamify.app.data.models.Track
import com.streamify.app.ui.screens.*
import com.streamify.app.viewmodel.PlayerViewModel

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

@Composable
fun AppNavGraph(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    dominantColor: androidx.compose.ui.graphics.Color = com.streamify.app.ui.theme.StreamifyColors.BgBase
) {
    NavHost(
        navController = navController,
        startDestination = "home",
        modifier = Modifier.fillMaxSize(),
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(400)
            ) + fadeIn(animationSpec = tween(400))
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { -it / 2 },
                animationSpec = tween(400)
            ) + fadeOut(animationSpec = tween(400))
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { -it / 2 },
                animationSpec = tween(400)
            ) + fadeIn(animationSpec = tween(400))
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(400)
            ) + fadeOut(animationSpec = tween(400))
        }
    ) {
        composable("home") {
            HomeScreen(
                playerViewModel = playerViewModel,
                dominantColor = dominantColor,
                onTrackClick = { track, list ->
                    playerViewModel.playTrack(track, list)
                },
                onSettingsClick = {
                    navController.navigate("settings")
                }
            )
        }
        composable("search") {
            SearchScreen(
                playerViewModel = playerViewModel,
                onTrackClick = { track, allTracks ->
                    playerViewModel.playTrack(track, allTracks)
                }
            )
        }
        composable("library") {
            LibraryScreen(
                playerViewModel = playerViewModel,
                onTrackClick = { track, allTracks ->
                    playerViewModel.playTrack(track, allTracks)
                }
            )
        }
        composable("queue") {
            QueueScreen(
                playerViewModel = playerViewModel,
                onTrackClick = { trackId ->
                    val track = playerViewModel.playerState.value.queue.find { it.id == trackId }
                    if (track != null) {
                        playerViewModel.playTrack(track, playerViewModel.playerState.value.queue)
                    }
                }
            )
        }
        composable(
            "lyrics",
            enterTransition = {
                androidx.compose.animation.slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(400)
                ) + fadeIn(animationSpec = tween(400))
            },
            exitTransition = {
                androidx.compose.animation.slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(400)
                ) + fadeOut(animationSpec = tween(400))
            },
            popEnterTransition = {
                androidx.compose.animation.slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(400)
                ) + fadeIn(animationSpec = tween(400))
            },
            popExitTransition = {
                androidx.compose.animation.slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(400)
                ) + fadeOut(animationSpec = tween(400))
            }
        ) {
            val playerState = playerViewModel.playerState.collectAsState().value
            val context = androidx.compose.ui.platform.LocalContext.current
            var lyricsLines by androidx.compose.runtime.remember(playerState.currentTrack) {
                androidx.compose.runtime.mutableStateOf<List<com.streamify.app.data.models.LyricsLine>>(emptyList())
            }

            androidx.compose.runtime.LaunchedEffect(playerState.currentTrack) {
                val track = playerState.currentTrack
                if (track != null) {
                    lyricsLines = com.streamify.app.data.LyricsCacheManager.getOrFetchLyrics(context, track)
                } else {
                    lyricsLines = emptyList()
                }
            }

            LyricsScreen(
                lyrics = lyricsLines,
                currentPositionMs = playerState.currentPosition,
                dominantColor = dominantColor,
                onSeek = { ms -> playerViewModel.seekTo(ms) }
            )
        }
        composable("downloads") {
            DownloadScreen()
        }
        composable("settings") {
            SettingsScreen(
                playerViewModel = playerViewModel,
                onBack = { navController.popBackStack() },
                onNavigateToEq = { navController.navigate("eq") }
            )
        }
        composable("eq") {
            EqualizerScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable("artist/{artistName}") { backStackEntry ->
            val artistName = java.net.URLDecoder.decode(backStackEntry.arguments?.getString("artistName") ?: "", "UTF-8")
            val libraryState by com.streamify.app.data.TrackRepository.trackFlow.collectAsState(initial = emptyList())
            ArtistScreen(
                artistName = artistName,
                allTracks = libraryState,
                playerViewModel = playerViewModel,
                onBack = { navController.popBackStack() },
                onTrackClick = { track, list -> playerViewModel.playTrack(track, list) }
            )
        }
        composable("album/{albumName}") { backStackEntry ->
            val albumName = java.net.URLDecoder.decode(backStackEntry.arguments?.getString("albumName") ?: "", "UTF-8")
            val libraryState by com.streamify.app.data.TrackRepository.trackFlow.collectAsState(initial = emptyList())
            AlbumScreen(
                albumName = albumName,
                allTracks = libraryState,
                playerViewModel = playerViewModel,
                onBack = { navController.popBackStack() },
                onTrackClick = { track, list -> playerViewModel.playTrack(track, list) }
            )
        }
    }
}

