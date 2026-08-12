# Streamify APK 🎧📱

The official Android mobile client for Streamify. An ultra-high-performance, fully native Spotify-like music player and downloader built with **Jetpack Compose** and **Kotlin**, backed by a powerful **C++ JNI AI Recommendation Engine** and embedded **Python (Chaquopy)** audio ingestion pipelines.

Streamify APK delivers a pixel-perfect, hyper-responsive "Spotify-tier" native UI, completely offline music library management, real-time audio extraction, and localized AI-powered song recommendations — all running entirely on your mobile device.

---

## 🚀 Key Features

* **🎨 Peak Native Android UI (Jetpack Compose)**:
  * **Pixel-Perfect Aesthetics**: Built with an authentic Spotify dark palette (`#121212` backgrounds, `#1DB954` accents) and highly polished modern typography (Montserrat/Poppins).
  * **Immersive Player Sheet**: A beautiful bottom-sheet player with album art crossfading, synchronized scrolling LRC lyrics, and interactive seekbars.
  * **Dynamic Refreshing**: Reactive state flows ensure that when downloads finish in the background, your Home and Library screens update instantly without reloading.
* **🧠 Embedded C++ AI Recommendation Engine (JNI)**:
  * **Native Core**: Bypasses Java garbage collection by utilizing a high-performance C++ backend for the database (SQLite3) and AI processing.
  * **On-Device Embedding Generation**: When a track is downloaded, the C++ AVX/ONNX pipeline analyzes the acoustic features to generate a 512-dimensional vector.
  * **Real-time Discovery**: Uses C++ SIMD cosine similarity to instantly suggest "Made For You" tracks based on your listening history, skip/play events, and musical taste.
* **📥 Real-Time Music Ingestion (Python via Chaquopy)**:
  * **Embedded yt-dlp**: Search YouTube directly from the app and queue tracks for downloading.
  * **Background Processing**: Powered by Android `WorkManager`, background threads execute Python scripts (`download_engine`) to download tracks, extract MP3s via FFmpeg, inject ID3 tags, and generate mock `.lrc` lyrics.
  * **Live Tracking**: Monitor all active downloads via the Downloads tab with real-time speed and progress indicators.
* **🎵 Advanced Offline Playback**:
  * **Media3 / ExoPlayer Integration**: Built on top of AndroidX Media3 for robust background playback, lock-screen controls, and accurate media seeking.
  * **Zero-Latency Interactions**: Liked songs, skipping, and queue management execute instantaneously.

---

## 🏗️ Architecture Overview

The app combines three programming languages for maximum efficiency:

1. **Kotlin (UI & Android Framework)**: Jetpack Compose for the declarative UI, Coroutines/Flows for state management, and WorkManager for background tasks.
2. **C++ (Database & AI Engine)**: JNI bindings (`NativeBridge.kt`) connect to the highly optimized C++ core for vector math, AI embeddings, and fast SQLite transactions.
3. **Python (Scraping & Metadata)**: Chaquopy runs `yt-dlp` and `mutagen` securely inside the Android sandbox to handle complex extraction logic.

---

## 📂 Project Directory Structure

```text
streamify-apk/
├── app/src/main/
│   ├── java/com/streamify/app/
│   │   ├── ui/                 # Jetpack Compose Screens, Components, and Theme
│   │   ├── viewmodel/          # StateFlow ViewModels (Player, Home, Search, Ingestion)
│   │   ├── data/               # NativeBridge.kt JNI bindings and Models
│   │   ├── worker/             # CoroutineWorker for Background Downloads
│   │   └── service/            # Media3 PlaybackService
│   ├── python/download_engine/ # Chaquopy Python Scripts (core.py, metadata.py, search.py)
│   └── cpp/ (or native/)       # C++ Core, SQLite, and AI Engine sources
├── .github/workflows/          # CI/CD GitHub Actions pipeline for automated APK builds
└── build.gradle.kts            # Gradle configuration (Chaquopy, Compose, Media3)
```

---

## 🛠️ Build & Installation Instructions

### Prerequisites
* **Android Studio** (Koala or newer recommended)
* **JDK 17**
* **Android NDK** (Required for compiling the C++ core)
* **Python** (Required for Chaquopy Gradle plugin)

### Setup & Compilation

```bash
# Clone the repository
git clone git@github.com:zephyr4289/streamify-apk.git
cd streamify-apk

# Build the Debug APK locally
./gradlew assembleDebug
```

Alternatively, open the project in Android Studio and click the **Run** button to deploy directly to your device or emulator.

---

## 📜 License

MIT License © 2026 zephyr4289
