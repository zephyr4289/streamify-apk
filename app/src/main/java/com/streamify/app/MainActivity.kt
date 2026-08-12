package com.streamify.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.streamify.app.data.NativeBridge
import com.streamify.app.navigation.AppNavGraph
import com.streamify.app.ui.components.BottomNavBar
import com.streamify.app.ui.components.BottomTab
import com.streamify.app.ui.theme.StreamifyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            StreamifyTheme {
                val navController = rememberNavController()
                var currentTab by remember { mutableStateOf(BottomTab.HOME) }

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
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        AppNavGraph(navController = navController)
                        
                        // MiniPlayerBar would be placed here anchored to bottom above the nav bar.
                        // Implemented in Phase 4.
                    }
                }
            }
        }
    }
}
