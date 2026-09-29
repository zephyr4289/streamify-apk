# `legacy/` — Archived Code (NOT BUILT)

Frozen historical artifacts. Nothing here is compiled by the app or
CI; kept for archaeology and diffing against old behavior.

| Path | What it was |
|---|---|
| `server/` + `music-procengine/` | The old C++ backend (controllers, vector search, ingest). |
| `web/` | Old web client. |
| `kaviraj-tool/` | Legacy tooling. |
| `schema.sql` / `cluster.md` | Pre-Supabase schemas. |
| `scripts/` | One-shot migration scripts. |

Do not add new code here. If you need to reference behavior, copy it
out and modernize it — `legacy/` must stay bit-frozen.
