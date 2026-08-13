# Streamify APK — Implementation v4: Engineering Marvel Overhaul

> **Codename**: *Project Obsidian*  
> **Date**: 2026-08-14  
> **Scope**: Full-stack overhaul — C++ AI Engine, Python Pipeline, Kotlin UI/UX, Animation System  
> **Goal**: Transform Streamify from a functional prototype into an indistinguishable Spotify clone with superior offline AI capabilities.

---

## Table of Contents

1. [Executive Audit Summary](#1-executive-audit-summary)
2. [PHASE 1 — AI Engine & Audio Pipeline Resurrection](#2-phase-1--ai-engine--audio-pipeline-resurrection)
3. [PHASE 2 — Python Pipeline Hardening](#3-phase-2--python-pipeline-hardening)
4. [PHASE 3 — Theme System & Design Foundation](#4-phase-3--theme-system--design-foundation)
5. [PHASE 4 — Full-Screen Player Overhaul](#5-phase-4--full-screen-player-overhaul)
6. [PHASE 5 — Home Screen Transformation](#6-phase-5--home-screen-transformation)
7. [PHASE 6 — Search Experience Overhaul](#7-phase-6--search-experience-overhaul)
8. [PHASE 7 — Library & Playlist System](#8-phase-7--library--playlist-system)
9. [PHASE 8 — Animation & Gesture System](#9-phase-8--animation--gesture-system)
10. [PHASE 9 — Component-Level Polish](#10-phase-9--component-level-polish)
11. [PHASE 10 — Downloads & Settings Redesign](#11-phase-10--downloads--settings-redesign)
12. [PHASE 11 — Playback Service Hardening](#12-phase-11--playback-service-hardening)
13. [PHASE 12 — Final Integration & QA](#13-phase-12--final-integration--qa)

---

## 1. Executive Audit Summary

### What We Found — The Brutal Truth

After a full-depth audit of **every single file** in the codebase (100+ source files across Kotlin, C++, Python), comparing against actual Spotify screenshots, here is the state of affairs:

#### 🔴 CRITICAL — AI Engine is Non-Functional
- **BPM is always 0** across the entire app. The root cause: **BPM extraction does not exist anywhere in the codebase.** The C++ `AudioPipeline` generates CLAP embeddings but has zero beat-tracking code. The Python `metadata.py` can read/write BPM tags via mutagen but never computes them. The Kotlin `IngestionViewModel` calls `processAudioFile()` which returns a vector offset but no tempo data. The `insertTrack()` JNI call receives `bpm: Float` but it's always passed `0f`.
- **VectorStore claims SIMD cosine similarity** in the README but uses plain C++ `for` loops — no NEON intrinsics, no AVX.
- **RecommendEngine** works in theory but produces mediocre results because: (a) half the tracks have `vector_offset = -1` (never processed), (b) BPM scoring is useless (always 0 vs 0), (c) no temporal decay on events, (d) brute-force O(n) search.

#### 🔴 CRITICAL — UI is Prototype-Grade
Comparing Streamify screenshots against Spotify:
- **Home Screen**: Static grid and rows with no entry animations, no parallax, no collapsing toolbar, no user avatar, no gradient backgrounds. Cards lack press feedback.
- **Full Player**: Embarrassingly basic — static album art (no swipe gestures), plain `Slider` seekbar (no custom thumb or time bubble), no lyrics integration in player, no animated gradient transitions, no swipe-down-to-dismiss.
- **Mini Player**: No progress bar line, no swipe gestures, title doesn't marquee.
- **Search**: Flat colored category blocks (Spotify has gradient cards with art overlays). YouTube results show non-music content. No voice search.
- **Library**: No grid/list toggle, no sort dropdown, no playlist creation UI, no swipe actions on items.
- **Downloads**: Empty state is just centered text. No download progress rings, no history.
- **Settings**: Bare minimum. No storage, cache, about, or account sections.
- **Zero meaningful animations** — no staggered list entry, no shared element transitions, no spring physics, no gesture navigation, no haptic feedback.

#### 🟡 HIGH — Python Pipeline Issues
- Downloads in WebM/Opus format without FFmpeg post-processing to MP3
- Quality parameter (`preferred_quality`) is accepted but ignored
- YouTube search returns non-music results (TV show episodes)
- No BPM analysis anywhere in Python layer
- Playlists stored in SharedPreferences instead of C++ DB

---

## 2. PHASE 1 — AI Engine & Audio Pipeline Resurrection

### 2.1 BPM Extraction Engine (C++)

The single most important backend fix. We need to implement **real-time beat tracking** in `AudioPipeline.cc`.

#### Algorithm: Onset Detection + Autocorrelation BPM

```
1. Load audio with miniaudio (already done)
2. Compute Short-Time Energy Envelope
3. Compute Spectral Flux (onset strength signal)
4. Apply onset peak-picking with adaptive threshold
5. Compute autocorrelation of onset signal
6. Find dominant periodicity → BPM
7. Apply tempo octave correction (60-200 BPM range)
```

#### Implementation Plan

**File: `native/ingest/AudioPipeline.cc`** — Add new method `extractBPM()`:
- Input: Raw PCM float buffer (already loaded by `processAudio()`)
- Process: Compute STFT frames → spectral flux → onset envelope → autocorrelation
- Output: `float bpm` value in range [60, 200]
- The existing KissFFT library is already linked and can be reused for STFT

**File: `native/ingest/AudioPipeline.h`** — Expose:
```cpp
float extractBPM(const float* samples, size_t numSamples, int sampleRate);
```

**File: `native/jni/jni_bridge.cc`** — Modify `processAudioFile()`:
- After generating embedding vector, also call `extractBPM()`
- Call `StreamifyDB::updateTrackBPM(trackId, bpm)` to persist

**File: `native/engine/StreamifyDB.cc`** — Add:
```cpp
bool updateTrackBPM(int track_id, double bpm);
```

**File: `native/jni/jni_bridge.cc`** — New JNI function:
```cpp
Java_com_streamify_app_data_NativeBridge_extractBPM(JNIEnv*, jobject, jint trackId, jstring filePath) → jfloat
```

**File: `NativeBridge.kt`** — Add:
```kotlin
external fun extractBPM(trackId: Int, filePath: String): Float
```

#### Key Extraction Enhancement
While we're in the audio analysis code, also extract the **musical key** (C major, A minor, etc.) using chroma feature analysis:
- Compute chromagram from STFT
- Use Krumhansl-Schmuckler key-finding algorithm
- Store in `StreamifyDB` `key` column

### 2.2 VectorStore NEON SIMD Optimization

**File: `native/engine/VectorStore.cc`** — Replace cosine similarity with ARM NEON:

```cpp
#include <arm_neon.h>

float cosineSimilarityNEON(const float* a, const float* b, int dim) {
    float32x4_t sum_ab = vdupq_n_f32(0.0f);
    float32x4_t sum_aa = vdupq_n_f32(0.0f);
    float32x4_t sum_bb = vdupq_n_f32(0.0f);
    
    for (int i = 0; i < dim; i += 4) {
        float32x4_t va = vld1q_f32(a + i);
        float32x4_t vb = vld1q_f32(b + i);
        sum_ab = vfmaq_f32(sum_ab, va, vb);
        sum_aa = vfmaq_f32(sum_aa, va, va);
        sum_bb = vfmaq_f32(sum_bb, vb, vb);
    }
    // Horizontal sum and compute cosine
    float dot = vaddvq_f32(sum_ab);
    float na = sqrtf(vaddvq_f32(sum_aa));
    float nb = sqrtf(vaddvq_f32(sum_bb));
    return (na * nb > 1e-9f) ? dot / (na * nb) : 0.0f;
}
```

### 2.3 RecommendEngine Improvements

**File: `native/engine/RecommendEngine.cc`**:
- Now that BPM is real, the `beta_bpm * bpm_score` term will actually contribute
- Add **temporal decay** on events: recent plays weighted 3× more than week-old plays
- Add **genre clustering** fallback when vector similarity is unavailable
- Add **diversity injection**: ensure recommendations include at least 2 different artists
- Add **musical key compatibility** bonus: boost tracks in the same key or relative major/minor

### 2.4 Database Integrity Fixes

**File: `native/engine/StreamifyDB.cc`**:
- Add `UNIQUE` constraint on `filepath` column (or use `INSERT OR IGNORE`)
- Add `updateTrackBPM()` and `updateTrackKey()` methods
- Add periodic WAL checkpoint (`PRAGMA wal_checkpoint(TRUNCATE)` every 100 operations)
- Add `CREATE INDEX IF NOT EXISTS idx_tracks_filepath ON tracks(filepath)` for fast lookups
- Move playlist storage from SharedPreferences into SQLite (new `playlists` and `playlist_tracks` tables)

---

## 3. PHASE 2 — Python Pipeline Hardening

### 3.1 FFmpeg Post-Processing

**File: `core.py`** — Add FFmpeg postprocessor to force MP3 output:

```python
ydl_opts = {
    'format': 'bestaudio/best',
    'postprocessors': [{
        'key': 'FFmpegExtractAudio',
        'preferredcodec': 'mp3',
        'preferredquality': preferred_quality,  # Actually USE the parameter
    }],
    ...
}
```

This ensures all downloads are in `.mp3` format with proper ID3 support, and the quality parameter actually works.

### 3.2 Python BPM Analysis (Fallback)

**File: `metadata.py`** — Add BPM extraction using `librosa`-like approach (or simpler onset detection):

Since we can't easily add librosa to Chaquopy (large native deps), use a lightweight approach:
- Read the audio with a pure-Python WAV decoder
- Compute onset strength via spectral flux
- Autocorrelation for tempo estimation
- Write BPM to ID3 `TBPM` tag via mutagen

Alternative: Use the C++ AudioPipeline for BPM and just pass it back through JNI. This is the better approach since C++ is much faster.

### 3.3 YouTube Search Filtering

**File: `search.py`** — Filter search results for music:

```python
# Add to ydl_opts for search:
ydl_opts = {
    'default_search': 'ytsearch10',
    'extract_flat': True,
    'force_generic_extractor': False,
}

# Post-filter: only include results that look like music
def is_likely_music(entry):
    """Heuristic: music videos are usually 1-10 minutes, have 'music' category"""
    duration = entry.get('duration', 0)
    title = entry.get('title', '').lower()
    
    # Filter out obviously non-music content
    non_music_keywords = ['episode', 'season', 'explained', 'review', 'trailer', 'gameplay']
    if any(kw in title for kw in non_music_keywords):
        return False
    if duration and (duration < 30 or duration > 900):  # < 30s or > 15min
        return False
    return True
```

### 3.4 Spotify Metadata Enrichment

**File: `spotify.py`** — Improve integration:
- After downloading a track, automatically search Spotify for matching metadata
- Pull: proper artist name, album name, album art (high-res), genre, release year
- Use this to enrich the C++ database entry
- Credential management: store in a config file, not hardcoded

### 3.5 Lyrics Engine Hardening

**File: `lyrics.py`**:
- Add fallback API endpoints (LRCLIB, Genius, etc.)
- Cache fetched lyrics in the `.Streamify/lyrics/` directory
- Add timeout handling (5s max per request)
- Return structured data with timestamps for synced lyrics

---

## 4. PHASE 3 — Theme System & Design Foundation

This is the foundation everything else builds on. We need a proper design system before touching any screens.

### 4.1 Color System Overhaul

**File: `ui/theme/Color.kt`** — Complete rewrite:

```kotlin
// Core Palette (Spotify-authentic)
val SpotifyBlack = Color(0xFF000000)          // True black for AMOLED
val SpotifyDarkGray = Color(0xFF121212)       // Primary background
val SpotifyMediumGray = Color(0xFF1E1E1E)     // Elevated surfaces
val SpotifyLightGray = Color(0xFF282828)      // Cards, containers
val SpotifyBorderGray = Color(0xFF333333)     // Subtle borders
val SpotifyTextPrimary = Color(0xFFFFFFFF)    // Primary text
val SpotifyTextSecondary = Color(0xFFB3B3B3)  // Secondary text
val SpotifyTextTertiary = Color(0xFF727272)   // Tertiary/hint text
val SpotifyGreen = Color(0xFF1DB954)          // Primary accent
val SpotifyGreenDark = Color(0xFF1AA34A)      // Pressed state
val SpotifyGreenLight = Color(0xFF1ED760)     // Hover state

// Gradient Definitions
val HomeGradientTop = Color(0xFF1A3A2A)       // Dark green tint for home
val PlayerGradientTop = Color(0xFF333333)     // Default player gradient top

// Category Card Colors (matching Spotify's browse categories)
val CategoryPink = Color(0xFFE8115B)
val CategoryOrange = Color(0xFFE76C00)
val CategoryPurple = Color(0xFF8D67AB)
val CategoryBlue = Color(0xFF2D46B9)
val CategoryGreen = Color(0xFF1DB954)
val CategoryNavy = Color(0xFF1E3264)
val CategoryTeal = Color(0xFF148A08)
val CategoryMagenta = Color(0xFFDC148C)

// Surface Elevation System
val SurfaceElevation0 = Color(0xFF121212)     // Base
val SurfaceElevation1 = Color(0xFF1E1E1E)     // +1dp
val SurfaceElevation2 = Color(0xFF232323)     // +2dp
val SurfaceElevation3 = Color(0xFF252525)     // +3dp
val SurfaceElevation4 = Color(0xFF272727)     // +4dp
```

### 4.2 Typography System

**File: `ui/theme/Type.kt`** — Spotify-accurate text styles:

Define a complete typographic scale with:
- **Display**: 32sp Montserrat Bold (-0.5 letter-spacing) — for playlist names
- **HeadlineLarge**: 24sp Montserrat Bold — section headers
- **HeadlineMedium**: 20sp Montserrat SemiBold — screen titles
- **TitleLarge**: 18sp Poppins SemiBold — player song title
- **TitleMedium**: 16sp Poppins Medium — list item titles
- **TitleSmall**: 14sp Poppins Medium — card titles
- **BodyLarge**: 16sp Poppins Regular — body text
- **BodyMedium**: 14sp Poppins Regular — descriptions
- **BodySmall**: 12sp Poppins Regular — metadata, timestamps
- **LabelLarge**: 14sp Poppins Medium — buttons, chips
- **LabelSmall**: 10sp Poppins Regular — overline text
- **LyricsActive**: 24sp Poppins Bold — current lyrics line
- **LyricsInactive**: 18sp Poppins Regular 50% alpha — past/future lyrics

### 4.3 Animation Constants

**File: `ui/theme/Dimens.kt`** — Add animation configuration:

```kotlin
object StreamifyAnimations {
    const val PRESS_SCALE = 0.95f
    const val PRESS_DURATION_MS = 100
    const val SPRING_STIFFNESS = Spring.StiffnessMediumLow
    const val SPRING_DAMPING = Spring.DampingRatioLowBouncy
    const val STAGGER_DELAY_MS = 50L
    const val FADE_IN_DURATION_MS = 300
    const val SLIDE_IN_DURATION_MS = 400
    const val GRADIENT_CROSSFADE_MS = 800
    const val SEEK_BAR_EXPAND_MS = 150
    const val MINI_PLAYER_HEIGHT = 64.dp
    const val PLAYER_ART_SIZE = 320.dp
    const val BOTTOM_NAV_HEIGHT = 56.dp
}
```

### 4.4 Shape System

**File: `ui/theme/Shape.kt`** — Expanded shapes:

```kotlin
val CardShapeSmall = RoundedCornerShape(4.dp)    // Recent play cards
val CardShapeMedium = RoundedCornerShape(8.dp)    // Standard cards
val CardShapeLarge = RoundedCornerShape(12.dp)    // Feature cards
val ChipShape = RoundedCornerShape(50)            // Filter chips (fully rounded)
val SearchBarShape = RoundedCornerShape(8.dp)     // Search input
val BottomSheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
val AlbumArtShape = RoundedCornerShape(8.dp)      // Player album art
val CircleShape = CircleShape                      // Artist avatars
```

---

## 5. PHASE 4 — Full-Screen Player Overhaul

The player is the heart of a music app. It must be **breathtaking**.

### 5.1 Animated Gradient Background

**File: `ui/components/PlayerBackground.kt`** — Complete rewrite:

```
Current: Simple linear gradient with animateColorAsState
Target: Multi-layer animated gradient system
```

Implementation:
1. Extract **3 dominant colors** from album art using `Palette` (dark vibrant, muted, dominant)
2. Create a **3-stop radial gradient** that slowly rotates (360° over 30 seconds)
3. Apply **Gaussian blur** (RenderEffect.createBlurEffect) over the gradient
4. On track change: **crossfade** between old and new gradient colors over 800ms with `animateColorAsState(animationSpec = tween(800))`
5. Add a subtle **noise texture overlay** at 3% opacity for depth
6. Add a **bottom-to-top dark scrim** (70% → 0% opacity) so controls remain readable

### 5.2 Album Art with Gestures

**File: `ui/screens/FullPlayerSheet.kt`** — Album art section:

1. **Swipe-to-skip**: Horizontal pager (`HorizontalPager`) wrapping album art. Swipe left = next track, swipe right = previous. With spring physics and peek animation.
2. **Scale animation**: Album art scales from 0.85 → 1.0 when player opens (shared element feel)
3. **Shadow/elevation**: Subtle shadow under album art (8dp elevation with `Modifier.shadow()`)
4. **Page indicator dots** under album art (like Spotify — shows multiple cards)
5. **Rounded corners**: 8dp rounded corners on album art
6. **3D rotation hint**: Slight perspective tilt on swipe (15° max rotation)

### 5.3 Custom Seek Bar

**File: `ui/components/PlayerSeekBar.kt`** — Complete rewrite:

Replace `Material3 Slider` with a fully custom composable:

```
Design:
- Default state: 3dp height track, 12dp circular thumb
- Touch/drag state: 5dp height track, 16dp thumb with green glow ring
- Time labels: "0:12" left, "6:45" right, in BodySmall
- On drag: Show floating time bubble tooltip above thumb
- Buffered amount: Secondary track in darker color
- Active track color: SpotifyGreen
- Inactive track color: SpotifyTextTertiary at 30% alpha
```

Implementation:
- Custom `Canvas` drawing for the track
- `Modifier.pointerInput` for drag handling
- `animateDpAsState` for thumb size transition
- `AnimatedVisibility` for time bubble popup

### 5.4 Player Controls Redesign

**File: `ui/components/PlayerControls.kt`** — Redesign:

```
Layout (left to right):
[Shuffle] ---- [Previous] ---- [Play/Pause] ---- [Next] ---- [Repeat]

Sizes:
- Shuffle/Repeat: 24dp icons, green when active
- Previous/Next: 32dp icons
- Play/Pause: 64dp circle button, filled white circle, black icon inside
```

Animations:
- **Play ↔ Pause**: Morphing icon animation using `AnimatedContent` with `fadeIn + scaleIn`
- **Tap feedback**: All buttons scale to 0.85 on press with spring physics, bounce back
- **Shuffle activation**: Icon turns green with a subtle shake animation (rotate ±5° twice)
- **Repeat cycle**: OFF → ALL → ONE with smooth icon crossfade, green tint animation
- **Haptic feedback**: `HapticFeedbackType.LightClick` on all control taps

### 5.5 Player Bottom Section

Below controls, add:
1. **Device indicator**: "🎧 OnePlus Buds 3" or speaker icon with device name (like Spotify)
2. **Share button**: Opens Android share sheet with song info
3. **Queue button**: Opens QueueScreen with shared element transition
4. **Sleep timer indicator**: When active, shows countdown next to controls

### 5.6 Lyrics Integration in Player

Instead of a separate LyricsScreen, integrate lyrics as a **swipeable tab** in the full player:

```
[Album Art Tab] ←→ [Lyrics Tab] ←→ [Queue Tab]

Implemented with HorizontalPager:
- Page 0: Album art (current)
- Page 1: Synced lyrics with karaoke-style animation
- Page 2: Up Next queue
```

**Lyrics rendering**:
- Current line: 24sp Bold, full white
- Previous lines: 18sp Regular, 40% alpha, blur(2dp)
- Next lines: 18sp Regular, 60% alpha
- Smooth scroll: `LazyColumn` with `animateScrollToItem` triggered by timestamp
- Tap-to-seek: Tapping a lyrics line seeks to that timestamp
- Background: Album art at 10% opacity with heavy blur

### 5.7 Swipe-Down to Minimize

The full player should be a proper **BottomSheet** (`ModalBottomSheetLayout` or custom `SwipeToDismissBox`):
- Swipe down from the top area = minimize back to mini player
- The album art shrinks and slides into the mini player position
- Spring physics on the dismiss gesture

---

## 6. PHASE 5 — Home Screen Transformation

### 6.1 Collapsing App Bar with Gradient

```
Scrolled up (expanded):
┌──────────────────────────────────┐
│ [Avatar]  Good evening    [⚙️]  │  ← Green-to-black gradient bg
│ [Notification bell]              │
│                                  │
│ Recent Plays (2×3 grid)          │
└──────────────────────────────────┘

Scrolled down (collapsed):
┌──────────────────────────────────┐
│ Good evening              [⚙️]  │  ← Compact, solid #121212
│ ─ content below ─               │
└──────────────────────────────────┘
```

Implementation: Custom `LazyColumn` with `graphicsLayer` transformations based on scroll offset. First item is the greeting with gradient, pinned.

### 6.2 Recent Plays Grid — Spotify Clone

```
┌─────────┬──────────┐
│🎵 Art │ Title    │ 🎵 Art │ Title    │
│        │ Artist   │        │ Artist   │
├─────────┼──────────┤
│🎵 Art │ Title    │ 🎵 Art │ Title    │
│        │ Artist   │        │ Artist   │
├─────────┼──────────┤
│🎵 Art │ Title    │ 🎵 Art │ Title    │
│        │ Artist   │        │ Artist   │
└─────────┴──────────┘
```

- Each cell: Compact card (48dp height) with album art on left, title + artist on right
- Background: `SpotifyLightGray` (#282828)
- Corner radius: 4dp
- Press animation: scale(0.96) with spring
- On tap: play the track immediately
- Currently playing track: Subtle green equalizer animation overlay on the album art

### 6.3 Section Headers with Animations

Each section ("Made For You", "Your Library", "Recently Played") should:
- Fade in with 300ms delay as user scrolls
- Have a "See All" / ">" button on the right
- Bold 22sp Montserrat Bold text

### 6.4 Track Card Redesign

**File: `ui/components/TrackCard.kt`** — For horizontal carousel cards:

```
┌──────────────────┐
│                  │
│   Album Art      │  ← 150×150dp, 8dp radius
│   (with shadow)  │
│                  │
├──────────────────┤
│ Song Title       │  ← 14sp Medium, max 2 lines
│ Artist Name      │  ← 12sp Regular, SpotifyTextSecondary
└──────────────────┘
```

- Width: 160dp
- On press: Scale to 0.95 with spring animation
- Spacing between cards: 12dp
- Snap behavior: Cards snap to start position on fling

### 6.5 Staggered Entry Animations

When HomeScreen loads, items should animate in with staggered delays:
1. Greeting text: Fade in immediately
2. Recent Plays grid: Each card fades in + slides up with 50ms stagger (card 1 at 0ms, card 2 at 50ms, card 3 at 100ms, etc.)
3. Section headers: Fade in 200ms after their cards start appearing
4. Horizontal carousels: Slide in from right with 400ms duration

### 6.6 Pull-to-Refresh

Add `pullRefresh` modifier to the `LazyColumn`:
- Custom refresh indicator: Green Streamify logo spinning
- On refresh: Re-fetch library from C++ DB, re-run recommendation engine

---

## 7. PHASE 6 — Search Experience Overhaul

### 7.1 Animated Search Bar

```
Idle state: "What do you want to listen to?" with search icon
Focus state: 
  - Search bar slightly expands
  - Background dims
  - Search icon slides left
  - Cancel button fades in on right
  - Keyboard opens with 200ms delay
```

### 7.2 Browse Categories — Spotify Clone

Replace flat colored rectangles with proper gradient cards:

```
┌──────────────────┬──────────────────┐
│ ✨ Music         │ 🎙️ Podcasts     │
│ [gradient bg     │ [gradient bg     │
│  + tilted art]   │  + tilted art]   │
├──────────────────┼──────────────────┤
│ 🎫 Live Events  │ 🏠 Home of I-Pop│
│ [gradient bg     │ [gradient bg     │
│  + tilted art]   │  + tilted art]   │
└──────────────────┴──────────────────┘
```

Each category card:
- Gradient background (2-color)
- Category name in Bold 16sp, white
- Small tilted image (20° rotation) in bottom-right corner, partially overflowing
- Rounded corners: 8dp
- Aspect ratio: ~1.5:1
- Press animation: Scale(0.96) + elevation increase

### 7.3 Search Results — Dual Source

```
Your Library (local DB matches first)
────────────────────────────
🎵 Art | Track Title        | ⋮
       | Artist • Song      |
────────────────────────────

YouTube Results
────────────────────────────
🎬 Thumb | Video Title      | ⋮
         | Channel • 3:45   |
────────────────────────────
```

- Local results appear instantly (no network)
- YouTube results load below with shimmer loading animation
- Each result has a 3-dot menu: Play, Download, Add to Queue, Add to Playlist
- **Music filtering**: Apply heuristic filter to exclude non-music YouTube results
- Search debounce: 500ms after typing stops

### 7.4 Recent Searches

- Show recent search terms with clock icon
- Swipe-to-delete individual items (with slide animation)
- "Clear" button top-right with confirmation dialog
- Tapping a recent search re-executes it

---

## 8. PHASE 7 — Library & Playlist System

### 8.1 Library Screen Redesign

```
┌──────────────────────────────────┐
│ Your Library              🔽 ↻  │  ← Sort icon + Refresh
├──────────────────────────────────┤
│ [All] [Liked] [Downloads] [▶]   │  ← Scrollable filter chips
├──────────────────────────────────┤
│ ≡ List │ ⊞ Grid                 │  ← View toggle
├──────────────────────────────────┤
│                                  │
│  Track list / grid here          │
│  with sort: Recent / A-Z /      │
│  Artist / Duration               │
│                                  │
└──────────────────────────────────┘
```

Features:
- **View toggle**: List view (current) ↔ Grid view (album art focus)
- **Sort dropdown**: Recently Added, Alphabetical, Artist, Duration
- **Filter chips**: Animated selection (green fill slides in)
- **Swipe actions on items**: Swipe right to add to queue, swipe left to delete
- **Long-press**: Opens context menu bottom sheet
- **Currently playing indicator**: Animated equalizer bars on the playing track
- **Pull-to-refresh**: Re-scan library

### 8.2 Playlist System (New Feature)

This is a major new feature. Playlists need to move from SharedPreferences to C++ SQLite.

**Database changes** (StreamifyDB.cc):
```sql
CREATE TABLE IF NOT EXISTS playlists (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    description TEXT DEFAULT '',
    cover_art_path TEXT DEFAULT '',
    user_id INTEGER DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS playlist_tracks (
    playlist_id INTEGER NOT NULL,
    track_id INTEGER NOT NULL,
    position INTEGER NOT NULL,
    added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (playlist_id, track_id),
    FOREIGN KEY (playlist_id) REFERENCES playlists(id) ON DELETE CASCADE,
    FOREIGN KEY (track_id) REFERENCES tracks(id) ON DELETE CASCADE
);
```

**New JNI functions**: createPlaylist, deletePlaylist, renamePlaylist, addTrackToPlaylist, removeTrackFromPlaylist, getPlaylistTracks, reorderPlaylistTrack, getAllPlaylists

**New screens**:
- `PlaylistDetailScreen.kt` — Shows playlist with art mosaic header, track list, play/shuffle buttons
- `CreatePlaylistSheet.kt` — Bottom sheet with name input, description, optional cover art
- Playlist art: Auto-generated mosaic from first 4 track cover arts (like Spotify)

### 8.3 "Liked Songs" Playlist

The "Liked Songs" collection should behave like a special playlist:
- Has the purple gradient heart icon (like Spotify)
- Shows total duration
- Shuffle play button
- Download all button

---

## 9. PHASE 8 — Animation & Gesture System

This is what separates a prototype from an engineering marvel. Every single interaction must feel alive.

### 9.1 Global Press Effect

**File: `ui/animations/CardPressEffect.kt`** — Rewrite with spring physics:

```kotlin
@Composable
fun Modifier.pressEffect(
    pressScale: Float = 0.95f,
    pressAlpha: Float = 0.85f,
): Modifier {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )
    val alpha by animateFloatAsState(
        targetValue = if (isPressed) pressAlpha else 1f,
        animationSpec = tween(100)
    )
    
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    isPressed = true
                    tryAwaitRelease()
                    isPressed = false
                }
            )
        }
}
```

Apply this to: EVERY card, EVERY button, EVERY list item, EVERY interactive element.

### 9.2 Staggered List Animation

**New File: `ui/animations/StaggeredAnimation.kt`**:

```kotlin
@Composable
fun StaggeredAnimatedItem(
    index: Int,
    delayPerItem: Long = 50L,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        delay(index * delayPerItem)
        visible = true
    }
    
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300)) + slideInVertically(
            initialOffsetY = { it / 4 },
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)
        )
    ) {
        content()
    }
}
```

### 9.3 Screen Transitions

**File: `navigation/AppNavGraph.kt`** — Add animated transitions:

```kotlin
composable("home",
    enterTransition = { fadeIn(tween(300)) },
    exitTransition = { fadeOut(tween(200)) }
)
composable("search",
    enterTransition = { slideInHorizontally { it } + fadeIn() },
    exitTransition = { slideOutHorizontally { -it } + fadeOut() }
)
// Player: slide up from bottom
composable("player",
    enterTransition = { slideInVertically { it } },
    exitTransition = { slideOutVertically { it } }
)
```

### 9.4 Heart Animation Overhaul

**File: `ui/animations/HeartBurstEffect.kt`** — Full rewrite:

When user likes a song:
1. Heart icon scales from 1.0 → 1.3 → 1.0 (bounce)
2. Heart fills with red/green color from bottom to top (200ms)
3. **Particle burst**: 12 small hearts of varying sizes (8-16dp) explode outward in random directions
4. Particles fade out and fall with gravity over 600ms
5. Haptic: `HapticFeedbackType.MediumClick`

When user unlikes:
1. Heart icon briefly shakes (rotate ±8° twice)
2. Color fades out with subtle break animation

### 9.5 Now Playing Equalizer Indicator

**New File: `ui/animations/NowPlayingIndicator.kt`**:

Shows 3-4 animated bars (like Spotify's green equalizer) on the currently playing track:
- 3 vertical bars of varying height
- Each bar animates independently with `infiniteRepeatable` + different `tween` durations
- Green color (#1DB954)
- Placed over the album art thumbnail in list items and the home grid

### 9.6 Mini Player ↔ Full Player Shared Element

When tapping the mini player to open the full player:
1. Album art smoothly animates (expands + moves to center)
2. Background gradient fades in
3. Controls slide up from bottom
4. Title/artist text crossfade from mini-player style to player style

When swiping down to minimize:
1. Reverse of above
2. Album art shrinks back into mini player position
3. Spring physics on the dismiss gesture

### 9.7 Haptic Feedback System

Add haptic feedback to all key interactions:
- Button taps: `LightClick`
- Like/unlike: `MediumClick`
- Track skip: `LightClick`
- Seek bar interaction: `TextHandleMove` (continuous)
- Long press: `LongPress`
- Pull to refresh threshold: `MediumClick`

---

## 10. PHASE 9 — Component-Level Polish

### 10.1 MiniPlayerBar Redesign

**File: `ui/components/MiniPlayerBar.kt`** — Complete rewrite:

```
┌──────────────────────────────────────────┐
│ ▉▉▉▉▉▉▉▉▉▉▉▉▉▉▉░░░░░░░░░░░░░░░░░░░░░│ ← Thin progress bar (2dp)
│ [Art] Title - Artist           ❤️  ▶/❚❚ │ ← Main bar (64dp height)
└──────────────────────────────────────────┘
```

Features:
- **Thin progress bar** at top: 2dp green line showing playback progress, updates smoothly
- **Album art**: 48dp, 4dp rounded corners, with subtle shadow
- **Title - Artist**: Single line with MarqueeText for overflow
- **Heart button**: Toggle with animation
- **Play/Pause**: 40dp touch target, animated icon morph
- **Swipe right**: Skip to next (with spring physics, album art slides out)
- **Swipe up**: Open full player (with shared element transition)
- **Background**: #282828 with subtle elevation
- **Rounded corners**: 8dp top corners

### 10.2 BottomNavBar Redesign

**File: `ui/components/BottomNavBar.kt`** — Redesign:

```
┌──────────────────────────────────────────┐
│   🏠        🔍        📚        ⬇️       │
│  Home     Search   Library   Downloads   │
└──────────────────────────────────────────┘
```

Animations:
- Selected tab: Icon scales to 1.1x, label visible, tint = white
- Unselected tab: Icon at 1.0x, label visible, tint = SpotifyTextSecondary
- Tab switch: Smooth crossfade between icon states
- **Animated indicator**: Horizontal line under selected tab that slides left/right on tab change
- Background: Pure #000000 for AMOLED, or #121212
- Height: 56dp

### 10.3 Context Menu Bottom Sheet

**File: `ui/components/ContextMenuSheet.kt`** — Redesign:

When user taps the 3-dot menu or long-presses a track:

```
┌──────────────────────────────────────────┐
│              ── handle ──                │
│                                          │
│  [Large Art] Song Title                  │
│              Artist • Album              │
│                                          │
│  ─────────────────────────────────────── │
│  ❤️  Like                                │
│  ➕  Add to Playlist              >      │
│  📋  Add to Queue                        │
│  📥  Download                            │
│  📤  Share                               │
│  🎤  View Artist                         │
│  💿  View Album                          │
│  ⏱️  Sleep Timer                         │
│  🚫  Remove from this playlist           │
│                                          │
└──────────────────────────────────────────┘
```

- Slides up with spring animation
- Background dimming behind sheet (scrim)
- Each menu item has 48dp height, subtle ripple on tap
- "Add to Playlist" shows a sub-sheet with playlist list + "Create Playlist" option

### 10.4 TrackListItem with Now Playing Indicator

**File: `ui/components/TrackListItem.kt`**:

```
┌──────────────────────────────────────────┐
│ [Art/EQ] Title                        ⋮  │
│          Artist                           │
└──────────────────────────────────────────┘
```

- If this track is currently playing: Replace album art with animated equalizer bars
- Title text turns SpotifyGreen when playing
- Subtle press effect on tap
- 3-dot menu button on right, visible always (not just on hover)

### 10.5 EmptyStateView Redesign

**File: `ui/components/EmptyStateView.kt`**:

```
Current: Centered plain text
Target:
  - Relevant icon (64dp, SpotifyTextTertiary color)
  - Primary text: "No active downloads" (16sp SemiBold)
  - Secondary text: "Songs you download will appear here" (14sp Regular, 60% alpha)
  - Optional CTA button: "Search for songs" (green outline button)
```

Animate in: Icon fades in first, then text slides up 200ms later.

---

## 11. PHASE 10 — Downloads & Settings Redesign

### 11.1 Downloads Screen Overhaul

**File: `ui/screens/DownloadScreen.kt`**:

Active downloads show:
```
┌──────────────────────────────────────────┐
│ [Art] Song Title                  72%    │
│       Artist              3.2 MB/s  ETA  │
│       ▉▉▉▉▉▉▉▉▉▉▉▉░░░░░          12s  │
└──────────────────────────────────────────┘
```

- Circular progress indicator around album art (not just a bar)
- Speed and ETA text from Python progress hooks
- Pause/Resume/Cancel buttons
- Completed downloads: Show with checkmark overlay on art
- Failed downloads: Show with retry button
- Download history section below active downloads

### 11.2 Settings Screen Overhaul

**File: `ui/screens/SettingsScreen.kt`**:

Organized sections with icons:
```
Audio Quality
  ├── Streaming quality: [Low/Normal/High/Extreme]
  └── Download quality: [Low/Normal/High/Extreme]

Playback
  ├── Crossfade: [0s ----●---- 12s]
  ├── Gapless playback: [Toggle]
  └── Normalize volume: [Toggle]

Audio Effects
  └── Equalizer: [→ Open]

Sleep Timer
  ├── Off / 5 / 10 / 15 / 30 / 45 / 60 min
  └── End of track: [Toggle]

Storage
  ├── Cache: 245 MB [Clear]
  ├── Downloads: 1.2 GB
  └── Storage location: [Internal/SD Card]

About
  ├── Version: 1.0.x
  ├── Build: #xxx
  └── Licenses
```

- Each section has a colored icon (like Spotify settings)
- Animated toggles (custom Switch with spring animation)
- Section headers: 12sp LabelSmall, SpotifyGreen color, ALL CAPS

---

## 12. PHASE 11 — Playback Service Hardening

### 12.1 Media Notification

**File: `service/PlaybackService.kt`**:
- Rich notification with album art, title, artist
- Previous/Play/Pause/Next buttons
- Progress bar in notification (Android 13+)
- Long-press notification: Show track options
- Custom notification channel with proper name and importance

### 12.2 CrossfadeAudioProcessor

**File: `service/CrossfadeAudioProcessor.kt`**:
- Implement actual crossfade logic (not just a stub)
- Read crossfade duration from settings
- Apply fade-out to ending track, fade-in to starting track
- Handle edge cases: very short tracks, seek-to-end

### 12.3 Audio Focus

- Proper audio focus handling: duck when notification sounds, pause for calls
- Resume after interruption
- Bluetooth disconnect handling

### 12.4 Gapless Playback

- Pre-buffer next track when current track is 80% complete
- Seamless transition using Media3's preload API

---

## 13. PHASE 12 — Final Integration & QA

### 13.1 End-to-End Flow Verification

Test every flow:
1. Fresh install → Home screen loads → search → download → play → like → recommendations update
2. Download with progress → AI processes audio → BPM extracted → recommendations improve
3. Playlist creation → add tracks → reorder → play playlist → shuffle
4. Lyrics fetch → synced display in player
5. Equalizer → preset selection → audible effect
6. Sleep timer → countdown → auto-pause
7. Background playback → notification controls → lock screen

### 13.2 Performance Targets

| Metric | Target |
|---|---|
| Cold start | < 1.5s to interactive |
| Track switch | < 200ms to audio start |
| Search (local) | < 50ms results |
| Search (YouTube) | < 2s results |
| AI recommendation | < 100ms for 10 results |
| BPM extraction | < 3s per track |
| UI frame rate | 60fps constant |
| Memory usage | < 200MB resident |

### 13.3 Edge Cases

- No tracks in library → proper empty states everywhere
- No internet → graceful degradation (hide YouTube search, show offline mode badge)
- Very long titles → MarqueeText everywhere
- Corrupted audio files → skip with error toast
- Database corruption → auto-rebuild from filesystem scan
- Multiple rapid likes/unlikes → debounce DB operations

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────┐
│                    KOTLIN UI LAYER                       │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │
│  │HomeScreen│ │SearchScr │ │LibrarySc │ │PlayerScr │  │
│  │+Anims    │ │+YouTube  │ │+Playlist │ │+Gestures │  │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘  │
│       │             │            │             │         │
│  ┌────┴─────────────┴────────────┴─────────────┴─────┐  │
│  │              ViewModels (StateFlow)                │  │
│  │  PlayerVM │ HomeVM │ SearchVM │ LibraryVM │ IngestVM│ │
│  └───────────────────┬───────────────────────────────┘  │
│                      │                                   │
│  ┌───────────────────┴───────────────────────────────┐  │
│  │          TrackRepository / PlaylistRepository      │  │
│  └────────┬──────────────────────────────┬───────────┘  │
│           │                              │               │
│  ┌────────┴────────┐        ┌────────────┴────────────┐ │
│  │  NativeBridge   │        │   Python (Chaquopy)     │ │
│  │  (JNI calls)    │        │   yt-dlp / mutagen      │ │
│  └────────┬────────┘        │   search / lyrics       │ │
│           │                  └────────────────────────┘ │
└───────────┼──────────────────────────────────────────────┘
            │ JNI Boundary
┌───────────┼──────────────────────────────────────────────┐
│           │           C++ NATIVE LAYER                    │
│  ┌────────┴────────┐                                     │
│  │   jni_bridge.cc │                                     │
│  └──┬─────┬────┬───┘                                     │
│     │     │    │                                          │
│  ┌──┴──┐┌─┴──┐┌┴──────────┐┌────────────┐┌───────────┐ │
│  │DB   ││Vec ││AudioPipe  ││RecommendEng││EventTrack │ │
│  │SQLite││SIMD││BPM+CLAP  ││Multi-signal││Play/Skip  │ │
│  │+WAL ││NEON││+Key Det.  ││+Diversity  ││+Timestamp │ │
│  └─────┘└────┘└───────────┘└────────────┘└───────────┘ │
└──────────────────────────────────────────────────────────┘
```

---

## Priority Execution Order

| Order | Phase | Impact | Effort |
|---|---|---|---|
| 1 | PHASE 3: Theme System | Foundation for all UI work | Medium |
| 2 | PHASE 1: AI Engine (BPM fix) | Fixes critical broken feature | High |
| 3 | PHASE 8: Animation System | Reusable across all screens | High |
| 4 | PHASE 4: Full Player Overhaul | Highest-visibility screen | Very High |
| 5 | PHASE 9: Component Polish | Used everywhere | High |
| 6 | PHASE 5: Home Screen | First screen users see | High |
| 7 | PHASE 2: Python Pipeline | Fixes download quality | Medium |
| 8 | PHASE 6: Search Overhaul | Core feature | Medium |
| 9 | PHASE 7: Library & Playlists | Major new feature | Very High |
| 10 | PHASE 10: Downloads & Settings | Important but lower priority | Medium |
| 11 | PHASE 11: Playback Service | Background quality | Medium |
| 12 | PHASE 12: Integration & QA | Final polish | High |

---

*This document is the single source of truth for the v4 engineering overhaul. Every decision, every animation spec, every API change is documented here. We build exactly this — no shortcuts, no compromises.*
