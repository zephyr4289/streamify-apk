# 🛰️ Streamify Architecture & Engine Documentation Plan

This tracking checklist monitors the authoring of comprehensive, industrial-grade engineering documentation for all Streamify engines and subsystems in the `docs/` directory.

---

## 📌 Engine Documentation Progress

- [x] **Phase A: Stream Resolution, Media Routing & Downloader Engine** (`docs/01_STREAM_RESOLUTION_ENGINE.md`)
  - [x] A.1 InnerTube Architecture, Multi-Client Racing & Anti-Bot Bypass
  - [x] A.2 BotGuard VM, Hidden WebView & PO Token Generation Pipeline
  - [x] A.3 Perceptual Codec Scoring Matrix & Loudness Telemetry
  - [x] A.4 Multi-Tier Resolution Cascade (Tier 0 to Tier 5)
  - [x] A.5 Parallel Segmented Downloader & SHA-256 Integrity Verification
  - [x] A.6 Canonical Identity Gates & Resilient Media Routing

- [x] **Phase B: Digital Signal Processing (DSP), Loudness & Gapless Audio Engine** (`docs/02_DSP_ACOUSTIC_ENGINE.md`)
  - [x] B.1 EBU R128 Loudness Normalizer (K-Weighting, Gated Energy, Momentary/Short/Integrated)
  - [x] B.2 True-Peak Soft-Knee Limiter (Lookahead Buffer & THD Attenuation)
  - [x] B.3 Equal-Power Sine/Cosine Crossfader & Harmonic Key/BPM Matching
  - [x] B.4 KissFFT Spectral Processing & Sub-band Analysis
  - [x] B.5 Beat-Synchronized Acoustic Haptics Subsystem

- [ ] **Phase C: Phoneme Lyrics Engine, Dynamic Time Warping (DTW) & Canvas** (`docs/03_LYRICS_ALIGNMENT_ENGINE.md`)
  - [ ] C.1 Multiformat Parser (LRC, TTML, Syllable JSON, Kanji/Hanja Romanization)
  - [ ] C.2 Dynamic Time Warping (DTW) Audio-to-Phoneme Alignment
  - [ ] C.3 120 FPS Fluid Shaded Lyrics Canvas & Kinematic Scrolling

- [ ] **Phase D: Neural ML, CLAP ONNX Embeddings & Continuum Radio Engine** (`docs/04_NEURAL_CONTINUUM_ENGINE.md`)
  - [ ] D.1 ONNX Runtime & CLAP 512-D Acoustic Feature Extraction
  - [ ] D.2 Multi-Order Markov Transition Chains & Skip Penalty Attenuation
  - [ ] D.3 Anti-Drift Vector Anchor Mathematics & Continuous Radio
  - [ ] D.4 NeuroQueue Dynamic Scoring & Real-Time Queue Optimization

- [ ] **Phase E: Decentralized Mesh, P2P AirDrop & LAN Clock Sync Engine** (`docs/05_MESH_AIRDROP_ENGINE.md`)
  - [ ] E.1 Precision Time Protocol (IEEE 1588 Microsecond UDP PTP)
  - [ ] E.2 AirDrop Physics & BLE/mDNS P2P Discovery
  - [ ] E.3 Mesh Consensus & Swarm Chunk Distribution

- [ ] **Phase F: Jam Engine (Decentralized Multi-Device Realtime Sync)** (`docs/06_JAM_DISTRIBUTED_ENGINE.md`)
  - [ ] F.1 Skew-Free Cristian Clock Synchronization (Monotonic Domain)
  - [ ] F.2 2048-Slot Sequence Ring & Linear Gap Repair Matrix
  - [ ] F.3 Kalman Phase-Locked Loop (PLL) & Pitch Anti-Windup
  - [ ] F.4 Operation-based CmRDT Queue & Midpoint Fractional Indexing
  - [ ] F.5 SQLite WAL Mutation Outbox Journal
  - [ ] F.6 TTL Lease, A+B Hybrid Succession & The Death Pivot

- [ ] **Phase G: Native Database, Canonical CAD-ID & Smart Offline Vault** (`docs/07_DATABASE_STORAGE_ENGINE.md`)
  - [ ] G.1 Canonical CAD-ID FNV-1a 64-Bit Normalization & Hashing
  - [ ] G.2 Native SQLite Engine (C++ Zero-GC Memory Mapped I/O)
  - [ ] G.3 Smart Offline Vault & LRU Segmented Storage
  - [ ] G.4 ID3v2/FLAC/MP4 Native Metadata Tagging & Serialization
  - [ ] G.5 Disaster Recovery, Backup Journaling & Nuclear Reset

- [ ] **Phase H: Ingestion, Multi-Platform Importers & Web Scrapers** (`docs/08_INGESTION_SCRAPER_ENGINE.md`)
  - [ ] H.1 Multi-Platform Playlist Parsers (Spotify, YouTube, Apple Music, CSV, M3U8)
  - [ ] H.2 Tokenless Web Ingestion & HTML Scraper Pipeline
  - [ ] H.3 High-Throughput Batch Track Reconciliation & Fuzzy Matching

- [ ] **Phase I: Multi-Tier Search, Trigram Index & Canonical Graph** (`docs/09_SEARCH_GRAPH_ENGINE.md`)
  - [ ] I.1 Trigram Inverted Index & Phonetic Soundex Matching
  - [ ] I.2 Multi-Hop Collaborative Artist & Discovery Graph
  - [ ] I.3 Federated Multi-Source Search Aggregator

- [ ] **Phase J: Chronos Telemetry, Profiling & Wrapped Analytics** (`docs/10_TELEMETRY_PROFILER_ENGINE.md`)
  - [ ] J.1 Lockless Microsecond Chronos Native Profiler
  - [ ] J.2 Local-First Listening Telemetry & Statistical Modeling
  - [ ] J.3 Native Priority Thread Pool & Work-Stealing Task Orchestrator

- [ ] **Phase K: Playback Lifecycle, Media3 Architecture & UI/UX System** (`docs/11_PLAYBACK_UI_ENGINE.md`)
  - [ ] K.1 Media3 / ExoPlayer Foreground Service Lifecycle & Focus Management
  - [ ] K.2 Dynamic GPU Mesh Gradient Shaders & Palette Extraction
  - [ ] K.3 Full-Screen Player Sheet, Kinematics & Compose Architecture

- [ ] **Phase L: Server-Side Infrastructure, Supabase Realtime & Security Model** (`docs/12_SERVER_SECURITY_ENGINE.md`)
  - [ ] L.1 Postgres Row-Level Security, Migrations & Fencing Tokens
  - [ ] L.2 Phoenix WebSocket Realtime Multiplexing
  - [ ] L.3 Cryptographic Identity, JWTs & Tamper Verification
