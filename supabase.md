# 🎧 Streamify Flagship Supabase Architecture & Schema Reference

Streamify uses **Supabase (PostgreSQL 15 + `pgvector` + WebSockets Realtime + Row Level Security)** for its global cloud synchronization, social listening rooms, acoustic similarity search, and admin command center.

---

## 📑 Table of Contents
1. [Core Extensions & Architecture](#1-core-extensions--architecture)
2. [Complete Database Schema](#2-complete-database-schema)
   - [profiles (User Profiles & Roles)](#profiles-user-profiles--roles)
   - [tracks (Cloud Audio & 512-dim Vector Catalog)](#tracks-cloud-audio--512-dim-vector-catalog)
   - [user_likes (Two-Way Cloud Likes Sync)](#user_likes-two-way-cloud-likes-sync)
   - [playlists & playlist_tracks (Collaborative Playlists)](#playlists--playlist_tracks-collaborative-playlists)
   - [listening_sessions (Realtime Jam Listening Rooms)](#listening_sessions-realtime-jam-listening-rooms)
   - [track_comments (Live Timestamped Song Reactions)](#track_comments-live-timestamped-song-reactions)
   - [user_listening_history (Streamify Wrapped & Stats)](#user_listening_history-streamify-wrapped--stats)
   - [friend_connections (Presence & Social Connections)](#friend_connections-presence--social-connections)
   - [admin_broadcasts (Live System Announcements)](#admin_broadcasts-live-system-announcements)
3. [Vector Indexing & AI Similarity Search (`pgvector`)](#3-vector-indexing--ai-similarity-search-pgvector)
4. [Stored Procedures & RPC Functions](#4-stored-procedures--rpc-functions)
5. [Row Level Security (RLS) Policies](#5-row-level-security-rls-policies)
6. [Auth Triggers & Automatic Admin Provisioning](#6-auth-triggers--automatic-admin-provisioning)

---

## 1. Core Extensions & Architecture

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
```

* **`vector` (`pgvector`)**: Stores 512-dimensional acoustic embedding vectors computed on-device (KissFFT spectral flux + chromagram) or from audio neural models. Enables sub-5ms cosine similarity nearest-neighbor lookup across millions of songs.
* **`uuid-ossp`**: Generates cryptographically secure V4 UUIDs for playlists, rooms, comments, and history entries.

---

## 2. Complete Database Schema

### `profiles` (User Profiles & Roles)
Stores public user profile data, stats, and administrative privileges.

| Column | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | `PRIMARY KEY, REFERENCES auth.users(id) ON DELETE CASCADE` | Matches the Supabase Auth user ID. |
| `email` | `TEXT` | `UNIQUE NOT NULL` | Google OAuth or verified email address. |
| `display_name` | `TEXT` | - | User's public username or Google full name. |
| `avatar_url` | `TEXT` | - | Profile picture CDN URL. |
| `bio` | `TEXT` | `DEFAULT 'Music lover on Streamify 🎧'` | Short user bio. |
| `is_admin` | `BOOLEAN` | `DEFAULT FALSE` | Grants access to the Admin Portal & Command Center (`sireenyadav@gmail.com` is auto-granted). |
| `total_plays` | `INT` | `DEFAULT 0` | Lifetime stream count. |
| `listening_seconds` | `BIGINT` | `DEFAULT 0` | Lifetime playback time in seconds (for Streamify Wrapped). |
| `favorite_genre` | `TEXT` | `DEFAULT 'All'` | User's top listening genre. |
| `is_private` | `BOOLEAN` | `DEFAULT FALSE` | Hides friend listening presence when true. |
| `last_active_at` | `TIMESTAMPTZ` | `DEFAULT NOW()` | Used to calculate real-time 24h Daily Active Users (DAU). |
| `created_at` | `TIMESTAMPTZ` | `DEFAULT NOW()` | Account creation date. |

---

### `tracks` (Cloud Audio & 512-dim Vector Catalog)
Global metadata and vector registry for streamed and downloaded music.

| Column | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `TEXT` | `PRIMARY KEY` | YouTube video ID, ISRC code, or content hash. |
| `title` | `TEXT` | `NOT NULL` | Song title. |
| `artist` | `TEXT` | `NOT NULL` | Primary artist or contributing artist string. |
| `album` | `TEXT` | `DEFAULT 'Single'` | Album name. |
| `duration_sec` | `INT` | `DEFAULT 0` | Track duration in seconds. |
| `cover_url` | `TEXT` | - | 1400x1400 HD Retina cover artwork CDN URL. |
| `stream_url` | `TEXT` | - | High-bitrate audio streaming link. |
| `bpm` | `REAL` | `DEFAULT 120.0` | Exact extracted tempo from KissFFT autocorrelation. |
| `key_signature` | `TEXT` | `DEFAULT 'C'` | Musical key extracted from 12-bin Chromagram (e.g., `Ab Minor`). |
| `lyrics` | `TEXT` | - | Crowdsourced synchronized `.lrc` lyrics text. |
| `play_count` | `INT` | `DEFAULT 0` | Global platform play count. |
| `like_count` | `INT` | `DEFAULT 0` | Total user favorites. |
| `embedding` | `vector(512)` | - | 512-dimensional L2-normalized acoustic embedding. |
| `created_at` | `TIMESTAMPTZ` | `DEFAULT NOW()` | Ingestion timestamp. |

---

### `user_likes` (Two-Way Cloud Likes Sync)
Synchronizes liked tracks across all Android devices in real-time.

| Column | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `user_id` | `UUID` | `REFERENCES profiles(id) ON DELETE CASCADE` | User who favorited the song. |
| `track_id` | `TEXT` | `REFERENCES tracks(id) ON DELETE CASCADE` | Target song ID. |
| `created_at` | `TIMESTAMPTZ` | `DEFAULT NOW()` | Like timestamp. |
| **PRIMARY KEY** | `(user_id, track_id)` | Composite key | Ensures idempotent likes. |

---

### `playlists` & `playlist_tracks` (Collaborative Playlists)
Community and personal collaborative playlist system.

```
playlists
 ├── id (UUID, PK)
 ├── user_id (UUID, FK -> profiles.id)
 ├── name (TEXT)
 ├── description (TEXT)
 ├── cover_url (TEXT)
 ├── is_public (BOOLEAN)
 ├── is_collaborative (BOOLEAN)
 ├── collaborator_ids (UUID[])
 └── likes_count (INT)

playlist_tracks
 ├── id (UUID, PK)
 ├── playlist_id (UUID, FK -> playlists.id)
 ├── track_id (TEXT, FK -> tracks.id)
 ├── position (INT)
 ├── added_by (UUID, FK -> profiles.id)
 └── added_at (TIMESTAMPTZ)
```

---

### `listening_sessions` (Realtime Jam Listening Rooms)
Powers synchronized listening rooms (Spotify Jam clone) with sub-50ms clock drift correction over WebSockets.

| Column | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | `PRIMARY KEY DEFAULT uuid_generate_v4()` | Unique room UUID. |
| `host_user_id` | `UUID` | `REFERENCES profiles(id) ON DELETE CASCADE` | Room creator and session master. |
| `session_code` | `TEXT` | `UNIQUE NOT NULL` | 6-character room PIN (e.g. `JAM78X`). |
| `current_track_id` | `TEXT` | - | Active playing track ID. |
| `current_track_json` | `JSONB` | - | Full track snapshot (title, artist, cover URL, duration). |
| `position_ms` | `BIGINT` | `DEFAULT 0` | Playback position in milliseconds. |
| `is_playing` | `BOOLEAN` | `DEFAULT FALSE` | Play / Pause state of the room. |
| `host_clock_timestamp` | `BIGINT` | `DEFAULT 0` | System clock epoch timestamp for drift calculations. |
| `queue_json` | `JSONB` | `DEFAULT '[]'::jsonb` | Reorderable shared song queue. |
| `participant_ids` | `UUID[]` | `DEFAULT '{}'` | Array of connected listener user IDs. |
| `updated_at` | `TIMESTAMPTZ` | `DEFAULT NOW()` | Heartbeat timestamp. |

---

### `track_comments` (Live Timestamped Song Reactions)
Enables SoundCloud / YouTube-style comments anchored to precise audio playback millisecond offsets.

| Column | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | `PRIMARY KEY DEFAULT uuid_generate_v4()` | Comment ID. |
| `track_id` | `TEXT` | `REFERENCES tracks(id) ON DELETE CASCADE` | Target song ID. |
| `user_id` | `UUID` | `REFERENCES profiles(id) ON DELETE CASCADE` | Author user ID. |
| `user_name` | `TEXT` | `NOT NULL` | Author display name. |
| `user_avatar` | `TEXT` | - | Author avatar URL. |
| `timestamp_ms` | `BIGINT` | `NOT NULL` | Exact position in audio track (e.g. `45230` for 0:45). |
| `comment_text` | `TEXT` | `NOT NULL` | Reaction or comment string. |
| `likes_count` | `INT` | `DEFAULT 0` | Upvote count. |
| `created_at` | `TIMESTAMPTZ` | `DEFAULT NOW()` | Creation timestamp. |

---

### `user_listening_history` (Streamify Wrapped & Stats)
Granular stream logging for personal annual recaps and top genres.

| Column | Type | Description |
| :--- | :--- | :--- |
| `id` | `UUID` | Unique record ID. |
| `user_id` | `UUID` | Listener user ID. |
| `track_id` | `TEXT` | Track streamed. |
| `listened_duration_sec` | `INT` | Duration listened before skip or completion. |
| `created_at` | `TIMESTAMPTZ` | Play timestamp. |

---

### `admin_broadcasts` (Live System Announcements)
Push notification announcements displayed on the top of the mobile home screen.

| Column | Type | Description |
| :--- | :--- | :--- |
| `id` | `UUID` | Announcement ID. |
| `message` | `TEXT` | Banner text (e.g., *"🚀 New AI Radio features deployed!"*). |
| `author_email` | `TEXT` | Admin who broadcasted the message. |
| `is_active` | `BOOLEAN` | Controls whether the banner is visible in the client. |
| `created_at` | `TIMESTAMPTZ` | Broadcast timestamp. |

---

## 3. Vector Indexing & AI Similarity Search (`pgvector`)

### HNSW Cosine Index
```sql
CREATE INDEX IF NOT EXISTS idx_tracks_embedding_hnsw 
ON public.tracks USING hnsw (embedding vector_cosine_ops);
```
Hierarchical Navigable Small World (HNSW) indexing allows sub-5ms multi-dimensional cosine distance searches ($1 - \text{cosine\_distance}$) across millions of high-dimensional vectors.

### `match_tracks` RPC Function
```sql
CREATE OR REPLACE FUNCTION public.match_tracks (
    query_embedding vector(512),
    match_threshold float DEFAULT 0.20,
    match_count int DEFAULT 20
)
RETURNS TABLE (
    id TEXT, title TEXT, artist TEXT, album TEXT, duration_sec INT,
    cover_url TEXT, stream_url TEXT, bpm REAL, key_signature TEXT, similarity float
)
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN QUERY
    SELECT
        tracks.id, tracks.title, tracks.artist, tracks.album, tracks.duration_sec,
        tracks.cover_url, tracks.stream_url, tracks.bpm, tracks.key_signature,
        1 - (tracks.embedding <=> query_embedding) AS similarity
    FROM public.tracks
    WHERE tracks.embedding IS NOT NULL
      AND 1 - (tracks.embedding <=> query_embedding) > match_threshold
    ORDER BY tracks.embedding <=> query_embedding
    LIMIT match_count;
END;
$$;
```

---

## 4. Stored Procedures & Admin RPC Functions

### 1. `get_admin_dashboard_stats()`
Aggregates live database metrics without hardcoded numbers.
```json
{
  "total_users": 1420,
  "total_tracks": 8540,
  "total_playlists": 320,
  "active_jam_sessions": 14,
  "total_comments": 4120,
  "total_likes": 19400,
  "total_plays": 84520,
  "dau_24h": 482,
  "server_status": "Operational",
  "engine_mode": "PostgreSQL 15 + pgvector 0.5.1"
}
```

### 2. `set_user_admin_role(target_user_id, new_admin_status)`
Promotes or demotes user profiles to/from `is_admin = true`.

### 3. `terminate_jam_session(target_session_id)`
Force-terminates stuck or abandoned listening rooms.

### 4. `delete_comment_admin(target_comment_id)`
Moderates and removes toxic comments from the platform.

### 5. `toggle_admin_broadcast(target_broadcast_id, active_state)`
Enables or disables active system announcements.

### 6. `get_admin_jam_sessions()` & `get_admin_recent_comments(limit_count)`
Feeds real-time monitoring and moderation dashboards in the Admin Portal.

---

## 5. Row Level Security (RLS) Policies

All tables enforce PostgreSQL RLS policies with strict admin bypass:

```sql
CREATE OR REPLACE FUNCTION public.is_admin()
RETURNS BOOLEAN AS $$
BEGIN
    RETURN (auth.jwt()->>'email' = 'sireenyadav@gmail.com') OR
           EXISTS (SELECT 1 FROM public.profiles WHERE id = auth.uid() AND is_admin = TRUE);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
```

* **Public Read**: Anyone can read public tracks, public playlists, live Jam sessions, timestamped comments, and active broadcasts.
* **Owner Write**: Authenticated users can insert/update their own likes, playlists, profiles, and comments.
* **Admin Full**: `sireenyadav@gmail.com` and users with `is_admin = TRUE` have unrestricted control across all tables.

---

## 6. Auth Triggers & Automatic Admin Provisioning

When a new user signs in via Google OAuth or Email Auth:

```sql
CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO public.profiles (id, email, display_name, avatar_url, is_admin)
    VALUES (
        NEW.id,
        NEW.email,
        COALESCE(NEW.raw_user_meta_data->>'full_name', NEW.raw_user_meta_data->>'name', split_part(NEW.email, '@', 1)),
        COALESCE(NEW.raw_user_meta_data->>'avatar_url', NEW.raw_user_meta_data->>'picture', ''),
        (NEW.email = 'sireenyadav@gmail.com')
    )
    ON CONFLICT (id) DO UPDATE
    SET email = EXCLUDED.email,
        is_admin = (EXCLUDED.email = 'sireenyadav@gmail.com'),
        last_active_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
```
Ensures `sireenyadav@gmail.com` is automatically granted `is_admin = TRUE` immediately on first login.

---

## 7. Project Chronos: Circadian Patterns & Musical Chronotype

### `public.user_listening_patterns`
Stores 4 circadian time slots per user:
* `MORNING` (06:00 – 11:00)
* `AFTERNOON` (11:00 – 17:00)
* `EVENING` (17:00 – 22:00)
* `NIGHT` (22:00 – 06:00)

Each slot maintains an exponential moving average (EMA) of `avg_bpm`, `preferred_keys`, `top_genres`, `slot_embedding vector(512)`, and `skip_ratio`.

### `public.user_musical_chronotype`
Generates personal musical personas (e.g. *"The Night Explorer 🦉 • Peak 11 PM • 124 BPM"*) based on weekly listening distributions.

---

## 8. Project Nexus: Contextual Intelligence & Co-occurrence Graph

### `public.user_device_context`
Tracks the active listening environment in real time:
* `audio_output_type`: `BLUETOOTH_CAR`, `HEADPHONES`, `SPEAKER`, `BLUETOOTH_GENERIC`.
* `battery_level` & `is_charging`: Triggers battery-saving AMOLED UI and background AI scheduling.
* `network_type`: `WIFI`, `CELLULAR`, `OFFLINE` for adaptive bitrate streaming.

### `public.track_hook_telemetry`
Captures micro-interaction psychometrics:
* `favorite_seek_timestamp_ms`: Detects the exact chorus or drop timestamp the user repeatedly scrubs to.
* `lyrics_dwell_seconds`: Measures focus on the synchronized karaoke lyrics tab.
* `volume_flare_count`: Identifies emotional resonance volume boost spikes.
* `satiation_score`: Implements burnout decay to prevent song fatigue.

### `public.track_cooccurrence_graph`
A behavioral graph mapping songs frequently played in the same 30-minute session:
```sql
CREATE TABLE public.track_cooccurrence_graph (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    track_a_id TEXT NOT NULL,
    track_b_id TEXT NOT NULL,
    cooccurrence_weight REAL DEFAULT 1.0,
    pair_count INT DEFAULT 1,
    last_paired_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(track_a_id, track_b_id)
);
```
Used by the next-track recommendation engine to discover seamless track transitions purely through listener behavior.
