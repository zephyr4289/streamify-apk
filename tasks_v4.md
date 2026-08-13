# Streamify APK — Tasks v4: Engineering Marvel Checklist

> **Companion to**: `implementation_v4.md`  
> **Date**: 2026-08-14  
> **Status Legend**: `[ ]` = Not Started, `[~]` = In Progress, `[x]` = Done, `[!]` = Blocked

---

## PHASE 1 — AI Engine & Audio Pipeline Resurrection

### 1.1 BPM Extraction Engine (CRITICAL — Root cause of "BPM = 0")
- [x] 1.1.1 Implement `extractBPM()` in `AudioPipeline.cc` using onset detection + autocorrelation
  - [x] Compute short-time energy envelope from PCM buffer
  - [x] Compute spectral flux (onset strength signal) using KissFFT STFT
  - [x] Apply adaptive threshold peak-picking on onset signal
  - [x] Compute autocorrelation of onset envelope
  - [x] Find dominant periodicity and convert to BPM (60-200 range)
  - [x] Apply tempo octave correction for edge cases
- [x] 1.1.2 Implement `extractKey()` in `AudioPipeline.cc` using chromagram analysis
  - [x] Compute 12-bin chromagram from STFT frames
  - [x] Apply Krumhansl-Schmuckler key profiles
  - [x] Return key string (e.g., "C major", "A minor")
- [x] 1.1.3 Add `updateTrackBPM(int track_id, double bpm)` method to `StreamifyDB.cc`
- [x] 1.1.4 Add `updateTrackKey(int track_id, const std::string& key)` method to `StreamifyDB.cc`
- [x] 1.1.5 Modify `processAudioFile` JNI entry point to call `extractBPM()` + `extractKey()` and persist results via DB
- [x] 1.1.6 Add new JNI function `NativeBridge_extractBPM(trackId, filePath)` → `jfloat`
- [x] 1.1.7 Add `external fun extractBPM(trackId: Int, filePath: String): Float` to `NativeBridge.kt`
- [x] 1.1.8 Update `IngestionViewModel.kt` to read and display real BPM after processing
- [x] 1.1.9 Verify BPM shows correct values in UI for at least 5 test tracks

### 1.2 VectorStore NEON SIMD Optimization
- [x] 1.2.1 Replace cosine similarity loop with ARM NEON intrinsics (`arm_neon.h`)
- [x] 1.2.2 Add `#ifdef __aarch64__` guard with plain C++ fallback
- [x] 1.2.3 Pre-allocate results vector to avoid heap churn on each search
- [x] 1.2.4 Add NaN/Inf guard before vector insertion to prevent sort crashes

### 1.3 RecommendEngine Improvements
- [x] 1.3.1 Add temporal decay to event weighting (recent events weighted 3× more)
- [x] 1.3.2 Add diversity injection (at least 2 different artists in top-5 results)
- [x] 1.3.3 Add musical key compatibility bonus (same key or relative major/minor)
- [x] 1.3.4 BPM scoring now functional (real values instead of 0 vs 0)

### 1.4 Database Integrity & Thread Safety
- [x] 1.4.1 Add `UNIQUE` constraint on `filepath` column (or `INSERT OR IGNORE`)
- [x] 1.4.2 Fix `insertTrack` UPSERT `last_insert_rowid` bug — query DB for track ID after UPSERT
- [x] 1.4.3 Add NULL check on `sqlite3_column_text()` before `std::string` construction (prevent SIGSEGV)
- [x] 1.4.4 Add per-statement `std::lock_guard` mutex locking (not just on connection open)
- [x] 1.4.5 Add periodic WAL checkpoint (`PRAGMA wal_checkpoint(TRUNCATE)`)
- [x] 1.4.6 Add indexes: `idx_tracks_filepath`, `idx_user_liked_user_track`
- [x] 1.4.7 Fix EventTracker cold-start play drop (allow `fromTrackId <= 0` for initial plays)

### 1.5 CMake Build Fixes
- [x] 1.5.1 Link `onnxruntime` in `target_link_libraries` (currently missing!)
- [x] 1.5.2 Add `set(CMAKE_CXX_STANDARD 17)` explicitly
- [x] 1.5.3 Add NEON compile flags for arm64: `-mfpu=neon` / `-march=armv8-a+fp+simd`

### 1.6 AudioPipeline Robustness
- [x] 1.6.1 Wrap `session_->Run()` in try-catch to prevent ONNX crashes
- [x] 1.6.2 Validate ONNX output tensor shape is exactly 512 floats
- [x] 1.6.3 Add `is_initialized_` check before `processAudio()` execution
- [x] 1.6.4 Fix KissFFT memory leak on exception paths (RAII wrappers or cleanup)
- [x] 1.6.5 Add AudioPipeline mutex for thread-safe ONNX session access

---

## PHASE 2 — Python Pipeline Hardening

### 2.1 FFmpeg Post-Processing
- [x] 2.1.1 Add `FFmpegExtractAudio` postprocessor to `core.py` ydl_opts
- [x] 2.1.2 Use `preferred_quality` parameter in postprocessor config (320/256/192/128)
- [x] 2.1.3 Verify downloads produce `.mp3` files with proper ID3 support
- [x] 2.1.4 Test all quality levels produce correct bitrate output

### 2.2 YouTube Search Filtering
- [x] 2.2.1 Add `is_likely_music()` heuristic filter to `search.py`
- [x] 2.2.2 Filter out results with non-music keywords (episode, season, explained, review, trailer)
- [x] 2.2.3 Filter out results < 30s or > 15min duration
- [x] 2.2.4 Verify search for "house of balloon" returns music, not TV shows

### 2.3 ANSI Stripping Fix
- [x] 2.3.1 Replace hardcoded ANSI escape stripping with generic regex `re.sub(r'\x1b\[[0-9;]*m', '', s)`

### 2.4 Metadata Enrichment
- [x] 2.4.1 Remove synthetic BPM estimation from `metadata.py` (`90 + size % 500 / 10`)
- [x] 2.4.2 BPM now comes from C++ AudioPipeline — update metadata flow
- [x] 2.4.3 Ensure WebM/Opus files get Vorbis tags via mutagen (already in metadata.py, verify)

### 2.5 Dead Code Cleanup
- [x] 2.5.1 Remove or mark `recommender.py` as deprecated (100% stub)
- [x] 2.5.2 Remove or mark `downloader.py` as deprecated (orphaned, unused by DownloadWorker)

### 2.6 Lyrics Engine Hardening
- [x] 2.6.1 Add local file cache check before network fetch in `lyrics.py`
- [x] 2.6.2 Add strict rate-limiting / sleep fallback if 429 Too Many Requests hit
- [x] 2.6.3 Return empty string on persistent failure (don't crash ingestion) in `search.py` `process_single_entry` gracefully

---

## PHASE 3 — Theme System & Design Foundation

### 3.1 Color System
- [x] 3.1.1 Rewrite `Color.kt` with complete Spotify-authentic palette (15+ colors)
- [x] 3.1.2 Define surface elevation colors (0dp through 4dp)
- [x] 3.1.3 Define category gradient colors (8 distinct gradients for browse cards)
- [x] 3.1.4 Define player gradient colors (base + dynamic from Palette)

### 3.2 Typography System
- [x] 3.2.1 Rewrite `Type.kt` with 12+ text styles (Display through LabelSmall)
- [x] 3.2.2 Add letter-spacing tuning to all headline/display styles
- [x] 3.2.3 Add lyrics-specific text styles (LyricsActive, LyricsInactive)

### 3.3 Animation Constants
- [x] 3.3.1 Add `StreamifyAnimations` object to `Dimens.kt` with standardized durations
- [x] 3.3.2 Define spring stiffness/damping constants for reuse
- [x] 3.3.3 Define stagger delay constants

### 3.4 Shape System
- [x] 3.4.1 Expand `Shape.kt` with 7+ shape definitions (CardSmall through BottomSheet)

---

## PHASE 4 — Full-Screen Player Overhaul

### 4.1 Animated Gradient Background
- [x] 4.1.1 Extract 3 dominant colors from album art via Palette
- [x] 4.1.2 Create multi-stop radial gradient that slowly rotates
- [x] 4.1.3 Apply Gaussian blur (RenderEffect) over gradient
- [x] 4.1.4 Implement 800ms crossfade on track change
- [x] 4.1.5 Add dark scrim gradient (bottom 70% → top 0%) for readability
- [x] 4.1.6 Add subtle noise texture overlay at 3% opacity

### 4.2 Album Art with Gestures
- [x] 4.2.1 Implement HorizontalPager for swipe-to-skip
- [x] 4.2.2 Add spring physics on swipe overshoot
- [x] 4.2.3 Add album art scale animation on player open (0.85 → 1.0)
- [x] 4.2.4 Add subtle shadow under album art (8dp elevation)
- [x] 4.2.5 Add page indicator dots under album art
- [x] 4.2.6 Rounded corners on album art (8dp)

### 4.3 Custom Seek Bar
- [x] 4.3.1 Replace Material3 Slider with custom Canvas seek bar
- [x] 4.3.2 Implement touch state: track height 3dp → 5dp, thumb 12dp → 16dp
- [x] 4.3.3 Add floating time bubble tooltip on drag
- [x] 4.3.4 Add buffering indicator (secondary track color)
- [x] 4.3.5 Add Android Accessibility semantics for TalkBack

### 4.4 Player Controls Redesign
- [x] 4.4.1 Redesign control layout: Shuffle / Prev / Play|Pause / Next / Repeat
- [x] 4.4.2 Play/Pause: 64dp filled white circle, AnimatedContent icon morph
- [x] 4.4.3 All buttons: scale(0.85) spring bounce on press
- [x] 4.4.4 Shuffle/Repeat: green tint + subtle shake animation on activation
- [x] 4.4.5 Add haptic feedback on all control interactions

### 4.5 Player Bottom Section
- [x] 4.5.1 Add device indicator (speaker icon + device name)
- [x] 4.5.2 Add share button with Android share sheet
- [x] 4.5.3 Add queue button with transition animation
- [x] 4.5.4 Add sleep timer countdown indicator when active

### 4.6 Lyrics in Player (Swipeable Tabs)
- [x] 4.6.1 Implement HorizontalPager: Album Art ↔ Lyrics ↔ Queue
- [x] 4.6.2 Lyrics: Current line 24sp Bold white, past lines 40% alpha + blur
- [x] 4.6.3 Smooth auto-scroll to current lyrics line
- [x] 4.6.4 Tap-to-seek on lyrics lines
- [x] 4.6.5 Album art at 10% opacity with heavy blur behind lyrics

### 4.7 Swipe-Down to Minimize
- [x] 4.7.1 Implement BottomSheet or SwipeToDismissBox for player
- [ ] 4.7.2 Add spring physics on dismiss gesture
- [ ] 4.7.3 Album art shrinks/slides into mini player position on dismiss

---

## PHASE 5 — Home Screen Transformation

### 5.1 Collapsing App Bar
- [ ] 5.1.1 Implement collapsing app bar with green-to-black gradient
- [ ] 5.1.2 Add user avatar placeholder in app bar
- [ ] 5.1.3 Smooth collapse animation on scroll (parallax)

### 5.2 Recent Plays Grid (Spotify Clone)
- [ ] 5.2.1 Redesign to 2×3 compact cards (48dp height, art left, text right)
- [ ] 5.2.2 Background: #282828, corner radius 4dp
- [ ] 5.2.3 Add press scale animation (0.96) with spring physics
- [ ] 5.2.4 Show animated equalizer overlay on currently playing track

### 5.3 Section Headers
- [ ] 5.3.1 Bold 22sp Montserrat headers with "See All" button
- [ ] 5.3.2 Fade-in animation on scroll into view

### 5.4 Track Card Redesign
- [ ] 5.4.1 Redesign cards: 160dp width, 150dp art, shadow, 8dp radius
- [ ] 5.4.2 Press: scale(0.95) with spring animation
- [ ] 5.4.3 Snap-to-item behavior on horizontal fling

### 5.5 Staggered Entry Animations
- [ ] 5.5.1 Greeting: fade in immediately
- [ ] 5.5.2 Recent Plays: staggered fade+slide (50ms per card)
- [ ] 5.5.3 Section headers: fade in 200ms after cards
- [ ] 5.5.4 Carousels: slide in from right (400ms)

### 5.6 Pull-to-Refresh
- [ ] 5.6.1 Add pullRefresh modifier to LazyColumn
- [ ] 5.6.2 Custom refresh indicator (spinning Streamify logo)
- [ ] 5.6.3 Re-fetch library + re-run recommendations on refresh

### 5.7 Layout Fixes
- [ ] 5.7.1 Remove hardcoded `height(300.dp)` on header
- [ ] 5.7.2 Remove unused `PlayerViewModel` parameter
- [ ] 5.7.3 Replace `chunked(2)` loop with proper `LazyVerticalGrid` or fixed grid

---

## PHASE 6 — Search Experience Overhaul

### 6.1 Animated Search Bar
- [ ] 6.1.1 Animated focus state (expand, dim background, cancel button fade-in)
- [ ] 6.1.2 Search icon slide animation on focus
- [ ] 6.1.3 Auto-focus on screen entry (with 200ms keyboard delay)

### 6.2 Browse Categories Redesign
- [ ] 6.2.1 Replace flat colored rectangles with gradient cards
- [ ] 6.2.2 Add tilted image overlay in bottom-right corner (20° rotation)
- [ ] 6.2.3 Each card: gradient background, Bold 16sp white text, 8dp radius
- [ ] 6.2.4 Press animation: scale(0.96) + elevation increase

### 6.3 Search Results Improvements
- [ ] 6.3.1 Local DB results appear instantly above YouTube results
- [ ] 6.3.2 YouTube results load with shimmer placeholder animation
- [ ] 6.3.3 Add music-only filter to search results (see Phase 2.2)
- [ ] 6.3.4 Add 500ms search debounce (fix keystroke-triggered search)

### 6.4 Recent Searches
- [ ] 6.4.1 Show recent searches with clock icon
- [ ] 6.4.2 Swipe-to-delete individual items with slide animation
- [ ] 6.4.3 "Clear" button with confirmation dialog

### 6.5 Layout Fixes
- [ ] 6.5.1 Fix nested `LazyVerticalGrid` inside `LazyColumn` anti-pattern
- [ ] 6.5.2 Remove hardcoded `heightIn(max = 1000.dp)` hack
- [ ] 6.5.3 Replace Toast on track click with proper Snackbar

---

## PHASE 7 — Library & Playlist System

### 7.1 Library Screen Redesign
- [ ] 7.1.1 Add list/grid view toggle (animated switch)
- [ ] 7.1.2 Add sort dropdown: Recent / A-Z / Artist / Duration
- [ ] 7.1.3 Animated filter chip selection (green fill slides in)
- [ ] 7.1.4 Swipe-right on item: add to queue (with feedback)
- [ ] 7.1.5 Swipe-left on item: delete (with confirmation)
- [ ] 7.1.6 Long-press: opens context menu bottom sheet
- [ ] 7.1.7 Animated equalizer bars on currently playing track
- [ ] 7.1.8 Pull-to-refresh to re-scan library

### 7.2 Layout Fixes
- [ ] 7.2.1 Fix unsafe `context as ViewModelStoreOwner` cast
- [ ] 7.2.2 Fix empty "Add to Playlist" action in ContextMenuSheet
- [ ] 7.2.3 Replace magic number filters (0, 1, 2, 3) with enum
- [ ] 7.2.4 Deduplicate filter chip code (DRY refactor)

### 7.3 Playlist Database Migration
- [ ] 7.3.1 Add `playlists` table to `StreamifyDB.cc` schema
- [ ] 7.3.2 Add `playlist_tracks` table with position ordering
- [ ] 7.3.3 Add JNI functions: createPlaylist, deletePlaylist, renamePlaylist
- [ ] 7.3.4 Add JNI functions: addTrackToPlaylist, removeTrackFromPlaylist, getPlaylistTracks
- [ ] 7.3.5 Add JNI function: reorderPlaylistTrack
- [ ] 7.3.6 Add JNI function: getAllPlaylists
- [ ] 7.3.7 Update `NativeBridge.kt` with all new external functions
- [ ] 7.3.8 Migrate existing SharedPreferences playlists to SQLite on first run

### 7.4 Playlist UI
- [ ] 7.4.1 Create `PlaylistDetailScreen.kt` with art mosaic header
- [ ] 7.4.2 Auto-generate playlist cover from first 4 tracks' art
- [ ] 7.4.3 Create `CreatePlaylistSheet.kt` bottom sheet (name, description, art)
- [ ] 7.4.4 Add playlist shuffle/play buttons
- [ ] 7.4.5 Add drag-to-reorder tracks within playlist
- [ ] 7.4.6 "Liked Songs" special playlist with purple gradient heart icon

---

## PHASE 8 — Animation & Gesture System

### 8.1 Global Press Effect
- [ ] 8.1.1 Rewrite `CardPressEffect.kt` with spring physics (DampingRatioLowBouncy)
- [ ] 8.1.2 Add alpha dimming on press (0.85f)
- [ ] 8.1.3 Apply `.pressEffect()` modifier to ALL interactive elements globally

### 8.2 Staggered List Animation
- [ ] 8.2.1 Create `StaggeredAnimatedItem.kt` composable
- [ ] 8.2.2 Apply to HomeScreen sections
- [ ] 8.2.3 Apply to LibraryScreen track list
- [ ] 8.2.4 Apply to SearchScreen results

### 8.3 Screen Transitions
- [ ] 8.3.1 Add fadeIn/fadeOut to Home tab
- [ ] 8.3.2 Add slideIn/slideOut horizontal for Search/Library
- [ ] 8.3.3 Add slideInVertically from bottom for Player
- [ ] 8.3.4 Update `AppNavGraph.kt` with enterTransition/exitTransition specs

### 8.4 Heart Animation Overhaul
- [ ] 8.4.1 Rewrite `HeartBurstEffect.kt` with 12-particle burst
- [ ] 8.4.2 Heart: scale 1.0 → 1.3 → 1.0 bounce
- [ ] 8.4.3 Color fill animation from bottom to top (200ms)
- [ ] 8.4.4 Particles fade + fall with gravity (600ms)
- [ ] 8.4.5 Unlike: shake animation (±8° twice)
- [ ] 8.4.6 Haptic: MediumClick on like

### 8.5 Now Playing Equalizer Indicator
- [ ] 8.5.1 Create `NowPlayingIndicator.kt` — 3 animated green bars
- [ ] 8.5.2 Each bar animates independently (infiniteRepeatable, different durations)
- [ ] 8.5.3 Integrate into TrackListItem.kt when track is playing
- [ ] 8.5.4 Integrate into HomeScreen recent plays grid

### 8.6 Mini Player ↔ Full Player Transition
- [ ] 8.6.1 Album art expand + move to center animation
- [ ] 8.6.2 Background gradient fade-in
- [ ] 8.6.3 Controls slide up from bottom
- [ ] 8.6.4 Reverse animation on dismiss
- [ ] 8.6.5 Spring physics on dismiss gesture

### 8.7 Haptic Feedback System
- [ ] 8.7.1 Button taps: LightClick
- [ ] 8.7.2 Like/unlike: MediumClick
- [ ] 8.7.3 Track skip: LightClick
- [ ] 8.7.4 Seek bar interaction: TextHandleMove (continuous)
- [ ] 8.7.5 Long press: LongPress
- [ ] 8.7.6 Pull-to-refresh threshold: MediumClick

---

## PHASE 9 — Component-Level Polish

### 9.1 MiniPlayerBar Redesign
- [ ] 9.1.1 Add 2dp progress bar at top (smooth animated progress)
- [ ] 9.1.2 Album art: 48dp, 4dp corners, shadow
- [ ] 9.1.3 MarqueeText for title overflow
- [ ] 9.1.4 Animated play/pause icon morph
- [ ] 9.1.5 Swipe-right to skip with spring physics + visual feedback
- [ ] 9.1.6 Swipe-up to open full player
- [ ] 9.1.7 Background: #282828 with 8dp top corners
- [ ] 9.1.8 Fix progress bar: use `animateFloatAsState` for smooth updates

### 9.2 BottomNavBar Redesign
- [ ] 9.2.1 Selected tab: icon scale 1.1x, white tint
- [ ] 9.2.2 Unselected tab: icon 1.0x, SpotifyTextSecondary tint
- [ ] 9.2.3 Add animated horizontal indicator line under selected tab
- [ ] 9.2.4 Smooth crossfade between tab states
- [ ] 9.2.5 Restore ripple effect or implement custom press effect (fix removed ripple)

### 9.3 Context Menu Bottom Sheet
- [ ] 9.3.1 Redesign with large art header + song info
- [ ] 9.3.2 Add all menu items: Like, Add to Playlist, Add to Queue, Download, Share, View Artist
- [ ] 9.3.3 Spring slide-up animation
- [ ] 9.3.4 Background scrim dimming
- [ ] 9.3.5 Each item: 48dp height, subtle ripple
- [ ] 9.3.6 Fix "Add to Playlist" action (currently empty/noop)

### 9.4 TrackListItem with Now Playing Indicator
- [ ] 9.4.1 Replace album art with equalizer animation when playing
- [ ] 9.4.2 Title text turns SpotifyGreen when track is playing
- [ ] 9.4.3 Press effect on tap
- [ ] 9.4.4 3-dot menu always visible

### 9.5 EmptyStateView Redesign
- [ ] 9.5.1 Add relevant icon (64dp, tertiary color)
- [ ] 9.5.2 Primary text: 16sp SemiBold
- [ ] 9.5.3 Secondary text: 14sp Regular, 60% alpha
- [ ] 9.5.4 Optional CTA button (green outline)
- [ ] 9.5.5 Animated entry: icon fade-in, then text slide-up

### 9.6 ShimmerPlaceholder Usage
- [ ] 9.6.1 Apply shimmer to HomeScreen during initial data load
- [ ] 9.6.2 Apply shimmer to LibraryScreen during load
- [ ] 9.6.3 Apply shimmer to SearchScreen YouTube results while loading
- [ ] 9.6.4 Apply shimmer to DownloadScreen during status fetch

---

## PHASE 10 — Downloads & Settings Redesign

### 10.1 Downloads Screen Overhaul
- [ ] 10.1.1 Active downloads: circular progress ring around album art
- [ ] 10.1.2 Show speed (MB/s) and ETA from Python callbacks
- [ ] 10.1.3 Add pause/resume/cancel buttons per download
- [ ] 10.1.4 Completed downloads: checkmark overlay on art
- [ ] 10.1.5 Failed downloads: retry button
- [ ] 10.1.6 Download history section
- [ ] 10.1.7 Redesigned empty state with icon + CTA button

### 10.2 Settings Screen Overhaul
- [ ] 10.2.1 Organized sections with colored icons
- [ ] 10.2.2 Audio Quality section: streaming + download quality selectors
- [ ] 10.2.3 Playback section: crossfade slider, gapless toggle, normalize toggle
- [ ] 10.2.4 Audio Effects section: equalizer link
- [ ] 10.2.5 Sleep Timer section: preset options + end-of-track toggle
- [ ] 10.2.6 Storage section: cache size + clear button, download size, location
- [ ] 10.2.7 About section: version, build number, licenses
- [ ] 10.2.8 Custom animated toggles (spring Switch)
- [ ] 10.2.9 Section headers: 12sp LabelSmall, SpotifyGreen, ALL CAPS

---

## PHASE 11 — Playback Service Hardening

### 11.1 Media Notification
- [ ] 11.1.1 Rich notification: album art, title, artist, prev/play/next buttons
- [ ] 11.1.2 Progress bar in notification (Android 13+)
- [ ] 11.1.3 Custom notification channel with proper name/importance

### 11.2 CrossfadeAudioProcessor
- [ ] 11.2.1 Implement real crossfade logic (not stub)
- [ ] 11.2.2 Read crossfade duration from settings
- [ ] 11.2.3 Handle edge cases: very short tracks, seek-to-end

### 11.3 Audio Focus & Connectivity
- [ ] 11.3.1 Duck audio on notification sounds
- [ ] 11.3.2 Pause on phone calls, resume after
- [ ] 11.3.3 Handle Bluetooth disconnect (pause playback)
- [ ] 11.3.4 Auto-resume on Bluetooth reconnect (optional)

### 11.4 Queue Persistence
- [ ] 11.4.1 Save current queue to DB/SharedPrefs on app kill
- [ ] 11.4.2 Restore queue + position on app restart
- [ ] 11.4.3 Save playback position for resume

### 11.5 Gapless Playback
- [ ] 11.5.1 Pre-buffer next track at 80% completion
- [ ] 11.5.2 Seamless transition using Media3 preload API

---

## PHASE 12 — Final Integration & QA

### 12.1 End-to-End Flow Testing
- [ ] 12.1.1 Fresh install → Home → Search → Download → Play → Like → Recommendations
- [ ] 12.1.2 Download with progress → AI process → BPM extracted → Recommendations improve
- [ ] 12.1.3 Playlist creation → add tracks → reorder → play → shuffle
- [ ] 12.1.4 Lyrics fetch → synced display in player tabs
- [ ] 12.1.5 Equalizer preset → audible effect confirmed
- [ ] 12.1.6 Sleep timer → countdown visible → auto-pause
- [ ] 12.1.7 Background playback → notification controls → lock screen

### 12.2 Performance Verification
- [ ] 12.2.1 Cold start: < 1.5s to interactive
- [ ] 12.2.2 Track switch: < 200ms to audio start
- [ ] 12.2.3 Local search: < 50ms results
- [ ] 12.2.4 YouTube search: < 2s results
- [ ] 12.2.5 AI recommendation: < 100ms for 10 results
- [ ] 12.2.6 BPM extraction: < 3s per track
- [ ] 12.2.7 UI: constant 60fps (no jank during animations)
- [ ] 12.2.8 Memory: < 200MB resident

### 12.3 Edge Case Handling
- [ ] 12.3.1 Empty library → proper empty states everywhere
- [ ] 12.3.2 No internet → hide YouTube, show offline badge
- [ ] 12.3.3 Very long titles → MarqueeText on all surfaces
- [ ] 12.3.4 Corrupted audio → skip with error toast
- [ ] 12.3.5 Multiple rapid likes → debounce DB operations
- [ ] 12.3.6 RTL language support → verify Canvas components handle RTL

### 12.4 Code Quality
- [ ] 12.4.1 Remove all TODO/FIXME comments that are addressed
- [ ] 12.4.2 Ensure all new composables have `@Preview` annotations
- [ ] 12.4.3 Verify ProGuard rules don't strip JNI methods
- [ ] 12.4.4 Verify Chaquopy Python modules load correctly on clean install

---

## Progress Summary

| Phase | Total Tasks | Done | Remaining |
|---|---|---|---|
| Phase 1: AI Engine | 26 | 0 | 26 |
| Phase 2: Python Pipeline | 14 | 0 | 14 |
| Phase 3: Theme System | 10 | 0 | 10 |
| Phase 4: Full Player | 24 | 0 | 24 |
| Phase 5: Home Screen | 17 | 0 | 17 |
| Phase 6: Search | 14 | 0 | 14 |
| Phase 7: Library & Playlists | 19 | 0 | 19 |
| Phase 8: Animations & Gestures | 28 | 0 | 28 |
| Phase 9: Components | 24 | 0 | 24 |
| Phase 10: Downloads & Settings | 17 | 0 | 17 |
| Phase 11: Playback Service | 12 | 0 | 12 |
| Phase 12: Integration & QA | 18 | 0 | 18 |
| **TOTAL** | **223** | **0** | **223** |

---

*Every checkbox here maps to a concrete code change. No checkbox is vague. When all 223 are checked, Streamify will be an engineering marvel.*
