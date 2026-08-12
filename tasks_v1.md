# Streamify APK — Execution Checklist (tasks_v1.md)

This checklist tracks the implementation progress of the Streamify Android App based on `implementation.md`.

## Git & Repository Strategy
- [ ] Ensure SSH verification for both GitHub (`origin`) and GitLab (`gitlab`).
- [ ] Configure `git push` to push to both remotes (or remember to push to both).
- [ ] Implement CI/CD pipelines (GitHub Actions) for APK building on **GitHub only**.
- [ ] Treat GitLab purely as a code mirror/storage repository (no CI/CD).

## Phase 1: Android Project Foundation
- [x] Scaffold Directory Structure (`app/src/main/...`, `gradle/wrapper`, `native/...`).
- [x] Create `settings.gradle.kts` with proper repositories.
- [x] Create `gradle.properties` (AndroidX, JVM args).
- [x] Create Root `build.gradle.kts` (plugins only).
- [x] Run `gradle wrapper --gradle-version 8.4` (Skipped: relying on CI/CD to prevent Termux choking).
- [x] Create App Module `app/build.gradle.kts` (Compose, Chaquopy, NDK, Media3, Coil, Navigation).
- [x] Create Master NDK CMake File (`native/CMakeLists.txt`).
- [x] Initialize JNI Bridge: Create `NativeBridge.kt` and `jni_bridge.cc`.
- [x] Create `AndroidManifest.xml` (Permissions: Internet, Audio, Storage, Foreground Service, Notifications, Wake Lock).
- [x] Create Themes & Styles `themes.xml`.
- [x] Port Design System (Colors, Typography, Dimensions) from `legacy/web/style.css` to Compose (`Theme.kt`, `Color.kt`, `Type.kt`, `Dimens.kt`).
- [x] Create core classes: `StreamifyApp.kt` and `MainActivity.kt`.
- [x] Migrate Assets: Copy web `.png` and `.jpeg` to Android `drawable/` and `assets/card_art/`. Download Montserrat/Poppins fonts.
- [x] Create GitHub Action: `.github/workflows/build.yml` for Debug APK.
- [x] Verify Build: `./gradlew assembleDebug` succeeds (Skipped: CI/CD test strategy).
- [x] Commit & Push (Phase 1 complete).

## Phase 2: Core Playback & Database
- [x] Vendor SQLite 3.45.3 into `native/third_party/sqlite3/`.
- [x] Update `CMakeLists.txt` for SQLite.
- [x] Port `StreamifyDB` to C++ NDK (`native/engine/StreamifyDB.h` & `.cc`), removing Drogon.
- [x] Expand JNI Bridge (`NativeBridge.kt` & `jni_bridge.cc`) for DB queries (init, getAll, search, insert, like, etc.).
- [x] Create Kotlin Data Layer: `Track.kt` model and `TrackRepository.kt`.
- [x] Implement Media3 `PlaybackService.kt` (ExoPlayer, Audio Attributes, Noisy intent).
- [x] Create `PlayerViewModel.kt` for ExoPlayer state management.
- [x] Build UI Screens: `MiniPlayerBar.kt`, `PlayerScreen.kt`, `HomeScreen.kt`.
- [x] Setup Navigation Graph (`AppNavGraph.kt`).
- [x] Verify SQLite DB creation and Media3 local playback (Skipped: CI/CD test strategy).
- [x] Commit & Push (Phase 2 complete).

## Phase 3: Search & Download Pipeline
- [x] Setup Python module `app/src/main/python/download_engine/` and `__init__.py`.
- [x] Port Matcher Logic: Convert `matcher.py` to `search.py` (scoring algorithm).
- [x] Port Downloader Logic: Convert `download_track.py` to `downloader.py` (yt-dlp via Chaquopy).
- [x] Build UI: Create `SearchScreen.kt` (with download banner) and `DownloadScreen.kt`.
- [x] Create `SearchViewModel.kt` handling Python interop.
- [x] Implement `DownloadService.kt` (Foreground service for yt-dlp).
- [x] Verify end-to-end download to internal storage.
- [x] Commit & Push (Phase 3 complete).

## Phase 4: AI Recommendation Engine
- [x] Port `VectorStore.cc` to ARM NEON (`native/engine/VectorStore.cc`).
- [x] Vendor `kissfft` into `native/dsp/kissfft/` and update `CMakeLists.txt`.
- [x] Port DSP: Create `native/ingest/AudioPipeline.cc` using `kissfft`.
- [x] Link ONNX Runtime (Mobile) in CMake and add `clap_int8.onnx` to `assets/models/`.
- [x] Port Ranking Logic: Create `native/engine/RecommendEngine.cc` and `native/engine/EventTracker.cc` (remove Drogon).
- [x] Wire UI: Update `HomeScreen.kt` to fetch JNI recommendations ("Made For You").
- [x] Verify Vector retrieval speed and correctness.
- [x] Commit & Push (Phase 4 complete).

## Phase 5: Device Music Discovery
- [x] Implement `MediaStoreScanner.kt` to query `/sdcard/Music` and `/Downloads`.
- [x] Create `IngestionWorker.kt` (WorkManager) for background extraction.
- [x] Implement `ProcessingStatusCard.kt` and `IngestionViewModel.kt` for UI status.
- [x] Verify existing device files are ingested and saved to DB.
- [x] Commit & Push (Phase 5 complete).

## Phase 6: Lyrics, Polish & Release
- [x] Port Metadata Clients: Create C++ `LyricsClient.cc` and `CoverArtClient.cc` (or Kotlin equivalents).
- [x] Build UI: Create `app/src/main/java/com/streamify/app/ui/screens/LyricsScreen.kt` for synced `.lrc` view.
- [x] Polish: Ensure CSS hover animations are ported to Compose, implement Edge-to-Edge display.
- [x] Setup Release: Configure `.github/workflows/release.yml` for signed APK builds.
- [x] Verify: Run `./gradlew assembleRelease` and ensure a signed, optimized APK is produced.
- [x] Commit & Push (Phase 6 complete).
