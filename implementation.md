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

## 🤖 AI EXECUTION INSTRUCTIONS

> **ATTENTION AI AGENT:** This document is the single source of truth for building the Streamify Android app.
> When the user says "read implementation.md and execute", follow this protocol exactly.

### How This Document Works

- **Sections 1–14** are the architecture reference. Read them to understand *what* you're building and *why*.
- **Section 15** is the execution roadmap. It tells you *exactly what to do*, step by step.
- **Sections 16–17** are design/asset references you'll consult during execution.

### How To Determine Which Phase To Execute

1. Check if `app/build.gradle.kts` exists. If **NO** → execute **Phase 1**.
2. Check if `native/engine/StreamifyDB.cc` exists. If **NO** → execute **Phase 2**.
3. Check if `app/src/main/python/download_engine/downloader.py` exists. If **NO** → execute **Phase 3**.
4. Check if `native/engine/VectorStore.cc` exists. If **NO** → execute **Phase 4**.
5. Check if `app/src/main/java/com/streamify/app/util/MediaStoreScanner.kt` exists. If **NO** → execute **Phase 5**.
6. Check if `app/src/main/java/com/streamify/app/ui/screens/LyricsScreen.kt` exists. If **NO** → execute **Phase 6**.
7. If all exist → all phases are complete. Ask the user what to do next.

### Reference File Protocol

Many steps say **"Reference: `legacy/path/to/file`"**. This means:
1. **Read that legacy file first** to understand the original implementation.
2. **Port the logic** into the new file path specified, applying the changes described (e.g., remove Drogon HTTP, replace AVX2 with NEON, etc.).
3. **Do NOT copy the file verbatim.** Strip framework dependencies, adapt APIs, and integrate with JNI.

### After Completing Each Phase

```bash
git add -A
git commit -m "<commit message template from the phase>"
git push origin main
git push gitlab main
```
Then report to the user: "Phase N complete. [summary of what was built]. Ready for Phase N+1."

### Environment Expectations

- **OS**: Android development on Linux (Termux ARM64 or standard x86_64)
- **Java**: JDK 17 (install via `apt install openjdk-17` or equivalent)
- **Android SDK**: Install via `sdkmanager` or manual setup
- **NDK**: Version `26.1.10909125` (install via `sdkmanager "ndk;26.1.10909125"`)
- **Git remotes already configured**: `origin` → GitHub, `gitlab` → GitLab
- **All legacy source code**: Located in `legacy/` directory within this repo

### Version Pinning (Use These Exact Versions)

| Dependency | Version |
|:-----------|:--------|
| Kotlin | `1.9.22` |
| Compose BOM | `2024.02.00` |
| Compose Compiler | `1.5.10` |
| Gradle | `8.4` |
| AGP (Android Gradle Plugin) | `8.2.2` |
| NDK | `26.1.10909125` |
| Min SDK | `26` (Android 8.0) |
| Target SDK | `34` (Android 14) |
| Compile SDK | `34` |
| Media3 | `1.2.1` |
| Coil Compose | `2.5.0` |
| Navigation Compose | `2.7.7` |
| Chaquopy | `15.0.1` |
| ONNX Runtime Android | `1.17.0` |
| SQLite (vendored amalgamation) | `3.45.3` |

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

This section dictates exactly how to build this app. Execute steps sequentially.

### Phase 1: Android Project Foundation

**Objective**: Scaffold the Android project, configure Gradle/CMake, setup JNI, and build the UI theme.

**Step-by-Step Instructions**:

1. **Scaffold Directory Structure**:
   Run the following shell command to create the necessary directories:
   ```bash
   mkdir -p app/src/main/java/com/streamify/app/ui/theme app/src/main/res/drawable app/src/main/res/font app/src/main/assets/card_art gradle/wrapper native/jni native/engine native/ingest native/metadata native/dsp native/third_party/sqlite3 native/third_party/onnxruntime
   ```

2. **Root Gradle Configuration**:
   Create `settings.gradle.kts`:
   ```kotlin
   pluginManagement {
       repositories {
           google()
           mavenCentral()
           gradlePluginPortal()
       }
   }
   dependencyResolutionManagement {
       repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
       repositories {
           google()
           mavenCentral()
           maven { url = uri("https://chaquo.com/maven") }
       }
   }
   rootProject.name = "Streamify"
   include(":app")
   ```
   Create `gradle.properties`:
   ```properties
   android.useAndroidX=true
   android.nonTransitiveRClass=true
   org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
   ```
   Create `build.gradle.kts` (Root):
   ```kotlin
   plugins {
       id("com.android.application") version "8.2.2" apply false
       id("org.jetbrains.kotlin.android") version "1.9.22" apply false
       id("com.chaquo.python") version "15.0.1" apply false
   }
   ```
   Run: `gradle wrapper --gradle-version 8.4`

3. **App Module Configuration (`app/build.gradle.kts`)**:
   Create the file with this exact content:
   ```kotlin
   plugins {
       id("com.android.application")
       id("org.jetbrains.kotlin.android")
       id("com.chaquo.python")
   }

   android {
       namespace = "com.streamify.app"
       compileSdk = 34

       defaultConfig {
           applicationId = "com.streamify.app"
           minSdk = 26
           targetSdk = 34
           versionCode = 1
           versionName = "1.0"

           externalNativeBuild {
               cmake {
                   cppFlags += "-std=c++17 -O3 -flto"
                   arguments += "-DANDROID_STL=c++_shared"
               }
           }
           ndk {
               abiFilters += listOf("arm64-v8a", "armeabi-v7a")
           }
       }

       buildTypes {
           release {
               isMinifyEnabled = true
               proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
           }
       }
       compileOptions {
           sourceCompatibility = JavaVersion.VERSION_17
           targetCompatibility = JavaVersion.VERSION_17
       }
       kotlinOptions {
           jvmTarget = "17"
       }
       buildFeatures {
           compose = true
       }
       composeOptions {
           kotlinCompilerExtensionVersion = "1.5.10"
       }
       externalNativeBuild {
           cmake {
               path = file("../native/CMakeLists.txt")
               version = "3.22.1"
           }
       }
       chaquopy {
           defaultConfig {
               version = "3.11"
               pip {
                   install("yt-dlp")
                   install("mutagen")
                   install("requests")
               }
           }
       }
   }

   dependencies {
       val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
       implementation(composeBom)
       implementation("androidx.core:core-ktx:1.12.0")
       implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
       implementation("androidx.activity:activity-compose:1.8.2")
       implementation("androidx.compose.ui:ui")
       implementation("androidx.compose.ui:ui-graphics")
       implementation("androidx.compose.ui:ui-tooling-preview")
       implementation("androidx.compose.material3:material3")
       
       // Media3
       implementation("androidx.media3:media3-exoplayer:1.2.1")
       implementation("androidx.media3:media3-session:1.2.1")
       
       // Coil
       implementation("io.coil-kt:coil-compose:2.5.0")
       
       // Navigation
       implementation("androidx.navigation:navigation-compose:2.7.7")

       debugImplementation("androidx.compose.ui:ui-tooling")
   }
   ```

4. **Master NDK CMake File (`native/CMakeLists.txt`)**:
   Create the file with:
   ```cmake
   cmake_minimum_required(VERSION 3.22.1)
   project("streamify_core")

   add_library(streamify_core SHARED
       jni/jni_bridge.cc
   )

   target_link_libraries(streamify_core
       android
       log
   )
   ```

5. **JNI Bridge Initialization**:
   Create `app/src/main/java/com/streamify/app/data/NativeBridge.kt`:
   ```kotlin
   package com.streamify.app.data
   
   object NativeBridge {
       init { System.loadLibrary("streamify_core") }
       external fun stringFromJNI(): String
   }
   ```
   Create `native/jni/jni_bridge.cc`:
   ```cpp
   #include <jni.h>
   #include <string>
   
   extern "C" JNIEXPORT jstring JNICALL
   Java_com_streamify_app_data_NativeBridge_stringFromJNI(JNIEnv* env, jobject /* this */) {
       return env->NewStringUTF("Streamify C++ Core Initialized");
   }
   ```

6. **AndroidManifest (`app/src/main/AndroidManifest.xml`)**:
   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <manifest xmlns:android="http://schemas.android.com/apk/res/android">
       <uses-permission android:name="android.permission.INTERNET" />
       <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
       <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
       <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
       <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
       <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
       <uses-permission android:name="android.permission.WAKE_LOCK" />

       <application
           android:name=".StreamifyApp"
           android:allowBackup="true"
           android:icon="@mipmap/ic_launcher"
           android:label="Streamify"
           android:roundIcon="@mipmap/ic_launcher_round"
           android:supportsRtl="true"
           android:theme="@style/Theme.Streamify">
           <activity
               android:name=".MainActivity"
               android:exported="true"
               android:theme="@style/Theme.Streamify">
               <intent-filter>
                   <action android:name="android.intent.action.MAIN" />
                   <category android:name="android.intent.category.LAUNCHER" />
               </intent-filter>
           </activity>
       </application>
   </manifest>
   ```

7. **Theme and Styles**:
   Create `app/src/main/res/values/themes.xml`:
   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <resources>
       <style name="Theme.Streamify" parent="android:Theme.Material.Light.NoActionBar" />
   </resources>
   ```
   *Reference: Port colors from `legacy/web/style.css` (lines 1-16) to Compose `Color.kt`.*
   Create `app/src/main/java/com/streamify/app/ui/theme/Color.kt`, `Type.kt`, `Theme.kt`, `Dimens.kt`.

8. **Core Application Classes**:
   Create `app/src/main/java/com/streamify/app/StreamifyApp.kt`:
   ```kotlin
   package com.streamify.app
   import android.app.Application
   class StreamifyApp : Application()
   ```
   Create `app/src/main/java/com/streamify/app/MainActivity.kt`:
   ```kotlin
   package com.streamify.app
   import android.os.Bundle
   import androidx.activity.ComponentActivity
   import androidx.activity.compose.setContent
   import androidx.compose.material3.Text
   import com.streamify.app.data.NativeBridge
   
   class MainActivity : ComponentActivity() {
       override fun onCreate(savedInstanceState: Bundle?) {
           super.onCreate(savedInstanceState)
           setContent {
               Text(text = NativeBridge.stringFromJNI())
           }
       }
   }
   ```

9. **Assets & CI**:
   - Shell: `cp legacy/web/assets/*.png app/src/main/res/drawable/`
   - Shell: `cp legacy/web/assets/*.jpeg app/src/main/assets/card_art/`
   - Download Montserrat/Poppins `.ttf` files to `app/src/main/res/font/`.
   - Create `.github/workflows/build.yml` with `./gradlew assembleDebug` step.

10. **Verify Build**:
    Run `./gradlew assembleDebug`. Ensure it succeeds.

**DONE CRITERIA**:
- `app/build/outputs/apk/debug/app-debug.apk` is generated.
- The JNI string appears on screen when run.

**Commit Message Template**:
```
feat: initialize Android project and NDK foundation (Phase 1)
```

---

### Phase 2: Core Playback & Database

**Objective**: Port SQLite logic to NDK, wire up JNI models, and build the ExoPlayer/Media3 foreground service.

**Step-by-Step Instructions**:

1. **Vendor SQLite**:
   - Download SQLite amalgamation 3.45.3 and place `sqlite3.c` and `sqlite3.h` in `native/third_party/sqlite3/`.
   - Update `native/CMakeLists.txt` to include `third_party/sqlite3/sqlite3.c`.

2. **Port `StreamifyDB` to C++ NDK**:
   - Read `legacy/server/services/StreamifyDB.cc` and `.h`.
   - Create `native/engine/StreamifyDB.h` and `native/engine/StreamifyDB.cc`.
   - **Crucial**: Remove ALL Drogon includes. Use standard `sqlite3_prepare_v2`, `sqlite3_step`. Use `sqlite3_open_v2` with a database path passed from Kotlin (e.g., `context.getDatabasePath("streamify.db").absolutePath`).

3. **Expand JNI Bridge**:
   - In `NativeBridge.kt`, add:
     ```kotlin
     external fun initDatabase(dbPath: String): Boolean
     external fun getAllTracks(): Array<TrackNative>
     // ... add searchTracks, insertTrack, toggleLike, getLikedTracks
     ```
   - In `jni_bridge.cc`, implement these JNI functions. Construct Java objects (`TrackNative`) from C++ SQLite results using `env->NewObject`.

4. **Kotlin Data Layer**:
   - Create `app/src/main/java/com/streamify/app/data/models/Track.kt` representing a song.
   - Create `app/src/main/java/com/streamify/app/data/TrackRepository.kt` wrapping `NativeBridge` calls into suspend functions / Flows.

5. **Media3 Playback Service**:
   - Create `app/src/main/java/com/streamify/app/service/PlaybackService.kt`.
   - Extend `MediaSessionService`. Build an `ExoPlayer` instance in `onCreate()`. Set `C.AUDIO_CONTENT_TYPE_MUSIC`, `C.USAGE_MEDIA`, and `setHandleAudioBecomingNoisy(true)`.

6. **UI Implementation**:
   - Create `PlayerViewModel.kt` to manage `ExoPlayer` state via StateFlow.
   - Implement `MiniPlayerBar.kt` mirroring the web's bottom sticky player.
   - Implement `PlayerScreen.kt` for full-screen playback.
   - Implement `HomeScreen.kt` displaying tracks from `TrackRepository`.
   - Setup `AppNavGraph.kt` and integrate into `MainActivity.kt`.

7. **Verify**:
   - Ensure the app launches, queries the NDK database, and can play a local `.mp3` file via Media3.

**DONE CRITERIA**:
- `StreamifyDB.cc` is compiled and queried via JNI.
- Media3 service plays audio and shows a notification.

**Commit Message Template**:
```
feat: core playback and native sqlite db via JNI (Phase 2)
```

---

### Phase 3: Search & Download Pipeline

**Objective**: Integrate Chaquopy to run yt-dlp and kaviraj-tool matching logic natively.

**Step-by-Step Instructions**:

1. **Python Module Setup**:
   - Create `app/src/main/python/download_engine/` and `__init__.py`.

2. **Port Matcher Logic**:
   - Read `legacy/kaviraj-tool/Music.yt.Spot/downloader/matcher.py`.
   - Create `app/src/main/python/download_engine/search.py`.
   - Keep the exact scoring algorithm (`seq_ratio`, `set_ratio`, duration penalties, bad candidate flags).

3. **Port Downloader Logic**:
   - Read `legacy/scripts/download_track.py`.
   - Create `app/src/main/python/download_engine/downloader.py`.
   - Ensure it calls `yt-dlp` using the embedded Python environment. Configure it to write to `context.filesDir.absolutePath`.

4. **UI Integration**:
   - Create `app/src/main/java/com/streamify/app/ui/screens/SearchScreen.kt`. Implement the `.download-banner` UI when local results are empty.
   - Create `app/src/main/java/com/streamify/app/ui/screens/DownloadScreen.kt`.
   - Create `SearchViewModel.kt` handling Kotlin-to-Python calls via `Python.getInstance().getModule(...)`.
   - Create `DownloadService.kt` (Foreground Service) to manage long-running yt-dlp processes.

5. **Verify**:
   - Search for a song, view ranked results, select one, and verify it downloads successfully to app storage.

**DONE CRITERIA**:
- yt-dlp executes via Chaquopy and downloads an audio file.

**Commit Message Template**:
```
feat: search and yt-dlp download pipeline via Chaquopy (Phase 3)
```

---

### Phase 4: AI Recommendation Engine

**Objective**: Port the C++ AVX2 vector engine to ARM NEON and implement ONNX inference.

**Step-by-Step Instructions**:

1. **Port VectorStore to ARM NEON**:
   - Read `legacy/music-procengine/server/src/services/VectorStore.cc`.
   - Create `native/engine/VectorStore.cc`.
   - **Crucial**: Use the NEON code provided in section 10.2 (4x unrolled `vfmaq_f32`). Update `native/CMakeLists.txt` to compile with NEON support if necessary.

2. **Port DSP and Inference**:
   - Replace FFTW3 with `kissfft` (download and vendor into `native/dsp/kissfft/`).
   - Read `legacy/music-procengine/server/src/ingest/AudioPipeline.cc`.
   - Create `native/ingest/AudioPipeline.cc`. Update to use `kissfft` for the STFT.
   - Link `onnxruntime` (Android build) in CMake. Include the LAION CLAP `clap_int8.onnx` model in `assets/models/`.

3. **Port Ranking Logic**:
   - Read `legacy/music-procengine/server/controllers/RecommendController.cc` and `EventController.cc`.
   - Create `native/engine/RecommendEngine.cc` and `native/engine/EventTracker.cc`. Remove Drogon, expose pure C++ functions to JNI.

4. **UI Wire-up**:
   - Update `HomeScreen.kt` to call JNI recommendations and display them in the "Made For You" grid.

5. **Verify**:
   - Run recommendation JNI call; ensure it completes in <10ms utilizing NEON.

**DONE CRITERIA**:
- `VectorStore.cc` compiles for ARM64 and performs NEON vector similarity.
- Audio pipeline extracts 512-D vectors from audio.

**Commit Message Template**:
```
feat: ONNX AI recommendation engine with ARM NEON SIMD (Phase 4)
```

---

### Phase 5: Device Music Discovery

**Objective**: Scan local device storage for music and process embeddings in the background.

**Step-by-Step Instructions**:

1. **MediaStore Scanner**:
   - Create `app/src/main/java/com/streamify/app/util/MediaStoreScanner.kt`. Use `ContentResolver` to query `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`.

2. **Background Ingestion**:
   - Create `app/src/main/java/com/streamify/app/service/IngestionWorker.kt` extending `CoroutineWorker`.
   - Pass discovered file paths to C++ `AudioPipeline` via JNI for ONNX vector generation.

3. **UI Status Card**:
   - Implement `ProcessingStatusCard.kt` in `HomeScreen` observing `IngestionViewModel` StateFlow.

4. **Verify**:
   - Ensure existing `/sdcard/Music` files are ingested and vectors added to SQLite.

**DONE CRITERIA**:
- Local files are successfully processed and embedded without blocking the UI.

**Commit Message Template**:
```
feat: device music discovery and background ingestion (Phase 5)
```

---

### Phase 6: Lyrics, Polish & Release

**Objective**: Complete metadata fetching, finalize UI animations, and build the release APK.

**Step-by-Step Instructions**:

1. **Port Metadata Clients**:
   - Read `legacy/kaviraj-tool/Music.yt.Spot/downloader/lyrics.py` and `cover_art.py`.
   - Implement these network calls in C++ (`native/metadata/LyricsClient.cc` and `CoverArtClient.cc`) or Kotlin (using OkHttp).

2. **Lyrics UI**:
   - Create `app/src/main/java/com/streamify/app/ui/screens/LyricsScreen.kt`. Implement auto-scrolling synced `.lrc` view.

3. **Polish**:
   - Verify all CSS hover animations are ported to Compose interactions (e.g., card lift).
   - Implement Edge-to-Edge display (transparent status bar).

4. **Release Configuration**:
   - Ensure `build.gradle.kts` release type has `isMinifyEnabled = true`.
   - Create `.github/workflows/release.yml`.

5. **Verify**:
   - Run `./gradlew assembleRelease` and ensure a signed, optimized APK is produced.

**DONE CRITERIA**:
- Synced lyrics scroll smoothly in the UI.
- Release APK builds successfully.

**Commit Message Template**:
```
chore: release preparations, lyrics UI, and final polish (Phase 6)
```

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
