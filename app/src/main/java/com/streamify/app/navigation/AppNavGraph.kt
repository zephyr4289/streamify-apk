package com.streamify.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.streamify.app.ui.screens.*

@Composable
fun AppNavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = "home") {
        composable("home") { 
            HomeScreen(onTrackClick = { trackId -> 
                // Navigate to player or just play it via viewmodel
            }) 
        }
        composable("search") { 
            SearchScreen(onTrackClick = { trackId -> 
                // Play track
            }) 
        }
        composable("library") { 
            LibraryScreen(onTrackClick = { trackId -> 
                // Play track
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
