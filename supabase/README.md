# `supabase/` — Backend Schema

The database contract consumed by `app/.../data/supabase/`.

| Path | What it is |
|---|---|
| `schema.sql` | Base schema. |
| `migrations/2026_01_stats_overhaul.sql` | Stats tables for `YtStatsTelemetryEngine`. |
| `sql/jam_lease.sql` / `sql/jam_rpc.sql` | Jam lease + RPC functions for `SupabaseJamClient`. |
| `supabase.md` | Ops notes. |

Client ↔ schema changes must land in the same PR.
