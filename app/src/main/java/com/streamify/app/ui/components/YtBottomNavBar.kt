package com.streamify.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.*

@Composable
fun YtBottomNavBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    alpha: Float = 1f,
    modifier: Modifier = Modifier
) {
    Surface(
        color = BgBase,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer { this.alpha = alpha }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Hairline top border
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BorderSubtle)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                YtNavItem(
                    label = "Home",
                    activeIcon = Icons.Filled.Home,
                    inactiveIcon = Icons.Outlined.Home,
                    isSelected = currentRoute == "home" || currentRoute == null,
                    onClick = { onNavigate("home") }
                )
                YtNavItem(
                    label = "Explore",
                    activeIcon = Icons.Filled.Explore,
                    inactiveIcon = Icons.Outlined.Explore,
                    isSelected = currentRoute == "search" || currentRoute == "explore",
                    onClick = { onNavigate("search") }
                )
                YtNavItem(
                    label = "Library",
                    activeIcon = Icons.Filled.LibraryMusic,
                    inactiveIcon = Icons.Outlined.LibraryMusic,
                    isSelected = currentRoute == "library",
                    onClick = { onNavigate("library") }
                )
                YtNavItem(
                    label = "Downloads",
                    activeIcon = Icons.Filled.Download,
                    inactiveIcon = Icons.Outlined.Download,
                    isSelected = currentRoute == "downloads",
                    onClick = { onNavigate("downloads") }
                )
            }
        }
    }
}

@Composable
private fun RowScope.YtNavItem(
    label: String,
    activeIcon: ImageVector,
    inactiveIcon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val tintColor = if (isSelected) TextMain else TextSecondary

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 4.dp)
    ) {
        Icon(
            imageVector = if (isSelected) activeIcon else inactiveIcon,
            contentDescription = label,
            tint = tintColor,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            style = LocalAppTypography.current.chipText.copy(fontSize = 10.sp),
            color = tintColor
        )
    }
}
