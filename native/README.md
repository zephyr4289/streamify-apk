# `native/` — C++20 DSP & Physics Core

Compiled into `libstreamify_native_core.so` via CMake
(`app/build.gradle.kts → externalNativeBuild → ../native/CMakeLists.txt`).

## Layout

| Path | What it is |
|---|---|
| `dsp/` | KissFFT STFT, HPCP Camelot key detection, EBU R128 LUFS, Ellis BPM, soft-knee true-peak limiter. |
| `dsp/kissfft/` | Vendored KissFFT. |
| `physics/` | 6-DOF RK4 fluid dynamics (AirDrop tokens), NEON vector store. |
| `storage/` | SQLite WAL layer, lock-free SPSC telemetry buffer, SHA-256 proof-of-compute. |
| `ingest/` | Native ingest helpers (+ vendored miniaudio). |
| `jni/` | `jni_bridge.cc` — name-mangled JNI entry points (`Java_com_streamify_app_data_NativeBridge_*`). |

⚠️ JNI symbols are name-mangled to
`com.streamify.app.data.NativeBridge` — that Kotlin class's package
is an ABI contract.

Tests run in CI as the `native-dsp`, `native-simd-physics`, and
`native-telemetry-storage` sanitizer shards.
