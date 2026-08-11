package com.streamify.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    background = StreamifyColors.BgBase,
    surface = StreamifyColors.BgSurface,
    primary = StreamifyColors.Primary,
    onPrimary = StreamifyColors.TextMain,
    onBackground = StreamifyColors.TextMain,
    onSurface = StreamifyColors.TextMain
)

@Composable
fun StreamifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
