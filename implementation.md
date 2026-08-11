# 🎧 Streamify APK — Complete Android Implementation Blueprint

> **Version**: 1.0 — Master Architecture Specification
> **Status**: Planning Phase
> **Target**: Native Android Application (API 26+ / Android 8.0+)
> **Repository**: `github.com/zephyr4289/streamify-apk` (CI/CD) + `gitlab.com/sireenyadav/streamify-apk` (Code Mirror)
> **License**: MIT © 2026 sireenyadav

---

## 📋 Table of Contents

1. [Executive Vision](#1-executive-vision)
2. [Technology Stack Decision](#2-technology-stack-decision)
3. [Project Directory Structure](#3-project-directory-structure)
4. [Architecture Overview](#4-architecture-overview)
5. [Module 1: Native C/C++ Core Engine (NDK)](#5-module-1-native-cc-core-engine-ndk)
6. [Module 2: UI — Pixel-Perfect Spotify Replica](#6-module-2-ui--pixel-perfect-spotify-replica)
7. [Module 3: Search → Discover → Download Pipeline](#7-module-3-search--discover--download-pipeline)
8. [Module 4: Audio Playback Engine](#8-module-4-audio-playback-engine)
9. [Module 5: Local Device Music Discovery & Ingestion](#9-module-5-local-device-music-discovery--ingestion)
10. [Module 6: AI Recommendation Engine (ARM NEON)](#10-module-6-ai-recommendation-engine-arm-neon)
11. [Module 7: Lyrics & Metadata Pipeline (C/C++ Port)](#11-module-7-lyrics--metadata-pipeline-cc-port)
12. [Module 8: SQLite Database Schema (Android)](#12-module-8-sqlite-database-schema-android)
13. [Module 9: CI/CD Pipeline (GitHub Actions)](#13-module-9-cicd-pipeline-github-actions)
14. [Module 10: Git Workflow & Repository Strategy](#14-module-10-git-workflow--repository-strategy)
15. [Phase-by-Phase Execution Roadmap](#15-phase-by-phase-execution-roadmap)
16. [Design System Reference (Ported from Web)](#16-design-system-reference-ported-from-web)
17. [Existing Assets Inventory](#17-existing-assets-inventory)

---

## 1. Executive Vision

Transform the existing NAS-hosted Streamify web server into a **standalone, offline-first Android application** that functions as the most faithful, high-performance Spotify clone ever built. The app will:

- **Run entirely on-device** — no external server needed. The C/C++ AI recommendation engine, SQLite database, audio processing pipeline, and playback engine all execute locally on the phone.
- **Deliver extreme responsiveness** — all heavy computation (vector similarity search, audio feature extraction, metadata parsing) happens in native C/C++ via Android NDK, with JNI bridging to the Kotlin UI layer.
- **Replicate Spotify's UI/UX down to the pixel** — using Jetpack Compose with the exact Spotify dark design system already built in `web/style.css` (colors, typography, spacing, animations, component structure).
- **Provide intelligent music discovery** — when a song isn't on the device, the app searches online sources (yt-dlp), lets the user preview and choose the correct version, downloads at user-selected quality, fetches HD cover art (iTunes API) and synced lyrics (LRCLIB), processes the audio through the AI embedding pipeline, and makes it instantly playable with full recommendations.
- **Continuously learn from local music** — scans the device's existing music folders, processes them through the ONNX neural embedding pipeline in the background, and integrates them into the AI recommendation engine seamlessly.

---

## 2. Technology Stack Decision

| Layer | Technology | Rationale |
|:------|:-----------|:----------|
| **UI Framework** | **Kotlin + Jetpack Compose** | Native 60/120fps rendering, direct Android API access, Material 3 components, first-class support for animations, gestures, and media session integration. No WebView overhead. |
| **Native Engine** | **C/C++17 via Android NDK** | Direct port of `music-procengine` and `StreamifyDB`. Compiled to ARM64-v8a and armeabi-v7a `.so` shared libraries. Sub-millisecond vector search via ARM NEON SIMD intrinsics. |
| **JNI Bridge** | **JNI (Java Native Interface)** | Zero-overhead function calls between Kotlin and C++. The UI calls C++ directly for recommendations, database queries, and audio processing — no HTTP layer, no serialization overhead. |
| **Audio Playback** | **Android Media3 / ExoPlayer** | Handles all audio decoding, format support (MP3/FLAC/M4A/OGG/WAV), gapless playback, lock-screen media controls, notification player, Bluetooth/headphone integration, and Android Auto. |
| **Database** | **SQLite3 (NDK-compiled)** | Direct C API via NDK for maximum performance. WAL mode for concurrent reads. The Kotlin layer accesses the DB through JNI, not Room. |
| **AI Inference** | **ONNX Runtime Mobile (C++ API)** | INT8 quantized LAION CLAP model for 512-D audio embedding extraction, compiled for ARM. ~2-5MB model binary. |
| **Audio DSP** | **kissfft (or pffft)** | Lightweight FFT library replacing FFTW3 (which has no official Android NDK build). Used for Log-Mel Spectrogram STFT computation. |
| **Audio Decoding** | **Miniaudio** | Single-header C library, already used in `music-procengine`. Cross-platform, zero-dependency. Works perfectly on Android NDK. |
| **Online Search** | **yt-dlp (embedded Python via Chaquopy)** | Chaquopy Gradle plugin embeds a Python 3 interpreter inside the APK. Allows us to run the existing `yt-dlp` and `kaviraj-tool` search/download logic natively without Termux. |
| **Lyrics Fetching** | **Native C++ HTTP (libcurl-lite or Android HttpURLConnection via JNI)** | Port LRCLIB API client from Python `lyrics.py` to native C++ for speed. Fallback to Kotlin `OkHttp` if needed. |
| **Cover Art** | **Native C++ HTTP + Kotlin Coil** | iTunes Search API queried in C++, image loading/caching handled by Coil (Jetpack Compose image loader). |
| **Build System** | **Gradle + CMake** | Standard Android build. Gradle orchestrates the APK. CMake compiles all C/C++ NDK code. |
| **CI/CD** | **GitHub Actions** | APK builds, lint, and release artifact uploads on GitHub only. GitLab is code mirror only. |

---

## 3. Project Directory Structure

```text
streamify-apk/
├── implementation.md                    # This document
├── README.md                            # Project overview
├── .github/
│   └── workflows/
│       ├── build.yml                    # CI: Lint + Build debug APK on every push
│       └── release.yml                  # CD: Build signed release APK on tag push
├── app/
│   ├── build.gradle.kts                 # App-level Gradle (Compose, NDK, Chaquopy)
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── java/com/streamify/app/
│   │   │   │   ├── MainActivity.kt               # Single-Activity Compose host
│   │   │   │   ├── StreamifyApp.kt                # Application class + init
│   │   │   │   ├── navigation/
│   │   │   │   │   └── AppNavGraph.kt             # Bottom nav routing (Home, Search, Library)
│   │   │   │   ├── ui/
│   │   │   │   │   ├── theme/
│   │   │   │   │   │   ├── Color.kt              # Spotify palette from CSS :root vars
│   │   │   │   │   │   ├── Type.kt               # Montserrat + Poppins typography
│   │   │   │   │   │   ├── Theme.kt              # StreamifyTheme composable
│   │   │   │   │   │   └── Dimens.kt             # Spacing, radius, elevation tokens
│   │   │   │   │   ├── screens/
│   │   │   │   │   │   ├── HomeScreen.kt          # Greeting, Quick Grid, AI Recs, Library
│   │   │   │   │   │   ├── SearchScreen.kt        # Search bar, results, download prompt
│   │   │   │   │   │   ├── LibraryScreen.kt       # Liked songs, playlists
│   │   │   │   │   │   ├── PlayerScreen.kt        # Full-screen player (swipe-up)
│   │   │   │   │   │   ├── QueueScreen.kt         # Up Next queue sheet
│   │   │   │   │   │   ├── DownloadScreen.kt      # Download progress & source selection
│   │   │   │   │   │   └── LyricsScreen.kt        # Synced lyrics overlay
│   │   │   │   │   ├── components/
│   │   │   │   │   │   ├── MusicCard.kt           # Album art card with play FAB
│   │   │   │   │   │   ├── MiniPlayerBar.kt       # Sticky bottom player bar
│   │   │   │   │   │   ├── TrackListItem.kt       # Compact track row
│   │   │   │   │   │   ├── SearchResultItem.kt    # Search result with download action
│   │   │   │   │   │   ├── QualitySelector.kt     # Audio quality picker dialog
│   │   │   │   │   │   ├── DownloadCard.kt        # Download progress card
│   │   │   │   │   │   ├── ProcessingStatusCard.kt # AI processing status indicator
│   │   │   │   │   │   └── SeekBar.kt             # Custom Spotify-style seekbar
│   │   │   │   │   └── animations/
│   │   │   │   │       ├── CardHoverAnim.kt       # Card lift + play button reveal
│   │   │   │   │       └── PlayerTransitions.kt   # Mini → full player transitions
│   │   │   │   ├── viewmodel/
│   │   │   │   │   ├── HomeViewModel.kt            # Catalog + recommendations state
│   │   │   │   │   ├── SearchViewModel.kt          # Search + download state
│   │   │   │   │   ├── PlayerViewModel.kt          # Playback + seek + queue state
│   │   │   │   │   ├── LibraryViewModel.kt         # Liked songs + playlists state
│   │   │   │   │   └── IngestionViewModel.kt       # Background processing status
│   │   │   │   ├── service/
│   │   │   │   │   ├── PlaybackService.kt          # Media3 foreground service
│   │   │   │   │   ├── DownloadService.kt          # Foreground service for downloads
│   │   │   │   │   └── IngestionWorker.kt          # WorkManager for background AI processing
│   │   │   │   ├── data/
│   │   │   │   │   ├── NativeBridge.kt             # JNI function declarations (external fun)
│   │   │   │   │   ├── TrackRepository.kt          # Kotlin wrapper around JNI DB calls
│   │   │   │   │   └── models/
│   │   │   │   │       ├── Track.kt                # Track data class
│   │   │   │   │       ├── Recommendation.kt       # Recommendation result data class
│   │   │   │   │       ├── DownloadResult.kt       # Download pipeline result
│   │   │   │   │       ├── LyricsData.kt           # Synced lyrics model
│   │   │   │   │       └── SearchCandidate.kt      # Online search result model
│   │   │   │   └── util/
│   │   │   │       ├── MediaStoreScanner.kt        # Device music folder discovery
│   │   │   │       └── PermissionHelper.kt         # Runtime permission handling
│   │   │   ├── res/
│   │   │   │   ├── drawable/                        # Icons (from web/assets, converted to VectorDrawable)
│   │   │   │   ├── mipmap-xxxhdpi/                  # App icon (from logo.png)
│   │   │   │   ├── font/                            # Montserrat + Poppins .ttf files
│   │   │   │   └── values/
│   │   │   │       ├── strings.xml
│   │   │   │       └── colors.xml                   # Spotify palette XML fallback
│   │   │   └── assets/
│   │   │       ├── models/
│   │   │       │   └── clap_int8.onnx              # Quantized ONNX model (~2-5MB)
│   │   │       └── card_art/                        # Default card images (from web/assets)
│   │   └── python/                                  # Chaquopy Python source root
│   │       └── download_engine/
│   │           ├── __init__.py
│   │           ├── downloader.py                    # Ported from scripts/download_track.py
│   │           ├── search.py                        # Ported from kaviraj-tool matcher.py
│   │           └── metadata.py                      # Cover art + lyrics fetch helpers
├── native/                                           # All C/C++ NDK source code
│   ├── CMakeLists.txt                               # Master CMake build for NDK
│   ├── jni/
│   │   ├── jni_bridge.cc                            # JNI_OnLoad + all JNI function exports
│   │   └── jni_bridge.h
│   ├── engine/
│   │   ├── StreamifyDB.cc                           # Port of server/services/StreamifyDB
│   │   ├── StreamifyDB.h
│   │   ├── VectorStore.cc                           # ARM NEON port of VectorStore (was AVX2)
│   │   ├── VectorStore.h
│   │   ├── RecommendEngine.cc                       # Port of RecommendController logic
│   │   ├── RecommendEngine.h
│   │   ├── EventTracker.cc                          # Port of EventController (play/skip Markov)
│   │   └── EventTracker.h
│   ├── ingest/
│   │   ├── AudioPipeline.cc                         # Port of AudioPipeline (Miniaudio + FFT + ONNX)
│   │   ├── AudioPipeline.h
│   │   ├── DeviceScanner.cc                         # New: Android MediaStore folder scanner
│   │   ├── DeviceScanner.h
│   │   └── miniaudio.h                              # Single-header audio decoder
│   ├── metadata/
│   │   ├── LyricsClient.cc                          # C++ port of kaviraj-tool lyrics.py (LRCLIB API)
│   │   ├── LyricsClient.h
│   │   ├── CoverArtClient.cc                        # C++ port of cover_art.py (iTunes API)
│   │   ├── CoverArtClient.h
│   │   ├── ID3Parser.cc                             # Native ID3v2/MP4 metadata reader
│   │   └── ID3Parser.h
│   ├── dsp/
│   │   ├── LogMelSpectrogram.cc                     # 64-bin STFT via kissfft, replaces FFTW3
│   │   ├── LogMelSpectrogram.h
│   │   └── kissfft/                                 # Vendored lightweight FFT library
│   │       ├── kiss_fft.h
│   │       └── kiss_fft.c
│   └── third_party/
│       ├── sqlite3/                                 # Vendored SQLite3 amalgamation
│       │   ├── sqlite3.c
│       │   └── sqlite3.h
│       └── onnxruntime/                             # ONNX Runtime Mobile AAR headers + libs
│           ├── include/
│           └── lib/
├── build.gradle.kts                                 # Root Gradle (plugins, repos)
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
└── legacy/                                          # Original NAS server code (reference only)
    ├── server/                                      # Original Drogon C++ server
    ├── music-procengine/                            # Original submodule (reference for porting)
    ├── kaviraj-tool/                                # Original Python downloader (reference for porting)
    ├── web/                                         # Original Spotify web UI (design reference)
    ├── scripts/                                     # Original download script
    ├── schema.sql                                   # Original DB schema
    └── cluster.md                                   # NAS cluster architecture (historical)
```

---

## 4. Architecture Overview

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│                         STREAMIFY ANDROID APP                              │
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                    KOTLIN / JETPACK COMPOSE UI                        │  │
│  │                                                                       │  │
│  │   HomeScreen ─── SearchScreen ─── LibraryScreen ─── PlayerScreen     │  │
│  │       │               │                │                 │            │  │
│  │       └───────────────┴────────────────┴─────────────────┘            │  │
│  │                              │                                        │  │
│  │                     ViewModel Layer                                    │  │
│  │              (StateFlow / LiveData / Coroutines)                      │  │
│  └──────────────────────────────┬────────────────────────────────────────┘  │
│                                 │                                           │
│                          ╔══════╧══════╗                                    │
│                          ║  JNI Bridge ║                                    │
│                          ╚══════╤══════╝                                    │
│                                 │                                           │
│  ┌──────────────────────────────┴────────────────────────────────────────┐  │
│  │                    NATIVE C/C++ ENGINE (NDK)                          │  │
│  │                                                                       │  │
│  │  ┌─────────────┐  ┌──────────────┐  ┌────────────────┐              │  │
│  │  │ StreamifyDB  │  │ VectorStore  │  │ RecommendEngine│              │  │
│  │  │ (SQLite WAL) │  │ (ARM NEON)   │  │ (Two-Stage AI) │              │  │
│  │  └──────┬───────┘  └──────┬───────┘  └───────┬────────┘              │  │
│  │         │                 │                   │                        │  │
│  │  ┌──────┴─────────────────┴───────────────────┴──────────────────┐   │  │
│  │  │                  AudioPipeline                                 │   │  │
│  │  │  Miniaudio → kissfft STFT → Log-Mel → ONNX CLAP → 512-D Vec  │   │  │
│  │  └────────────────────────────────────────────────────────────────┘   │  │
│  │                                                                       │  │
│  │  ┌──────────────┐  ┌───────────────┐  ┌──────────────┐              │  │
│  │  │ LyricsClient │  │ CoverArtClient│  │ ID3Parser    │              │  │
│  │  │ (LRCLIB API) │  │ (iTunes API)  │  │ (Tag Reader) │              │  │
│  │  └──────────────┘  └───────────────┘  └──────────────┘              │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │              ANDROID PLATFORM SERVICES                                │  │
│  │                                                                       │  │
│  │  PlaybackService ─── DownloadService ─── IngestionWorker             │  │
│  │  (Media3/ExoPlayer)  (Foreground Svc)    (WorkManager - Background)  │  │
│  │   • Lock screen       • yt-dlp via        • Scans /sdcard/Music      │  │
│  │   • Notification        Chaquopy Python    • Feeds files to C++      │  │
│  │   • Bluetooth          • ffmpeg extract     AudioPipeline            │  │
│  │   • Android Auto       • Quality select   • Generates embeddings    │  │
│  │   • Gapless play       • Progress notify   • Silent, battery-aware  │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Module 1: Native C/C++ Core Engine (NDK)

### 5.1 What Gets Ported

| Original NAS Component | Android NDK Port | Key Changes |
|:------------------------|:------------------|:------------|
| `server/services/StreamifyDB.cc` | `native/engine/StreamifyDB.cc` | Remove Drogon dependency. Use raw SQLite3 C API. DB stored at `context.getDatabasePath()`. |
| `music-procengine/server/src/services/VectorStore.cc` | `native/engine/VectorStore.cc` | **Port AVX2 → ARM NEON**. Replace `_mm256_dp_ps` with `vmulq_f32` + `vpaddq_f32`. Add `#ifdef __ARM_NEON` guards. |
| `music-procengine/server/controllers/RecommendController.cc` | `native/engine/RecommendEngine.cc` | Remove HTTP handler. Expose as pure C++ function: `std::vector<Recommendation> getNextTracks(int trackId, std::vector<int> history, int userId, int limit)`. |
| `music-procengine/server/controllers/EventController.cc` | `native/engine/EventTracker.cc` | Remove HTTP handler. Expose: `void logPlay(int fromId, int toId, int userId)` and `void logSkip(...)`. |
| `music-procengine/server/src/ingest/AudioPipeline.cc` | `native/ingest/AudioPipeline.cc` | Replace FFTW3 with kissfft. Keep Miniaudio (already cross-platform). Keep ONNX Runtime (use Mobile build). |
| `music-procengine/server/src/ingest/DirectoryWatcher.cc` | `native/ingest/DeviceScanner.cc` | Replace Linux `inotify` with Android `FileObserver` (via JNI callback) or periodic scan via WorkManager. |

### 5.2 AVX2 → ARM NEON Translation

The most critical port. Example transformation:

```cpp
// BEFORE (x86 AVX2) — VectorStore.cc
__m256 sum = _mm256_setzero_ps();
for (int i = 0; i < 512; i += 8) {
    __m256 a = _mm256_loadu_ps(&vecA[i]);
    __m256 b = _mm256_loadu_ps(&vecB[i]);
    sum = _mm256_add_ps(sum, _mm256_mul_ps(a, b));
}

// AFTER (ARM NEON) — VectorStore.cc
float32x4_t sum = vdupq_n_f32(0.0f);
for (int i = 0; i < 512; i += 4) {
    float32x4_t a = vld1q_f32(&vecA[i]);
    float32x4_t b = vld1q_f32(&vecB[i]);
    sum = vmlaq_f32(sum, a, b);  // fused multiply-add
}
float result = vaddvq_f32(sum);  // horizontal reduction
```

### 5.3 JNI Bridge Design

```kotlin
// NativeBridge.kt — Kotlin JNI declarations
object NativeBridge {
    init { System.loadLibrary("streamify_core") }

    // Database
    external fun initDatabase(dbPath: String): Boolean
    external fun getAllTracks(): Array<TrackNative>
    external fun searchTracks(query: String): Array<TrackNative>
    external fun insertTrack(filepath: String, title: String, artist: String,
                             album: String, durationSec: Int, bpm: Float): Long

    // Recommendations
    external fun getRecommendations(trackId: Int, recentHistory: IntArray,
                                     userId: Int, limit: Int): Array<RecommendationNative>

    // Events
    external fun logPlayEvent(fromTrackId: Int, toTrackId: Int, userId: Int)
    external fun logSkipEvent(fromTrackId: Int, toTrackId: Int, userId: Int)

    // Liked Songs
    external fun toggleLike(userId: Int, trackId: Int): Boolean
    external fun getLikedTracks(userId: Int): Array<TrackNative>

    // Audio Processing
    external fun processAudioFile(filepath: String): Boolean  // Run full ONNX pipeline
    external fun getProcessingProgress(): Float               // 0.0 to 1.0

    // Metadata
    external fun fetchLyrics(title: String, artist: String, album: String): String?
    external fun fetchCoverArt(title: String, artist: String): ByteArray?
    external fun parseID3Tags(filepath: String): TrackMetadata?
}
```

---

## 6. Module 2: UI — Pixel-Perfect Spotify Replica

### 6.1 Design System (Ported from `web/style.css`)

Every single CSS variable, color, font, spacing, and animation from the existing web frontend will be faithfully translated:

```kotlin
// Color.kt — Direct port from CSS :root variables
object StreamifyColors {
    val BgBase       = Color(0xFF000000)     // --bg-base: #000000
    val BgSurface    = Color(0xFF121212)     // --bg-surface: #121212
    val BgCard       = Color(0xFF181818)     // --bg-card: #181818
    val BgCardHover  = Color(0xFF282828)     // --bg-card-hover: #282828
    val BgPlayer     = Color(0xFF0F0F0F)     // --bg-player: #0f0f0f
    val Primary      = Color(0xFF1DB954)     // --primary: #1DB954
    val PrimaryHover = Color(0xFF1ED760)     // --primary-hover: #1ed760
    val TextMain     = Color(0xFFFFFFFF)     // --text-main: #FFFFFF
    val TextSub      = Color(0xFFB3B3B3)     // --text-sub: #b3b3b3
    val Border       = Color(0xFF242424)     // --border-color: #242424
    val ErrorRed     = Color(0xFFFF4D4D)
    val ErrorBg      = Color(0x26EB5757)     // rgba(235, 87, 87, 0.15)
}

// Type.kt — Spotify typography system
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
```

### 6.2 Screen-by-Screen UI Specification

#### HomeScreen
- **Greeting Header**: Dynamic time-based greeting (Good morning/afternoon/evening, {Username}) — `Montserrat Bold 2rem` (port from CSS `.greeting-header`)
- **Quick Grid**: 2x3 grid of recent/frequent tracks — compact cards with 48x48 album art, title, subtitle (port from `.playlist-card-mini`)
- **AI Recommendations Section**: "Made For You (ProcEngine AI)" — horizontal scrollable card grid. Each card: square album art with 1:1 aspect ratio, green play FAB that animates in on hover/press (port from `.card-play-btn` opacity+translateY animation), title in `Montserrat Bold 0.95rem`, subtitle in `Poppins 0.8rem` color `#b3b3b3`
- **Your Audio Library Section**: Vertical grid of all local tracks with "Refresh" button
- **Trending Section**: Static featured content cards

#### SearchScreen
- **Search Bar**: Pill-shaped input (`border-radius: 50px`, background `#242424`, icon on left) — port from `.search-bar-container`
- **Local Results**: Filtered track cards matching query
- **Online Download Banner**: When no local results found — gradient banner (port from `.download-banner`) with cloud download icon, "Search & Download Online" title, "Download to Device" CTA button (port from `.download-trigger-btn`). On tap opens **DownloadScreen**

#### DownloadScreen (NEW — Enhanced from current pipeline)
- **Source Selection**: List of yt-dlp search results showing:
  - Thumbnail preview
  - Title + channel name
  - Duration
  - Match confidence score (ported from `kaviraj-tool/matcher.py` algorithm)
  - Visual badge: "Official" for YouTube Music Topic channels (+20 score bonus)
  - Visual badge: Warning for detected edits (slowed, reverb, nightcore, cover, remix)
- **Quality Selector Dialog**: User picks audio quality:
  - `Best Native` (Opus/AAC original — 0% re-encoding)
  - `320kbps MP3`
  - `256kbps AAC`
  - `128kbps MP3` (data saver)
- **Progress View**: Real-time step-by-step status:
  1. Downloading audio stream... (progress bar)
  2. Fetching HD cover art... (iTunes API 1400x1400)
  3. Fetching synced lyrics... (LRCLIB API)
  4. Embedding metadata tags... (ID3/MP4 tagging)
  5. Generating AI embeddings... (ONNX pipeline)
  6. Ready to play! Auto-starts playback

#### PlayerScreen (Full-screen, swipe-up from MiniPlayerBar)
- **Large Album Art**: Full-width, square, with subtle shadow
- **Track Info**: Title (Montserrat Bold), Artist (Poppins, `#b3b3b3`)
- **Heart Button**: Toggle liked state (empty heart to green filled heart `#1DB954`)
- **Seekbar**: Custom Spotify-style (thin `4px` track, green accent, draggable thumb, time labels `mm:ss`)
- **Controls Row**: Shuffle, Previous, Play/Pause (large white circle, scale animation on press), Next, Repeat
- **Lyrics Button**: Opens `LyricsScreen` overlay with synced `.lrc` lyrics scrolling in real-time
- **Queue Button**: Opens `QueueScreen` bottom sheet

#### LyricsScreen (NEW)
- **Synced Lyrics Display**: Current line highlighted in white bold, past lines in `#b3b3b3`, future lines dimmed
- **Auto-Scroll**: Lyrics scroll automatically synced to playback position
- **Background**: Blurred album art with dark overlay
- **Tap to Seek**: Tapping a lyrics line seeks to that timestamp

#### MiniPlayerBar (Sticky bottom bar — always visible)
- Direct port of `.player-bar` CSS:
  - Height: `90dp` (port from `90px`)
  - Background: `#0F0F0F` with top border `#242424`
  - Left: 56x56 album art + track info + heart button
  - Center: Playback controls + seekbar
  - Right: Volume + queue toggle
- **Swipe Up Gesture**: Expands to full `PlayerScreen`

#### LibraryScreen
- **Liked Songs**: Grid/list of user's liked tracks
- **Playlists**: User-created playlists (future feature)
- **Local Music**: Device-discovered tracks being processed

### 6.3 Animations & Micro-Interactions

All ported from the CSS transitions:

| CSS Animation | Compose Implementation |
|:--------------|:-----------------------|
| `.music-card:hover { transform: translateY(-4px) }` | `Modifier.graphicsLayer { translationY = animateFloatAsState(-4.dp) }` on press |
| `.card-play-btn { opacity: 0 to 1, translateY: 8px to 0 }` | `AnimatedVisibility(enter = fadeIn + slideInVertically)` |
| `.play-pause-btn:hover { transform: scale(1.08) }` | `Modifier.scale(animateFloatAsState(1.08f))` on press |
| `.auth-overlay @keyframes fadeIn { scale 0.96 to 1 }` | `AnimatedContent` with `scaleIn(initialScale = 0.96f) + fadeIn` |
| `.download-trigger-btn:hover { transform: scale(1.04) }` | `Modifier.scale(animateFloatAsState(1.04f))` on press |

---

## 7. Module 3: Search to Discover to Download Pipeline

### 7.1 Complete User Flow

```text
User types "Dil Nu" in SearchScreen
         |
         v
+----------------------------------+
| 1. LOCAL SEARCH (instant, <1ms)  |
|    C++ StreamifyDB.searchTracks  |
|    via JNI bridge                |
+-----------------+----------------+
                  |
            Found locally?
           /            \
         YES             NO
          |               |
     Play track    +------+------------------------+
     immediately   | 2. SHOW DOWNLOAD PROMPT       |
                   |    "Not on device. Search     |
                   |    online & download?"         |
                   +------+------------------------+
                          |
                   User taps "Search Online"
                          |
             +------------+----------------------+
             | 3. ONLINE SEARCH (yt-dlp)         |
             |    via Chaquopy Python runtime     |
             |    ytsearch5:{query}               |
             |    Returns 5 candidates with:      |
             |    - thumbnail URL                 |
             |    - title, channel, duration       |
             +------------+----------------------+
                          |
             +------------+----------------------+
             | 4. SCORE & RANK CANDIDATES        |
             |    Port of matcher.py:             |
             |    - 60% difflib title match       |
             |    - 40% word overlap              |
             |    - Artist presence (0-40pts)     |
             |    - Topic channel bonus (+20)     |
             |    - Bad candidate penalty         |
             |      (slowed/reverb/nightcore)     |
             |    - Duration match (+/-10sec)     |
             +------------+----------------------+
                          |
             +------------+----------------------+
             | 5. USER SELECTS VERSION           |
             |    Scrollable list showing:        |
             |    - Thumbnail + title + channel   |
             |    - Confidence score badge        |
             |    - Duration                      |
             |    - "Official" / "Edit" tags      |
             +------------+----------------------+
                          |
             +------------+----------------------+
             | 6. USER SELECTS QUALITY           |
             |    Dialog: Best / 320k / 256k /    |
             |    128k                            |
             +------------+----------------------+
                          |
             +------------+----------------------+
             | 7. DOWNLOAD + PROCESS             |
             |    (Foreground Service)            |
             |                                    |
             |  Step 1: yt-dlp download           |
             |  Step 2: ffmpeg extract/convert    |
             |  Step 3: iTunes HD cover art       |
             |  Step 4: LRCLIB synced lyrics      |
             |  Step 5: Mutagen/ID3 metadata      |
             |  Step 6: C++ ONNX AI embedding     |
             |  Step 7: Insert into SQLite        |
             |                                    |
             |  Each step shown in real-time      |
             |  progress card in the UI           |
             +------------+----------------------+
                          |
             +------------+----------------------+
             | 8. AUTO-PLAY + INTEGRATE          |
             |    Track plays immediately         |
             |    Recommendations update          |
             |    Track appears in library        |
             +----------------------------------+
```

### 7.2 Python Download Engine (Chaquopy)

The download logic stays in Python (via Chaquopy embedded interpreter) because:
- `yt-dlp` is a massive, actively maintained Python library with no C equivalent
- `mutagen` (audio tagging) is Python-native
- Chaquopy packages `yt-dlp`, `mutagen`, and `requests` directly into the APK
- The Kotlin layer calls Python functions via `Python.getInstance().getModule("download_engine.downloader")`

```python
# app/src/main/python/download_engine/downloader.py

def search_online(query: str, count: int = 5) -> list:
    """Search yt-dlp and return scored candidates for user selection."""
    # Uses ytsearch{count}:{query}
    # Applies matcher.py scoring algorithm
    # Returns list of {title, channel, duration, thumbnail_url, score, url, flags}

def download_track(url: str, quality: str, output_dir: str,
                   progress_callback=None) -> dict:
    """Download selected track at chosen quality with progress reporting."""
    # Step 1: yt-dlp download with quality args
    # Step 2: ffmpeg extract if needed
    # Returns {filepath, title, artist, duration_sec, thumbnail_path}

def fetch_cover_art(title: str, artist: str, preferred_url: str = None) -> str:
    """Fetch HD cover art from iTunes API (1400x1400) or fallback."""
    # Port of kaviraj-tool cover_art.py
    # Returns local path to saved cover image

def fetch_lyrics(title: str, artist: str, album: str) -> str:
    """Fetch synced .lrc lyrics from LRCLIB API."""
    # Port of kaviraj-tool lyrics.py
    # Multi-pass: /api/get then /api/search
    # Returns .lrc text content or None
```

---

## 8. Module 4: Audio Playback Engine

### 8.1 Media3 / ExoPlayer Integration

```kotlin
// PlaybackService.kt — Android Foreground Media Service
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(), true)  // Handle audio focus
            .setHandleAudioBecomingNoisy(true)  // Pause on headphone disconnect
            .build()

        mediaSession = MediaSession.Builder(this, player).build()
    }
}
```

### 8.2 Playback Features

- **Gapless Playback**: ExoPlayer handles seamless track transitions
- **Lock Screen Controls**: Full media notification with album art, play/pause, next/prev, seekbar
- **Bluetooth Metadata**: AVRCP metadata broadcasting (title, artist, album art)
- **Android Auto**: Media browse tree integration (future)
- **Audio Focus**: Proper ducking/pausing when phone rings or navigation speaks
- **Headphone Detection**: Auto-pause when headphones disconnect (`AudioBecomingNoisy`)
- **Background Playback**: Runs as foreground service with persistent notification

---

## 9. Module 5: Local Device Music Discovery & Ingestion

### 9.1 Device Scanner Flow

```text
App Launch / Permission Granted
         |
         v
+----------------------------------+
| MediaStoreScanner.kt             |
| Query Android MediaStore for:    |
|   audio/mpeg, audio/flac,        |
|   audio/mp4, audio/ogg           |
| From /sdcard/Music, /Downloads,  |
| /sdcard/Download, custom paths   |
+-----------------+----------------+
                  |
            Found N files
                  |
            +-----+-----+
            | Check DB   |  (Which files already have embeddings?)
            | via JNI    |
            +-----+------+
                  |
            M files need processing
                  |
            +-----+-------------------------------+
            | IngestionWorker (WorkManager)        |
            |                                      |
            | Constraints:                         |
            |   - Battery not low                  |
            |   - Device idle (preferred)          |
            |   - Or user-triggered                |
            |                                      |
            | For each unprocessed file:           |
            |   1. C++ ID3Parser -> read metadata  |
            |   2. C++ AudioPipeline:              |
            |      a. Miniaudio decode             |
            |      b. Sample at 25%, 50%, 75%      |
            |      c. kissfft STFT                 |
            |      d. 64-bin Log-Mel Spectrogram   |
            |      e. ONNX CLAP -> 512-D vector    |
            |   3. C++ StreamifyDB -> store track  |
            |   4. C++ VectorStore -> add vector   |
            |   5. Update progress in UI           |
            +--------------------------------------+
```

### 9.2 Background Processing UI

The `IngestionViewModel` exposes a `StateFlow<IngestionState>`:

```kotlin
data class IngestionState(
    val totalFiles: Int = 0,
    val processedFiles: Int = 0,
    val currentFile: String = "",
    val currentStep: String = "",  // "Decoding audio...", "Generating embeddings..."
    val isActive: Boolean = false,
    val progress: Float = 0f       // 0.0 to 1.0
)
```

Displayed as a subtle `ProcessingStatusCard` at the bottom of `HomeScreen`:
- "Processing your music library... 42/128 tracks"
- Thin green progress bar
- Dismissible, runs silently in background

---

## 10. Module 6: AI Recommendation Engine (ARM NEON)

### 10.1 Two-Stage Pipeline (Ported from music-procengine)

**Stage 1 — Session Vector Retrieval (ARM NEON SIMD)**
```text
Session Vector = 0.70 x current_track_embedding
               + 0.20 x prev_track_embedding
               + 0.10 x prev_prev_track_embedding

-> ARM NEON cosine similarity against all vectors in VectorStore
-> Top 100 candidates retrieved in <1ms on modern ARM CPUs
```

**Stage 2 — Multi-Factor Ranking**
```text
For each of 100 candidates:
  final_score = (w1 x cosine_similarity)
              + (w2 x markov_transition_probability)
              - (w3 x skip_penalty)
              - (w4 x bpm_tempo_jump_penalty)

-> Sort by final_score DESC
-> Return top N (default 5) recommendations
```

### 10.2 Runtime CPU Detection

```cpp
// ARM NEON is baseline on ARM64 (aarch64), but we still guard for armeabi-v7a
#if defined(__aarch64__)
    // ARM64: NEON guaranteed, use advanced intrinsics
    #include <arm_neon.h>
    #define HAS_NEON 1
#elif defined(__ARM_NEON)
    // ARMv7 with NEON: check at runtime
    #include <arm_neon.h>
    #define HAS_NEON 1
#else
    // Fallback: scalar C++ loop
    #define HAS_NEON 0
#endif
```

---

## 11. Module 7: Lyrics & Metadata Pipeline (C/C++ Port)

### 11.1 LRCLIB Lyrics Client (Port of `kaviraj-tool/lyrics.py`)

```text
Query Strategy (multi-pass, same as Python):
  Pass 1: GET https://lrclib.net/api/get?artist_name={}&track_name={}&album_name={}
  Pass 2: GET https://lrclib.net/api/search?artist_name={}&track_name={}
  Pass 3: GET https://lrclib.net/api/search?q={title} {artist}

Response: JSON with "syncedLyrics" field (LRC format)
Save: .lrc file alongside audio file
```

### 11.2 iTunes Cover Art Client (Port of `kaviraj-tool/cover_art.py`)

```text
Query: GET https://itunes.apple.com/search?term={title}+{artist}&media=music&entity=song&limit=5
Parse: results[0].artworkUrl100 -> replace "100x100" with "1400x1400"
Download: Save as {track_id}_cover.jpg in app internal storage
```

### 11.3 ID3/MP4 Metadata Parser

Native C++ reader for:
- **MP3**: ID3v2.3/ID3v2.4 tags (title, artist, album, track number, embedded art)
- **M4A/MP4**: iTunes-style atoms (nam, ART, alb, covr)
- **FLAC**: Vorbis comments
- **OGG/Opus**: Vorbis comments

---

## 12. Module 8: SQLite Database Schema (Android)

Extended from original `schema.sql` with Android-specific additions:

```sql
-- Core tables (same as NAS version)
CREATE TABLE tracks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    filepath TEXT UNIQUE NOT NULL,
    title TEXT,
    artist TEXT,
    album TEXT DEFAULT 'Single',
    duration_sec INTEGER DEFAULT 180,
    bpm REAL DEFAULT 120.0,
    key TEXT DEFAULT 'C',
    vector_offset INTEGER DEFAULT -1,
    cover_art_path TEXT,              -- NEW: path to local cover art file
    lyrics_path TEXT,                 -- NEW: path to .lrc file
    source TEXT DEFAULT 'local',      -- NEW: 'local' | 'downloaded' | 'device_scan'
    is_processed INTEGER DEFAULT 0,   -- NEW: 0=pending, 1=embeddings generated
    download_quality TEXT,            -- NEW: 'best' | '320k' | '256k' | '128k'
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- User tables (same as NAS version)
CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT UNIQUE NOT NULL,
    pin_hash TEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_sessions (
    token TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE user_liked_songs (
    user_id INTEGER NOT NULL,
    track_id INTEGER NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, track_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (track_id) REFERENCES tracks(id) ON DELETE CASCADE
);

CREATE TABLE transitions (
    user_id INTEGER NOT NULL DEFAULT 1,
    from_track_id INTEGER NOT NULL,
    to_track_id INTEGER NOT NULL,
    count INTEGER DEFAULT 1,
    PRIMARY KEY (user_id, from_track_id, to_track_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE skips (
    user_id INTEGER NOT NULL DEFAULT 1,
    from_track_id INTEGER NOT NULL,
    to_track_id INTEGER NOT NULL,
    count INTEGER DEFAULT 1,
    PRIMARY KEY (user_id, from_track_id, to_track_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE playlists (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL DEFAULT 1,
    name TEXT NOT NULL,
    description TEXT,
    cover_art TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE playlist_tracks (
    playlist_id INTEGER NOT NULL,
    track_id INTEGER NOT NULL,
    position INTEGER NOT NULL,
    PRIMARY KEY (playlist_id, track_id),
    FOREIGN KEY (playlist_id) REFERENCES playlists(id) ON DELETE CASCADE,
    FOREIGN KEY (track_id) REFERENCES tracks(id) ON DELETE CASCADE
);

-- NEW: Download history
CREATE TABLE download_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    query TEXT NOT NULL,
    source_url TEXT,
    track_id INTEGER,
    quality TEXT,
    status TEXT DEFAULT 'pending',   -- 'pending' | 'downloading' | 'processing' | 'complete' | 'failed'
    error_msg TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (track_id) REFERENCES tracks(id) ON DELETE SET NULL
);

-- NEW: Device scan state
CREATE TABLE device_scan_state (
    filepath TEXT PRIMARY KEY,
    last_modified INTEGER,           -- file mtime for change detection
    is_processed INTEGER DEFAULT 0,
    scan_date DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);
CREATE INDEX IF NOT EXISTS idx_sessions_token ON user_sessions(token);
CREATE INDEX IF NOT EXISTS idx_tracks_filepath ON tracks(filepath);
CREATE INDEX IF NOT EXISTS idx_tracks_source ON tracks(source);
CREATE INDEX IF NOT EXISTS idx_tracks_processed ON tracks(is_processed);
CREATE INDEX IF NOT EXISTS idx_transitions_user_from ON transitions(user_id, from_track_id);
CREATE INDEX IF NOT EXISTS idx_skips_user_from ON skips(user_id, from_track_id);
CREATE INDEX IF NOT EXISTS idx_playlists_user ON playlists(user_id);
CREATE INDEX IF NOT EXISTS idx_download_status ON download_history(status);
```

---

## 13. Module 9: CI/CD Pipeline (GitHub Actions)

> **GitHub** = CI/CD (builds APK artifacts). **GitLab** = code mirror only, no builds.

### 13.1 Build Workflow (`.github/workflows/build.yml`)

```yaml
name: Build Debug APK
on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Setup Android SDK
        uses: android-actions/setup-android@v3
      - name: Install NDK
        run: sdkmanager "ndk;26.1.10909125"
      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: gradle-${{ hashFiles('**/*.gradle*') }}
      - name: Build Debug APK
        run: ./gradlew assembleDebug
      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: streamify-debug
          path: app/build/outputs/apk/debug/*.apk
```

### 13.2 Release Workflow (`.github/workflows/release.yml`)

```yaml
name: Release APK
on:
  push:
    tags: ['v*']

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 17 + NDK
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Build Release APK
        run: ./gradlew assembleRelease
        env:
          KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          files: app/build/outputs/apk/release/*.apk
          generate_release_notes: true
```

---

## 14. Module 10: Git Workflow & Repository Strategy

### 14.1 Dual-Remote Setup

```bash
# Already configured:
origin  -> git@github.com:zephyr4289/streamify-apk.git    (CI/CD + primary)
gitlab  -> git@gitlab.com:sireenyadav/streamify-apk.git    (code mirror)

# Push to both remotes:
git push origin main
git push gitlab main

# Or configure push-to-both (recommended):
git remote set-url --add --push origin git@github.com:zephyr4289/streamify-apk.git
git remote set-url --add --push origin git@gitlab.com:sireenyadav/streamify-apk.git
# Now: git push origin main  -> pushes to BOTH GitHub and GitLab
```

### 14.2 Branch Strategy

| Branch | Purpose |
|:-------|:--------|
| `main` | Stable release branch. Tagged for APK releases. |
| `develop` | Active development. PRs merge here first. |
| `feature/*` | Feature branches (e.g., `feature/lyrics-screen`, `feature/neon-vectorstore`) |

---

## 15. Phase-by-Phase Execution Roadmap

### Phase 1: Foundation (Week 1-2)
- [ ] Initialize Android project (Gradle + Compose + NDK + Chaquopy)
- [ ] Port `StreamifyDB.cc` to NDK (remove Drogon, pure SQLite3 C API)
- [ ] Create JNI bridge skeleton (`NativeBridge.kt` <-> `jni_bridge.cc`)
- [ ] Implement `StreamifyTheme` (colors, typography, spacing from CSS)
- [ ] Build `MiniPlayerBar` composable
- [ ] Build `HomeScreen` with static data
- [ ] Set up GitHub Actions build workflow

### Phase 2: Core Playback (Week 3-4)
- [ ] Integrate Media3/ExoPlayer in `PlaybackService`
- [ ] Wire `PlayerViewModel` <-> `NativeBridge` <-> C++ database
- [ ] Build full `PlayerScreen` (seekbar, controls, album art)
- [ ] Build `LibraryScreen` (liked songs via JNI)
- [ ] Implement lock-screen + notification media controls
- [ ] Local file playback working end-to-end

### Phase 3: Search & Download (Week 5-7)
- [ ] Integrate Chaquopy Python runtime
- [ ] Port `download_track.py` + `matcher.py` search/score logic
- [ ] Build `SearchScreen` with local search (JNI)
- [ ] Build `DownloadScreen` with source selection UI
- [ ] Build `QualitySelector` dialog
- [ ] Implement `DownloadService` foreground service
- [ ] Build real-time download progress UI (`DownloadCard`, `ProcessingStatusCard`)
- [ ] Port `cover_art.py` -> `CoverArtClient.cc` (iTunes API)
- [ ] Port `lyrics.py` -> `LyricsClient.cc` (LRCLIB API)

### Phase 4: AI Engine (Week 8-10)
- [ ] Port `VectorStore.cc` AVX2 -> ARM NEON
- [ ] Port `AudioPipeline.cc` (replace FFTW3 with kissfft)
- [ ] Integrate ONNX Runtime Mobile
- [ ] Port `RecommendEngine.cc` (two-stage ranking)
- [ ] Port `EventTracker.cc` (Markov chain play/skip logging)
- [ ] Wire AI recommendations to HomeScreen "Made For You" section
- [ ] Wire Up Next Queue with live AI predictions

### Phase 5: Device Music Discovery (Week 11-12)
- [ ] Implement `MediaStoreScanner.kt`
- [ ] Implement `IngestionWorker` (WorkManager)
- [ ] Build `DeviceScanner.cc` (batch file processor)
- [ ] Background ONNX embedding generation
- [ ] `ProcessingStatusCard` in HomeScreen
- [ ] Full integration: device music -> AI recommendations

### Phase 6: Lyrics & Polish (Week 13-14)
- [ ] Build `LyricsScreen` with synced `.lrc` display
- [ ] Tap-to-seek on lyrics lines
- [ ] Auto-scroll synced to playback
- [ ] Implement all animations (card hover, player transitions, FAB reveal)
- [ ] Edge-to-edge design, status bar theming
- [ ] Performance profiling and optimization
- [ ] Release APK build + GitHub Release workflow

---

## 16. Design System Reference (Ported from Web)

### Color Palette

| Token | Hex | Usage |
|:------|:----|:------|
| `--bg-base` | `#000000` | App background |
| `--bg-surface` | `#121212` | Sidebar, cards container background |
| `--bg-card` | `#181818` | Music cards, auth modal, status cards |
| `--bg-card-hover` | `#282828` | Card pressed/hover state |
| `--bg-player` | `#0F0F0F` | Sticky bottom player bar |
| `--primary` | `#1DB954` | Spotify green accent (play FAB, buttons, active states) |
| `--primary-hover` | `#1ED760` | Green pressed/hover state |
| `--text-main` | `#FFFFFF` | Primary text, headings |
| `--text-sub` | `#B3B3B3` | Secondary text, subtitles, timestamps |
| `--border-color` | `#242424` | Dividers, card borders |
| Error Red | `#FF4D4D` | Error messages, logout hover |
| Error BG | `rgba(235,87,87,0.15)` | Error message background |
| Badge Green BG | `rgba(29,185,84,0.2)` | Engine badge background |

### Typography

| Element | Font | Weight | Size |
|:--------|:-----|:-------|:-----|
| Greeting header | Montserrat | Bold (700) | 32sp |
| Section titles | Montserrat | Bold (700) | 22sp |
| Card titles | System | Bold (700) | 15sp |
| Card subtitles | Poppins | Normal (400) | 13sp |
| Nav items | Poppins | SemiBold (600) | 16sp |
| Player track title | Poppins | SemiBold (600) | 14sp |
| Player track artist | Poppins | Normal (400) | 12sp |
| Time labels | Poppins | Normal (400) | 12sp |
| Badge text | Poppins | SemiBold (600) | 12sp |

### Component Dimensions

| Component | Dimension |
|:----------|:----------|
| Player bar height | 90dp |
| Album art (card) | 1:1 aspect ratio, 6dp corner radius |
| Album art (player bar) | 56x56dp, 6dp corner radius |
| Album art (mini list) | 48x48dp, 6dp corner radius |
| Play FAB | 48x48dp circle, green |
| Play/Pause button | 36x36dp circle, white |
| Card corner radius | 8dp |
| Card grid gap | 20dp |
| Search bar border-radius | 50dp (pill) |
| Auth modal border-radius | 16dp |

---

## 17. Existing Assets Inventory

### Reusable Image Assets (from `web/assets/`)

| File | Dimensions | Purpose | Android Destination |
|:-----|:-----------|:--------|:--------------------|
| `logo.png` | 911x930 | App icon / splash | `mipmap-xxxhdpi/ic_launcher.png` |
| `backward_icon.png` | 24x24 | Back navigation | `drawable/ic_back.xml` (VectorDrawable) |
| `forward_icon.png` | 24x24 | Forward navigation | `drawable/ic_forward.xml` |
| `library_icon.png` | 100x102 | Library nav icon | `drawable/ic_library.xml` |
| `play_musicbar.png` | 101x100 | Queue / musicbar icon | `drawable/ic_queue.xml` |
| `player_icon1.png` | 112x101 | Shuffle | `drawable/ic_shuffle.xml` |
| `player_icon2.png` | 91x100 | Previous track | `drawable/ic_previous.xml` |
| `player_icon3.png` | 101x100 | Play | `drawable/ic_play.xml` |
| `player_icon4.png` | 91x100 | Next track | `drawable/ic_next.xml` |
| `player_icon5.png` | 104x101 | Repeat | `drawable/ic_repeat.xml` |
| `card1img.jpeg` - `card10img` | 300x300 | Default album art | `assets/card_art/` |
| `desktop-view.png` | 1920x1032 | README screenshot | Reference only |
| `mobile-view.png` | 289x625 | README screenshot | Reference only |

### Reusable Code (from NAS codebase)

| Source | Reuse Strategy |
|:-------|:---------------|
| `server/services/StreamifyDB.cc` | Direct C++ port (remove Drogon) |
| `music-procengine/server/src/services/VectorStore.cc` | Port AVX2 -> ARM NEON |
| `music-procengine/server/src/ingest/AudioPipeline.cc` | Replace FFTW3 with kissfft |
| `music-procengine/server/src/ingest/miniaudio.h` | Use directly (cross-platform) |
| `music-procengine/server/controllers/RecommendController.cc` | Port logic, remove HTTP |
| `music-procengine/server/controllers/EventController.cc` | Port logic, remove HTTP |
| `music-procengine/server/services/DatabaseService.cc` | Merge into StreamifyDB |
| `kaviraj-tool/downloader/matcher.py` | Run via Chaquopy (or port scoring to C++) |
| `kaviraj-tool/downloader/lyrics.py` | Port to `LyricsClient.cc` |
| `kaviraj-tool/downloader/cover_art.py` | Port to `CoverArtClient.cc` |
| `kaviraj-tool/downloader/ffmpeg_tagger.py` | Run via Chaquopy |
| `scripts/download_track.py` | Port to Chaquopy `download_engine/downloader.py` |
| `web/style.css` | Extract all design tokens -> `Color.kt`, `Type.kt`, `Dimens.kt` |
| `web/index.html` | Component structure reference for Compose screens |
| `web/app.js` | Business logic reference for ViewModels |
| `schema.sql` | Base schema, extended with Android-specific columns |

---

> **This document is the single source of truth for the Streamify APK project.**
> All development decisions, architectural choices, and implementation details
> should reference this specification. Update this document as the project evolves.
