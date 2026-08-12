# 🎧 Streamify APK — Implementation V2: Execution Task Checklist

> **Target**: Execution checklist based on `implementation_v2.md`
> **Status**: PENDING
> **Note**: Check off items `[x]` as they are completed. Do NOT mark as complete until tested.

---

## 🏗 Phase 1: Design Foundation & Configuration

### Dependencies & Build Config
- [ ] Add `androidx.palette:palette-ktx:1.0.0` to `app/build.gradle.kts`.
- [ ] Add `androidx.compose.material:material-icons-extended` to `app/build.gradle.kts`.
- [ ] Add `androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0` to `app/build.gradle.kts`.
- [ ] Add `androidx.compose.animation:animation` to `app/build.gradle.kts`.
- [ ] Add `com.google.accompanist:accompanist-systemuicontroller:0.34.0` to `app/build.gradle.kts`.
- [ ] Create `app/proguard-rules.pro` and add JNI/Chaquopy keep rules (e.g., `-keep class com.streamify.app.data.models.** { *; }`).
- [ ] Ensure `themes.xml` parent is `Theme.Material.NoActionBar` with black window background and status bar.

### Design Tokens
- [ ] Download and place `montserrat_*.ttf` (5 weights) into `app/src/main/res/font/`.
- [ ] Download and place `poppins_*.ttf` (4 weights) into `app/src/main/res/font/`.
- [ ] Rewrite `Type.kt` to use the actual `.ttf` files and define all `StreamifyType` text styles.
- [ ] Update `Color.kt` with missing tokens: `BgElevated`, `BgSearchBar`, `TextDimmed`, `TextOnSearch`, `Divider`, `Explicit`, `Scrim`, `PlayerGradient`, `PrimaryDark`, `Shuffle`.
- [ ] Rewrite `Dimens.kt` to define the complete 30+ dimension/spacing token system.
- [ ] Create `Shape.kt` and define `StreamifyShapes` (CardShape, MiniPlayerShape, ChipShape, SearchBarShape, CategoryShape, BottomSheet, PlayButton).
- [ ] Update `Theme.kt` to apply the updated Typography, Colors, and Shapes.

---

## 🧩 Phase 2: Component Library & Utilities

### Core Utilities
- [ ] Create `TimeGreeting.kt` (returns dynamic greeting based on system clock).
- [ ] Create `DurationFormatter.kt` (formats seconds into `mm:ss`).
- [ ] Create `PaletteExtractor.kt` (extracts dominant color from Bitmap).
  - [ ] **CRITICAL**: Implement Dynamic Palette Luminance Safety (clamp luminance > 0.35 towards `#121212` or apply dark scrim).
- [ ] Create `PermissionHelper.kt` (handles `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE`).

### UI Components
- [ ] Create `EmptyStateView.kt` (illustration, title, subtitle, CTA).
- [ ] Create `ShimmerPlaceholder.kt` (animated linear gradient brush).
- [ ] Create `MarqueeText.kt` (auto-scrolling text for overflowed titles).
- [ ] Create `HeartButton.kt` (animated like toggle with burst effect).
- [ ] Create `BottomNavBar.kt` (4-tab destination bar matching Spotify style).
- [ ] Create `TrackCard.kt` (square album art, text, animated play FAB overlay).
- [ ] Create `ArtistCircleCard.kt` (circular clip, name text).
- [ ] Create `RecentPlayCard.kt` (compact 56dp horizontal card).
- [ ] Create `TrackListItem.kt` (56dp row, art, title, artist, context menu button).
- [ ] Create `CategoryCard.kt` (160x100dp gradient card for search).
- [ ] Create `ContextMenuSheet.kt` (long-press bottom sheet for track actions).

---

## 🏠 Phase 3: Core Screens (Home, Library, Search)

### Architecture & Data Layer Updates
- [ ] Update `TrackRepository.kt` with methods for recommendations, queue management, liked tracks.
  - [ ] **CRITICAL**: Enforce `Dispatchers.IO` / dedicated native thread executor for ALL JNI calls to prevent UI-Thread freeze (ANRs).
- [ ] Update `Track.kt` model to include `lyricsPath`, `source`, `key`.
- [ ] Create `HomeViewModel.kt` (manage catalog, recommendations, ingestion state).
- [ ] Create `LibraryViewModel.kt` (manage liked tracks, filter/sort state).
- [ ] Rewrite `SearchViewModel.kt` (handle local JNI search + online yt-dlp search).

### Screens
- [ ] Rewrite `HomeScreen.kt` (Greeting, Quick Grid, "Made For You", "Your Library", pull-to-refresh).
- [ ] Rewrite `SearchScreen.kt` (Search pill, Browse Category grid, local search results, download prompt).
- [ ] Create `LibraryScreen.kt` (Filter chips, list/grid toggle, Liked Songs entry, track list).
- [ ] Rewrite `AppNavGraph.kt` (integrate 4-tab bottom navigation).
- [ ] Rewrite `MainActivity.kt` (full scaffold, `BottomNavBar`, `MiniPlayerBar`, NavHost, edge-to-edge).
- [ ] Initialize `NativeBridge` database explicitly in `StreamifyApp.onCreate()`.

---

## 🎵 Phase 4: Player System

### ExoPlayer & ViewModel
- [ ] Rewrite `PlayerViewModel.kt` for full `ExoPlayer` integration (position polling, state management, queue, AI logging).
- [ ] Update `PlaybackService.kt` to bind correctly to `PlayerViewModel` (MediaSession, audio focus, notification).

### Player UI Components
- [ ] Create `PlayerSeekBar.kt` (custom Canvas-based seekbar, thin track, white thumb on touch).
- [ ] Create `PlayerControls.kt` (Shuffle, Previous, Play [64dp animated circle], Next, Repeat).
- [ ] Create `PlayerBackground.kt` (radial gradient utilizing `PaletteExtractor`).

### Screens
- [ ] Rewrite `MiniPlayerBar.kt` (40dp art, MarqueeText, Play/Heart, 2dp progress line).
  - [ ] **CRITICAL**: Isolate touch targets using `pointerInput(detectHorizontalDragGestures)` to prevent BottomSheet gesture collisions.
- [ ] Create `FullPlayerSheet.kt` (`ModalBottomSheet` with drag handle, Crossfade album art, controls).
  - [ ] **CRITICAL**: Control `ModalBottomSheetState` programmatically with explicit drag-distance thresholds.
- [ ] Create `QueueScreen.kt` (drag-to-reorder, swipe-to-remove, "Now Playing" highlight).
- [ ] Create `LyricsData.kt` (LRC parser model).
- [ ] Rewrite `LyricsScreen.kt` (Blurred album art bg, active line highlight, auto-scroll, tap-to-seek).

---

## ☁️ Phase 5: Download Pipeline UI

### Data Layer
- [ ] Create `SearchCandidate.kt` (model for yt-dlp results: title, channel, duration, score, flags).
- [ ] Create `DownloadState.kt` (model for pipeline steps and progress).
- [ ] Create `DownloadViewModel.kt` (Chaquopy invocation, JSON parsing, step updates).

### UI Components
- [ ] Create `SearchResultItem.kt` (card with thumbnail, badges, download CTA).
- [ ] Create `QualitySelector.kt` (BottomSheet with Best/320k/256k/128k options).
- [ ] Create `DownloadProgressCard.kt` (step-by-step checklist and progress bar).

### Service & Screens
- [ ] Update `DownloadService.kt` to be a foreground service with progress notifications and StateFlow updates.
- [ ] Rewrite `DownloadScreen.kt` (Source list with best match highlighted, quality selector trigger, progress view).

---

## ✨ Phase 6: Animations, Polish & Production Readiness

### Micro-Interactions
- [ ] Create `CardPressEffect.kt` (0.95 scale-down with high stiffness spring on press).
- [ ] Create `HeartBurstEffect.kt` (1.0 -> 1.3 -> 1.0 keyframe scale + color transition).
- [ ] Create `PlayerTransition.kt` (spring animation spec for mini <-> full player).
- [ ] Integrate Shimmer placeholder into all initial data loading states.
- [ ] Integrate `PullToRefreshIndicator` into HomeScreen.
- [ ] Implement `Crossfade` (300ms) for track album art changes in the full player.

### Haptics & Feedback
- [ ] Add haptic feedback to Heart toggle, queue reorder, pull-to-refresh snap.
- [ ] Implement Snackbar system anchored above the `MiniPlayerBar` (e.g., "Added to Liked Songs").

### System Integration
- [ ] Finalize edge-to-edge layout (transparent status bar, navigation bar padding).
- [ ] Create Notification Channels (`streamify_playback`, `streamify_download`) in `StreamifyApp.onCreate()`.
- [ ] Wire runtime permission flow on first launch with rationale dialog.
- [ ] Verify missing AndroidManifest.xml declarations (`FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `<uses-permission>`, etc.).
