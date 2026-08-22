-- ═══════════════════════════════════════════════════════════════════════════
-- STREAMIFY STATS OVERHAUL MIGRATION (Wrapped v2 / Cross-Device Truth)
-- Apply in Supabase Dashboard → SQL Editor. Safe to re-run (idempotent).
--
-- Fixes:
--   C1  ingest sink targeted non-existent table      -> user_play_events
--   C2  absolute last-writer-wins aggregates          -> GREATEST() monotonic RPC
--   C3  per-track plays never left the device         -> user_track_plays + RPC
-- ═══════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. PER-TRACK PLAY COUNTS (cross-device Top Songs truth)
-- ─────────────────────────────────────────────────────────────────────────────
create table if not exists public.user_track_plays (
    id uuid primary key default uuid_generate_v4(),
    user_id uuid not null references public.profiles(id) on delete cascade,
    track_sig text not null,                    -- "title_artist" lowercase identity
    plays int not null default 0,
    listened_seconds bigint not null default 0,
    track_snapshot jsonb not null default '{}'::jsonb,
    last_played_at timestamptz default now(),
    created_at timestamptz default now(),
    unique(user_id, track_sig)
);

create index if not exists idx_utp_user_plays
    on public.user_track_plays(user_id, plays desc);

alter table public.user_track_plays enable row level security;

drop policy if exists "utp_select_own" on public.user_track_plays;
create policy "utp_select_own" on public.user_track_plays
    for select using (auth.uid() = user_id);
drop policy if exists "utp_insert_own" on public.user_track_plays;
create policy "utp_insert_own" on public.user_track_plays
    for insert with check (auth.uid() = user_id);
drop policy if exists "utp_update_own" on public.user_track_plays;
create policy "utp_update_own" on public.user_track_plays
    for update using (auth.uid() = user_id);

-- Atomic delta increment: concurrent devices can never lose plays.
create or replace function public.increment_user_track_play(
    p_track_sig text,
    p_plays_delta int,
    p_seconds_delta bigint,
    p_snapshot jsonb default '{}'::jsonb
) returns void
language sql
security invoker
set search_path = public
as $$
    insert into user_track_plays (user_id, track_sig, plays, listened_seconds, track_snapshot, last_played_at)
    values (auth.uid(), p_track_sig, greatest(p_plays_delta,0), greatest(p_seconds_delta,0), p_snapshot, now())
    on conflict (user_id, track_sig) do update set
        plays            = user_track_plays.plays + greatest(excluded.plays, 0),
        listened_seconds = user_track_plays.listened_seconds + greatest(excluded.listened_seconds, 0),
        track_snapshot   = case
                               when coalesce(excluded.track_snapshot,'{}'::jsonb) = '{}'::jsonb
                               then user_track_plays.track_snapshot
                               else excluded.track_snapshot
                           end,
        last_played_at   = now();
$$;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. MONOTONIC AGGREGATES (kills cross-device regression of hours/plays)
-- ─────────────────────────────────────────────────────────────────────────────
create or replace function public.upsert_user_telemetry(
    p_listening_seconds bigint,
    p_total_plays int,
    p_top_track text default ''
) returns void
language sql
security invoker
set search_path = public
as $$
    update profiles set
        listening_seconds = greatest(coalesce(listening_seconds,0), greatest(p_listening_seconds,0)),
        total_plays       = greatest(coalesce(total_plays,0),       greatest(p_total_plays,0)),
        top_track         = case when coalesce(p_top_track,'') = '' then top_track else p_top_track end,
        last_active_at    = now()
    where id = auth.uid();
$$;

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. RAW PLAY EVENTS (listening-clock radar / hour-of-day analytics)
--    No FK by design: streamed/local tracks may not exist in cloud tracks yet.
-- ─────────────────────────────────────────────────────────────────────────────
create table if not exists public.user_play_events (
    id bigint generated always as identity primary key,
    user_id uuid not null references public.profiles(id) on delete cascade,
    track_sig text not null,
    track_title text not null default '',
    track_artist text not null default '',
    duration_played_sec int not null default 0,
    completion_ratio real not null default 1.0,
    hour_of_day int not null default extract(hour from now()),
    created_at timestamptz default now()
);

create index if not exists idx_upe_user_time
    on public.user_play_events(user_id, created_at desc);

alter table public.user_play_events enable row level security;

drop policy if exists "upe_insert_own" on public.user_play_events;
create policy "upe_insert_own" on public.user_play_events
    for insert with check (auth.uid() = user_id);
drop policy if exists "upe_select_own" on public.user_play_events;
create policy "upe_select_own" on public.user_play_events
    for select using (auth.uid() = user_id);
