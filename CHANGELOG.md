# Changelog

All notable changes to Streamify are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased] — Modular Architecture & Documentation Overhaul

Zero logic changes: a structural + documentation pass that carves the
monolith into layered, domain-scoped packages and documents every folder.

### Restructured (behavior-preserving package moves)
- `data/` root grab-bag (21 files) split into `repository/`, `persistence/`,
  `discovery/`, `ingestion/`, `telemetry/`, `lyrics/`, `native/`.
- `data/remote/` (16 files) split into `data/supabase/` (11), `data/spotify/`
  (3), `data/youtube/` (3), `data/update/`; `JamTrackCodec` moved to `jam/`.
- `service/` (25 files) reorganized into `media/` domains: `playback/`,
  `audio/`, `cache/`, `sync/`, `ingestion/`, `lyrics/`.
- 34 `Yt*` widgets grouped under `ui/components/yt/`.
- Radio strays (`ContinuumRadioEngine`, `UniversalCandidateBroker`) folded
  into `radio/`. `FuzzyTitleMatcherTest` moved to `data.discovery`.

### Kept stable (hard contracts honored)
- `data/NativeBridge` package unchanged (name-mangled JNI ABI).
- `data/models/`, `ui/models/` unchanged (ProGuard keep rules).
- No logic, behavior, resource, or manifest-permission changes; playback,
  sync, jam and UI behavior are byte-identical.

### CI
- JVM shard renamed `data-identity-fuzzy` -> `jvm-unit-suite` and now runs
  the **full** unit-test suite (no package filter), so reorganizations
  can't silently de-scope tests and new tests run automatically.

### Documentation
- 45 new folder-level READMEs (every Kotlin package + every repo directory).
- New guides: `docs/ARCHITECTURE.md`, `docs/CODEBASE_GUIDE.md`,
  `docs/ADD_A_FEATURE.md` (feature playbooks), refreshed
  `docs/CI-TESTING-GUIDE.md`.
- Root declutter: `goalarchitct.md` -> `docs/architecture/GOALS.md`,
  `MASTER-README.md` -> `docs/MASTER-STREAM-RESOLUTION.md`,
  `JAM-ENGINE.md` -> `docs/JAM-ENGINE.md`, historical logs -> `docs/history/`.

## [1.1.0] — Professional Polish & Performance Refactor

This release is a full-codebase quality pass: jank elimination, error-handling
discipline, dead-code removal and architectural decomposition. No user-facing
feature removals; the UI look-and-feel is preserved by design.

### Performance (UI)

- Every Compose lazy list now declares stable `key`s (21 previously keyless
  sites) — ending positional-reuse recomposition storms and enabling item
  animation and scroll restoration.
- Fixed a latent **duplicate-key crash**: Up Next / Play History can contain
  the same track twice; their keys are now index-qualified in QueueScreen and
  FullPlayerSheet.
- All always-on animations are now gated:
  - The branding badge shimmer is a one-shot sweep instead of a permanent
    frame loop on three primary screens.
  - Equalizer "now playing" indicators render a static silhouette while
    playback is paused (previously they animated forever due to a
    "current-track vs actually-playing" semantics bug), and disappear-free
    semantics on Queue/FullPlayer.
  - The pull-to-refresh spinner only animates while a refresh is actually in
    flight; the pull gesture itself is arc-progress only.
  - The Wrapped persona ring pulses a bounded number of times, then rests.
- The comments sheet no longer recomposes the entire player sheet five times
  per second: the 5 Hz position ticker is now collected inside the sheet's
  own recomposition scope.
- Album-art decode sizes are deterministic at small-cell call sites
  (TrackCard 150dp, TrackListItem 48dp) instead of relying on layout timing.
- The Jam session queue renders a bounded 8 rows collapsed with a
  "Show all N tracks" toggle instead of composing an unbounded distributed
  queue.
- Track-tap disk work (offline-vault probe, local-file existence check)
  moved off the main thread; playlist JSON parsing during pull-to-refresh
  moved to `Dispatchers.IO`.

### Robustness

- All 96 bare `printStackTrace()` sites across 36 files now route through
  the SLog facade (`SLog.st` with class + operation context) — failures are
  visible to the opt-in diagnostic spool, crash forensics and redaction
  pipeline.
- 33 of 37 unsafe `!!` assertions eliminated, including two genuinely
  dangerous ones: an unchecked OkHttp response body on the radio gzip path
  and a TOCTOU media-item dereference across a thread hop in the CDN
  renewal path. The remaining 4 are documented as guarded invariants.
- `CancellationException` is rethrown instead of swallowed in online-track
  resolution, restoring structured-cancellation correctness.
- The last raw `android.util.Log` call moved to SLog.

### Concurrency & Lifecycle

- SearchViewModel's speculative-prefetch scope is cancelled in `onCleared`
  (previously leaked SupervisorJob + IO threads per nav entry).
- PlaybackService CDN-token recovery runs on a service-lifecycle scope
  instead of a fresh unmanaged `CoroutineScope(...)` per player error.
- Explicit `kotlinx.coroutines.cancel` import added for the existing
  jam-scope cancellation call.

### Architecture

- **di/AppGraph** — single wiring point for the service-layer graph,
  owning the process-wide applicationScope, the init-order contract
  (SLog → fleet config → repositories → native async engines → services →
  authenticated resolution) and repository access. Application.onCreate is
  now app-level concerns only.
- **di/DispatcherProvider** — injectable dispatcher abstraction for
  deterministic testing.
- **TrackRepositoryApi** — interface over the track catalog; the four
  repository-consuming ViewModels depend on the abstraction while keeping
  their all-default constructors (reflective `viewModel()` wiring
  untouched).
- Single-writer rule for `TrackRepository.appContext` (owned by
  AppGraph.initialize).
- **SupabaseClient split**: the 2641-line god object is now a 518-line
  facade (session state, auth, HTTP/RPC plumbing, delegation stubs) over
  eight domain clients — Realtime, Stats, Tracks, Community, Jam, Admin,
  EdgeMesh, PlaylistSync — plus extracted wire models and the jam-track
  codec. Zero external call-site changes; 11 dead members deleted.
- **PlayerViewModel split**: 1816 lines of mixed concerns → ~1150 lines of
  cohesive playback control plus PlayerModels, PlayerTrackMedia (pure
  helpers), PlayerTrackLoader (loading pipeline), PlayerTicker (position
  and jam tickers) and PlayerHousekeeping (controller wiring, restore,
  lyric fetching) via same-package extensions. Public API unchanged.
- **FullPlayerSheet split**: the three self-contained landscape panes
  moved to FullPlayerSheetPanes.kt.

### Removed (dead code)

- `PlayerSeekBar` (unused legacy seekbar with the per-tick recomposition
  anti-pattern), `RecentPlayCard` and `PaletteExtractor` (zero call sites),
  `LatencyProbe` (never bound to the audio path; its estimate was
  permanently 0), 11 dead SupabaseClient members, 3 write-only
  PlayerViewModel state properties.

### Build

- versionName 1.1.0; lint safety net (`abortOnError = false`) so historical
  warnings inform instead of blocking release builds; official Kotlin code
  style; boot banner now reads `BuildConfig.VERSION_NAME` (was a hard-coded
  "1.0." prefix).

### Known issues (deliberately not actioned this release)

- Supabase credentials remain committed in `app/build.gradle.kts` per
  maintainer decision; rotation is strongly recommended before any public
  distribution.
- Release builds fall back to debug signing when no CI keystore is present.
- 128 silent/empty catch blocks remain (mostly intentional haptic/native
  cleanup fallbacks); the printStackTrace routing in this release covers
  the failure-visibility gap they represented.
- `runBlocking` in the NewPipe PO-token provider is retained: it mirrors
  upstream NewPipe's synchronous provider contract and blocks an IO-pool
  thread, not the main thread.
