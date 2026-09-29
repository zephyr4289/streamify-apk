# `rust/` — Rust I/O & Auth Engine

Compiled to `libstreamify_core_rs.so` via cargo-ndk, packaged into
the APK by `app/build.gradle.kts` before the Android build runs.

## Layout (`src/`)

| File | What it is |
|---|---|
| `resolver.rs` | Sliding 2-track JIT stream resolver (direct hit → ISRC → search). |
| `downloader.rs` | Zero-copy downloader primitives. |
| `jni_bridge.rs` | JNI surface (`Java_com_streamify_app_data_NativeBridge_*`). |
| `auth.rs` | Native SAPISIDHASH / PKCE crypto. |
| `ingest.rs` | Spotify library ingest acceleration. |
| `shelf.rs` | Virtual shelf hydration. |
| `ptp.rs` | Precision Time Protocol math. |
| `lyric.rs` | SLYR binary lyric pre-compiler. |
| `vector.rs` | 128-D SIMD vector store. |
| `tagger.rs` | Lofty metadata tagging. |
| `json.rs` | Zero-copy SIMD JSON. |

Tests run in CI as the `rust-core-engine` cargo shard. Panic safety:
`catch_unwind` isolates network failures — never let a panic cross
the FFI boundary.
