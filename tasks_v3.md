# 📋 Streamify APK — Tasks V3 Checklist (Engineering Marvel Roadmap)

> **Goal**: Comprehensive, step-by-step checklist to transform Streamify into a Spotify-grade local music player.
> **Source**: Generated directly from `implementation_v3.md`.

---

## 🚨 Phase 1: Critical Data Pipeline & Architecture Overhaul

- [x] **Task 1.1: Fix `DownloadWorker.kt` Thread Safety & JNI Execution**
  - [x] Replace direct JNI calls in Python `DownloadCallback.onFinished` with `CompletableDeferred<String>`
  - [x] Ensure `doWork()` awaits completion and executes `NativeBridge.insertTrack()` and `NativeBridge.processAudioFile()` on `Dispatchers.IO`
  - [x] Parse all 4 metadata fields returned by `metadata.py` (including `lyricsPath` at index 3)
  - [x] Trigger `TrackRepository.refresh()` immediately after track insertion
  - [x] Fix import in `DownloadWorker.kt` to use `androidx.work.ListenableWorker.Result` instead of `kotlin.Result`

- [x] **Task 1.2: Refactor `IngestionViewModel.kt` & Fix Instantiation Crash**
  - [x] Change `IngestionViewModel` from `AndroidViewModel` to standard `ViewModel`
  - [x] Pass explicit `Context` to `enqueueDownload()` and `cancelDownload()` methods
  - [x] Use `WorkManager.getInstance(context)` inside functions using the passed context
  - [x] Replace `observeForever` with `WorkManager.getWorkInfosByTagFlow("download_worker")`

- [x] **Task 1.3: Convert `TrackRepository` to Reactive Singleton**
  - [x] Convert `TrackRepository` into a Kotlin `object` (or singleton instance)
  - [x] Add `_allTracks: MutableStateFlow<List<Track>>` and public `allTracks: StateFlow<List<Track>>`
  - [x] Add `_likedTracks: MutableStateFlow<List<Track>>` and public `likedTracks: StateFlow<List<Track>>`
  - [x] Implement `suspend fun refresh()` to reload from `NativeBridge` and emit state updates
  - [x] Update `insertTrack()`, `toggleLike()`, and DB updates to automatically trigger `refresh()`
  - [x] Update `HomeViewModel` and `LibraryViewModel` to collect `TrackRepository.allTracks` flow

- [x] **Task 1.4: Fix `SearchScreen.kt` Compilation Error**
  - [x] Remove duplicate `import androidx.compose.ui.unit.dp` at line 19 of `SearchScreen.kt`

---

## 🔍 Phase 2: Device Audio Media Scanner (VLC-Style Local Discovery)

- [x] **Task 2.1: Implement Auto-Scan on Storage Permission Grant**
  - [x] Update `MainActivity.kt` permission request callback to enqueue `IngestionWorker`
  - [x] Use `ExistingWorkPolicy.KEEP` to avoid redundant concurrent scans
  - [x] Show subtle progress/status banner on `HomeScreen` during active scan

- [x] **Task 2.2: Upgrade `IngestionWorker.kt` Performance & Capabilities**
  - [x] Add deduplication check against `NativeBridge` before inserting local tracks
  - [x] Extract embedded MP3 artwork / fallback to `MediaStore.Audio.Albums.ALBUM_ART` URI
  - [x] Perform initial scan fast-path (insert metadata without blocking on full ONNX feature extraction)
  - [x] Fix import in `IngestionWorker.kt` to use `androidx.work.ListenableWorker.Result`
  - [x] Post a foreground notification with live count progress ("Scanning music: 42/180")

- [x] **Task 2.3: Manual Rescan & ContentObserver**
  - [x] Add "Scan Device for Music" button in `LibraryScreen` empty state
  - [x] Add "Rescan Storage" menu item in `LibraryScreen` header
  - [x] Register `ContentObserver` on `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` for auto-detecting new files

---

## 🎨 Phase 3: Spotify-Exact Typography, Theme & Design System

- [x] **Task 3.1: Typography & Font Family Upgrade**
  - [x] Add Inter font family (Regular, Medium, SemiBold, Bold, ExtraBold) to `res/font/`
  - [x] Update `Type.kt` to pair Inter (headings) with Poppins / Plus Jakarta Sans (body)
  - [x] Remove hardcoded colors inside `TextStyle` definitions (e.g. `PlayerArtist`, `SeekbarTime`)
  - [x] Fine-tune letter spacing and kerning metrics across Display, Headline, Title, and Body tokens

- [x] **Task 3.2: Color Token Corrections**
  - [x] Change `BgSearchBar` from white `#FFFFFF` to Spotify dark `#2A2A2A`
  - [x] Change `TextOnSearch` from `#000000` to `#B3B3B3`
  - [x] Adjust `TextSub` to `#A7A7A7`
  - [x] Add `BgMiniPlayer = Color(0xFF282828)` token

- [x] **Task 3.3: System Bar & Visual Polish**
  - [x] Configure transparent status bar and dark navigation bar matching `#000000`
  - [x] Ensure edge-to-edge layout padding across all device screen aspect ratios

---

## 📱 Phase 4: Frontend Reconstruction (Screen-by-Screen Polish)

- [x] **Task 4.1: `HomeScreen.kt` Overhaul**
  - [x] Remove redundant `init { loadData() }` in `HomeViewModel` to prevent double-loading
  - [x] Refactor nested scrollables to clean flat list structure with `stickyHeader`
  - [x] Integrate Material 3 `PullToRefreshContainer`
  - [x] Implement smooth dominant color gradient header
  - [x] Build "Recently Played" 2x3 grid with active track indicators

- [x] **Task 4.2: `SearchScreen.kt` Overhaul**
  - [x] Style dark search input bar with clean placeholder & clear button
  - [x] Add animated progress line indicator during active Chaquopy search execution
  - [x] Add quality picker chip selector (320kbps / 192kbps / 128kbps) with haptic feedback
  - [x] Show animated success Snackbar on download completion + auto-refresh library

- [x] **Task 4.3: `LibraryScreen.kt` Overhaul**
  - [x] Fix `DisposableEffect` key observer leak
  - [x] Show total track count & aggregate duration ("142 songs • 8 hrs 15 mins")
  - [x] Add sorting dropdown (By Title, By Artist, By Date Added, By Duration)
  - [x] Add layout toggle (Compact List vs Grid View)
  - [x] Implement dedicated "Liked Songs" hero header item with Spotify purple gradient accent
  - [x] Add swipe-left action on items to enqueue track

- [x] **Task 4.4: Navigation & Route Repair (`AppNavGraph.kt`)**
  - [x] Add `composable("download")` route or integrate active transfers drawer
  - [x] Add Downloads tab item to `BottomNavBar.kt` with tab indicator
  - [x] Remove broken/dead `"player"` route
  - [x] Implement custom slide + fade page transition animations across all routes
  - [x] Fix bottom bar back navigation stack behavior

---

## 🎵 Phase 5: Player System & Audio Immersion

- [x] **Task 5.1: `MiniPlayerBar.kt` Refactoring**
  - [x] Fix swipe gesture logic: accumulate offset, execute skip action strictly on `onDragEnd`
  - [x] Wire `HeartButton` `onToggle` directly to `playerViewModel.toggleLike(track)`
  - [x] Fix progress bar track color to be visible against `BgElevated` background
  - [x] Add swipe-up gesture to trigger full player expand sheet
  - [x] Add elevation shadow & subtle green glow when audio is actively playing

- [x] **Task 5.2: `FullPlayerSheet.kt` Refactoring**
  - [x] Fix time calculation: derive timestamp directly from `currentPositionMs`
  - [x] Add top sheet pull indicator handle
  - [x] Implement ambient blurred album artwork background
  - [x] Add dynamic drop shadow around album cover based on extracted palette color
  - [x] Add device destination indicator ("This Device (Local JNI)")

- [x] **Task 5.3: `PlayerViewModel.kt` Hardening**
  - [x] Guard `playTrack()` against uninitialized null `MediaController`
  - [x] Fix `toggleLike()` to update track state inside `queue` list to prevent state reversion
  - [x] Split `currentPosition` into an independent `StateFlow<Long>` to stop 200ms full-state recompositions
  - [x] Fix transition reason check (`MEDIA_ITEM_TRANSITION_REASON_AUTO` vs manual user skip)

- [x] **Task 5.4: Real DSP Audio Crossfader (`CrossfadeAudioProcessor.kt`)**
  - [x] Implement PCM buffer ring to retain ending samples of track A
  - [x] Apply smooth equal-power crossfade curve during track transition
  - [x] Expose configurable crossfade duration setting (0s - 12s)

- [x] **Task 5.5: Queue & Lyrics Experience**
  - [x] Add drag-to-reorder support in `QueueScreen`
  - [x] Add active animated equalizer indicator on playing track in Queue
  - [x] Fix hardcoded `-200` pixel offset in `LyricsScreen.kt` with density-independent scrolling
  - [x] Add tap-to-seek haptic feedback & line highlight in `LyricsScreen`

---

## ✨ Phase 6: Micro-Animations & Motion Engineering

- [x] **Task 6.1: Spring Physics Card Press Effects (`CardPressEffect.kt`)**
  - [x] Add tactile haptic feedback on card touch down
  - [x] Integrate subtle elevation drop during pressed state

- [x] **Task 6.2: Component Animations**
  - [x] Animated icon morphing for Play/Pause button state transition
  - [x] Heart icon particle burst animation on like toggle
  - [x] Shimmer loading animation width fix (`fillMaxWidth()`)
  - [x] Smooth expand/collapse spring physics on player sheet gesture

---

## ⚡ Phase 7: Native C++ & System Performance Hardening

- [x] **Task 7.1: C++ Engine Bug Fixes & Leaks**
  - [x] Add explicit `delete session_` in `AudioPipeline.cc` destructor
  - [x] Fix thread-local `sqlite3*` handle cleanup in `StreamifyDB.cc`
  - [x] Replace `std::cout`/`std::cerr` with `__android_log_print` across all native code
  - [x] Fix AVX dot-product mask logic in `VectorStore.cc` for x86/x86_64 architecture compatibility
  - [x] Create missing SQL database indexes (`idx_tracks_filepath`, `idx_tracks_title`, `idx_transitions_user`)

- [x] **Task 7.2: Compose Recomposition & Memory Optimization**
  - [x] Annotate `Track` data model with `@Stable`
  - [x] Add explicit stable keys (`key = { it.id }`) in all `LazyColumn` and `LazyRow` items
  - [x] Defer seekbar progress reading to canvas `DrawScope`
  - [x] Configure bitmap memory caching for Coil image loader in `StreamifyApp`

---

*Roadmap synchronized with `implementation_v3.md`.*
