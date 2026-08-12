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

@Composable
fun AppNavGraph(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    dominantColor: androidx.compose.ui.graphics.Color = com.streamify.app.ui.theme.StreamifyColors.BgBase
) {
    NavHost(
        navController = navController,
        startDestination = "home",
        modifier = Modifier.fillMaxSize()
    ) {
        composable("home") {
            HomeScreen(
                playerViewModel = playerViewModel,
                dominantColor = dominantColor,
                onTrackClick = { id, list ->
                    playerViewModel.playTrack(id, list)
                }
            )
        }
        composable("search") {
            SearchScreen(
                playerViewModel = playerViewModel,
                onTrackClick = { trackId, allTracks ->
                    val track = allTracks.find { it.id == trackId }
                    if (track != null) playerViewModel.playTrack(track, allTracks)
                }
            )
        }
        composable("library") {
            LibraryScreen(
                playerViewModel = playerViewModel,
                onTrackClick = { trackId, allTracks ->
                    val track = allTracks.find { it.id == trackId }
                    if (track != null) playerViewModel.playTrack(track, allTracks)
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
        composable("lyrics") {
            val playerState = playerViewModel.playerState.collectAsState().value
            val lyricsLines = remember(playerState.currentTrack) {
                val dbPath = playerState.currentTrack?.lyricsPath
                val path = if (dbPath.isNullOrBlank()) {
                    playerState.currentTrack?.filepath?.replace(".mp3", ".lrc")
                } else dbPath
                
                if (path != null && path.isNotBlank()) {
                    val file = java.io.File(path)
                    if (file.exists()) {
                        com.streamify.app.data.models.LyricsData.parseLrc(file.readText()).lines
                    } else emptyList()
                } else emptyList()
            }

            LyricsScreen(
                lyrics = lyricsLines,
                currentPositionMs = playerState.currentPosition,
                onSeek = { ms -> playerViewModel.seekTo(ms) }
            )
        }
        composable("player") {
            PlayerScreen(track = null) { navController.popBackStack() }
        }
    }
}

