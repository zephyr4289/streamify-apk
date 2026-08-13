package com.streamify.app.ui.theme

import androidx.compose.ui.graphics.Color

object StreamifyColors {
    // Backgrounds (dark-to-light hierarchy)
    val BgBase         = Color(0xFF000000)     // App root background
    val BgSurface      = Color(0xFF121212)     // Primary surface (avoids OLED smearing)
    val BgCard         = Color(0xFF181818)     // Card/container backgrounds
    val BgCardHover    = Color(0xFF282828)     // Elevated surfaces, pressed cards
    val BgElevated     = Color(0xFF282828)     // Mini player, dialogs, bottom sheets
    val BgMiniPlayer   = Color(0xFF282828)     // Mini player bar container
    val BgPlayer       = Color(0xFF0F0F0F)     // Full player background base
    val BgSearchBar    = Color(0xFF2A2A2A)     // Dark search bar input (Spotify exact)

    // Brand
    val Primary        = Color(0xFF1DB954)     // Spotify Green — CTAs, active states
    val PrimaryHover   = Color(0xFF1ED760)     // Green hover/pressed variant
    val PrimaryDark    = Color(0xFF169C46)     // Green for dark contexts

    // Text
    val TextMain       = Color(0xFFFFFFFF)     // Primary text — titles, active lyrics
    val TextSub        = Color(0xFFA7A7A7)     // Secondary text — artist names, metadata
    val TextDimmed     = Color(0xFF6A6A6A)     // Tertiary — timestamps, inactive elements
    val TextOnSearch   = Color(0xFFB3B3B3)     // Light gray text on dark search bar

    // Borders & Dividers
    val Border         = Color(0xFF242424)     // Subtle dividers
    val Divider        = Color(0xFF333333)     // Track list dividers

    // Semantic
    val ErrorRed       = Color(0xFFFF4D4D)     // Error states
    val ErrorBg        = Color(0x26EB5757)     // Error background tint
    val Explicit       = Color(0xFF9E9E9E)     // "E" badge background
    val Shuffle        = Color(0xFF1DB954)     // Active shuffle/repeat

    // Overlay
    val Scrim          = Color(0x99000000)     // 60% black overlay for modals
    val PlayerGradient = Color(0xCC121212)     // 80% dark for player gradient bottom
    
    // Elevations (Surface colors)
    val Surface0dp = Color(0xFF121212)
    val Surface1dp = Color(0xFF1E1E1E)
    val Surface2dp = Color(0xFF232323)
    val Surface3dp = Color(0xFF252525)
    val Surface4dp = Color(0xFF272727)

    // Category Gradients (for Browse cards)
    val GradientPop = listOf(Color(0xFFE13300), Color(0xFFFF8A65))
    val GradientHipHop = listOf(Color(0xFFBA5D07), Color(0xFFFFB74D))
    val GradientRock = listOf(Color(0xFFE91E63), Color(0xFFF48FB1))
    val GradientIndie = listOf(Color(0xFF608108), Color(0xFFAED581))
    val GradientWorkout = listOf(Color(0xFF777777), Color(0xFFE0E0E0))
    val GradientFocus = listOf(Color(0xFF509BF5), Color(0xFF90CAF9))
    val GradientChill = listOf(Color(0xFF8D67AB), Color(0xFFCE93D8))
    val GradientSleep = listOf(Color(0xFF1E3264), Color(0xFF5C6BC0))

    // Player dynamic gradient base fallback
    val PlayerDynamicBase = Color(0xFF404040)
}
