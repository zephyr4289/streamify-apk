package com.streamify.app.ui.theme

import androidx.compose.ui.unit.dp

object StreamifyDimens {
    // Spacing Scale (4dp base grid)
    val SpaceXXS  = 2.dp
    val SpaceXS   = 4.dp
    val SpaceSM   = 8.dp
    val SpaceMD   = 12.dp
    val SpaceLG   = 16.dp
    val SpaceXL   = 20.dp
    val SpaceXXL  = 24.dp
    val SpaceHuge = 32.dp
    val SpaceGiant= 48.dp

    // Component Sizes
    val BottomNavHeight   = 56.dp
    val MiniPlayerHeight  = 56.dp
    val MiniPlayerMargin  = 8.dp      // Floating mini player margin from edges
    val MiniPlayerRadius  = 8.dp      // Floating mini player corner radius
    val MiniPlayerArt     = 40.dp     // Album art thumbnail in mini player
    val FullPlayerArtSize = 340.dp    // Large album art in full player
    val SearchBarHeight   = 48.dp
    val RecentCardHeight  = 48.dp     // Compact recent play grid items
    val RecentCardArt     = 48.dp     // Album art in recent grid
    val TrackRowHeight    = 56.dp     // Track list item height
    val TrackRowArt       = 48.dp     // Track list album art
    val CategoryCardH     = 100.dp    // Search browse category card height
    val ChipHeight        = 32.dp     // Filter chip height

    // Card Sizes
    val CardWidth         = 150.dp    // Standard carousel card width
    val CardArtSize       = 150.dp    // Square album art on cards (1:1)
    val ArtistCardSize    = 150.dp    // Circular artist card diameter

    // Radii
    val RadiusNone    = 0.dp
    val RadiusSM      = 4.dp
    val RadiusMD      = 8.dp
    val RadiusLG      = 12.dp
    val RadiusXL      = 16.dp
    val RadiusFull    = 50.dp     // Fully rounded (pills, search bar, play button)

    // Player Controls
    val PlayButtonSize    = 64.dp     // Main play/pause circle
    val SkipButtonSize    = 32.dp     // Next/previous icons
    val ShuffleButtonSize = 24.dp     // Shuffle/repeat icons
    val SeekBarHeight     = 4.dp      // Seekbar track thickness
    val SeekBarThumb      = 12.dp     // Seekbar thumb diameter
    val ProgressLineH     = 2.dp      // Mini player progress line height
    val DividerThickness  = 1.dp      // Standard divider thickness
    val PlayerBarHeight   = 72.dp     // MiniPlayerBar height + margin

    // Bottom Sheet
    val SheetPeekHeight   = 0.dp
    val SheetMaxHeight    = 0.92f     // 92% of screen height
}

object StreamifyAnimations {
    // Durations
    const val Fast = 150
    const val Normal = 300
    const val Slow = 500
    const val SuperSlow = 800

    // Spring Constants
    const val SpringStiffness = 300f
    const val SpringDamping = 0.75f

    // Stagger Delays
    const val StaggerBase = 50
    const val StaggerIncrement = 30
}
