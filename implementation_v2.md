# 🎧 Streamify APK — Implementation V2: UI/UX Engineering Blueprint

> **Version**: 2.0 — Pixel-Perfect Spotify Clone — Frontend & UI/UX Overhaul
> **Status**: Planning Phase — Backend Complete, Frontend at Ground Zero
> **Supersedes**: `implementation.md` (backend architecture — retained as reference)
> **Target**: Production-quality Spotify-identical Android Experience
> **Repository**: `github.com/zephyr4289/streamify-apk` (CI/CD) + `gitlab.com/sireenyadav/streamify-apk` (Mirror)

---

## 📋 Table of Contents

1. [Situation Assessment](#1-situation-assessment)
2. [Frontend Gap Analysis](#2-frontend-gap-analysis)
3. [Spotify Design System Specification](#3-spotify-design-system-specification)
4. [Screen-by-Screen Engineering Blueprints](#4-screen-by-screen-engineering-blueprints)
5. [Animation & Motion System](#5-animation--motion-system)
6. [State Architecture & ViewModel Redesign](#6-state-architecture--viewmodel-redesign)
7. [Component Library](#7-component-library)
8. [Navigation Architecture](#8-navigation-architecture)
9. [Failure Analysis & Solutions](#9-failure-analysis--solutions)
10. [Phased Execution Roadmap](#10-phased-execution-roadmap)
11. [File Manifest](#11-file-manifest)

---

## 🤖 AI EXECUTION INSTRUCTIONS

> **ATTENTION AI AGENT:** This document governs ALL frontend/UI work for Streamify APK.
> The backend (native C++, JNI bridge, Python download engine, SQLite, VectorStore, RecommendEngine) is **COMPLETE** and must NOT be modified unless explicitly called out in this document.

### How To Determine Which Phase To Execute

1. Check if `app/src/main/res/font/` contains `.ttf` files. If **EMPTY** → execute **Phase 1** (Design Foundation).
2. Check if `app/src/main/java/com/streamify/app/ui/components/TrackCard.kt` exists. If **NO** → execute **Phase 2** (Component Library).
3. Check if `app/src/main/java/com/streamify/app/ui/screens/HomeScreen.kt` is > 200 lines. If **NO** → execute **Phase 3** (Core Screens).
4. Check if `app/src/main/java/com/streamify/app/ui/player/FullPlayerSheet.kt` exists. If **NO** → execute **Phase 4** (Player System).
5. Check if `app/src/main/java/com/streamify/app/ui/screens/LibraryScreen.kt` exists. If **NO** → execute **Phase 5** (Library, Search & Download).
6. Check if `MiniPlayerBar.kt` contains `SwipeToDismiss` or swipe gesture logic. If **NO** → execute **Phase 6** (Animations & Polish).
7. If all checks pass → all phases are complete.

### Zero Demo Data Policy

**CRITICAL**: No hardcoded demo data, no mock tracks, no placeholder images, no fake recommendations. Every piece of UI content must come from:
- `NativeBridge` JNI calls to the real SQLite database
- `TrackRepository` Kotlin coroutine wrappers
- `MediaStoreScanner` device audio discovery
- `Chaquopy` Python download engine
- Real album art extracted from audio files or fetched via iTunes API

If the database is empty, the UI must show **elegant empty states** — not fake data.

---

## 1. Situation Assessment

### What Is Complete (Backend — Do Not Touch)

| Component | File | Status |
|:----------|:-----|:-------|
| SQLite DB Engine | `native/engine/StreamifyDB.cc/h` | ✅ Full CRUD, multi-user, WAL mode |
| ARM NEON VectorStore | `native/engine/VectorStore.cc/h` | ✅ 512-D cosine similarity |
| AI RecommendEngine | `native/engine/RecommendEngine.cc/h` | ✅ Two-stage session vector ranking |
| Event Tracker | `native/engine/EventTracker.cc/h` | ✅ Play/skip Markov logging |
| Audio DSP Pipeline | `native/ingest/AudioPipeline.cc/h` | ✅ kissfft + miniaudio |
| JNI Bridge | `native/jni/jni_bridge.cc` | ✅ All DB/AI/Vector calls exposed |
| Kotlin JNI Declarations | `data/NativeBridge.kt` | ✅ All external fun declarations |
| Track Repository | `data/TrackRepository.kt` | ✅ Coroutine wrappers |
| Data Models | `data/models/Track.kt`, `Recommendation.kt` | ✅ TrackNative ↔ Track mapping |
| Python Download Engine | `python/download_engine/*.py` | ✅ yt-dlp search + download |
| PlaybackService | `service/PlaybackService.kt` | ✅ Media3 ExoPlayer foreground service |
| DownloadService | `service/DownloadService.kt` | ✅ Foreground download service |
| IngestionWorker | `service/IngestionWorker.kt` | ✅ WorkManager background processor |
| MediaStoreScanner | `util/MediaStoreScanner.kt` | ✅ Device audio discovery |
| CI/CD Pipelines | `.github/workflows/*.yml` | ✅ Debug + Release APK builds |
| CMake NDK Build | `native/CMakeLists.txt` | ✅ Compiles libstreamify_core.so |

### What Is At Ground Zero (Frontend — This Document)

| Component | Current State | Required State |
|:----------|:-------------|:---------------|
| **MainActivity** | Shows `NativeBridge.stringFromJNI()` text | Full Scaffold with BottomNavBar + NavHost + MiniPlayer |
| **AppNavGraph** | 2 routes (home, player), no bottom nav | 4 tabs (Home, Search, Library, Downloads) + sheet routes |
| **HomeScreen** | Single `Text("Good Morning, User")` | Time-of-day greeting, recent grid, AI recommendation carousels, library grid |
| **SearchScreen** | Basic `OutlinedTextField` + dead button | Spotify search bar, browse categories, results list, download prompt |
| **PlayerScreen** | Shows `Text("Full Player: title")` | Full-screen modal with album art, gradient bg, seekbar, controls, lyrics |
| **MiniPlayerBar** | Shows `Text("Playing: title")` | Album art thumb, title marquee, play/pause + heart, progress line, swipe-up |
| **LyricsScreen** | Basic `LazyColumn` with timestamp sync | Blurred album art background, karaoke highlighting, tap-to-seek |
| **DownloadScreen** | Shows `Text("Downloads")` | Source selection cards, quality picker, step-by-step progress |
| **LibraryScreen** | Does not exist | Filter chips, grid/list toggle, liked songs, playlists |
| **QueueScreen** | Does not exist | Bottom sheet with drag-to-reorder upcoming tracks |
| **Theme/Type.kt** | `FontFamily.Default` | Actual Montserrat + Poppins `.ttf` font families |
| **Dimens.kt** | 5 dimension tokens | Complete spacing/sizing system (~30 tokens) |
| **Color.kt** | 12 color tokens | ✅ Already complete |
| **All Animations** | Zero animations | Spring player expand, card press scale, heart burst, shimmer loading |

---

## 2. Frontend Gap Analysis

### Critical Missing Files (Must Create)

```
app/src/main/java/com/streamify/app/
├── ui/
│   ├── player/
│   │   ├── FullPlayerSheet.kt          # Modal bottom sheet full-screen player
│   │   ├── PlayerControls.kt           # Shuffle/Prev/Play/Next/Repeat row
│   │   ├── PlayerSeekBar.kt            # Custom Spotify seekbar with timestamps
│   │   └── PlayerBackground.kt         # Dynamic gradient from album art Palette
│   ├── screens/
│   │   ├── HomeScreen.kt               # REWRITE — complete Spotify home
│   │   ├── SearchScreen.kt             # REWRITE — browse categories + search
│   │   ├── LibraryScreen.kt            # NEW — Your Library with filters
│   │   ├── DownloadScreen.kt           # REWRITE — source selection + progress
│   │   ├── ArtistScreen.kt             # NEW — artist detail with parallax
│   │   ├── AlbumScreen.kt              # NEW — album detail with track list
│   │   └── QueueScreen.kt              # NEW — drag-reorder queue bottom sheet
│   ├── components/
│   │   ├── MiniPlayerBar.kt            # REWRITE — floating bar + swipe gesture
│   │   ├── TrackCard.kt                # NEW — square album art card with play FAB
│   │   ├── TrackListItem.kt            # NEW — compact row for track lists
│   │   ├── ArtistCircleCard.kt         # NEW — circular artist card
│   │   ├── RecentPlayCard.kt           # NEW — compact recent play grid card
│   │   ├── CategoryCard.kt             # NEW — colorful search browse card
│   │   ├── SearchResultItem.kt         # NEW — search result with download action
│   │   ├── DownloadProgressCard.kt     # NEW — step-by-step download progress
│   │   ├── QualitySelector.kt          # NEW — quality picker bottom sheet
│   │   ├── EmptyStateView.kt           # NEW — elegant empty states
│   │   ├── ShimmerPlaceholder.kt       # NEW — loading shimmer effect
│   │   ├── BottomNavBar.kt             # NEW — custom Spotify bottom nav
│   │   ├── HeartButton.kt              # NEW — animated like toggle
│   │   ├── MarqueeText.kt             # NEW — auto-scrolling text overflow
│   │   └── ContextMenuSheet.kt         # NEW — long-press track options
│   ├── animations/
│   │   ├── PlayerTransition.kt         # NEW — mini ↔ full player spring
│   │   ├── CardPressEffect.kt          # NEW — scale-down press animation
│   │   └── HeartBurstEffect.kt         # NEW — like button particle burst
│   └── theme/
│       ├── Color.kt                    # ✅ EXISTS — add 3 missing tokens
│       ├── Type.kt                     # REWRITE — real font families
│       ├── Theme.kt                    # UPDATE — add shape system
│       ├── Dimens.kt                   # REWRITE — complete token system
│       └── Shape.kt                    # NEW — rounded corner presets
├── viewmodel/
│   ├── HomeViewModel.kt                # NEW — catalog + recommendations state
│   ├── PlayerViewModel.kt              # REWRITE — full ExoPlayer integration
│   ├── SearchViewModel.kt              # REWRITE — local + online search
│   ├── LibraryViewModel.kt             # NEW — liked songs + playlists state
│   ├── DownloadViewModel.kt            # NEW — download pipeline state
│   ├── QueueViewModel.kt               # NEW — queue management state
│   └── IngestionViewModel.kt           # UPDATE — remove mock simulation
├── navigation/
│   └── AppNavGraph.kt                  # REWRITE — bottom nav + sheet routing
├── data/
│   ├── TrackRepository.kt              # UPDATE — add recommendation + queue methods
│   └── models/
│       ├── Track.kt                    # UPDATE — add lyricsPath, source fields
│       ├── LyricsData.kt              # NEW — parsed LRC line model
│       ├── SearchCandidate.kt         # NEW — online search result model
│       └── DownloadState.kt           # NEW — download pipeline state model
├── service/
│   └── PlaybackService.kt             # UPDATE — wire to PlayerViewModel
└── util/
    ├── TimeGreeting.kt                # NEW — time-of-day greeting logic
    ├── DurationFormatter.kt           # NEW — seconds → mm:ss formatter
    ├── PaletteExtractor.kt            # NEW — album art dominant color extraction
    └── PermissionHelper.kt            # NEW — runtime permission handler
```

### Missing Font Assets

The `app/src/main/res/font/` directory is **EMPTY**. Required `.ttf` files:

```
res/font/
├── montserrat_regular.ttf
├── montserrat_medium.ttf
├── montserrat_semibold.ttf
├── montserrat_bold.ttf
├── montserrat_extrabold.ttf
├── poppins_light.ttf
├── poppins_regular.ttf
├── poppins_medium.ttf
└── poppins_semibold.ttf
```

**Source**: Download from Google Fonts API or include via Gradle font provider.

---

## 3. Spotify Design System Specification

### 3.1 Color System

```kotlin
// Color.kt — COMPLETE Spotify Android palette
object StreamifyColors {
    // Backgrounds (dark-to-light hierarchy)
    val BgBase         = Color(0xFF000000)     // App root background
    val BgSurface      = Color(0xFF121212)     // Primary surface (avoids OLED smearing)
    val BgCard         = Color(0xFF181818)     // Card/container backgrounds
    val BgCardHover    = Color(0xFF282828)     // Elevated surfaces, pressed cards
    val BgElevated     = Color(0xFF282828)     // Mini player, dialogs, bottom sheets
    val BgPlayer       = Color(0xFF0F0F0F)     // Full player background base
    val BgSearchBar    = Color(0xFFFFFFFF)     // Search input (white on dark)

    // Brand
    val Primary        = Color(0xFF1DB954)     // Spotify Green — CTAs, active states
    val PrimaryHover   = Color(0xFF1ED760)     // Green hover/pressed variant
    val PrimaryDark    = Color(0xFF169C46)     // Green for dark contexts

    // Text
    val TextMain       = Color(0xFFFFFFFF)     // Primary text — titles, active lyrics
    val TextSub        = Color(0xFFB3B3B3)     // Secondary text — artist names, metadata
    val TextDimmed     = Color(0xFF6A6A6A)     // Tertiary — timestamps, inactive elements
    val TextOnSearch   = Color(0xFF000000)     // Black text on white search bar

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
```

### 3.2 Typography System

```kotlin
// Type.kt — Spotify-authentic typography
val Montserrat = FontFamily(
    Font(R.font.montserrat_regular, FontWeight.Normal),
    Font(R.font.montserrat_medium, FontWeight.Medium),
    Font(R.font.montserrat_semibold, FontWeight.SemiBold),
    Font(R.font.montserrat_bold, FontWeight.Bold),
    Font(R.font.montserrat_extrabold, FontWeight.ExtraBold),
)

val Poppins = FontFamily(
    Font(R.font.poppins_light, FontWeight.Light),
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
)

// Semantic type styles
object StreamifyType {
    // Headings (Montserrat)
    val DisplayLarge  = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, letterSpacing = (-0.5).sp)
    val DisplayMedium = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.3).sp)
    val HeadlineLarge = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.2).sp)
    val HeadlineMedium= TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    val TitleLarge    = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
    val TitleMedium   = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    val TitleSmall    = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Bold, fontSize = 14.sp)

    // Body (Poppins)
    val BodyLarge     = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
    val BodyMedium    = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    val BodySmall     = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
    val Caption       = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.5.sp)

    // Player-specific
    val PlayerTitle   = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Bold, fontSize = 22.sp)
    val PlayerArtist  = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 15.sp, color = StreamifyColors.TextSub)
    val SeekbarTime   = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 11.sp, color = StreamifyColors.TextSub)

    // Card labels
    val CardTitle     = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2)
    val CardSubtitle  = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 13.sp, color = StreamifyColors.TextSub)
}
```

### 3.3 Dimension & Spacing System

```kotlin
// Dimens.kt — Complete spacing token system
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
    val RecentCardHeight  = 56.dp     // Compact recent play grid items
    val RecentCardArt     = 56.dp     // Album art in recent grid
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

    // Bottom Sheet
    val SheetPeekHeight   = 0.dp
    val SheetMaxHeight    = 0.92f     // 92% of screen height
}

// Shape.kt — Rounded corner presets
object StreamifyShapes {
    val CardShape      = RoundedCornerShape(8.dp)
    val MiniPlayerShape= RoundedCornerShape(8.dp)
    val ChipShape      = RoundedCornerShape(16.dp)
    val SearchBarShape = RoundedCornerShape(50.dp)
    val CategoryShape  = RoundedCornerShape(8.dp)
    val BottomSheet    = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    val PlayButton     = CircleShape
}
```

---

## 4. Screen-by-Screen Engineering Blueprints

### 4.1 HomeScreen — The Heart of Spotify

```
┌─────────────────────────────────────────┐
│  Good evening                    [⚙️]   │  ← Time-of-day greeting (28sp Bold Montserrat)
│                                         │
│  ┌──────────┬──────────┐                │  ← 2-column Recent Play grid (2×3 max)
│  │ 🎵 Art │ Title    ││ 🎵 Art │ Title │  │     56dp height each, #282828 bg, 4dp radius
│  ├──────────┼──────────┤                │     Left: 56×56 album art, Right: title (14sp)
│  │ 🎵 Art │ Title    ││ 🎵 Art │ Title │  │
│  ├──────────┼──────────┤                │
│  │ 🎵 Art │ Title    ││ 🎵 Art │ Title │  │
│  └──────────┴──────────┘                │
│                                         │
│  Made For You                           │  ← Section header (20sp Bold Montserrat)
│  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐  ──→ │  ← Horizontal LazyRow, 150×150dp cards
│  │     │ │     │ │     │ │     │       │     8dp corner radius, 16dp item spacing
│  │ Art │ │ Art │ │ Art │ │ Art │       │     Green play FAB appears on press
│  │     │ │     │ │     │ │     │       │     Title: 15sp Bold, 2 lines max
│  ├─────┤ ├─────┤ ├─────┤ ├─────┤       │     Subtitle: 13sp Regular #B3B3B3
│  │Title│ │Title│ │Title│ │Title│       │
│  │Sub  │ │Sub  │ │Sub  │ │Sub  │       │
│  └─────┘ └─────┘ └─────┘ └─────┘       │
│                                         │
│  Your Library                           │  ← All tracks section
│  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐  ──→ │
│  │ ... │ │ ... │ │ ... │ │ ... │       │
│  └─────┘ └─────┘ └─────┘ └─────┘       │
│                                         │
│  [Processing: 42/128 tracks ████░░░░]   │  ← IngestionStatusCard (only when active)
│                                         │
├─────────────────────────────────────────┤
│ 🎵 Art │ Title - Artist │ ♥ ▶ │────── │  ← MiniPlayerBar (floating, 56dp)
├─────────────────────────────────────────┤
│   🏠      🔍      📚      ⬇️          │  ← BottomNavBar (56dp, #121212)
└─────────────────────────────────────────┘
```

**Data Sources** (NO demo data):
- Greeting: `TimeGreeting.getGreeting()` → reads system clock
- Recent Plays: `TrackRepository.getRecentlyPlayed()` → queries `tracks` table ORDER BY last_played DESC
- Made For You: `NativeBridge.getRecommendations()` → real AI engine session vector results
- Your Library: `TrackRepository.getAllTracks()` → all tracks in SQLite
- Processing Status: `IngestionViewModel.ingestionState` → real WorkManager progress

**Empty States**:
- No tracks at all → Show "Welcome to Streamify" illustration + "Scan your music library" CTA button + "Search & download" CTA button
- No recommendations → Hide "Made For You" section entirely (don't show empty carousel)
- No recent plays → Hide recent grid, move greeting closer to first section

### 4.2 Full-Screen Player — The Showcase

```
┌─────────────────────────────────────────┐
│  ▼ (swipe down to collapse)             │  ← Drag handle indicator (40×4dp, #666)
│                                         │
│  ╔═══════════════════════════════════╗   │  ← Dynamic gradient background:
│  ║                                   ║   │     Extract dominant color from album art
│  ║                                   ║   │     via Android Palette API
│  ║         ┌───────────────┐         ║   │     Radial gradient: dominant → #121212
│  ║         │               │         ║   │
│  ║         │   Album Art   │         ║   │  ← 340×340dp, 12dp corner radius
│  ║         │   (Coil)      │         ║   │     Shadow: 24dp elevation
│  ║         │               │         ║   │     Crossfade animation on track change
│  ║         └───────────────┘         ║   │
│  ║                                   ║   │
│  ║   Track Title                     ║   │  ← 22sp Bold Montserrat, white
│  ║   Artist Name              ♥      ║   │  ← 15sp Regular Poppins, #B3B3B3
│  ║                                   ║   │     Heart: animated toggle, #1DB954 when liked
│  ║   0:42 ━━━━━━━━━●━━━━━━━ 3:21    ║   │  ← Custom seekbar:
│  ║                                   ║   │     Active track: white, Inactive: 30% white
│  ║         🔀  ⏮  ▶  ⏭  🔁        ║   │     Thumb: 12dp white circle
│  ║                                   ║   │     Time labels: 11sp Poppins #B3B3B3
│  ║                                   ║   │
│  ║   📱 Devices    📝 Lyrics   🎵 Queue ║   │  ← Bottom action row
│  ╚═══════════════════════════════════╝   │
└─────────────────────────────────────────┘
```

**Implementation Details**:
- Uses `ModalBottomSheet` with `sheetState.expand()` to fill screen
- Background gradient: Extract dominant swatch from album art bitmap via `Palette.from(bitmap).generate()`
- Seekbar: Custom `Canvas` composable, NOT Material3 `Slider` (Spotify's seekbar has specific behaviors: thin track, no padding, time labels outside, thumb appears on touch only)
- Play/Pause: White circle 64dp, `animateScale` on press (0.92f spring-back)
- Track change: Album art `Crossfade` with 300ms duration
- Heart: `AnimatedContent` with `scaleIn(initialScale = 0.5f) + fadeIn` transition, color change to Primary

**Gesture System**:
- Swipe down from any point → collapse to MiniPlayer (spring damping 0.7, stiffness Medium)
- Horizontal swipe on album art → skip track (with velocity threshold)

### 4.3 MiniPlayerBar — The Persistent Companion

```
┌───────────────────────────────────────────┐
│ ████████████████████░░░░░░░░░░░░░░░░░░░░ │  ← 2dp progress line at TOP edge
│                                           │     Color: white, tracks playback position
│  ┌────┐  Title of Track       ♥    ▶     │  ← 56dp height total
│  │Art │  Artist Name                      │     Art: 40×40dp, 4dp corner radius
│  └────┘                                   │     Title: 14sp SemiBold white (MarqueeText if overflow)
│                                           │     Artist: 12sp Regular #B3B3B3
└───────────────────────────────────────────┘     Heart: 20dp icon, Play: 24dp icon
                                                  Background: #2A2A2A or dominant color blend
                                                  Floating: 8dp margin sides + bottom, 8dp radius
                                                  Tap anywhere → expand to FullPlayerSheet
```

**Interactions**:
- Tap on bar body → expand to full player (spring animation)
- Tap play/pause → toggle playback (no expand)
- Tap heart → toggle like (no expand)
- Swipe right → dismiss/skip (optional, Spotify-like)
- Progress line auto-updates via `PlayerViewModel.currentPosition` flow

### 4.4 SearchScreen — Discovery Hub

```
┌─────────────────────────────────────────┐
│  🔍 Search songs, artists...     🎤    │  ← Search bar: white bg, black text
│                                         │     48dp height, full-radius pill shape
│                                         │     Voice icon on right (future)
│  Browse All                             │  ← Section header (20sp Bold)
│  ┌──────────┐ ┌──────────┐              │
│  │ Pop      │ │ Hip-Hop  │              │  ← 2-column grid of category cards
│  │   🎵    │ │   🎵    │              │     ~160×100dp each
│  └──────────┘ └──────────┘              │     Gradient backgrounds (randomized palettes)
│  ┌──────────┐ ┌──────────┐              │     Title: 16sp Bold, top-left corner
│  │ Rock     │ │ R&B      │              │     Category image: rotated 25° bottom-right
│  │   🎵    │ │   🎵    │              │
│  └──────────┘ └──────────┘              │
│  ┌──────────┐ ┌──────────┐              │
│  │ Chill    │ │ Workout  │              │
│  └──────────┘ └──────────┘              │
│                                         │
│  ── WHEN QUERY TYPED: ──                │
│                                         │
│  Local Results                          │
│  ┌─ 🎵 │ Track Title │ Artist │ ▶ ─┐  │  ← TrackListItem rows for local matches
│  ├─ 🎵 │ Track Title │ Artist │ ▶ ─┤  │
│  └─────────────────────────────────────┘│
│                                         │
│  ┌─────────────────────────────────┐    │  ← Download banner (gradient #1DB954→#169C46)
│  │ ☁️  Not found locally?          │    │     Appears when 0 local results
│  │     Search & download online    │    │     CTA button → opens DownloadScreen
│  │         [Download →]            │    │
│  └─────────────────────────────────┘    │
└─────────────────────────────────────────┘
```

**Data Sources**:
- Local search: `NativeBridge.searchTracks(query)` — real SQLite LIKE query
- Categories: Hardcoded genre list (Pop, Hip-Hop, Rock, R&B, Chill, Workout, Bollywood, Electronic, Jazz, Classical) — these are NOT demo data, they're UI navigation categories that filter real tracks by genre metadata
- Online search: `Python.getInstance().getModule("download_engine.search").callAttr("search_youtube", query)` — real yt-dlp results

### 4.5 LibraryScreen — Your Collection

```
┌─────────────────────────────────────────┐
│  Your Library                     ≡ ⊞   │  ← Header with list/grid toggle icons
│                                         │
│  [Playlists] [Artists] [Albums] [Liked] │  ← Horizontal chip row (scrollable)
│                                         │     Active chip: white bg, black text
│  Sort: Recently Added            ▼      │     Inactive chip: #282828 bg, white text
│                                         │
│  ── LIST VIEW: ──                       │
│  ┌────┐                                │
│  │ ♥ │  Liked Songs                    │  ← Special "Liked Songs" entry
│  │    │  42 songs                       │     Purple-green gradient art
│  └────┘                                │
│  ┌────┐                                │
│  │Art │  Album/Playlist Name           │  ← Standard 72dp row height
│  │    │  Artist • Album                │     64×64dp art, 16sp title, 14sp subtitle
│  └────┘                                │
│  ┌────┐                                │
│  │Art │  Another Album                 │
│  └────┘                                │
│                                         │
│  ── GRID VIEW: ──                       │
│  ┌─────┐ ┌─────┐                       │
│  │     │ │     │                       │  ← 2-column grid, same card as HomeScreen
│  │ Art │ │ Art │                       │
│  │     │ │     │                       │
│  ├─────┤ ├─────┤                       │
│  │Title│ │Title│                       │
│  └─────┘ └─────┘                       │
└─────────────────────────────────────────┘
```

**Data Sources**:
- Liked songs: `NativeBridge.getLikedTracks(userId)` — real per-user liked tracks
- All tracks: `NativeBridge.getAllTracks()` grouped by album
- Artists: Distinct artist names from tracks table
- Albums: Distinct album names from tracks table

### 4.6 DownloadScreen — Acquisition Pipeline

```
┌─────────────────────────────────────────┐
│  ← Back    Download                     │
│                                         │
│  Search: "Dil Nu"                       │
│                                         │
│  Best Match                      95%    │  ← Confidence badge
│  ┌─────────────────────────────────┐    │
│  │ 🖼 │ Dil Nu - AP Dhillon       │    │  ← Source selection card
│  │    │ AP Dhillon - Topic  [Official] │    │     Thumbnail, title, channel
│  │    │ 3:42                [📥]  │    │     Duration, Official/Edit badges
│  └─────────────────────────────────┘    │     Download icon button
│                                         │
│  Other Sources                          │
│  ┌─ 🖼 │ Dil Nu Remix │ DJ K │ 4:12 ─┐│
│  ├─ 🖼 │ Dil Nu Cover │ User │ 3:38 ─┤│  ← [⚠️ Edit] warning badge
│  └─────────────────────────────────────┘│
│                                         │
│  ── AFTER SELECTION: ──                 │
│                                         │
│  ┌─────────────────────────────────┐    │
│  │  Downloading...                 │    │
│  │  ✅ Audio stream downloaded     │    │  ← Step-by-step progress
│  │  ✅ HD cover art fetched        │    │     Each step: icon + label + checkmark
│  │  ⏳ Fetching synced lyrics...   │    │     Current step: spinner + green text
│  │  ○ Embedding metadata          │    │     Future steps: dimmed circle
│  │  ○ Generating AI embeddings    │    │
│  │  ○ Ready to play               │    │
│  │                                 │    │
│  │  ████████████░░░░░░░ 62%       │    │
│  └─────────────────────────────────┘    │
└─────────────────────────────────────────┘
```

### 4.7 LyricsScreen — Immersive Karaoke View

```
┌─────────────────────────────────────────┐
│  ╔═══════════════════════════════════╗   │
│  ║     (Heavily blurred album art    ║   │  ← Background: album art gaussian blur
│  ║      + 60% dark scrim overlay)    ║   │     radius 25dp, scrim #000000 60%
│  ║                                   ║   │
│  ║                                   ║   │
│  ║   Previous lyric line             ║   │  ← 20sp, #B3B3B3, FontWeight.Normal
│  ║                                   ║   │
│  ║   ██ Current active line ██       ║   │  ← 24sp, #FFFFFF, FontWeight.Bold
│  ║                                   ║   │     Auto-scroll to center
│  ║   Next upcoming line              ║   │  ← 20sp, #B3B3B3 50% alpha
│  ║                                   ║   │
│  ║   Another future line             ║   │
│  ║                                   ║   │
│  ║                                   ║   │
│  ╚═══════════════════════════════════╝   │
└─────────────────────────────────────────┘
    Tap any line → seek to that timestamp
    Vertical 32dp padding between lines
    LazyColumn with animateScrollToItem
```

### 4.8 QueueScreen — Up Next

```
┌─────────────────────────────────────────┐
│  ─── (drag handle) ───                  │  ← Bottom sheet, 92% screen height
│                                         │
│  Now Playing                            │
│  ┌────┐                                │
│  │Art │  Current Track Title           │  ← Highlighted current track
│  │ 48 │  Artist Name                   │
│  └────┘                                │
│                                         │
│  Next in Queue                          │
│  ┌────┐                         ≡      │  ← Drag handle for reorder
│  │Art │  Upcoming Track 1       ≡      │
│  └────┘                         ≡      │
│  ┌────┐                         ≡      │
│  │Art │  Upcoming Track 2       ≡      │
│  └────┘                         ≡      │
│  ┌────┐                         ≡      │
│  │Art │  Upcoming Track 3       ≡      │
│  └────┘                         ≡      │
└─────────────────────────────────────────┘
    Drag handles on right for reorder
    Swipe-to-remove individual tracks
    Long-press → context menu
```

---

## 5. Animation & Motion System

### 5.1 Animation Specifications

| Animation | Trigger | Compose API | Duration | Curve |
|:----------|:--------|:------------|:---------|:------|
| **Player Expand** | Tap mini player | `ModalBottomSheetState.show()` | ~400ms | `spring(dampingRatio = 0.7f, stiffness = StiffnessMediumLow)` |
| **Player Collapse** | Swipe down / tap ▼ | `ModalBottomSheetState.hide()` | ~350ms | `spring(dampingRatio = 0.8f, stiffness = StiffnessMedium)` |
| **Card Press** | Touch down on any card | `animateFloatAsState(targetValue = 0.95f)` + `Modifier.graphicsLayer { scaleX; scaleY }` | Instant down, 200ms spring up | `spring(stiffness = StiffnessHigh)` |
| **Play FAB Reveal** | Card press/hover | `AnimatedVisibility(enter = fadeIn(200ms) + slideInVertically(initialOffsetY = 8.dp))` | 200ms | `tween(easing = FastOutSlowInEasing)` |
| **Heart Toggle** | Tap heart button | `animateFloatAsState(1.0f → 1.3f → 1.0f)` scale + color animate to Primary | 300ms total | `keyframes { at(150ms) scale=1.3f; at(300ms) scale=1.0f }` |
| **Album Art Crossfade** | Track change | `Crossfade(targetState = currentTrack, animationSpec = tween(300))` | 300ms | Linear crossfade |
| **Seekbar Thumb** | Touch seekbar | Thumb visibility `animateFloatAsState(alpha 0→1)`, size `animate 0→12.dp` | 150ms | `tween` |
| **Shimmer Loading** | Data loading | Custom `shimmerBrush` via `infiniteTransition.animateFloat` on gradient offset | 1200ms per cycle | Linear, infinite repeat |
| **Screen Transition** | Navigate between tabs | `fadeIn(300ms) + fadeOut(200ms)` | 300ms | `tween(easing = FastOutSlowInEasing)` |
| **Pull to Refresh** | Swipe down on home | `PullToRefreshIndicator` Material3 | System default | System spring |
| **Marquee Text** | Title overflow in mini player | `MarqueeText` custom composable with `infiniteTransition.animateFloat` scroll offset | 6000ms per loop | Linear, 2000ms pause at ends |
| **Context Menu** | Long press track | `ModalBottomSheet` slide-up | 250ms | `spring(dampingRatio = 0.85f)` |
| **Snackbar** | Action feedback | Anchored above mini player, `AnimatedVisibility(slideInVertically + fadeIn)` | 200ms in, 3000ms visible, 200ms out | `tween` |

### 5.2 Custom Animation Composables

```kotlin
// CardPressEffect.kt
@Composable
fun Modifier.cardPressEffect(): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh)
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interactionSource, indication = null) { }
}

// ShimmerPlaceholder.kt
@Composable
fun shimmerBrush(): Brush {
    val shimmerColors = listOf(
        StreamifyColors.BgCard,
        StreamifyColors.BgCardHover,
        StreamifyColors.BgCard
    )
    val transition = rememberInfiniteTransition()
    val translateAnim by transition.animateFloat(
        initialValue = 0f, targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )
    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 200f, 0f),
        end = Offset(translateAnim, 0f)
    )
}

// MarqueeText.kt
@Composable
fun MarqueeText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    // Measure text width. If > container width, animate scroll offset
    // infiniteTransition: pause 2s → scroll left over 4s → pause 2s → reset
}
```

---

## 6. State Architecture & ViewModel Redesign

### 6.1 PlayerViewModel — Complete Rewrite

The current `PlayerViewModel` is a stub. The new version must:

```kotlin
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    // --- State ---
    data class PlayerState(
        val currentTrack: Track? = null,
        val queue: List<Track> = emptyList(),
        val queueIndex: Int = 0,
        val isPlaying: Boolean = false,
        val isShuffled: Boolean = false,
        val repeatMode: RepeatMode = RepeatMode.OFF,  // OFF, ONE, ALL
        val currentPositionMs: Long = 0,
        val durationMs: Long = 0,
        val isBuffering: Boolean = false,
        val dominantColor: Color = StreamifyColors.BgSurface,
        val isPlayerExpanded: Boolean = false,
    )
    enum class RepeatMode { OFF, ONE, ALL }

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    // --- ExoPlayer Integration ---
    private var exoPlayer: ExoPlayer? = null

    fun bindPlayer(player: ExoPlayer) {
        exoPlayer = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { ... }
            override fun onIsPlayingChanged(isPlaying: Boolean) { ... }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { ... }
        })
        // Launch coroutine to poll position every 200ms while playing
    }

    // --- Actions ---
    fun playTrack(track: Track, queue: List<Track> = listOf(track))
    fun playPause()
    fun seekTo(positionMs: Long)
    fun skipNext()
    fun skipPrevious()
    fun toggleShuffle()
    fun toggleRepeat()
    fun toggleLike()
    fun addToQueue(track: Track)
    fun removeFromQueue(index: Int)
    fun reorderQueue(from: Int, to: Int)

    // --- AI Integration ---
    fun loadRecommendationsIntoQueue()  // Calls NativeBridge.getRecommendations()
    fun logPlayEvent(fromTrackId: Int, toTrackId: Int)
    fun logSkipEvent(fromTrackId: Int, toTrackId: Int)
}
```

### 6.2 HomeViewModel — New

```kotlin
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    data class HomeState(
        val greeting: String = "",
        val recentTracks: List<Track> = emptyList(),
        val recommendations: List<Track> = emptyList(),
        val allTracks: List<Track> = emptyList(),
        val isLoading: Boolean = true,
        val ingestionState: IngestionState = IngestionState()
    )

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init { loadHome() }

    private fun loadHome() {
        viewModelScope.launch {
            _state.update { it.copy(
                greeting = TimeGreeting.getGreeting(),
                isLoading = true
            )}
            val tracks = withContext(Dispatchers.IO) {
                NativeBridge.getAllTracks().map { it.toTrack() }
            }
            _state.update { it.copy(
                allTracks = tracks,
                recentTracks = tracks.take(6),  // Most recent
                isLoading = false
            )}
            loadRecommendations(tracks.firstOrNull())
        }
    }

    private fun loadRecommendations(seedTrack: Track?) {
        if (seedTrack == null) return
        viewModelScope.launch(Dispatchers.IO) {
            val recs = NativeBridge.getRecommendations(
                seedTrack.id, intArrayOf(), 1, 10
            )
            val recTracks = recs.mapNotNull { rec ->
                NativeBridge.getAllTracks().find { it.id == rec.trackId }?.toTrack()
            }
            _state.update { it.copy(recommendations = recTracks) }
        }
    }

    fun refresh() { loadHome() }
}
```

### 6.3 SearchViewModel — Rewrite with JSON Parsing

```kotlin
class SearchViewModel(application: Application) : AndroidViewModel(application) {

    data class SearchState(
        val query: String = "",
        val localResults: List<Track> = emptyList(),
        val onlineResults: List<SearchCandidate> = emptyList(),
        val isSearchingLocal: Boolean = false,
        val isSearchingOnline: Boolean = false,
        val showDownloadPrompt: Boolean = false,
    )

    fun searchLocal(query: String)    // NativeBridge.searchTracks()
    fun searchOnline(query: String)   // Python yt-dlp via Chaquopy
    fun clearSearch()
}
```

### 6.4 DownloadViewModel — New

```kotlin
class DownloadViewModel : ViewModel() {
    data class DownloadState(
        val candidates: List<SearchCandidate> = emptyList(),
        val selectedCandidate: SearchCandidate? = null,
        val selectedQuality: String = "best",
        val currentStep: Int = 0,        // 0-6 pipeline steps
        val stepLabels: List<String> = listOf(
            "Downloading audio stream...",
            "Fetching HD cover art...",
            "Fetching synced lyrics...",
            "Embedding metadata tags...",
            "Generating AI embeddings...",
            "Ready to play!"
        ),
        val overallProgress: Float = 0f,
        val isDownloading: Boolean = false,
        val downloadedTrack: Track? = null,
        val error: String? = null,
    )
}
```

### 6.5 LibraryViewModel — New

```kotlin
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    data class LibraryState(
        val filter: LibraryFilter = LibraryFilter.ALL,
        val viewMode: ViewMode = ViewMode.LIST,
        val sortBy: SortBy = SortBy.RECENTLY_ADDED,
        val likedTracks: List<Track> = emptyList(),
        val allTracks: List<Track> = emptyList(),
        val artists: List<String> = emptyList(),
        val albums: List<String> = emptyList(),
    )
    enum class LibraryFilter { ALL, LIKED, ARTISTS, ALBUMS }
    enum class ViewMode { LIST, GRID }
    enum class SortBy { RECENTLY_ADDED, ALPHABETICAL, ARTIST }
}
```

---

## 7. Component Library

### 7.1 Component Specifications

| Component | Props | Layout | Interactions |
|:----------|:------|:-------|:-------------|
| **TrackCard** | track: Track, onClick, onPlayClick | 150×(150+60)dp vertical, square art + text below | cardPressEffect, play FAB reveal on press |
| **ArtistCircleCard** | name: String, imageUri: Uri?, onClick | 150dp circle clip + name below | cardPressEffect |
| **RecentPlayCard** | track: Track, onClick | 56dp height, horizontal, art(56×56) + title | Background #282828, 4dp radius |
| **TrackListItem** | track: Track, showArt, onClick, onMore | 56dp row, art(48×48) + title/artist + more(⋮) | Ripple, long-press → ContextMenuSheet |
| **MiniPlayerBar** | playerState, onExpand, onPlayPause, onLike | 56dp floating bar, 8dp margins + radius | Tap → expand, tap controls → action |
| **CategoryCard** | name: String, color: Color | 160×100dp, gradient bg, title top-left | cardPressEffect → filter tracks by genre |
| **SearchResultItem** | candidate: SearchCandidate, onDownload | Row with thumbnail + title + channel + download btn | Tap download → start pipeline |
| **DownloadProgressCard** | state: DownloadState | Card with step-by-step checklist + progress bar | Non-interactive (display only) |
| **QualitySelector** | selectedQuality, onSelect | BottomSheet with 4 radio options | Tap to select quality |
| **EmptyStateView** | icon, title, subtitle, actionLabel?, onAction? | Centered column, muted illustration | Optional CTA button |
| **ShimmerPlaceholder** | shape: Shape, width, height | Animated gradient rectangle | Non-interactive |
| **HeartButton** | isLiked: Boolean, onToggle | 24dp icon, animated scale + color | Tap → toggle with burst animation |
| **MarqueeText** | text, style | Scrolling text when overflow | Auto-scroll after 2s pause |
| **BottomNavBar** | currentRoute, onNavigate | 56dp, 4 destinations with icons + labels | Tap → navigate, no swipe between tabs |
| **ContextMenuSheet** | track: Track, options | ModalBottomSheet with action list | Tap option → execute action |

---

## 8. Navigation Architecture

### 8.1 Navigation Graph

```kotlin
// AppNavGraph.kt — Complete routing
sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Search : Screen("search")
    object Library : Screen("library")
    object Downloads : Screen("downloads")
    object Artist : Screen("artist/{artistName}") {
        fun createRoute(name: String) = "artist/$name"
    }
    object Album : Screen("album/{albumName}") {
        fun createRoute(name: String) = "album/$name"
    }
}

@Composable
fun AppNavGraph(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    bottomSheetState: SheetState,
) {
    NavHost(navController, startDestination = Screen.Home.route) {
        composable(Screen.Home.route) { HomeScreen(navController, playerViewModel) }
        composable(Screen.Search.route) { SearchScreen(navController) }
        composable(Screen.Library.route) { LibraryScreen(navController, playerViewModel) }
        composable(Screen.Downloads.route) { DownloadScreen(navController) }
        composable(Screen.Artist.route) { backStackEntry ->
            ArtistScreen(backStackEntry.arguments?.getString("artistName") ?: "")
        }
        composable(Screen.Album.route) { backStackEntry ->
            AlbumScreen(backStackEntry.arguments?.getString("albumName") ?: "")
        }
    }
}
```

### 8.2 MainActivity Scaffold

```kotlin
// MainActivity.kt — Complete rewrite
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Initialize native DB
        val dbPath = getDatabasePath("streamify.db").absolutePath
        NativeBridge.initDatabase(dbPath)

        setContent {
            StreamifyTheme {
                val navController = rememberNavController()
                val playerViewModel: PlayerViewModel = viewModel()
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                Scaffold(
                    containerColor = StreamifyColors.BgBase,
                    bottomBar = {
                        Column {
                            MiniPlayerBar(
                                state = playerViewModel.state.collectAsState().value,
                                onExpand = { /* expand sheet */ },
                                onPlayPause = { playerViewModel.playPause() },
                                onLike = { playerViewModel.toggleLike() }
                            )
                            BottomNavBar(navController)
                        }
                    }
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        AppNavGraph(navController, playerViewModel, sheetState)
                    }
                }

                // Full Player Sheet (overlays everything)
                if (sheetState.isVisible) {
                    ModalBottomSheet(
                        sheetState = sheetState,
                        containerColor = Color.Transparent,
                        dragHandle = null
                    ) {
                        FullPlayerSheet(playerViewModel)
                    }
                }
            }
        }
    }
}
```

---

## 9. Failure Analysis & Solutions

### 9.1 Build Failures

| Failure Point | Root Cause | Solution |
|:-------------|:-----------|:---------|
| **Missing font `.ttf` files** | `res/font/` is empty, `Type.kt` references `R.font.*` | Download Montserrat + Poppins TTFs from Google Fonts, place in `res/font/`. Use `FontFamily.Default` as fallback until fonts are added. |
| **Missing ProGuard rules** | `proguard-rules.pro` not found, referenced in `build.gradle.kts` | Create `app/proguard-rules.pro` with rules to keep JNI classes: `-keep class com.streamify.app.data.models.** { *; }` `-keep class com.streamify.app.data.NativeBridge { *; }` |
| **Missing `ic_launcher` mipmap** | `mipmap-xxxhdpi/ic_launcher.png` and `ic_launcher_round.png` not present as proper launcher icons | Generate adaptive icon from existing `drawable/logo.png` using `ic_launcher_foreground.xml` + `ic_launcher_background.xml` already in place |
| **ONNX Runtime AAR missing** | `native/third_party/onnxruntime/` exists but no `.so` files for ARM | Add `implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")` to `build.gradle.kts` OR download pre-built ARM `.so` into `jniLibs/` |
| **Chaquopy Python build failure** | Chaquopy requires specific Gradle configuration and may fail on certain host OS | Ensure `chaquopy` plugin version matches `15.0.1`. Add `buildPython "/usr/bin/python3"` if auto-detection fails. Pin `yt-dlp` version: `install("yt-dlp==2024.1.1")` |
| **JNI signature mismatch** | `TrackNative` constructor signature in `jni_bridge.cc` must exactly match Kotlin class field order | Verify JNI constructor descriptor `(ILjava/lang/String;...;ILjava/lang/String;)V` matches `TrackNative` data class field order precisely. Any field reorder in Kotlin WILL crash. |
| **NDK CMake version mismatch** | `CMakeLists.txt` specifies `cmake_minimum_required(VERSION 3.22.1)` but NDK may bundle older | Lock NDK to `26.1.10909125` in CI. Locally ensure `sdkmanager "cmake;3.22.1"` is installed. |
| **Gradle wrapper missing** | `gradle/wrapper/gradle-wrapper.jar` might not be committed (often gitignored) | Add `gradle-wrapper.jar` to git: `git add -f gradle/wrapper/gradle-wrapper.jar`. CI uses `./gradlew` which requires it. |
| **`PlaybackService` not in Manifest** | `PlaybackService` and `DownloadService` not declared in `AndroidManifest.xml` | Add `<service android:name=".service.PlaybackService" android:foregroundServiceType="mediaPlayback" android:exported="false" />` and same for `DownloadService` with `dataSync` type |
| **Missing `FOREGROUND_SERVICE_DATA_SYNC` permission** | `DownloadService` needs this on API 34+ | Add `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />` to manifest |
| **Compose Material3 BottomSheet API** | `ModalBottomSheet` is experimental in Compose BOM 2024.02 | Add `@OptIn(ExperimentalMaterial3Api::class)` annotations where needed |

### 9.2 Runtime Crashes

| Crash Scenario | Root Cause | Solution |
|:-------------|:-----------|:---------|
| **App crash on first launch** | `NativeBridge.initDatabase()` never called before DB queries | Call `NativeBridge.initDatabase(dbPath)` in `StreamifyApp.onCreate()` BEFORE any UI renders. Use `applicationContext.getDatabasePath("streamify.db").absolutePath` |
| **Empty tracks → NPE in player** | `PlayerViewModel.playTrack()` called with null track from empty list | Guard all `TrackCard.onClick` with null checks. `MiniPlayerBar` already returns early on null track. |
| **JNI crash on `getAllTracks()` with 0 rows** | `convertTrackList()` returns empty `jobjectArray` — this is safe, but callers may not handle empty arrays | Already handled: returns `Array<TrackNative>` of size 0. Kotlin `.map { }` on empty array is safe. |
| **Chaquopy `Python.getInstance()` before `Python.start()`** | Python runtime not initialized | Call `Python.start(new AndroidPlatform(context))` in `StreamifyApp.onCreate()`. Check with `Python.isStarted()` first. |
| **ExoPlayer `setMediaItem` with invalid path** | Track filepath doesn't exist (deleted file, unmounted SD) | Wrap `player.setMediaItem(MediaItem.fromUri(track.filepath))` in try-catch. Show snackbar "Track not available" on FileNotFoundException. |
| **SQLite WAL corruption on force-kill** | Thread-local connections not properly closed | WAL mode handles this gracefully. Add `PRAGMA wal_checkpoint(TRUNCATE)` on app background via `ProcessLifecycleOwner`. |
| **VectorStore `.bin` file missing** | `NativeBridge.initVectorStore()` called with non-existent path | Create empty `.bin` file on first launch if missing: `File(vectorPath).also { if (!it.exists()) it.createNewFile() }` |
| **OOM on large album art Palette extraction** | Full-resolution bitmap loaded for `Palette.from()` | Scale bitmap to 100×100 before palette extraction: `Bitmap.createScaledBitmap(original, 100, 100, true)` |
| **ANR on main thread DB query** | JNI `NativeBridge.getAllTracks()` called on main thread | ALL `NativeBridge` calls MUST be in `withContext(Dispatchers.IO) {}` via ViewModels. Never call from `@Composable` directly. |
| **Notification channel not created** | Android 8+ requires notification channels for foreground services | Create `"streamify_playback"` and `"streamify_download"` notification channels in `StreamifyApp.onCreate()` |

### 9.3 UI/UX Failures

| Failure Scenario | Root Cause | Solution |
|:-------------|:-----------|:---------|
| **White flash on app launch** | `Theme.Streamify` parent is `Theme.Material.Light.NoActionBar` | Change `themes.xml` to: `<style name="Theme.Streamify" parent="android:Theme.Material.NoActionBar">` with `<item name="android:windowBackground">@android:color/black</item>` and `<item name="android:statusBarColor">@android:color/black</item>` |
| **Status bar / nav bar overlap** | Edge-to-edge not configured properly | Use `WindowCompat.setDecorFitsSystemWindows(window, false)` + `systemBarsPadding()` modifier on content + `navigationBarsPadding()` on bottom bar |
| **MiniPlayer hidden behind nav bar** | System navigation bar overlaps custom bottom bar | Apply `Modifier.navigationBarsPadding()` to the bottom `Column` containing MiniPlayer + BottomNav |
| **Seekbar jumpy/laggy** | Using Material3 `Slider` which has built-in padding and thumb constraints | Build custom seekbar with `Canvas` + `pointerInput(detectHorizontalDragGestures)` for pixel-perfect control |
| **Text truncation on small screens** | Fixed text sizes don't adapt | Use `maxLines` + `overflow = TextOverflow.Ellipsis` on all title texts. Use `MarqueeText` in mini player. |
| **Album art not loading** | `Coil` can't load from local file paths starting with `/` | Use `rememberAsyncImagePainter(model = File(track.coverArtPath))` with Coil. Add `placeholder` and `error` drawables. |
| **Categories show with 0 tracks** | Genre filter returns empty for unpopulated genres | Only show category cards for genres that have ≥1 track: query `SELECT DISTINCT genre FROM tracks` |

### 9.4 Compatibility Failures

| Issue | Affected Devices | Solution |
|:------|:----------------|:---------|
| **NEON not available** | Very old armeabi-v7a devices without NEON | `VectorStore.cc` already has `#if defined(__ARM_NEON)` guards with scalar fallback. Safe. |
| **API 26 missing `foregroundServiceType`** | Android 8-8.1 (API 26-27) | `foregroundServiceType` is only required on API 29+. Use `if (Build.VERSION.SDK_INT >= 29)` guard in manifest with `tools:targetApi="29"`. |
| **No `READ_MEDIA_AUDIO` on API < 33** | Android 8-12 | Already handled: manifest has `READ_EXTERNAL_STORAGE` with `maxSdkVersion="32"` and `READ_MEDIA_AUDIO` for 33+. |
| **Coil memory pressure** | Low-RAM devices (2-3GB) | Configure Coil `ImageLoader` with `memoryCachePolicy(CachePolicy.ENABLED)` and `diskCachePolicy(CachePolicy.ENABLED)`, limit memory cache to 25% of available. |
| **SQLite concurrent write contention** | Multiple threads writing simultaneously | WAL mode + `PRAGMA busy_timeout = 5000` already configured in `StreamifyDB.cc`. Thread-local connections prevent lock contention. |

---

## 10. Phased Execution Roadmap

### Phase 1: Design Foundation (Fonts, Theme, Dimensions)

**Objective**: Establish the complete Spotify design system in Compose.

**Steps**:

1. **Download and place font files**:
   Download Montserrat (Regular, Medium, SemiBold, Bold, ExtraBold) and Poppins (Light, Regular, Medium, SemiBold) `.ttf` files. Place in `app/src/main/res/font/` with lowercase_underscore names.

2. **Rewrite `Type.kt`**: Replace `FontFamily.Default` with actual `FontFamily` using `Font(R.font.montserrat_regular, ...)` declarations. Define all `StreamifyType` semantic text styles.

3. **Rewrite `Dimens.kt`**: Replace 5 tokens with complete ~30-token spacing system as specified in Section 3.3.

4. **Create `Shape.kt`**: Define `StreamifyShapes` object with card, chip, search bar, bottom sheet corner presets.

5. **Update `Color.kt`**: Add missing tokens: `BgElevated`, `BgSearchBar`, `TextDimmed`, `TextOnSearch`, `Divider`, `Explicit`, `Scrim`, `PlayerGradient`, `PrimaryDark`, `Shuffle`.

6. **Update `Theme.kt`**: Wire new typography, add shape system to `MaterialTheme`, configure status bar colors.

7. **Fix `themes.xml`**: Change parent theme to dark, set `windowBackground` to black, set `statusBarColor` to black.

8. **Create `app/proguard-rules.pro`**: Add keep rules for JNI classes and data models.

**Commit**: `feat(ui): establish Spotify design system — fonts, typography, colors, dimensions, shapes`

---

### Phase 2: Component Library

**Objective**: Build all reusable UI components before screens.

**Steps**:

1. **Create `EmptyStateView.kt`**: Centered column with icon, title, subtitle, optional CTA button.

2. **Create `ShimmerPlaceholder.kt`**: Animated gradient loading placeholder matching card dimensions.

3. **Create `TrackCard.kt`**: Square album art (Coil) + title + subtitle + animated play FAB overlay.

4. **Create `ArtistCircleCard.kt`**: Circular-clipped image + name text below.

5. **Create `RecentPlayCard.kt`**: Compact 56dp horizontal card with art + title, #282828 background.

6. **Create `TrackListItem.kt`**: 56dp row with art, title, artist, more button. Long-press support.

7. **Create `CategoryCard.kt`**: Gradient background + title text, for search browse grid.

8. **Create `HeartButton.kt`**: Animated like toggle with scale burst and color change.

9. **Create `MarqueeText.kt`**: Auto-scrolling text for overflowed titles.

10. **Create `BottomNavBar.kt`**: 4-destination nav bar (Home, Search, Library, Downloads) matching Spotify's icon style.

11. **Create `ContextMenuSheet.kt`**: Long-press bottom sheet with track action options.

12. **Create utility files**: `TimeGreeting.kt`, `DurationFormatter.kt`, `PaletteExtractor.kt`, `PermissionHelper.kt`.

**Commit**: `feat(ui): build complete component library — cards, buttons, navigation, utilities`

---

### Phase 3: Core Screens (Home, Library, Search)

**Objective**: Build the three main tab screens with real data.

**Steps**:

1. **Create `HomeViewModel.kt`**: Wire to `NativeBridge.getAllTracks()`, `getRecommendations()`, `TimeGreeting`.

2. **Rewrite `HomeScreen.kt`**: Time-of-day greeting, recent play 2-column grid, "Made For You" horizontal carousel, "Your Library" carousel, ingestion status card, pull-to-refresh, shimmer loading states, empty state for no tracks.

3. **Create `LibraryViewModel.kt`**: Wire to liked tracks, all tracks, artist/album grouping.

4. **Create `LibraryScreen.kt`**: Filter chips, list/grid toggle, sort dropdown, liked songs entry, track list/grid.

5. **Rewrite `SearchScreen.kt`**: White pill search bar, browse category grid (populated from real genres in DB), local search results as `TrackListItem` rows, download prompt banner when no local results.

6. **Rewrite `SearchViewModel.kt`**: Actual JSON parsing of Python search results. Local search via `NativeBridge.searchTracks()`.

7. **Rewrite `AppNavGraph.kt`**: 4-tab bottom navigation with proper routes. Add artist/album detail routes.

8. **Rewrite `MainActivity.kt`**: Full scaffold with `BottomNavBar` + `MiniPlayerBar` + `NavHost`. Initialize DB in `StreamifyApp.onCreate()`. Edge-to-edge display. Permission request flow.

**Commit**: `feat(ui): implement Home, Library, Search screens with real data — zero demo data`

---

### Phase 4: Player System (Full Player, Mini Player, Queue, Lyrics)

**Objective**: Build the complete audio playback UI.

**Steps**:

1. **Rewrite `PlayerViewModel.kt`**: Full ExoPlayer integration — bind to `PlaybackService`, position polling, queue management, shuffle/repeat, like toggle, AI recommendation queue loading, play/skip event logging.

2. **Create `PlayerSeekBar.kt`**: Custom Canvas-based seekbar — thin white track, green progress (optional), white thumb that appears on touch, time labels `mm:ss` on both sides.

3. **Create `PlayerControls.kt`**: Shuffle / Previous / Play-Pause / Next / Repeat row. Play is 64dp white circle with scale animation. Shuffle/Repeat show green dot when active.

4. **Create `PlayerBackground.kt`**: Extract dominant color from album art via `Palette` API. Render radial gradient from dominant color → #121212.

5. **Create `FullPlayerSheet.kt`**: `ModalBottomSheet` containing: drag handle, album art (340dp, Crossfade on track change), track title + artist, heart button, seekbar, controls, bottom action row (devices, lyrics, queue).

6. **Rewrite `MiniPlayerBar.kt`**: Floating bar (8dp margin, 8dp radius), album art 40dp, title (MarqueeText), artist, heart + play/pause buttons, 2dp progress line at top. Tap → expand full player.

7. **Create `QueueScreen.kt`**: Bottom sheet with "Now Playing" header + upcoming tracks list with drag-to-reorder handles. Data from `PlayerViewModel.state.queue`.

8. **Rewrite `LyricsScreen.kt`**: Blurred album art background with dark scrim. White bold active line, gray past/future lines. Auto-scroll to active line. Tap-to-seek on any line. Parse `.lrc` files from `track.lyricsPath`.

9. **Wire `PlaybackService.kt`**: Connect ExoPlayer instance to `PlayerViewModel` via `MediaController` → `MediaSession` binding.

10. **Create `LyricsData.kt`**: Model for parsed LRC lines with `timestampMs: Long` and `text: String`.

**Commit**: `feat(ui): complete player system — full player, mini player, queue, lyrics, ExoPlayer integration`

---

### Phase 5: Download Pipeline UI

**Objective**: Build the full search → select → download → process UI.

**Steps**:

1. **Create `SearchCandidate.kt`**: Model for online search results (title, channel, duration, url, thumbnailUrl, confidence, flags).

2. **Create `DownloadState.kt`**: Model for download pipeline state (steps, progress, errors).

3. **Create `DownloadViewModel.kt`**: Wire to Chaquopy Python engine for search + download. Parse JSON results with `org.json.JSONObject`. Step-by-step progress tracking. Post-download: call `NativeBridge.insertTrack()` + auto-play.

4. **Create `SearchResultItem.kt`**: Card with thumbnail, title, channel name, duration, confidence badge, Official/Edit warning badges, download button.

5. **Create `QualitySelector.kt`**: BottomSheet with 4 quality radio options (Best Native, 320k MP3, 256k AAC, 128k MP3).

6. **Create `DownloadProgressCard.kt`**: Step-by-step checklist with checkmarks/spinners/dimmed states + overall progress bar.

7. **Rewrite `DownloadScreen.kt`**: Source selection list with best match highlighted, quality selector trigger, progress view during download.

8. **Update `DownloadService.kt`**: Make it a proper foreground service with notification channel, progress notification, and `DownloadViewModel` state updates via `LiveData`/`StateFlow`.

**Commit**: `feat(ui): download pipeline UI — source selection, quality picker, step-by-step progress`

---

### Phase 6: Animations, Polish & Release Readiness

**Objective**: Add all micro-interactions and polish for production quality.

**Steps**:

1. **Create `CardPressEffect.kt`**: Modifier extension for 0.95 scale-down on press with spring animation.

2. **Create `HeartBurstEffect.kt`**: Scale keyframe animation (1.0→1.3→1.0) with color transition for heart button.

3. **Create `PlayerTransition.kt`**: Spring animation spec for mini ↔ full player transitions.

4. **Add shimmer loading** to HomeScreen carousels while data loads.

5. **Add pull-to-refresh** to HomeScreen with `PullToRefreshIndicator`.

6. **Add Crossfade** to album art in FullPlayerSheet on track transitions.

7. **Add screen transitions** (`fadeIn + fadeOut`) to NavHost `composable()` calls.

8. **Add haptic feedback** to heart toggle, queue reorder, and pull-to-refresh.

9. **Add snackbar system** anchored above MiniPlayerBar for "Added to Liked Songs", "Download complete", etc.

10. **Fix edge-to-edge** — ensure status bar is transparent, navigation bar has proper padding, no content overlap.

11. **Add notification channels** in `StreamifyApp.onCreate()` for playback and download services.

12. **Add runtime permission flow**: Request `READ_MEDIA_AUDIO` (API 33+) or `READ_EXTERNAL_STORAGE` (API < 33) on first launch. Show rationale dialog.

13. **Update `AndroidManifest.xml`**: Add missing service declarations, notification permissions, foreground service types.

14. **Create `app/proguard-rules.pro`**: Keep rules for JNI, Chaquopy, data models, ExoPlayer.

15. **Test and fix** all failure scenarios from Section 9.

**Commit**: `feat(ui): animations, polish, edge-to-edge, permissions — production ready`

The JNI UI-Thread Freeze Risk
Your spec assumes direct execution between Kotlin ViewModels and the completed C++ native bridge (libstreamify_core.so).
The Flaw: If any Compose state read or ViewModel function invokes NativeBridge JNI calls (SQLite query, 512-D vector cosine similarity check, or RecommendEngine invocation) on Dispatchers.Main, you will cause severe frame drops and Android ANRs.
The Fix: The ViewModel state architecture must enforce explicit off-thread execution via a custom CoroutineDispatcher (e.g., Dispatchers.IO or a single-threaded native executor) for every JNI wrapper in TrackRepository. The UI state layer must consume read-only StateFlow primitives.
2. Gesture Collisions: MiniPlayer vs. BottomSheet
Placing a floating MiniPlayerBar with horizontal swipe actions (e.g., swipe-to-skip) directly above a BottomNavBar while anchoring a FullPlayerSheet drag gesture creates severe touch intercept collisions in Jetpack Compose.
The Flaw: Standard nested scrolling and drag modifiers in Compose routinely capture pointer input from child components, freezing the horizontal swipe gesture on the mini player or prematurely launching the player sheet during bottom nav taps.
The Fix: Isolate touch targets explicitly using pointerInput with detectHorizontalDragGestures on the mini player, and control the ModalBottomSheetState programmatically via explicit drag-distance thresholds rather than relying on default bottom sheet drag handles.
3. Dynamic Palette Luminance Safety
Section 2 mentions PaletteExtractor.kt, but your color specification relies heavily on hardcoded dark values (#0F0F0F, #121212).
The Flaw: Extracting dominant colors directly from raw album art for player backgrounds often produces low-contrast pairings (e.g., bright white or high-saturation yellow covers), rendering white typography (#FFFFFF) unreadable.
The Fix: Implement luminance clamping and dark-tone transformation in PaletteExtractor. If the extracted vibrant/dominant color's luminance exceeds 0.35, automatically blend it toward #121212 or apply a programmatic dark scrim before injecting it into Compose Brush.verticalGradient.

---

## 11. File Manifest

### Complete List of Files to Create/Modify

```
CREATE:
  app/src/main/res/font/montserrat_regular.ttf
  app/src/main/res/font/montserrat_medium.ttf
  app/src/main/res/font/montserrat_semibold.ttf
  app/src/main/res/font/montserrat_bold.ttf
  app/src/main/res/font/montserrat_extrabold.ttf
  app/src/main/res/font/poppins_light.ttf
  app/src/main/res/font/poppins_regular.ttf
  app/src/main/res/font/poppins_medium.ttf
  app/src/main/res/font/poppins_semibold.ttf
  app/src/main/java/com/streamify/app/ui/theme/Shape.kt
  app/src/main/java/com/streamify/app/ui/player/FullPlayerSheet.kt
  app/src/main/java/com/streamify/app/ui/player/PlayerControls.kt
  app/src/main/java/com/streamify/app/ui/player/PlayerSeekBar.kt
  app/src/main/java/com/streamify/app/ui/player/PlayerBackground.kt
  app/src/main/java/com/streamify/app/ui/screens/LibraryScreen.kt
  app/src/main/java/com/streamify/app/ui/screens/QueueScreen.kt
  app/src/main/java/com/streamify/app/ui/screens/ArtistScreen.kt
  app/src/main/java/com/streamify/app/ui/screens/AlbumScreen.kt
  app/src/main/java/com/streamify/app/ui/components/TrackCard.kt
  app/src/main/java/com/streamify/app/ui/components/ArtistCircleCard.kt
  app/src/main/java/com/streamify/app/ui/components/RecentPlayCard.kt
  app/src/main/java/com/streamify/app/ui/components/TrackListItem.kt
  app/src/main/java/com/streamify/app/ui/components/CategoryCard.kt
  app/src/main/java/com/streamify/app/ui/components/SearchResultItem.kt
  app/src/main/java/com/streamify/app/ui/components/DownloadProgressCard.kt
  app/src/main/java/com/streamify/app/ui/components/QualitySelector.kt
  app/src/main/java/com/streamify/app/ui/components/EmptyStateView.kt
  app/src/main/java/com/streamify/app/ui/components/ShimmerPlaceholder.kt
  app/src/main/java/com/streamify/app/ui/components/BottomNavBar.kt
  app/src/main/java/com/streamify/app/ui/components/HeartButton.kt
  app/src/main/java/com/streamify/app/ui/components/MarqueeText.kt
  app/src/main/java/com/streamify/app/ui/components/ContextMenuSheet.kt
  app/src/main/java/com/streamify/app/ui/animations/PlayerTransition.kt
  app/src/main/java/com/streamify/app/ui/animations/CardPressEffect.kt
  app/src/main/java/com/streamify/app/ui/animations/HeartBurstEffect.kt
  app/src/main/java/com/streamify/app/viewmodel/HomeViewModel.kt
  app/src/main/java/com/streamify/app/viewmodel/LibraryViewModel.kt
  app/src/main/java/com/streamify/app/viewmodel/DownloadViewModel.kt
  app/src/main/java/com/streamify/app/viewmodel/QueueViewModel.kt
  app/src/main/java/com/streamify/app/data/models/LyricsData.kt
  app/src/main/java/com/streamify/app/data/models/SearchCandidate.kt
  app/src/main/java/com/streamify/app/data/models/DownloadState.kt
  app/src/main/java/com/streamify/app/util/TimeGreeting.kt
  app/src/main/java/com/streamify/app/util/DurationFormatter.kt
  app/src/main/java/com/streamify/app/util/PaletteExtractor.kt
  app/src/main/java/com/streamify/app/util/PermissionHelper.kt
  app/proguard-rules.pro

REWRITE (complete replacement):
  app/src/main/java/com/streamify/app/MainActivity.kt
  app/src/main/java/com/streamify/app/navigation/AppNavGraph.kt
  app/src/main/java/com/streamify/app/ui/theme/Type.kt
  app/src/main/java/com/streamify/app/ui/theme/Dimens.kt
  app/src/main/java/com/streamify/app/ui/screens/HomeScreen.kt
  app/src/main/java/com/streamify/app/ui/screens/SearchScreen.kt
  app/src/main/java/com/streamify/app/ui/screens/PlayerScreen.kt
  app/src/main/java/com/streamify/app/ui/screens/DownloadScreen.kt
  app/src/main/java/com/streamify/app/ui/screens/LyricsScreen.kt
  app/src/main/java/com/streamify/app/ui/components/MiniPlayerBar.kt
  app/src/main/java/com/streamify/app/viewmodel/PlayerViewModel.kt
  app/src/main/java/com/streamify/app/viewmodel/SearchViewModel.kt
  app/src/main/java/com/streamify/app/viewmodel/IngestionViewModel.kt

UPDATE (targeted edits only):
  app/src/main/java/com/streamify/app/StreamifyApp.kt         # Add DB init, Python init, notification channels
  app/src/main/java/com/streamify/app/ui/theme/Color.kt       # Add ~10 missing color tokens
  app/src/main/java/com/streamify/app/ui/theme/Theme.kt       # Add shape system, status bar config
  app/src/main/java/com/streamify/app/data/TrackRepository.kt # Add recommendation + queue methods
  app/src/main/java/com/streamify/app/data/models/Track.kt    # Add lyricsPath, source, key fields
  app/src/main/java/com/streamify/app/service/PlaybackService.kt  # Wire to ViewModel  
  app/src/main/java/com/streamify/app/service/DownloadService.kt  # Proper foreground service
  app/src/main/AndroidManifest.xml                             # Add services, permissions
  app/src/main/res/values/themes.xml                           # Dark theme, black window bg
  app/build.gradle.kts                                         # Add Palette, Accompanist deps

DO NOT TOUCH:
  native/**                          # Backend is complete
  app/src/main/python/**             # Download engine is complete
  .github/workflows/**               # CI/CD is complete
  build.gradle.kts (root)            # Root config is complete
  settings.gradle.kts                # Settings are complete
  gradle.properties                  # Properties are complete
```

### Dependency Additions Required in `app/build.gradle.kts`

```kotlin
// ADD to dependencies block:
implementation("androidx.palette:palette-ktx:1.0.0")           // Album art color extraction
implementation("androidx.compose.material:material-icons-extended")  // Extended icon set
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")  // viewModel() in Compose
implementation("androidx.compose.animation:animation")          // Advanced animations
implementation("com.google.accompanist:accompanist-systemuicontroller:0.34.0")  // Status bar control
```

---

> **End of Implementation V2**
> This document is the single source of truth for all Streamify APK frontend/UI work.
> Execute phases sequentially. No demo data. No shortcuts. Pure engineering marvel.
