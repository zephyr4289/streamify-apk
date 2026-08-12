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
}
