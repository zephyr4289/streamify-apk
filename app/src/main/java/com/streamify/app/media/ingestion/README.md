# `media/ingestion/` — Background Workers

WorkManager jobs that must survive process death.

## Files

| File | What it is |
|---|---|
| `IngestionWorker.kt` | Library ingest pipeline runner. |
| `LibrarySyncWorker.kt` | Periodic cloud sync of library deltas. |
| `TitanComputeWorker.kt` | Heavy vector/embedding compute offload (feeds EdgeMesh). |
| `TextEmbeddingEngine.kt` | ONNX embedding inference for semantic search. |

The foreground download worker (`worker/DownloadWorker.kt`) stayed in
`worker/` because it serves the UI download screen directly; these
four are engine-side maintenance jobs.
