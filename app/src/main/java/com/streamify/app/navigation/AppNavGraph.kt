package com.streamify.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.streamify.app.data.models.Track
import com.streamify.app.ui.screens.*
import com.streamify.app.viewmodel.PlayerViewModel

@Composable
fun AppNavGraph(
    navController: NavHostController,
    playerViewModel: PlayerViewModel
) {
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(onTrackClick = { trackId, allTracks ->
                val track = allTracks.find { it.id == trackId }
                if (track != null) playerViewModel.playTrack(track, allTracks)
            })
        }
        composable("search") {
            SearchScreen(onTrackClick = { trackId, allTracks ->
                val track = allTracks.find { it.id == trackId }
                if (track != null) playerViewModel.playTrack(track, allTracks)
            })
        }
        composable("library") {
            LibraryScreen(onTrackClick = { trackId, allTracks ->
                val track = allTracks.find { it.id == trackId }
                if (track != null) playerViewModel.playTrack(track, allTracks)
            })
        }
        composable("downloads") {
            DownloadScreen()
        }
        composable("player") {
            PlayerScreen(track = null) { navController.popBackStack() }
        }
    }
}

