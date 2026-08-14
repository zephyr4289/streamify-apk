# Streamify APK 🎧📱

[![Build Debug APK](https://github.com/zephyr4289/streamify-apk/actions/workflows/build.yml/badge.svg)](https://github.com/zephyr4289/streamify-apk/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-brightgreen.svg)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Compose-BOM%202024.02.00-blue.svg)](https://developer.android.com/jetpack/compose)
[![C++](https://img.shields.io/badge/C%2B%2B-17%20%7C%20ARM%20NEON%20SIMD-orange.svg)](https://isocpp.org)
[![Python](https://img.shields.io/badge/Python-3.11%20(Chaquopy)-yellow.svg)](https://chaquo.com/chaquopy/)

**Streamify APK** is an ultra-high-performance, production-grade Android music streaming and ingestion client built with a tri-language architecture (**Kotlin + Jetpack Compose**, **Native C++17 JNI Core**, and **Embedded Python 3.11 via Chaquopy**). It delivers a pixel-perfect, hyper-responsive "Spotify-tier" native interface, high-speed on-device audio signal processing (BPM onset extraction & harmonic key detection), ARM NEON SIMD vector recommendations, sub-100ms Innertube streaming/search, 5-tier caching, Supabase cloud sync, and lossless background media downloads.

---

## 📑 Table of Contents
1. [Core Architectural Highlights](#-core-architectural-highlights)
2. [Detailed System Architecture](#-detailed-system-architecture)
3. [Deep-Dive Feature Breakdown](#-deep-dive-feature-breakdown)
4. [Complete Repository Directory Map](#-complete-repository-directory-map)
5. [Signal Processing & AI Vector Engine](#-signal-processing--ai-vector-engine)
6. [5-Tier Zero-Bloat Caching Subsystem](#-5-tier-zero-bloat-caching-subsystem)
7. [Cloud Infrastructure & Security](#-cloud-infrastructure--security)
8. [Build, Setup & CI/CD Pipeline](#-build-setup--cicd-pipeline)
9. [License](#-license)

---

## ⚡ Core Architectural Highlights

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                             STREAMIFY TRI-ENGINE RUNTIME                         │
├──────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│   🎨 KOTLIN & JETPACK COMPOSE (UI / Framework Layer)                             │
│   • Single-Activity Architecture + Navigation Component                          │
│   • Reactive StateFlow ViewModels (MVI / MVVM pattern)                           │
│   • AndroidX Media3 / ExoPlayer background audio session                         │
│   • Global UiEventBus (SharedFlow) for real-time instantaneous assurity          │
│   • Sub-100ms Native YouTube Music Innertube API & Apple iTunes CDN Client       │
│                                      │                                           │
│                       JNI Dynamic Link (NativeBridge)                            │
│                                      ▼                                           │
│   🧠 NATIVE C++17 ENGINE (Signal Processing & Persistence Core)                  │
│   • Thread-safe SQLite3 with WAL Mode and Mutex Concurrency Protection           │
│   • DSP Audio Ingestion Pipeline (KissFFT STFT spectral flux + Chromagram)       │
│   • Real On-Device BPM Autocorrelation & Harmonic Key Detection (Krumhansl)      │
│   • 512-dim Vector Store accelerated by 128-bit ARM NEON SIMD Matrix Math        │
│   • Cooperative Task Orchestrator with Dynamic Resource-Aware Scheduling         │
│                                      │                                           │
│                            Chaquopy Interop Layer                                │
│                                      ▼                                           │
│   🐍 EMBEDDED PYTHON 3.11 (Ingestion & Extraction Engine)                        │
│   • Embedded yt-dlp runtime sandboxed within Android storage                     │
│   • Mutagen ID3/Vorbis tag manipulation & album artwork injection                │
│   • Multi-provider LRC synced lyrics fetcher & local disk caching                │
│                                                                                  │
└──────────────────────────────────────────────────────────────────────────────────┘
```

---

## 🔬 Detailed System Architecture

### 1. Presentation & UI Layer (Kotlin + Jetpack Compose)
* **Spotify-Authentic Theme System**: Full HSL color tokens (`#121212` backgrounds, `#1DB954` accents, `#282828` elevated cards), fluid typography (Google Fonts Montserrat / Poppins), and responsive spacing tokens.
* **Physics & Gestures**: Interactive spring bouncy animations (`DampingRatioLowBouncy`), horizontal drag-to-skip gestures, and custom canvas-drawn seekbars with touch magnification.
* **Global Real-Time Event Bus**: Event-driven decoupled architecture using Kotlin `SharedFlow` (`UiEventBus.kt`) that pushes instant Snackbars to the root scaffold when background downloads or library mutations occur.

### 2. Audio Engine & Native Services
* **Media3 Playback Service**: Foreground audio service hosting ExoPlayer with lock-screen notification media controls, audio focus handling (ducking on notifications, pause on phone calls), and Bluetooth device routing.
* **Dynamic Audio Device Switcher**: Real-time broadcast receiver monitoring audio peripherals (Bluetooth A2DP, wired 3.5mm jack, USB DACs, internal speakers) with dynamic UI route badges.
* **DSP Audio Processing**: Android 10-band equalizer manager and loudness normalization tied to native audio session IDs.

### 3. Native C++ Signal Processing & Persistence Core
* **Acoustic Signal Analysis**: Native C++ processing via KissFFT STFT to compute real-time spectral flux, onset detection curves, autocorrelation-based BPM extraction (60–200 BPM range with octave correction), and 12-bin chromagram profile matching for musical keys.
* **ARM NEON SIMD Vector Engine**: 512-dimensional vector cosine similarity calculated directly on ARM hardware registers (`vld1q_f32`, `vmlaq_f32`, `vaddvq_f32`) yielding <1ms nearest-neighbor recommendations.
* **Rock-Solid SQLite3 Persistence**: Single shared connection with strict mutex locks, foreign key enforcement, WAL mode, and explicit checkpointing to eliminate race conditions and database corruption.

### 4. Background Ingestion & Extraction (Chaquopy)
* **WorkManager Integration**: Fault-tolerant background worker pipeline (`DownloadWorker.kt`) coordinating Python `yt-dlp` stream extraction, FFmpeg conversion, mutagen metadata stamping, and Android MediaStore indexing.

---

## 🚀 Deep-Dive Feature Breakdown

| Feature Module | Description & Technical Implementation |
| :--- | :--- |
| **Acoustic AI Core** | Native C++ STFT spectral flux onset detection & autocorrelation for BPM; 12-bin Chromagram with Krumhansl-Schmuckler profiles for harmonic key extraction. |
| **NEON SIMD Vector Search** | Hardware-accelerated 512-dimensional vector cosine similarity with NaN/Inf guards and plain C++ fallbacks for non-ARM64 platforms. |
| **Task Orchestrator** | Dynamic resource-aware background task scheduler with cooperative yielding preventing UI thread contention during heavy ingestion. |
| **Sub-100ms Search** | Pure Kotlin Innertube client bypasses heavy Python runtimes for near-instant YouTube Music search and iTunes CDN metadata resolution. |
| **5-Tier Caching** | Segmented disk cache for audio streaming chunks, memory/disk cover art caching (Coil), `.lrc` lyrics cache, stream URL LRU cache, and SQLite RAM cache. |
| **Dynamic Full Player** | Horizontal pager (Cover Art ↔ LRC Synced Lyrics ↔ Reorderable Queue), animated palette mesh background, floating time tooltip canvas seekbar. |
| **Reorderable Queue** | Smooth drag-and-drop queue management with instant disk persistence across app restarts. |
| **Cloud Sync & Auth** | Supabase backend integration with Google 1-Tap OAuth, user profiles, synced playlists, and Admin Command Center telemetry. |
| **Backup & Storage** | Full JSON database export/import engine, detailed storage breakdown (downloads vs cache), and one-tap cache flush. |
| **Call Recording Filter** | MediaStore ingestion scanner automatically excludes voice memos and call recordings from polluting music library. |

---

## 📂 Complete Repository Directory Map

Below is the complete file-by-file directory map detailing the purpose of every file across the codebase:

```text
streamify-apk/
├── .github/
│   └── workflows/
│       └── build.yml                                # GitHub Actions CI/CD pipeline for automated Android APK compilation and artifact deployment
├── .gitignore                                       # Git ignore rules for build artifacts, Gradle caches, IDE files, and credentials
├── README.md                                        # Primary project technical documentation, architecture specifications, and build guides
├── build.gradle.kts                                 # Top-level Gradle root project build configuration and plugin definitions
├── build_log.md                                     # Dedicated developer and CI guide detailing the automated build-logs branch workflow
├── gradle.properties                                # JVM arguments, AndroidX flags, and Gradle build environment optimization properties
├── gradlew                                          # Unix shell executable wrapper for Gradle
├── gradlew.bat                                      # Windows batch executable wrapper for Gradle
├── settings.gradle.kts                              # Gradle project and module repository inclusion configurations
├── app/
│   ├── build.gradle.kts                             # App module build script (Chaquopy Python config, Jetpack Compose, Media3, NDK CMake bindings)
│   ├── debug.keystore                               # Deterministic debug keystore for consistent Google OAuth SHA-1 verification
│   ├── proguard-rules.pro                           # R8 / ProGuard code shrinking and obfuscation rules for JNI and Chaquopy
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml                  # Android app manifest declaring activities, services, permissions, and intent filters
│           ├── assets/
│           │   ├── card_art/                        # Fallback assets for browse categories and promo cards
│           │   │   ├── card1img.jpeg                # Category banner asset for Hip-Hop / Urban
│           │   │   ├── card2img.jpeg                # Category banner asset for Pop / Hits
│           │   │   ├── card3img.jpeg                # Category banner asset for Electronic / Dance
│           │   │   ├── card4img.jpeg                # Category banner asset for Rock / Indie
│           │   │   ├── card5img.jpeg                # Category banner asset for Chill / Lo-Fi
│           │   │   └── card6img.jpeg                # Category banner asset for Acoustic / Classical
│           │   └── models/
│           │       └── clap_int8.onnx               # Quantized 8-bit ONNX neural model for audio feature representation
│           ├── java/com/streamify/app/
│           │   ├── MainActivity.kt                  # Root Single-Activity container hosting BottomSheetScaffold, NavHost, and EventBus listener
│           │   ├── StreamifyApp.kt                  # Custom Android Application class initializing JNI, Chaquopy, and Supabase clients
│           │   ├── data/
│           │   │   ├── BackupManager.kt             # Full database JSON serialization, backup export, and restore recovery engine
│           │   │   ├── LyricsCacheManager.kt        # High-performance disk cache manager for synced LRC lyrics files
│           │   │   ├── NativeBridge.kt              # Kotlin JNI bindings to C++ native engine (DB, VectorStore, Recommender, AudioPipeline)
│           │   │   ├── PlaylistRepository.kt        # Playlist persistence manager handling custom collections and playlist track relations
│           │   │   ├── StorageManager.kt            # Storage calculation utility managing app cache, downloads folder, and cleanup operations
│           │   │   ├── TrackRepository.kt           # Central track repository coordinating SQLite queries, like states, and Flow streams
│           │   │   ├── models/
│           │   │   │   ├── LyricsData.kt            # Data models representing synchronized LRC lyrics lines and timestamps
│           │   │   │   ├── OrchestratorStatus.kt    # Data class representing native C++ TaskOrchestrator worker thread status and queue depth
│           │   │   │   ├── Recommendation.kt        # Data model for recommendation results, similarity scores, and reason metadata
│           │   │   │   └── Track.kt                 # Core domain and JNI native Track entity representations
│           │   │   ├── network/
│           │   │   │   ├── YouTubeMusicSearchApi.kt # Ultra-fast sub-100ms pure Kotlin YouTube Music Innertube search client
│           │   │   │   └── iTunesSearchApi.kt       # Apple iTunes Search API client for fetching high-resolution album covers and metadata
│           │   │   └── remote/
│           │   │       ├── AuthManager.kt           # Google 1-Tap OAuth credentials and Supabase authentication session manager
│           │   │       └── SupabaseClient.kt        # Remote Supabase client handling user profiles, remote playlist sync, and telemetry
│           │   ├── navigation/
│           │   │   └── AppNavGraph.kt               # Jetpack Compose animated navigation graph with custom horizontal and vertical transitions
│           │   ├── service/
│           │   │   ├── AudioCacheManager.kt         # Segmented disk cache manager for media chunk streaming
│           │   │   ├── AudioDeviceManager.kt        # Broadcast listener and manager for detecting Bluetooth, wired, and speaker audio routes
│           │   │   ├── CrossfadeAudioProcessor.kt   # Custom Media3 audio processor executing seamless crossfade blending between tracks
│           │   │   ├── DownloadService.kt           # Foreground download notification service managing download workers
│           │   │   ├── EqualizerManager.kt          # Android 10-band audio equalizer controller and loudness normalization enhancer
│           │   │   ├── IngestionWorker.kt           # WorkManager worker executing local device MediaStore audio scanning and C++ ingestion
│           │   │   └── PlaybackService.kt           # Core AndroidX Media3 media session service for lock-screen controls and background audio
│           │   ├── ui/
│           │   │   ├── animations/
│           │   │   │   ├── CardPressEffect.kt       # Bouncy spring scale and alpha reduction modifier for interactive UI elements
│           │   │   │   ├── HeartBurstEffect.kt      # 12-particle burst canvas physics animation for track like events
│           │   │   │   └── PlayerTransition.kt      # Motion spec definitions for mini player expansion and sheet transitions
│           │   │   ├── components/
│           │   │   │   ├── ArtistCircleCard.kt      # Circular artist avatar component with name label and click ripple
│           │   │   │   ├── BottomNavBar.kt          # Bottom navigation bar with Spotify styling, icon scaling, and active indicators
│           │   │   │   ├── CategoryCard.kt          # Browse category card featuring 45-degree gradient fills and rotated artwork
│           │   │   │   ├── ContextMenuSheet.kt      # Bottom sheet modal providing track actions (Like, Add to Queue, Share, Download)
│           │   │   │   ├── EmptyStateView.kt        # Stylized empty state placeholder with contextual icon, title, and action CTA
│           │   │   │   ├── HeartButton.kt           # Interactive animated heart button with particle burst and haptic feedback
│           │   │   │   ├── MarqueeText.kt           # Auto-scrolling horizontal text composable for long track titles and artist names
│           │   │   │   ├── MiniPlayerBar.kt         # Bottom mini-player bar with progress line, track info, play/pause, and swipe gestures
│           │   │   │   ├── NowPlayingIndicator.kt   # 3-bar animated green equalizer indicator reflecting active playback state
│           │   │   │   ├── PlayerBackground.kt      # Animated dynamic multi-stop radial gradient background driven by Palette extraction
│           │   │   │   ├── PlayerControls.kt        # Media playback control buttons (Shuffle, Previous, Play/Pause, Next, Repeat)
│           │   │   │   ├── PlayerSeekBar.kt         # Custom canvas-drawn interactive seekbar with magnifying touch scrubber and time bubble
│           │   │   │   ├── RecentPlayCard.kt        # Spotify-style 2x3 compact grid card with album art and track metadata
│           │   │   │   ├── ReorderableList.kt       # Drag-and-drop reorderable lazy list implementation for queue management
│           │   │   │   ├── ShimmerPlaceholder.kt    # Shimmer loading skeleton effect for tracks, cards, and browse items
│           │   │   │   ├── TrackCard.kt             # Vertical card component for albums, playlists, and recommendation carousels
│           │   │   │   ├── TrackCoverArt.kt         # Optimized Coil async image loader with fallback icons and rounded corners
│           │   │   │   └── TrackListItem.kt         # Standard horizontal track item row with art, title, artist, like button, and context menu
│           │   │   ├── screens/
│           │   │   │   ├── AdminDashboardScreen.kt  # Authorized admin command center for telemetry, vector store stats, and sync management
│           │   │   │   ├── AlbumScreen.kt           # Album detail view showing tracklist, total runtime, header blur, and play/shuffle actions
│           │   │   │   ├── ArtistScreen.kt          # Artist profile screen featuring top tracks, full discography, and bio
│           │   │   │   ├── DownloadScreen.kt        # Downloads manager screen displaying active download speeds, progress, and offline items
│           │   │   │   ├── EqualizerScreen.kt       # Equalizer UI with multi-band sliders, bass boost, virtualizer, and audio presets
│           │   │   │   ├── FullPlayerSheet.kt       # Flagship full-screen player bottom sheet with 3-tab pager (Art, Synced Lyrics, Queue)
│           │   │   │   ├── HomeScreen.kt            # Home dashboard featuring time-based greetings, recent plays grid, and AI recommendation shelves
│           │   │   │   ├── LibraryScreen.kt         # User library screen with filter chips (All, Liked, Downloads, Streamify, Playlists)
│           │   │   │   ├── LyricsScreen.kt          # Dedicated synchronized lyrics screen with auto-scroll and tap-to-seek functionality
│           │   │   │   ├── PlayerScreen.kt          # Standalone full player container fallback
│           │   │   │   ├── QueueScreen.kt           # Interactive queue management screen with drag-to-reorder, swipe-to-delete, and clear options
│           │   │   │   ├── SearchScreen.kt          # Instant search screen with browse category grids, recent searches, and online results
│           │   │   │   └── SettingsScreen.kt        # Settings dashboard managing audio quality, crossfade, sleep timer, storage, and cloud sync
│           │   │   └── theme/
│           │   │       ├── Color.kt                 # Spotify dark theme color palette (Background, Surface, Primary Green, Accents)
│           │   │       ├── Dimens.kt                # UI dimension tokens, padding values, elevations, and animation timing constants
│           │   │       ├── Shape.kt                 # Corner radius definitions for cards, buttons, dialogs, and bottom sheets
│           │   │       ├── Theme.kt                 # Root Material3 theme wrapper configuring colors, typography, and status bar styles
│           │   │       └── Type.kt                  # Typography system specifying text styles, font weights, and letter-spacings
│           │   ├── util/
│           │   │   ├── DurationFormatter.kt         # Helper formatting millisecond durations into readable mm:ss strings
│           │   │   ├── MediaStoreScanner.kt         # Android MediaStore query utility with call recording and ringtone filtering
│           │   │   ├── PaletteExtractor.kt          # Async bitmap palette analyzer extracting dominant and muted colors for dynamic UI
│           │   │   ├── PermissionHelper.kt          # Android 13+ runtime permissions checker (READ_MEDIA_AUDIO, POST_NOTIFICATIONS)
│           │   │   ├── TimeGreeting.kt              # Utility returning time-contextual greeting strings ("Good morning", "Good evening")
│           │   │   └── TrackShareCard.kt            # Share utility generating rich track sharing intents and shareable media cards
│           │   ├── viewmodel/
│           │   │   ├── HomeViewModel.kt             # ViewModel managing Home screen recent tracks, shelves, and AI recommendations
│           │   │   ├── IngestionViewModel.kt        # ViewModel orchestrating background media scans and real-time C++ feature extraction
│           │   │   ├── LibraryViewModel.kt          # ViewModel managing library filtering, liked tracks, custom playlists, and folder views
│           │   │   ├── PlayerViewModel.kt           # Central player state machine controlling playback, queue, seek, shuffle, repeat, and like states
│           │   │   ├── SearchViewModel.kt           # ViewModel handling debounced sub-100ms Innertube search and local library queries
│           │   │   └── UiEventBus.kt                # Global Kotlin SharedFlow event bus broadcasting Snackbars and assurity alerts
│           │   └── worker/
│           │       └── DownloadWorker.kt            # CoroutineWorker executing background Python audio download, tagging, and C++ DB insertion
│           ├── python/download_engine/
│           │   ├── __init__.py                      # Python package initialization marker
│           │   ├── core.py                          # Core download orchestrator wrapping yt-dlp with FFmpeg audio extraction
│           │   ├── lyrics.py                        # Synchronized LRC lyrics scraper querying multi-provider lyrics endpoints
│           │   ├── metadata.py                      # Mutagen audio tagger injecting ID3v2.4 and Vorbis comment tags and cover art
│           │   ├── search.py                        # Python search backend with music-only heuristic filtering and duration validation
│           │   └── spotify.py                       # Spotify web metadata resolver and playlist track parser
│           └── res/
│               ├── drawable/                        # UI vector drawables, custom icons, and promo graphics
│               ├── mipmap-*/                        # Adaptive application launcher icons for various device screen densities
│               └── values/
│                   ├── fonts_certs.xml              # Google Fonts downloadable font provider certificate hashes
│                   └── themes.xml                   # Android base window themes and splash screen attributes
├── native/
│   ├── CMakeLists.txt                               # Native CMake build script linking C++17, SQLite3, KissFFT, and ARM NEON flags
│   ├── dsp/
│   │   └── kissfft/                                 # Embedded KissFFT library providing fast, lightweight Fast Fourier Transforms
│   │       ├── kiss_fft.c                           # Core complex FFT routine implementation
│   │       ├── kiss_fft.h                           # Header definitions for KissFFT data structures and prototypes
│   │       ├── kiss_fftr.c                          # Real-valued input FFT optimization routine
│   │       ├── kiss_fftr.h                          # Header definitions for real FFT routines
│   │       └── _kiss_fft_guts.h                     # Internal macros, trigonometric tables, and fixed/floating point math helpers
│   ├── engine/
│   │   ├── EventTracker.cc                          # User interaction tracker logging playback duration, skips, and track completions
│   │   ├── EventTracker.h                           # Header definitions for EventTracker
│   │   ├── RecommendEngine.cc                       # AI recommendation engine combining vector distance, BPM match, and key compatibility
│   │   ├── RecommendEngine.h                        # Header definitions for RecommendEngine
│   │   ├── StreamifyDB.cc                           # SQLite3 persistence layer with WAL mode, mutex synchronization, and transactional queries
│   │   ├── StreamifyDB.h                            # Header definitions for StreamifyDB
│   │   ├── TaskOrchestrator.cc                      # C++ resource-aware background task scheduler with cooperative yielding
│   │   ├── TaskOrchestrator.h                       # Header definitions for TaskOrchestrator
│   │   ├── VectorStore.cc                           # High-dimensional vector index accelerated by 128-bit ARM NEON SIMD cosine similarity
│   │   └── VectorStore.h                            # Header definitions for VectorStore
│   ├── ingest/
│   │   ├── AudioPipeline.cc                         # Signal processing pipeline: KissFFT STFT, spectral flux onset BPM extraction, Chromagram key detection
│   │   ├── AudioPipeline.h                          # Header definitions for AudioPipeline
│   │   └── miniaudio.h                              # Single-file audio decoding and playback header library
│   ├── jni/
│   │   └── jni_bridge.cc                            # JNI boundary linking Java/Kotlin NativeBridge native functions to C++ native classes
│   └── third_party/
│       ├── onnxruntime/include/
│       │   └── onnxruntime_cxx_api.h                # C++ header definitions for the ONNX Runtime neural inference engine
│       └── sqlite3/
│           ├── sqlite3.c                            # Amalgamated C source code for the embedded SQLite3 database engine
│           └── sqlite3.h                            # Header definitions for SQLite3
├── supabase/
│   └── schema.sql                                   # Cloud PostgreSQL database schema for Supabase (Users, Playlists, Tracks, Telemetry)
├── implementation_v4.md                             # Architectural specification, mathematical models, and engineering documentation for v4.0
└── tasks_v4.md                                      # Comprehensive engineering roadmap, QA checklist, and component status tracking
```

---

## 🧠 Signal Processing & AI Vector Engine

Streamify runs all acoustic feature extraction and similarity ranking completely on-device without external server dependencies.

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                      NATIVE C++ DSP & AI INGESTION PIPELINE                     │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│   Audio File (MP3/FLAC/M4A)                                                     │
│         │                                                                       │
│         ▼ [miniaudio Decoder]                                                   │
│   Raw PCM Buffer (44.1kHz / Mono / Float32)                                     │
│         │                                                                       │
│         ├────────► [KissFFT STFT Analysis (Real-FFT, Hanning Window)]           │
│         │                │                                                      │
│         │                ├────────► Spectral Flux Computation                   │
│         │                │                │                                     │
│         │                │                ▼                                     │
│         │                │          Onset Detection Curve                       │
│         │                │                │                                     │
│         │                │                ▼ [Autocorrelation + Octave Correct]  │
│         │                │          🎯 Accurate Track BPM (60 - 200 BPM)        │
│         │                │                                                      │
│         │                └────────► 12-Bin Chromagram Analysis                  │
│         │                                 │                                     │
│         │                                 ▼ [Krumhansl-Schmuckler Matching]     │
│         │                           🎼 Harmonic Key (e.g. "C Major", "A Minor") │
│         │                                                                       │
│         └────────► [512-Dimensional Acoustic Feature Embeddings]                │
│                          │                                                      │
│                          ▼ [ARM NEON 128-bit SIMD Dot Product]                  │
│                     VectorStore Cosine Similarity Index (< 1ms)                 │
│                          │                                                      │
│                          ▼ [Hybrid Multi-Factor Scoring]                        │
│                     ✨ Personalized "Made For You" Recommendations             │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Recommendation Scoring Formula
$$S(t) = \alpha \cdot \cos(\vec{v}_{user}, \vec{v}_{track}) + \beta \cdot \text{KeyMatch}(K_u, K_t) + \gamma \cdot \text{BpmProximity}(B_u, B_t) + \delta \cdot \text{Affinity}(A_t)$$

*   $\cos(\vec{v}_{user}, \vec{v}_{track})$: 512-dim acoustic vector similarity computed via ARM NEON SIMD.
*   $\text{KeyMatch}(K_u, K_t)$: Camelot wheel harmonic compatibility score ($1.0$ for exact, $0.8$ for relative major/minor, $0.5$ for dominant/subdominant).
*   $\text{BpmProximity}(B_u, B_t)$: Gaussian tempo distance curve: $\exp\left(-\frac{(B_u - B_t)^2}{2\sigma^2}\right)$.
*   $\text{Affinity}(A_t)$: User listening frequency, completion rate, and explicit Like weighting.

---

## 💾 5-Tier Zero-Bloat Caching Subsystem

To ensure smooth 60fps scrolling and instant playback with minimal memory overhead, Streamify employs a five-tier caching model:

1.  **Audio Stream Chunk Cache (`AudioCacheManager.kt`)**: Segmented LRU disk cache for remote streaming chunks, eliminating redundant network requests.
2.  **Synced Lyrics Cache (`LyricsCacheManager.kt`)**: Local disk store saving synchronized `.lrc` text files, enabling offline karaoke viewing.
3.  **Cover Art Image Cache (Coil)**: Dual-layer memory LRU cache and disk cache for high-resolution album artwork.
4.  **Streaming URL Cache**: In-memory short-lived LRU cache preventing repeated YouTube Music stream resolution queries.
5.  **SQLite RAM Cache & WAL**: High-speed page caching via SQLite WAL (Write-Ahead Logging) mode and memory-mapped I/O (`PRAGMA mmap_size = 268435456`).

---

## ☁️ Cloud Infrastructure & Security

*   **Supabase Cloud Sync**: PostgreSQL schema (`supabase/schema.sql`) supporting real-time cloud backup of playlists, favorites, and cross-device listening history.
*   **Google 1-Tap OAuth**: Seamless authentication with secure token exchange.
*   **Deterministic Keystore**: Bundled `app/debug.keystore` guarantees deterministic SHA-1 / SHA-256 fingerprint generation for Google OAuth across development and CI/CD environments.

---

## 🛠️ Build, Setup & CI/CD Pipeline

### Prerequisites
*   **Android Studio** (Koala / Ladybug or newer recommended)
*   **Android SDK & NDK** (`ndk;26.1.10909125` and `cmake;3.22.1`)
*   **JDK 17** (Temurin / OpenJDK 17)
*   **Python 3.11** (Required by Chaquopy Gradle plugin)

### Local Build Instructions

```bash
# Clone the repository
git clone git@github.com:zephyr4289/streamify-apk.git
cd streamify-apk

# Make Gradle wrapper executable
chmod +x gradlew

# Perform a clean build of the Debug APK
./gradlew clean assembleDebug --no-build-cache
```

The compiled APK will be generated at: `app/build/outputs/apk/debug/app-debug.apk`.

### Automated GitHub Actions CI/CD
Every commit pushed to `main` triggers `.github/workflows/build.yml`:
1.  Sets up JDK 17, Python 3.11, Android SDK, and NDK.
2.  Compiles the C++ core and builds the APK.
3.  On failure, **automatically extracts compiler error logs and commits them to the `build-logs` branch** (see [build_log.md](file:///data/data/com.termux/files/home/streamify-apk/build_log.md) for details).
4.  On success, publishes the APK as a GitHub Release artifact.

---

## 📜 License

Distributed under the **MIT License**. See `LICENSE` for more information.

Copyright © 2026 **zephyr4289**. All rights reserved.
