package com.streamify.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.streamify.app.ui.screens.HomeScreen
import com.streamify.app.ui.screens.PlayerScreen

@Composable
fun AppNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = "home") {
        composable("home") { HomeScreen() }
        composable("player") { PlayerScreen(track = null) { navController.popBackStack() } }
    }
}
