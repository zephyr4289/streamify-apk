# 🎧 Streamify APK — Implementation V3: Engineering Marvel Blueprint

> **Target**: Transform Streamify from a functional prototype into a Spotify-grade engineering marvel
> **Status**: PENDING
> **Authority**: Full — rewrite, restructure, and redesign anything necessary
> **Principle**: No servers. Pure raw native performance. Kotlin + C++ + Python. Offline-first. Zero compromise.

---

## Table of Contents

1. [Exhaustive Audit Report — Every Bug Found](#1-exhaustive-audit-report)
2. [Root Cause Analysis — The 3 Showstopper Failures](#2-root-cause-analysis)
3. [Spotify Font & Design System Correction](#3-spotify-font--design-system-correction)
4. [Architecture Overhaul Blueprint](#4-architecture-overhaul-blueprint)
5. [Phase 1: Critical Data Pipeline Fixes](#phase-1-critical-data-pipeline-fixes)
6. [Phase 2: Device Audio Scanner (MediaStore Integration)](#phase-2-device-audio-scanner)
7. [Phase 3: Spotify-Exact Typography & Theme](#phase-3-spotify-exact-typography--theme)
8. [Phase 4: Frontend Reconstruction — Screen-by-Screen](#phase-4-frontend-reconstruction)
9. [Phase 5: Player System — Spotify-Grade Immersion](#phase-5-player-system)
10. [Phase 6: Animation & Motion Engineering](#phase-6-animation--motion-engineering)
11. [Phase 7: Performance & Production Hardening](#phase-7-performance--production-hardening)
12. [Appendix: File-by-File Defect Registry](#appendix-file-by-file-defect-registry)

---

## 1. Exhaustive Audit Report

### 🔴 CRITICAL — Showstoppers (App is fundamentally broken)

| # | Severity | File | Issue |
|---|----------|------|-------|
| C1 | **CRITICAL** | `DownloadWorker.kt:82-146` | **Downloaded songs vanish forever.** The `onFinished` callback runs inside the `DownloadCallback` object, which is a **local anonymous class** created inside `doWork()`. The `onFinished` method calls `NativeBridge.insertTrack()` and `NativeBridge.processAudioFile()` — but **these JNI calls execute on the Python thread inside yt-dlp's progress hook**, NOT on the coroutine's `Dispatchers.IO`. The Python callback fires from the Python GIL thread. JNI calls from a non-JVM-attached thread cause silent failures or crashes. The track is never inserted into the database. |
| C2 | **CRITICAL** | `IngestionWorker.kt` + `StreamifyApp.kt` | **IngestionWorker is never enqueued anywhere.** The `MediaStoreScanner.kt` exists and correctly queries `MediaStore.Audio`, and `IngestionWorker.kt` correctly calls it — but **nothing in the entire codebase ever calls `WorkManager.enqueue()` for `IngestionWorker`**. The app never scans the device for existing audio files. |
| C3 | **CRITICAL** | `SearchScreen.kt:19` | **Duplicate import `import androidx.compose.ui.unit.dp`** causes a compilation error on strict Kotlin compilers. This was flagged in the CI build log (`comments_latest.json`). |
| C4 | **CRITICAL** | `SearchScreen.kt:47` | The CI log shows the previous compile error about `SearchTrack` was fixed, but the duplicate `dp` import at line 19 remains. |
| C5 | **CRITICAL** | `DownloadService.kt` | **Orphan dead code.** This service exists but is never declared in `AndroidManifest.xml`, never started from anywhere, and duplicates `DownloadWorker.kt`'s functionality. It will silently do nothing if ever called. |
| C6 | **CRITICAL** | `IngestionViewModel.kt:28` | Uses `AndroidViewModel(application)` but is instantiated with `viewModel()` in `LibraryScreen.kt:32` and `SearchScreen.kt:42` — **`viewModel()` cannot create `AndroidViewModel` instances without a factory**. This crashes at runtime with `InstantiationException`. |

### 🟠 MAJOR — Broken Functionality

| # | Severity | File | Issue |
|---|----------|------|-------|
| M1 | **MAJOR** | `DownloadWorker.kt:69-73` | Writes to `Environment.DIRECTORY_MUSIC/Streamify` but this path may not have write permission on Android 11+. Falls back to app-specific dir, but the stored filepath in the DB will be wrong if the primary path fails. |
| M2 | **MAJOR** | `DownloadWorker.kt:97` | `metadataModule.callAttr("inject_metadata", filepath, title, artist, album, null)` — passes Java `null` to Python. Chaquopy may convert this to `None`, but `metadata.py:69` returns a 4-element list `[duration, bpm, coverArtPath, lyricsPath]` while the Kotlin code at line 108 only checks `list.size >= 2` and `>= 3`, **never reads `list[3]` (lyricsPath)**. The lyrics path is computed but discarded. |
| M3 | **MAJOR** | `HomeViewModel.kt` | `loadData()` is called in `init {}` AND again via `ON_RESUME` lifecycle observer in `HomeScreen.kt:37-45`. This causes **double-loading** every time the screen first appears. |
| M4 | **MAJOR** | `LibraryScreen.kt:42` | `DisposableEffect(lifecycleOwner, downloadTasks)` — using `downloadTasks` (a `StateFlow` value) as a key means the `DisposableEffect` restarts and re-adds the lifecycle observer **every time the download task list changes**. This creates O(n) observer leaks. |
| M5 | **MAJOR** | `MiniPlayerBar.kt:54-58` | Horizontal drag gesture fires `onNext`/`onPrevious` on **every drag delta > 50px**, not on drag-end. A single swipe will fire `onNext()` dozens of times as each `onHorizontalDrag` callback fires. |
| M6 | **MAJOR** | `MiniPlayerBar.kt:93-96` | HeartButton `onToggle` callback is `{ /* Like */ }` — **a no-op**. Liking a song from the mini player does nothing. |
| M7 | **MAJOR** | `PlayerViewModel.kt:130-155` | `playTrack()` sets `queue` in state, builds `MediaItem` list, calls `controller?.setMediaItems()` — but `controller` is **null if `initialize()` hasn't completed yet** (the `ListenableFuture` is async). First song tap after app launch will silently fail. |
| M8 | **MAJOR** | `BottomNavBar.kt` | Only has 3 tabs (Home, Search, Library). The `DownloadScreen` has **no navigation entry** — it's unreachable from the UI. |
| M9 | **MAJOR** | `AudioPipeline.cc:26` | `session_ = new Ort::Session(...)` — raw `new` without `delete` in destructor. Memory leak on repeated calls or if `init()` is called twice. |
| M10 | **MAJOR** | `StreamifyDB.cc:94` | `thread_local sqlite3* tls_db` — the `sqlite3*` handle is **never closed** (`sqlite3_close` is never called). Each thread that opens a connection leaks it when the thread exits. |
| M11 | **MAJOR** | `PlayerViewModel.kt:192` | **Like state reverts.** `toggleLike()` updates `currentTrack` in `_playerState` but does NOT update the same track inside the `queue` list. When `updateCurrentTrackFromMediaItem()` fires next, it fetches the track from the unmodified queue, wiping the liked UI state. |
| M12 | **MAJOR** | `PlayerViewModel.kt:110` | **200ms recomposition storm.** `startPollingPosition()` replaces the entire `_playerState.value` every 200ms to update `currentPosition`. Because `queue` and `currentTrack` are in the same data class, this forces QueueScreen, PlayerSheet, and every observer to recompose every 200ms. `currentPosition` should be a separate `StateFlow<Long>`. |
| M13 | **MAJOR** | `DownloadWorker.kt:55`, `IngestionWorker.kt:17` | Both files override `doWork(): Result` but neither imports `androidx.work.ListenableWorker.Result`. Kotlin resolves `Result` to `kotlin.Result` which lacks `.success()` / `.failure()` — potential compilation failure. |
| M14 | **MAJOR** | `HomeViewModel.kt:32,53` | `_isRefreshing` is updated but never exposed to UI. `HomeScreen.kt` doesn't consume it — pull-to-refresh has no visual feedback. |

### 🟡 MODERATE — UI/UX & Correctness Issues

| # | Severity | File | Issue |
|---|----------|------|-------|
| U1 | MODERATE | `MiniPlayerBar.kt:107-114` | `LinearProgressIndicator` `trackColor` matches `BgElevated` — the inactive track is invisible against the background. |
| U2 | MODERATE | `PlayerSeekBar.kt:25-27` | `dragProgress` initialized to `progress` but only once via `remember`. If `progress` changes externally while dragging, initial position is stale. |
| U3 | MODERATE | `FullPlayerSheet.kt:150` | Calculates `currentMs` from `progress * track.durationSec * 1000` — but `progress` is already `currentPosition / duration` in milliseconds. This double-converts and shows wrong elapsed time. |
| U4 | MODERATE | `HomeScreen.kt:121,159,182` | Nests `LazyRow` inside `LazyColumn item {}`. This is **not an error** but prevents the inner list from contributing to scroll state. Compose warns about nested scrollable in same direction. |
| U5 | MODERATE | `QueueScreen.kt:62` | Uses `itemsIndexed` but ignores the index parameter. No visual "now playing" indicator differentiates the current track in the queue. |
| U6 | MODERATE | `LyricsScreen.kt:37` | `scrollOffset = -200` — hardcoded pixel offset for centering. This doesn't account for different screen densities. Should use dp-to-px conversion. |
| U7 | MODERATE | `ShimmerPlaceholder.kt` | Shimmer animation `targetValue = 1000f` is hardcoded. On tablets or landscape, the shimmer effect cuts off before reaching the right edge. |
| U8 | MODERATE | `CrossfadeAudioProcessor.kt` | The entire implementation is a **pass-through**. It does zero crossfading. The `queueEndOfStream()` method is empty, meaning no fade-out occurs at track boundaries. |
| U9 | MODERATE | `ContextMenuSheet.kt` | Uses deprecated `Divider()` composable (Material 3 deprecated it in favor of `HorizontalDivider()`). |
| U10 | MODERATE | `PlayerScreen.kt` | **Dead screen.** Exists as a route `"player"` in `AppNavGraph.kt:88-90` but shows only placeholder text "Full Player: title". The actual full player is `FullPlayerSheet`. This route is unreachable and dead. |

### 🔵 MINOR — Polish & Best Practices

| # | Severity | File | Issue |
|---|----------|------|-------|
| P1 | MINOR | `BottomNavBar.kt` | `indication = null` on clickable — zero ripple feedback on tab tap. Users get no visual response. |
| P2 | MINOR | `HeartButton.kt` | `contentDescription` is always "Like" even when `isLiked = true`. Should be "Unlike" for accessibility. |
| P3 | MINOR | `PlayerControls.kt` | `contentDescription` for Shuffle/Repeat is static. Screen readers can't distinguish active vs inactive state. |
| P4 | MINOR | `TrackListItem.kt:86` | Options button `contentDescription = "More options"` — should include track title for accessibility in lists. |
| P5 | MINOR | `CardPressEffect.kt:31` | `indication = null` removes all ripple. For the play button this is acceptable, but for cards the user gets no tap feedback other than the subtle 0.95 scale. |
| P6 | MINOR | `EventTracker.cc:13,19` | Uses `std::cout` for logging — this goes to `/dev/null` on Android. Should use `__android_log_print`. |
| P7 | MINOR | `StreamifyDB.cc:86` | Error logging uses `std::cerr` — invisible on Android. Should use Android's native logging. |
| P8 | MINOR | `VectorStore.cc:120` | AVX path uses `_mm256_dp_ps` which requires AVX, but `0xFF` mask is only valid for 128-bit `_mm_dp_ps`. On 256-bit, `_mm256_dp_ps` mask works differently — this produces incorrect results on x86 emulators. |
| P9 | MINOR | `metadata.py:69` | Returns different-length lists (3 or 4 elements) depending on success path vs error path. The error path returns `[0, 120.0, ""]` (3 items) while success returns 4. |
| P10 | MINOR | `core.py:13-15` | ANSI escape code stripping is hardcoded for specific color codes. Newer yt-dlp versions use different escape sequences. Should use a regex strip. |

---

## 2. Root Cause Analysis — The 3 Showstopper Failures

### 💀 Why downloaded songs don't appear in the app

**Complete trace:**

```
User taps "Download" in SearchScreen
  → IngestionViewModel.enqueueDownload() creates WorkManager OneTimeWorkRequest
    → DownloadWorker.doWork() runs on Dispatchers.IO
      → Python core.download_audio() is called with a DownloadCallback object
        → yt-dlp downloads the file
        → Python calls callback_java.onFinished(filepath) from PYTHON THREAD
          → DownloadCallback.onFinished() calls NativeBridge.insertTrack() ← THIS IS THE PROBLEM
            → JNI call from non-JVM-attached Python thread → SILENT FAILURE
              → Track is NEVER inserted into the database
                → HomeViewModel.loadData() queries DB → 0 tracks → empty screen
```

**Fix**: The `onFinished` callback must NOT call JNI directly. It must signal completion back to the `doWork()` coroutine (via a `CompletableDeferred` or similar), which then performs the JNI insertion on `Dispatchers.IO`.

### 💀 Why the app doesn't show existing phone music

**Complete trace:**

```
StreamifyApp.onCreate()
  → Initializes NativeBridge.initDatabase()
  → Initializes NativeBridge.initVectorStore()
  → Creates notification channels
  → DOES NOT enqueue IngestionWorker ← MISSING

IngestionWorker exists and correctly uses MediaStoreScanner
MediaStoreScanner exists and correctly queries MediaStore.Audio.Media
But NOTHING EVER CALLS THEM.

HomeScreen shows tracks from NativeBridge.getAllTracks()
  → Database is empty (no ingestion ever ran)
  → Empty state view shown
```

**Fix**: `StreamifyApp.onCreate()` or `MainActivity` must enqueue `IngestionWorker` on first launch (or when permissions are granted). Additionally, `HomeViewModel` should also trigger a scan from `MediaStoreScanner` as a secondary path.

### 💀 Why IngestionViewModel crashes

```
LibraryScreen:
  ingestionViewModel: IngestionViewModel = viewModel()  ← CRASH

IngestionViewModel extends AndroidViewModel(application)
viewModel() delegates can only create ViewModel() subclasses, not AndroidViewModel.
AndroidViewModel requires ViewModelProvider.AndroidViewModelFactory.
```

**Fix**: Either change `IngestionViewModel` to regular `ViewModel` and pass `Context` via method params, or use a custom `ViewModelProvider.Factory`, or use `viewModel(factory = ...)`.

---

## 3. Spotify Font & Design System Correction

### Spotify's Actual Fonts

Spotify uses **Circular** (a proprietary typeface by Lineto). Since Circular is not publicly available, the industry-standard substitutions are:

| Spotify Uses | Best Open Substitute | Current App |
|---|---|---|
| **Circular Std Bold** (headings) | **Inter** or **DM Sans** (geometrically closest) | Montserrat (❌ too wide, too "poster-ish") |
| **Circular Std Book** (body) | **Inter Regular** or **Plus Jakarta Sans** | Poppins (❌ acceptable but not ideal) |

**Recommendation**: Switch heading font from **Montserrat** to **Inter** (variable weight, tighter metrics, geometric, Google Fonts). Keep **Poppins** for body/labels or switch to **Plus Jakarta Sans**. Both are closer to Circular than Montserrat.

> **However**: If you want to keep Montserrat intentionally (brand differentiation from Spotify), that's valid — but the letter-spacing must be tightened significantly. Current `-0.5sp` on display styles is not aggressive enough for Montserrat at those sizes.

### Missing Design Tokens

```kotlin
// Current Type.kt hardcodes color in TextStyle (WRONG):
val PlayerArtist = TextStyle(..., color = StreamifyColors.TextSub, ...)
val SeekbarTime = TextStyle(..., color = StreamifyColors.TextSub, ...)
// Colors should be applied at the call site, not baked into the style.
```

### Color Audit

| Token | Current | Spotify Actual | Status |
|---|---|---|---|
| `BgBase` | `#000000` | `#000000` (OLED black) | ✅ |
| `BgSurface` | `#121212` | `#121212` | ✅ |
| `BgCard` | `#181818` | `#181818` | ✅ |
| `Primary` | `#1DB954` | `#1DB954` | ✅ |
| `TextMain` | `#FFFFFF` | `#FFFFFF` | ✅ |
| `TextSub` | `#B3B3B3` | `#A7A7A7` | ⚠️ Close but slightly off |
| `BgSearchBar` | `#FFFFFF` | `#2A2A2A` (Spotify uses dark search bar) | ❌ Wrong |
| `TextOnSearch` | `#000000` | `#B3B3B3` (light gray on dark bg) | ❌ Wrong |

---

## 4. Architecture Overhaul Blueprint

### Current Architecture Problems
1. **No reactive data layer**: ViewModels create new `TrackRepository()` instances every time. No shared state. No `Flow`-based observation of DB changes.
2. **No singleton repository**: Downloads complete → DB updated → but no ViewModels are notified because there's no shared observable state.
3. **No dependency injection**: Every ViewModel manually constructs `TrackRepository()`. This makes testing impossible and creates multiple repository instances.

### Target Architecture

```
┌─────────────────────────────────────────────────┐
│                  Compose UI Layer                │
│  HomeScreen  SearchScreen  LibraryScreen  Player │
└───────────────┬──────────────┬──────────────┬────┘
                │              │              │
         ┌──────▼──────┐ ┌────▼────┐  ┌──────▼──────┐
         │HomeViewModel│ │SearchVM │  │ PlayerVM    │
         └──────┬──────┘ └────┬────┘  └──────┬──────┘
                │              │              │
         ┌──────▼──────────────▼──────────────▼──────┐
         │        TrackRepository (SINGLETON)         │
         │  ┌─────────────────────────────────────┐  │
         │  │  _allTracks: MutableStateFlow<List>  │  │
         │  │  _likedIds: MutableStateFlow<Set>    │  │
         │  │  refreshTrigger: SharedFlow           │  │
         │  └─────────────────────────────────────┘  │
         └──────┬──────────────┬──────────────┬──────┘
                │              │              │
     ┌──────────▼──┐  ┌───────▼───┐  ┌───────▼───────┐
     │ NativeBridge │  │MediaStore │  │  Chaquopy     │
     │ (JNI/C++)   │  │ Scanner   │  │  (Python)     │
     └─────────────┘  └───────────┘  └───────────────┘
```

### Key Changes:
- **Singleton `TrackRepository`**: One instance shared across all ViewModels via `companion object` or DI
- **Reactive flows**: `TrackRepository` exposes `StateFlow<List<Track>>` that ViewModels collect
- **Refresh mechanism**: After download completes, `TrackRepository.refresh()` is called, which re-queries the DB and emits to all collectors
- **MediaStore integration**: On permission grant, scan device music and insert into DB

---

## Phase 1: Critical Data Pipeline Fixes

> **Goal**: Make downloaded songs actually appear. Make the data pipeline reliable.

### Task 1.1: Fix DownloadWorker Thread Safety
- [ ] Rewrite `DownloadWorker.doWork()` to use `CompletableDeferred<String>` pattern
- [ ] Python `onFinished` callback sets the deferred value with the filepath
- [ ] `doWork()` `awaits` the deferred, then performs JNI calls on `Dispatchers.IO`
- [ ] Read ALL 4 elements from `metadata.py` return value (including `lyricsPath` at index 3)
- [ ] After successful DB insert, call `TrackRepository.refresh()` to notify all ViewModels

### Task 1.2: Fix IngestionViewModel Instantiation
- [ ] Change `IngestionViewModel` from `AndroidViewModel` to regular `ViewModel`
- [ ] Accept `Context` as parameter in `enqueueDownload()` and `cancelDownload()` (already done)
- [ ] Use `WorkManager.getInstance(context)` with the passed context instead of `getApplication()`
- [ ] Remove `observeForever` in `init {}` — use `WorkManager.getWorkInfosByTagFlow("download_worker")` instead (API available since work-runtime 2.9)

### Task 1.3: Singleton TrackRepository with Reactive Flows
- [ ] Convert `TrackRepository` to `object` (Kotlin singleton) or use `companion object` factory
- [ ] Add `private val _allTracks = MutableStateFlow<List<Track>>(emptyList())`
- [ ] Add `val allTracks: StateFlow<List<Track>>` public accessor
- [ ] Add `suspend fun refresh()` method that re-queries `NativeBridge.getAllTracks()` and emits
- [ ] All ViewModels collect from this shared flow instead of calling `getAllTracks()` independently
- [ ] After any mutation (insert, toggleLike, delete), call `refresh()`

### Task 1.4: Fix SearchScreen Compilation Error
- [ ] Remove duplicate `import androidx.compose.ui.unit.dp` at line 19 of `SearchScreen.kt`

---

## Phase 2: Device Audio Scanner

> **Goal**: Scan all existing music files on the device and show them in the app, like VLC does.

### Task 2.1: Auto-Scan on Permission Grant
- [ ] In `MainActivity.kt`, after permission result is received, enqueue `IngestionWorker` via WorkManager
- [ ] Add `UNIQUE_WORK_NAME` policy (`ExistingWorkPolicy.KEEP`) to prevent duplicate scans
- [ ] Show a scan progress indicator in `HomeScreen` while `IngestionWorker` is running

### Task 2.2: Improve IngestionWorker
- [ ] Add deduplication: before `insertTrack()`, check if filepath already exists in DB (the `filepath UNIQUE` constraint in SQLite will reject duplicates, but handle the error gracefully)
- [ ] Extract embedded cover art from MP3 ID3 tags or use Android's `MediaStore.Audio.Albums.ALBUM_ART` content URI
- [ ] Skip ONNX processing on initial scan (too slow for 100+ files) — mark tracks as `is_processed = 0` and process lazily
- [ ] Add foreground notification showing scan progress: "Scanning music... 47/203 files"

### Task 2.3: Manual Rescan Button
- [ ] Add a "Scan device for music" button in Library screen's empty state
- [ ] Add a "Rescan" option in the Library screen's header menu
- [ ] Re-enqueue `IngestionWorker` when tapped

### Task 2.4: File System Watcher (Stretch)
- [ ] Register a `ContentObserver` on `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`
- [ ] When new audio files are detected, auto-insert them into the DB
- [ ] This catches downloads from other apps, file transfers, etc.

---

## Phase 3: Spotify-Exact Typography & Theme

> **Goal**: Pixel-perfect Spotify aesthetic. Every color, every spacing, every font weight.

### Task 3.1: Typography Overhaul
- [ ] Download **Inter** font family (Regular, Medium, SemiBold, Bold, ExtraBold) from Google Fonts
- [ ] Place `.ttf` files in `app/src/main/res/font/`
- [ ] Rewrite `Type.kt` to use Inter for headings and Poppins for body
- [ ] Remove hardcoded `color` from `TextStyle` definitions (`PlayerArtist`, `SeekbarTime`)
- [ ] Tighten letter-spacing on all heading styles to match Circular's tight metrics

### Task 3.2: Color System Fixes
- [ ] Fix `BgSearchBar` from `#FFFFFF` to `#2A2A2A` (Spotify uses dark search bar)
- [ ] Fix `TextOnSearch` from `#000000` to `#B3B3B3`
- [ ] Fix `TextSub` from `#B3B3B3` to `#A7A7A7`
- [ ] Add missing `BgMiniPlayer = Color(0xFF282828)` (mini player distinct from cards)
- [ ] Add `TextLink = Color(0xFF1DB954)` for tappable text

### Task 3.3: Status Bar & System UI
- [ ] Ensure status bar is truly transparent (not translucent)
- [ ] Use `accompanist-systemuicontroller` to set status bar icons to light
- [ ] Ensure navigation bar matches `BgBase` (pure black)

---

## Phase 4: Frontend Reconstruction — Screen-by-Screen

> **Goal**: Every screen must be indistinguishable from Spotify Premium in feel and flow.

### Task 4.1: HomeScreen Overhaul
- [ ] Fix double-loading (remove `init { loadData() }` from HomeViewModel, let lifecycle observer handle it)
- [ ] Replace nested `LazyRow` inside `LazyColumn` with flat list architecture using `stickyHeader` and horizontal `LazyRow` within dedicated composables
- [ ] Add pull-to-refresh via `PullToRefreshContainer` (Material 3)
- [ ] Add gradient header that blends the dominant album art color into the background (currently implemented but could be smoother)
- [ ] Add "Recently Played" section showing last 6 played tracks (requires adding play history tracking)
- [ ] Add animated transitions between loading → content states (Crossfade or AnimatedContent)

### Task 4.2: SearchScreen Fixes
- [ ] Remove duplicate `dp` import
- [ ] Fix search bar: change from white background to dark (#2A2A2A) with light gray placeholder text
- [ ] Add debounce indicator (subtle progress line under search bar during online search)
- [ ] Add haptic feedback on download quality selection
- [ ] After successful download, show Snackbar "Downloaded [title]" and auto-refresh library
- [ ] Add "Cancel" to close keyboard when back is pressed

### Task 4.3: LibraryScreen Overhaul
- [ ] Fix `DisposableEffect` key — remove `downloadTasks` from keys
- [ ] Fix IngestionViewModel creation (see Task 1.2)
- [ ] Add track count display: "47 songs • 3h 12m"
- [ ] Add sort options: by title, by artist, by date added, by duration
- [ ] Add list/grid toggle view
- [ ] Add "Liked Songs" as a dedicated card at the top (Spotify-style with purple gradient)
- [ ] Add swipe-to-queue gesture on track list items

### Task 4.4: DownloadScreen Integration
- [ ] Add Downloads tab to `BottomNavBar` (4th tab, or integrate into Library as a section)
- [ ] OR: Move active downloads into a persistent notification-style card at the top of Library screen
- [ ] Add download history (completed downloads) below active transfers
- [ ] Show download file size and quality badge (320kbps, 192kbps)

### Task 4.5: Navigation Fixes
- [ ] Remove dead `"player"` route from `AppNavGraph.kt`
- [ ] Add proper back-stack handling for queue and lyrics routes
- [ ] Add `enterTransition` and `exitTransition` animations to all nav routes
- [ ] Fix bottom nav: ensure back button doesn't exit app from non-home tabs (navigate to home first)

---

## Phase 5: Player System — Spotify-Grade Immersion

> **Goal**: The player must feel like a $15/month premium experience.

### Task 5.1: Fix MiniPlayerBar
- [ ] Fix swipe gesture: accumulate drag distance, fire skip only on `onDragEnd` when threshold exceeded
- [ ] Wire HeartButton `onToggle` to `playerViewModel.toggleLike()` (currently a no-op)
- [ ] Fix progress indicator: use `StreamifyColors.TextDimmed` for track background (not `BgElevated`)
- [ ] Add swipe-up gesture to expand to full player
- [ ] Ensure mini player sits above bottom nav with proper z-ordering
- [ ] Add glow/shadow effect on the mini player bar when playing

### Task 5.2: Fix FullPlayerSheet
- [ ] Fix time display calculation (U3): use `playerState.currentPosition` directly instead of re-deriving from progress
- [ ] Add drag handle at top for sheet dismiss gesture
- [ ] Add blurred album art background (like Spotify's immersive player)
- [ ] Make album art shadow dynamic based on dominant color
- [ ] Add smooth springy transition when expanding/collapsing
- [ ] Add device/speaker indicator at bottom ("This Phone" or connected device name)

### Task 5.3: Fix PlayerViewModel
- [ ] Guard `playTrack()` against null controller: queue the action and execute when controller connects
- [ ] Add `isReady: StateFlow<Boolean>` that emits true when controller is connected
- [ ] Fix skip detection logic: `MEDIA_ITEM_TRANSITION_REASON_AUTO` means auto-advance (NOT skip). Only `REASON_SEEK` with `currentPosition < 10s` should count as skip.
- [ ] Add gapless playback configuration

### Task 5.4: Implement Real Crossfade
- [ ] Replace the pass-through `CrossfadeAudioProcessor` with actual crossfade logic
- [ ] Buffer the last N seconds of outgoing track
- [ ] Apply linear or equal-power crossfade curve
- [ ] Make crossfade duration configurable (0s, 5s, 8s, 12s)

### Task 5.5: Queue System Enhancements
- [ ] Add drag-to-reorder in QueueScreen using `LazyColumn` reorder library or custom gestures
- [ ] Add swipe-to-remove from queue
- [ ] Highlight "Now Playing" track with green accent and animated equalizer bars
- [ ] Add "Add to Queue" Snackbar confirmation

### Task 5.6: Lyrics System
- [ ] Fix hardcoded scroll offset (`-200` pixels) — use `dp` conversion
- [ ] Add blurred album art background behind lyrics
- [ ] Add smooth spring animation for active line transition
- [ ] Add tap-to-seek (already implemented) with haptic feedback
- [ ] Improve mock LRC generation with more realistic timestamps

---

## Phase 6: Animation & Motion Engineering

> **Goal**: Every interaction must feel alive. 60fps minimum. Spring physics everywhere.

### Task 6.1: Screen Transitions
- [ ] Add slide + fade transitions between nav destinations
- [ ] HomeScreen → Full Player: vertical slide up with scale from 0.95
- [ ] Tab switches: subtle horizontal slide (50dp) with crossfade

### Task 6.2: Card Interactions
- [ ] Refine `cardPressEffect`: add haptic feedback on press
- [ ] Add subtle shadow elevation change on press (2dp → 0dp)
- [ ] TrackCard: add hover/focus highlight ring for keyboard/gamepad navigation

### Task 6.3: Player Animations
- [ ] Mini → Full transition: physics-based spring with configurable stiffness
- [ ] Album art: crossfade with slight scale animation on track change
- [ ] Play/Pause button: morph icon animation (play triangle → pause bars)
- [ ] Seekbar thumb: grow animation on touch, shrink on release (already partially done)
- [ ] Progress line: smooth `animateFloatAsState` for mini player progress

### Task 6.4: List Animations
- [ ] Track list items: staggered fade-in on first load
- [ ] Liked song heart: burst particle effect (HeartBurstEffect already exists, wire it)
- [ ] Pull-to-refresh: custom indicator with Streamify branding

### Task 6.5: Loading States
- [ ] Fix shimmer animation width to use `Modifier.fillMaxWidth()` measurement instead of hardcoded `1000f`
- [ ] Add skeleton screens for all data-loading states
- [ ] Use `AnimatedContent` for state transitions (Loading → Success → Error)

---

## Phase 7: Performance & Production Hardening

> **Goal**: Zero jank. Zero ANR. Zero crash. Ship-ready.

### Task 7.1: C++ Layer Hardening
- [ ] Fix `AudioPipeline.cc:26`: delete `session_` in destructor, add null check before re-init
- [ ] Fix `StreamifyDB.cc:94`: register `thread_local` cleanup via `pthread_key_create` destructor to close sqlite connections
- [ ] Replace all `std::cout`/`std::cerr` with `__android_log_print(ANDROID_LOG_DEBUG, "Streamify", ...)`
- [ ] Add SQL indexes: `CREATE INDEX IF NOT EXISTS idx_tracks_filepath ON tracks(filepath)` and `idx_tracks_title ON tracks(title)` and `idx_transitions_user ON user_transitions(user_id, from_track_id)`
- [ ] Fix VectorStore AVX `_mm256_dp_ps` mask for correctness on x86 emulators

### Task 7.2: Compose Performance
- [ ] Wrap all `collectAsState()` with `remember` + `derivedStateOf` where possible
- [ ] Move `progress` reads into `drawScope` (graphics layer) in `PlayerSeekBar` and `MiniPlayerBar`
- [ ] Use `key()` in `LazyColumn` items for stable recomposition: `items(tracks, key = { it.id })`
- [ ] Add `@Stable` annotation to `Track` data class
- [ ] Profile with Layout Inspector and fix any detected jank

### Task 7.3: Memory & Lifecycle
- [ ] Fix `IngestionViewModel` `observeForever` leak — replace with lifecycle-aware observation or Flow
- [ ] Add `Coil` memory cache configuration in `StreamifyApp`
- [ ] Ensure bitmap palette extraction uses `Palette.Builder.maximumColorCount(16)` for speed
- [ ] Release `ExoPlayer` properly on app task removal (override `onTaskRemoved` in `PlaybackService`)

### Task 7.4: Error Handling
- [ ] Add global exception handler for uncaught JNI exceptions
- [ ] Add retry logic for failed downloads
- [ ] Add offline mode detection — disable YouTube search when no network
- [ ] Add graceful fallback when ONNX model file is missing (currently crashes `AudioPipeline.init()`)

### Task 7.5: Build & CI
- [ ] Fix `SearchScreen.kt` duplicate import to unblock CI builds
- [ ] Add ProGuard rules for Chaquopy classes
- [ ] Add `WRITE_EXTERNAL_STORAGE` permission for SDK < 29 in manifest
- [ ] Test build on both arm64-v8a and x86_64 (emulator) ABIs

---

## Appendix: File-by-File Defect Registry

### Files with Zero Defects (No changes needed)
- `Dimens.kt` ✅
- `Shape.kt` ✅
- `Recommendation.kt` ✅
- `LyricsData.kt` ✅
- `DurationFormatter.kt` ✅
- `TimeGreeting.kt` ✅
- `PaletteExtractor.kt` ✅
- `PermissionHelper.kt` ✅
- `EventTracker.h` ✅
- `RecommendEngine.h` ✅

### Files to Delete
- `service/DownloadService.kt` — dead orphan, replaced by `DownloadWorker`
- `ui/screens/PlayerScreen.kt` — dead stub, replaced by `FullPlayerSheet`
- `python/download_engine/recommender.py` — mock stub, recommendations handled by C++ engine

### Files Requiring Major Rewrites
- `worker/DownloadWorker.kt` — thread safety fix (Phase 1)
- `viewmodel/IngestionViewModel.kt` — AndroidViewModel removal (Phase 1)
- `data/TrackRepository.kt` — singleton + reactive flows (Phase 1)
- `ui/components/MiniPlayerBar.kt` — gesture + HeartButton fix (Phase 5)
- `ui/screens/SearchScreen.kt` — compile fix + dark search bar (Phase 4)
- `ui/screens/LibraryScreen.kt` — DisposableEffect fix + sort/filter (Phase 4)

### Files Requiring Minor Fixes
- `StreamifyApp.kt` — add IngestionWorker enqueue
- `MainActivity.kt` — trigger scan after permission grant
- `HomeViewModel.kt` — remove double-load
- `PlayerViewModel.kt` — null controller guard
- `FullPlayerSheet.kt` — time display fix
- `AppNavGraph.kt` — remove dead routes, add transitions
- `BottomNavBar.kt` — add ripple, fix accessibility

---

## Execution Priority Order

```
Phase 1 (MUST DO FIRST — nothing works without this):
  1.1 DownloadWorker thread safety
  1.2 IngestionViewModel fix
  1.3 Singleton TrackRepository
  1.4 SearchScreen compile fix

Phase 2 (MUST DO SECOND — app is empty without this):
  2.1 Auto-scan on permission grant
  2.2 IngestionWorker improvements
  2.3 Manual rescan button

Phase 3 (Visual Foundation):
  3.1 Typography
  3.2 Colors
  3.3 System UI

Phase 4 (Screen-by-screen rebuild):
  4.1-4.5 in any order, parallel-safe

Phase 5 (Player polish):
  5.1-5.6 in order (MiniPlayer → FullPlayer → ViewModel → Crossfade → Queue → Lyrics)

Phase 6 (Animation):
  6.1-6.5 in any order, parallel-safe

Phase 7 (Hardening):
  7.1-7.5 in any order
```

---

*Generated by exhaustive codebase audit on 2026-08-13. Every line of every file was read and analyzed.*
