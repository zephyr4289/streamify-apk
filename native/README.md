# `native/` — C++20 DSP & Physics Core

Compiled into `libstreamify_native_core.so` via CMake
(`app/build.gradle.kts → externalNativeBuild → ../native/CMakeLists.txt`).

## Layout

| Path | What it is |
|---|---|
| `dsp/` | **Phase 1 (audiophile):** `LufsNormalizer` — full ITU-R BS.1770-4 (K-weighting at any rate via the De Man fit, 400 ms/75% block ladder, dual gating, ±12 dB EMA gain glide with per-sample one-pole — click-free by construction). `SoftKneeLimiter` — 4x-oversampled true-peak sidechain (two-stage halfband composite kernels), tanh soft-knee curve, slope-budgeted lookahead schedule (zero overshoot by construction), memoryless C1 ceiling guard. `ChannelOps` — constant-power balance (√2·sin/cos, unity center) + SIMD mono downmix M=(L+R)/√2. `MasterChain` — LUFS gain → mono/balance → true-peak limiter, silent-bypass for SINGLE_RENDER. KissFFT STFT, HPCP Camelot key detection, Ellis BPM. **Jam core:** `AcousticPhaseResampler` — NEON windowed-sinc fractional resampler, ±500 PPM continuous rate trim, 32.32 fixed-point phase, zero-alloc hot path, **silent PLL bypass (Gap #13)**. |
| `dsp/kissfft/` | Vendored KissFFT. |
| `mix/` | **Phase 1 (32-peer):** `AudioRingBuffer` — lock-free SPSC rings (power-of-two, cache-line-split indices, posix_memalign). `PeerMixPool` — 32-peer mixing pool: per-peer rings + gains, NEON FMA accumulation along the sample axis, C1 tanh saturation guard (±1.0 bound). |
| `math/` | **Phase 2 (harmonic math):** `CandidateHasher` — XXH3-style 64-bit candidate-ID hashing (v7-safe NEON 16-byte stripe kernels, bit-exact scalar twin on every ABI) + zero-heap dedup kernels: intersection bitmasks and A-first unique union with overlap flags, stack open-addressing key tables for O(nA+nB) expected time, exact brute fallback beyond 1024 distinct keys. `HarmonicTransitionEngine` — Camelot wheel key compatibility ladder (exact 1.0 / relative 0.95 / adjacent 0.90 / same-mode energy boosts 0.85·0.80 / diagonal 0.70 / two-step 0.60 / smoothstep clash falloff → 0.10) + continuous BPM scoring (octave folding 75↔150, ±8% window) combined via clamp(0.6·key + 0.4·bpm). `CircadianCurves` — C1-continuous circular raised-cosine daypart curves (energy/acousticness/valence) for the Daylist scheduler, zero steps at slot boundaries incl. the 24h wrap. |
| `engine/` (jam core) | `PtpEngine` — IEEE-1588-style two-way sync, two-state Kalman (offset + drift) on `CLOCK_MONOTONIC_RAW`, RTT-exponentially-weighted measurement variance, wait-free seqlock readout. `HardwareLatencyProfiler` — write-head→air latency regression over `AudioTrack.getTimestamp()` anchors, MONOTONIC↔RAW bridging, per-route/codec priors. |
| `physics/` | 6-DOF RK4 fluid dynamics (AirDrop tokens), NEON vector store. |
| `storage/` | SQLite WAL layer, lock-free SPSC telemetry buffer, SHA-256 proof-of-compute. |
| `ingest/` | Native ingest helpers (+ vendored miniaudio). |
| `jni/` | `jni_bridge.cc` — name-mangled JNI entry points (`Java_com_streamify_app_data_NativeBridge_*`). **Jam core:** `jni_bridge_dsp.cc` — frozen-ABI DSP/clock/latency bridge (PTP clock, fractional resampler, playout-delay calibration) plus documented additive extensions. **Phase 1:** `jni_bridge_native_dsp_engine.cc` — frozen ABI for `com.streamify.app.audio.NativeDspEngine` (setLufsTarget / setLimiterCeiling / setMonoDownmix / setBalance / setSilentBypass / processFloatPcm) + additive telemetry getters. `SharedInstances.h` — the single resampler/profiler pair shared by both bridges (one party-mode toggle reaches both engines). **Phase 2:** `jni_bridge_harmonic_engine.cc` — frozen ABI for `com.streamify.app.audio.NativeHarmonicEngine` (deduplicateCandidateHashes / calculateTransitionScore / getCircadianWeights) + additive hashCandidateId / keyTransitionScore / bpmTransitionScore. Plain-C exports for Rust FFI (`streamify_hash_candidate_*`, `streamify_candidate_*`, `streamify_*transition_score`, `streamify_circadian_weights`) carry default visibility under -fvisibility=hidden. |

⚠️ JNI symbols are name-mangled to
`com.streamify.app.data.NativeBridge` and
`com.streamify.app.audio.NativeDspEngine` — those Kotlin classes'
packages are an ABI contract.

Tests run in CI as the `native-dsp`, `native-simd-physics`, and
`native-telemetry-storage` sanitizer shards. The phase-lock core adds
`dsp_phase_lock_test_suite` (same flag), which also builds standalone
on any C++20 host — no NDK required:
