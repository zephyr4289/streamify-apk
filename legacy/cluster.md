# 🌐 NAS Home Cluster Architecture Specification (`cluster.md`)

> **IMPORTANT FOR AI ASSISTANTS & DEVELOPERS**: This document provides the complete, authoritative system architecture blueprint for the personal NAS media cluster. Read this file carefully before adding features, modifying code, or performing refactors.

---

## 🏗️ Master Cluster Topology

The NAS cluster is a modular, high-performance, bare-metal microservices ecosystem engineered specifically for constrained hardware (such as an Intel Pentium dual-core NAS with 4–8 GB RAM running Linux/Termux).

```text
                               ┌─────────────────────────────────────────────────────────────┐
                               │                    NAS HOME CLUSTER SYSTEM                  │
                               │                (Pentium Dual-Core / 4-8 GB RAM)             │
                               │                                                             │
 ┌──────────────────────┐      │  ┌─────────────────────────┐       ┌─────────────────────┐  │
 │  Cluster Dashboard   ├─────►│  │   Streamify Spotify     │       │   music-procengine  │  │
 │  / Reverse Proxy     │      │  │   Audio Engine (8888)   │◄─────►│   AI Engine (8080)   │  │
 └──────────┬───────────┘      │  └─────────────────────────┘       └─────────────────────┘  │
            │                  │  ┌─────────────────────────┐                              │
            ├─────────────────►│  │   Setflix Cinema        │                              │
            │                  │  │   Video Engine (8000)   │                              │
            │                  │  └─────────────────────────┘                              │
            │                  │  ┌─────────────────────────┐                              │
            └─────────────────►│  │   Future Microservices  │                              │
                               │  │ (Audiobooks, Drive, etc)│                              │
                               │  └─────────────────────────┘                              │
                               └─────────────────────────────────────────────────────────────┘
```

---

## 🧩 Cluster Microservices & Endpoints

### 1. 🎧 Streamify (Spotify Audio Engine)
* **Directory**: `/data/data/com.termux/files/home/streamify`
* **GitLab Repo**: [`https://gitlab.com/sireenyadav/streamify.git`](https://gitlab.com/sireenyadav/streamify.git)
* **Port**: `http://localhost:8888`
* **Tech Stack**: Drogon C++17, Thread-Local SQLite3 WAL (`streamify.db`), Vanilla JS SPA UI.
* **Core Capabilities**:
  * **Zero-CPU Audio Streaming**: HTTP 206 Partial Content (Byte-Range requests).
  * **Multi-User Profile System**: 4-digit PIN authentication & persistent session memory in `localStorage`.
  * **On-Demand Downloader (`dload-engine`)**: Async background audio acquisition via `nice -n 19 yt-dlp` and `ffmpeg` (`POST /api/v1/download`).
  * **Service Discovery Endpoint**: `GET /api/v1/health`.

### 2. 🧠 `music-procengine` (Audio Intelligence Submodule)
* **Directory**: `/data/data/com.termux/files/home/streamify/music-procengine`
* **GitLab Submodule Repo**: [`https://gitlab.com/sireenyadav/music-procengine.git`](https://gitlab.com/sireenyadav/music-procengine.git)
* **Port**: `http://localhost:8080`
* **Tech Stack**: C++17, AVX2 SIMD with runtime CPU guards (`__builtin_cpu_supports`), 64-bin Log-Mel Spectrogram STFT via FFTW3, 512-D ONNX CLAP neural embeddings, Markov chain play/skip transition matrices.

### 3. 🍿 Setflix (Netflix Cinema Video Engine)
* **Directory**: `/data/data/com.termux/files/home/setflix`
* **GitLab Repo**: [`https://gitlab.com/sireenyadav/setflix.git`](https://gitlab.com/sireenyadav/setflix.git)
* **Port**: `http://localhost:8000`
* **Tech Stack**: Drogon C++17, Thread-Local SQLite3 WAL (`setflix.db`), Vanilla JS SPA UI with `HLS.js`.
* **Core Capabilities**:
  * **Zero-CPU Video Streaming**: Adaptive HLS multi-bitrate streaming (`.m3u8` playlists + `.ts` 4-second video chunks).
  * **TMDB API Integration**: Dynamic movie artwork and catalog metadata (`api_key=d1ec01112d46b8db5791a37112cb13b1`).
  * **In-Memory Watch State Buffer**: High-frequency 5s heartbeats (`POST /api/v1/watch/heartbeat`) buffered in memory to prevent HDD disk IOPS thrashing.
  * **Multi-User PIN Profiles**: Isolated Continue Watching progress bars and My List bookmarks.
  * **Service Discovery Endpoint**: `GET /api/v1/health`.

---

## 📜 Global AI Guidelines & Rules

1. **Hardware Constraints**: Target hardware is a dual-core NAS. All CPU-intensive tasks (down-sampling, transcoding) MUST run at low priority using `nice -n 19`.
2. **SIMD CPU Safety**: NEVER hardcode bare AVX2 intrinsics without runtime CPU detection (`__builtin_cpu_supports("avx2")`) to prevent `SIGILL` crashes on Pentium/Atom CPUs.
3. **IDOR Security**: ALWAYS extract and validate `user_id` server-side from Bearer session tokens (`Authorization: Bearer <token>`). NEVER trust client-supplied `user_id` parameters.
4. **Git Version Control**: `music-procengine` is a Git Submodule. Whenever committing changes inside `music-procengine`, also commit the updated submodule pointer in `streamify`.
