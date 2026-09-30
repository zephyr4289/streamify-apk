# `native/` — C++20 DSP & Physics Core

Compiled into `libstreamify_native_core.so` via CMake
(`app/build.gradle.kts → externalNativeBuild → ../native/CMakeLists.txt`).

## Layout

| Path | What it is |
|---|---|
| `dsp/` | KissFFT STFT, HPCP Camelot key detection, EBU R128 LUFS, Ellis BPM, soft-knee true-peak limiter. **Jam core:** `AcousticPhaseResampler` — NEON windowed-sinc fractional resampler, ±500 PPM continuous rate trim, 32.32 fixed-point phase, zero-alloc hot path. |
| `dsp/kissfft/` | Vendored KissFFT. |
| `engine/` (jam core) | `PtpEngine` — IEEE-1588-style two-way sync, two-state Kalman (offset + drift) on `CLOCK_MONOTONIC_RAW`, RTT-exponentially-weighted measurement variance, wait-free seqlock readout. `HardwareLatencyProfiler` — write-head→air latency regression over `AudioTrack.getTimestamp()` anchors, MONOTONIC↔RAW bridging, per-route/codec priors. |
| `physics/` | 6-DOF RK4 fluid dynamics (AirDrop tokens), NEON vector store. |
| `storage/` | SQLite WAL layer, lock-free SPSC telemetry buffer, SHA-256 proof-of-compute. |
| `ingest/` | Native ingest helpers (+ vendored miniaudio). |
| `jni/` | `jni_bridge.cc` — name-mangled JNI entry points (`Java_com_streamify_app_data_NativeBridge_*`). **Jam core:** `jni_bridge_dsp.cc` — frozen-ABI DSP/clock/latency bridge (PTP clock, fractional resampler, playout-delay calibration) plus documented additive extensions. |

⚠️ JNI symbols are name-mangled to
`com.streamify.app.data.NativeBridge` — that Kotlin class's package
is an ABI contract.

Tests run in CI as the `native-dsp`, `native-simd-physics`, and
`native-telemetry-storage` sanitizer shards. The phase-lock core adds
`dsp_phase_lock_test_suite` (same flag), which also builds standalone
on any C++20 host — no NDK required:
