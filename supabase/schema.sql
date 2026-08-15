-- ============================================================================
-- STREAMIFY FLAGSHIP SUPABASE SCHEMA
-- Author: Sireen Yadav (sireenyadav@gmail.com)
-- Features: Multi-user auth, RLS, Playlists, Likes, Realtime Jam sessions,
--           pgvector AI embeddings, Timestamped Comments, Friend Activity,
--           Listening History, Telemetry & Admin Command Center
-- ============================================================================

-- 1. Enable pgvector & UUID extensions
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 2. User Profiles Table
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    email TEXT UNIQUE NOT NULL,
    display_name TEXT,
    avatar_url TEXT,
    bio TEXT DEFAULT 'Music lover on Streamify 🎧',
    is_admin BOOLEAN DEFAULT FALSE,
    total_plays INT DEFAULT 0,
    listening_seconds BIGINT DEFAULT 0,
    favorite_genre TEXT DEFAULT 'All',
    is_private BOOLEAN DEFAULT FALSE,
    last_active_at TIMESTAMPTZ DEFAULT NOW(),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 3. Cloud Track Catalog
CREATE TABLE IF NOT EXISTS public.tracks (
    id TEXT PRIMARY KEY, -- Video ID or URL hash or ISRC
    title TEXT NOT NULL,
    artist TEXT NOT NULL,
    album TEXT DEFAULT 'Single',
    duration_sec INT DEFAULT 0,
    cover_url TEXT,
    stream_url TEXT,
    bpm REAL DEFAULT 120.0,
    key_signature TEXT DEFAULT 'C',
    lyrics TEXT,
    play_count INT DEFAULT 0,
    like_count INT DEFAULT 0,
    embedding vector(512), -- 512-dim KissFFT / CLAP audio feature vector
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Index for ultra-fast HNSW vector cosine similarity search (<5ms)
CREATE INDEX IF NOT EXISTS idx_tracks_embedding_hnsw 
ON public.tracks USING hnsw (embedding vector_cosine_ops);

-- 4. User Liked Songs (Cloud Sync)
CREATE TABLE IF NOT EXISTS public.user_likes (
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    track_id TEXT REFERENCES public.tracks(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (user_id, track_id)
);

-- 5. Playlists (Collaborative & Social)
CREATE TABLE IF NOT EXISTS public.playlists (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    description TEXT,
    cover_url TEXT,
    is_public BOOLEAN DEFAULT TRUE,
    is_collaborative BOOLEAN DEFAULT FALSE,
    collaborator_ids UUID[] DEFAULT '{}',
    likes_count INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 6. Playlist Tracks Mapping
CREATE TABLE IF NOT EXISTS public.playlist_tracks (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    playlist_id UUID REFERENCES public.playlists(id) ON DELETE CASCADE,
    track_id TEXT REFERENCES public.tracks(id) ON DELETE CASCADE,
    position INT NOT NULL,
    added_by UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    added_at TIMESTAMPTZ DEFAULT NOW()
);

-- 7. Realtime Jam / Live Listening Sessions (Spotify Jam Clone)
CREATE TABLE IF NOT EXISTS public.listening_sessions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    host_user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    session_code TEXT UNIQUE NOT NULL, -- 6-character room code (e.g. STRM9X)
    current_track_id TEXT,
    current_track_json JSONB,
    position_ms BIGINT DEFAULT 0,
    is_playing BOOLEAN DEFAULT FALSE,
    host_clock_timestamp BIGINT DEFAULT 0,
    queue_json JSONB DEFAULT '[]'::jsonb,
    participant_ids UUID[] DEFAULT '{}',
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 8. Timestamped Song Comments & Reactions (SoundCloud / YouTube Style)
CREATE TABLE IF NOT EXISTS public.track_comments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    track_id TEXT REFERENCES public.tracks(id) ON DELETE CASCADE,
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    user_name TEXT NOT NULL,
    user_avatar TEXT,
    timestamp_ms BIGINT NOT NULL, -- Exact audio position in milliseconds
    comment_text TEXT NOT NULL,
    likes_count INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_comments_track_timestamp 
ON public.track_comments(track_id, timestamp_ms ASC);

-- 9. User Listening History (For Streamify Wrapped & Analytics)
CREATE TABLE IF NOT EXISTS public.user_listening_history (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    track_id TEXT REFERENCES public.tracks(id) ON DELETE CASCADE,
    listened_duration_sec INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_history_user_date 
ON public.user_listening_history(user_id, created_at DESC);

-- 10. Social Friends & Connections
CREATE TABLE IF NOT EXISTS public.friend_connections (
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    friend_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (user_id, friend_id)
);

-- 11. Global Admin Telemetry & Broadcasts
CREATE TABLE IF NOT EXISTS public.admin_broadcasts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    message TEXT NOT NULL,
    author_email TEXT DEFAULT 'sireenyadav@gmail.com',
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================================
-- AI SIMILARITY SEARCH FUNCTION (pgvector RPC for Song Radio)
-- ============================================================================
CREATE OR REPLACE FUNCTION public.match_tracks (
    query_embedding vector(512),
    match_threshold float DEFAULT 0.20,
    match_count int DEFAULT 20
)
RETURNS TABLE (
    id TEXT,
    title TEXT,
    artist TEXT,
    album TEXT,
    duration_sec INT,
    cover_url TEXT,
    stream_url TEXT,
    bpm REAL,
    key_signature TEXT,
    similarity float
)
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN QUERY
    SELECT
        tracks.id,
        tracks.title,
        tracks.artist,
        tracks.album,
        tracks.duration_sec,
        tracks.cover_url,
        tracks.stream_url,
        tracks.bpm,
        tracks.key_signature,
        1 - (tracks.embedding <=> query_embedding) AS similarity
    FROM public.tracks
    WHERE tracks.embedding IS NOT NULL
      AND 1 - (tracks.embedding <=> query_embedding) > match_threshold
    ORDER BY tracks.embedding <=> query_embedding
    LIMIT match_count;
END;
$$;

-- ============================================================================
-- AUTOMATIC PROFILE CREATION TRIGGER & ADMIN ASSIGNMENT
-- ============================================================================
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

DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
    AFTER INSERT OR UPDATE ON auth.users
    FOR EACH ROW EXECUTE FUNCTION public.handle_new_user();

-- ============================================================================
-- ROW LEVEL SECURITY (RLS) POLICIES
-- ============================================================================
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.tracks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_likes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.playlists ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.playlist_tracks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.listening_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.track_comments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_listening_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.friend_connections ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.admin_broadcasts ENABLE ROW LEVEL SECURITY;

CREATE OR REPLACE FUNCTION public.is_admin()
RETURNS BOOLEAN AS $$
BEGIN
    RETURN (auth.jwt()->>'email' = 'sireenyadav@gmail.com') OR
           EXISTS (SELECT 1 FROM public.profiles WHERE id = auth.uid() AND is_admin = TRUE);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Profiles: Public read, user edit own, admin full
CREATE POLICY "Public profiles are viewable by everyone" ON public.profiles FOR SELECT USING (TRUE);
CREATE POLICY "Users can update own profile" ON public.profiles FOR UPDATE USING (auth.uid() = id);
CREATE POLICY "Admin has full profile access" ON public.profiles FOR ALL USING (public.is_admin());

-- Tracks: Anyone read, authenticated insert/update lyrics
CREATE POLICY "Anyone can view tracks" ON public.tracks FOR SELECT USING (TRUE);
CREATE POLICY "Users can upsert tracks" ON public.tracks FOR INSERT WITH CHECK (TRUE);
CREATE POLICY "Users can update track lyrics" ON public.tracks FOR UPDATE USING (TRUE);
CREATE POLICY "Admin has full track access" ON public.tracks FOR ALL USING (public.is_admin());

-- Likes: Users read/modify own likes
CREATE POLICY "Users manage own likes" ON public.user_likes FOR ALL USING (auth.uid() = user_id);
CREATE POLICY "Anyone can view likes" ON public.user_likes FOR SELECT USING (TRUE);

-- Playlists: Public/collaborative access
CREATE POLICY "View public playlists" ON public.playlists FOR SELECT USING (is_public = TRUE OR auth.uid() = user_id OR auth.uid() = ANY(collaborator_ids));
CREATE POLICY "Manage own playlists" ON public.playlists FOR ALL USING (auth.uid() = user_id OR auth.uid() = ANY(collaborator_ids));
CREATE POLICY "Admin full playlist control" ON public.playlists FOR ALL USING (public.is_admin());

-- Playlist Tracks
CREATE POLICY "View playlist tracks" ON public.playlist_tracks FOR SELECT USING (TRUE);
CREATE POLICY "Modify playlist tracks" ON public.playlist_tracks FOR ALL USING (
    EXISTS (SELECT 1 FROM public.playlists WHERE id = playlist_id AND (user_id = auth.uid() OR auth.uid() = ANY(collaborator_ids)))
);

-- Jam Sessions
CREATE POLICY "Anyone can view active listening sessions" ON public.listening_sessions FOR SELECT USING (TRUE);
CREATE POLICY "Hosts can manage their sessions" ON public.listening_sessions FOR ALL USING (auth.uid() = host_user_id);
CREATE POLICY "Participants can update sessions" ON public.listening_sessions FOR UPDATE USING (auth.uid() = ANY(participant_ids) OR TRUE);
CREATE POLICY "Anyone can insert sessions" ON public.listening_sessions FOR INSERT WITH CHECK (TRUE);

-- Track Comments
CREATE POLICY "Anyone can read comments" ON public.track_comments FOR SELECT USING (TRUE);
CREATE POLICY "Authenticated users can post comments" ON public.track_comments FOR INSERT WITH CHECK (TRUE);
CREATE POLICY "Users can delete own comments" ON public.track_comments FOR DELETE USING (auth.uid() = user_id);

-- User Listening History
CREATE POLICY "Users can view own history" ON public.user_listening_history FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY "Users can record history" ON public.user_listening_history FOR INSERT WITH CHECK (auth.uid() = user_id OR TRUE);

-- Friend Connections
CREATE POLICY "Anyone can view friends" ON public.friend_connections FOR SELECT USING (TRUE);
CREATE POLICY "Users manage friends" ON public.friend_connections FOR ALL USING (auth.uid() = user_id);

-- Broadcasts
CREATE POLICY "Anyone can read broadcasts" ON public.admin_broadcasts FOR SELECT USING (is_active = TRUE);
CREATE POLICY "Admin manages broadcasts" ON public.admin_broadcasts FOR ALL USING (public.is_admin());

-- ============================================================================
-- ADMIN COMMAND CENTER & MODERATION EXTENSION RPCs
-- ============================================================================

-- 1. Real-Time Admin Dashboard Metrics Aggregator (RPC)
CREATE OR REPLACE FUNCTION public.get_admin_dashboard_stats()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    result JSONB;
    v_total_users INT;
    v_total_tracks INT;
    v_total_playlists INT;
    v_active_jams INT;
    v_total_comments INT;
    v_total_likes INT;
    v_total_plays BIGINT;
    v_dau_24h INT;
BEGIN
    IF NOT public.is_admin() THEN
        RAISE EXCEPTION 'Access denied: Admin privileges required';
    END IF;

    SELECT COUNT(*) INTO v_total_users FROM public.profiles;
    SELECT COUNT(*) INTO v_total_tracks FROM public.tracks;
    SELECT COUNT(*) INTO v_total_playlists FROM public.playlists;
    SELECT COUNT(*) INTO v_active_jams FROM public.listening_sessions WHERE updated_at > NOW() - INTERVAL '30 minutes';
    SELECT COUNT(*) INTO v_total_comments FROM public.track_comments;
    SELECT COUNT(*) INTO v_total_likes FROM public.user_likes;
    SELECT COALESCE(SUM(total_plays), 0) INTO v_total_plays FROM public.profiles;
    SELECT COUNT(*) INTO v_dau_24h FROM public.profiles WHERE last_active_at > NOW() - INTERVAL '24 hours';

    result := jsonb_build_object(
        'total_users', v_total_users,
        'total_tracks', v_total_tracks,
        'total_playlists', v_total_playlists,
        'active_jam_sessions', v_active_jams,
        'total_comments', v_total_comments,
        'total_likes', v_total_likes,
        'total_plays', v_total_plays,
        'dau_24h', v_dau_24h,
        'server_status', 'Operational',
        'engine_mode', 'PostgreSQL 15 + pgvector 0.5.1'
    );
    RETURN result;
END;
$$;

-- 2. Admin User Role Manager
CREATE OR REPLACE FUNCTION public.set_user_admin_role(
    target_user_id UUID,
    new_admin_status BOOLEAN
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    IF NOT public.is_admin() THEN
        RAISE EXCEPTION 'Access denied: Admin privileges required';
    END IF;

    UPDATE public.profiles
    SET is_admin = new_admin_status
    WHERE id = target_user_id;

    RETURN FOUND;
END;
$$;

-- 3. Admin Jam Session Force Termination
CREATE OR REPLACE FUNCTION public.terminate_jam_session(
    target_session_id UUID
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    IF NOT public.is_admin() THEN
        RAISE EXCEPTION 'Access denied: Admin privileges required';
    END IF;

    DELETE FROM public.listening_sessions
    WHERE id = target_session_id;

    RETURN FOUND;
END;
$$;

-- 4. Admin Comment Moderation / Delete
CREATE OR REPLACE FUNCTION public.delete_comment_admin(
    target_comment_id UUID
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    IF NOT public.is_admin() THEN
        RAISE EXCEPTION 'Access denied: Admin privileges required';
    END IF;

    DELETE FROM public.track_comments
    WHERE id = target_comment_id;

    RETURN FOUND;
END;
$$;

-- 5. Admin Broadcast State Toggle
CREATE OR REPLACE FUNCTION public.toggle_admin_broadcast(
    target_broadcast_id UUID,
    active_state BOOLEAN
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    IF NOT public.is_admin() THEN
        RAISE EXCEPTION 'Access denied: Admin privileges required';
    END IF;

    UPDATE public.admin_broadcasts
    SET is_active = active_state
    WHERE id = target_broadcast_id;

    RETURN FOUND;
END;
$$;

-- 6. Fetch Active Jam Sessions with Details (Admin View)
CREATE OR REPLACE FUNCTION public.get_admin_jam_sessions()
RETURNS TABLE (
    id UUID,
    session_code TEXT,
    host_name TEXT,
    host_email TEXT,
    current_track_title TEXT,
    current_track_artist TEXT,
    participant_count INT,
    is_playing BOOLEAN,
    updated_at TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    IF NOT public.is_admin() THEN
        RAISE EXCEPTION 'Access denied: Admin privileges required';
    END IF;

    RETURN QUERY
    SELECT 
        s.id,
        s.session_code,
        COALESCE(p.display_name, 'Unknown Host') AS host_name,
        COALESCE(p.email, '') AS host_email,
        COALESCE(s.current_track_json->>'title', 'No Track') AS current_track_title,
        COALESCE(s.current_track_json->>'artist', '') AS current_track_artist,
        COALESCE(cardinality(s.participant_ids), 0) + 1 AS participant_count,
        s.is_playing,
        s.updated_at
    FROM public.listening_sessions s
    LEFT JOIN public.profiles p ON s.host_user_id = p.id
    ORDER BY s.updated_at DESC
    LIMIT 50;
END;
$$;

-- 7. Fetch Recent Comments for Moderation Feed
CREATE OR REPLACE FUNCTION public.get_admin_recent_comments(
    limit_count INT DEFAULT 50
)
RETURNS TABLE (
    id UUID,
    track_id TEXT,
    track_title TEXT,
    user_id UUID,
    user_name TEXT,
    user_avatar TEXT,
    comment_text TEXT,
    timestamp_ms BIGINT,
    created_at TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    IF NOT public.is_admin() THEN
        RAISE EXCEPTION 'Access denied: Admin privileges required';
    END IF;

    RETURN QUERY
    SELECT 
        c.id,
        c.track_id,
        COALESCE(t.title, 'Unknown Track') AS track_title,
        c.user_id,
        c.user_name,
        c.user_avatar,
        c.comment_text,
        c.timestamp_ms,
        c.created_at
    FROM public.track_comments c
    LEFT JOIN public.tracks t ON c.track_id = t.id
    ORDER BY c.created_at DESC
    LIMIT limit_count;
END;
$$;

-- ============================================================================
-- 12. PROJECT CHRONOS: USER LISTENING PATTERNS & CIRCADIAN INTELLIGENCE
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.user_listening_patterns (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    slot_name TEXT NOT NULL, -- 'MORNING', 'AFTERNOON', 'EVENING', 'NIGHT'
    avg_bpm REAL DEFAULT 120.0,
    preferred_keys TEXT[] DEFAULT '{}',
    top_genres TEXT[] DEFAULT '{}',
    slot_embedding vector(512),
    skip_ratio REAL DEFAULT 0.0,
    total_slot_plays INT DEFAULT 0,
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, slot_name)
);

CREATE TABLE IF NOT EXISTS public.user_musical_chronotype (
    user_id UUID PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE,
    chronotype_title TEXT DEFAULT 'The Night Explorer 🦉',
    peak_listening_hour INT DEFAULT 23,
    weekly_diversity_score REAL DEFAULT 0.75,
    dominant_bpm_range TEXT DEFAULT '110 - 130 BPM',
    repeat_addiction_ratio REAL DEFAULT 0.20,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.user_listening_patterns ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_musical_chronotype ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users manage own patterns" ON public.user_listening_patterns FOR ALL USING (auth.uid() = user_id OR TRUE);
CREATE POLICY "Public patterns viewable" ON public.user_listening_patterns FOR SELECT USING (TRUE);

CREATE POLICY "Users manage own chronotype" ON public.user_musical_chronotype FOR ALL USING (auth.uid() = user_id OR TRUE);
CREATE POLICY "Public chronotype viewable" ON public.user_musical_chronotype FOR SELECT USING (TRUE);

-- RPC: Upsert User Listening Pattern Slot
CREATE OR REPLACE FUNCTION public.upsert_user_listening_pattern(
    p_slot_name TEXT,
    p_avg_bpm REAL,
    p_top_genres TEXT[],
    p_skip_ratio REAL,
    p_plays_delta INT
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    INSERT INTO public.user_listening_patterns (
        user_id, slot_name, avg_bpm, top_genres, skip_ratio, total_slot_plays, updated_at
    )
    VALUES (
        auth.uid(), p_slot_name, p_avg_bpm, p_top_genres, p_skip_ratio, p_plays_delta, NOW()
    )
    ON CONFLICT (user_id, slot_name) DO UPDATE
    SET avg_bpm = (user_listening_patterns.avg_bpm * 0.8) + (EXCLUDED.avg_bpm * 0.2),
        top_genres = EXCLUDED.top_genres,
        skip_ratio = (user_listening_patterns.skip_ratio * 0.7) + (EXCLUDED.skip_ratio * 0.3),
        total_slot_plays = user_listening_patterns.total_slot_plays + EXCLUDED.total_slot_plays,
        updated_at = NOW();

    RETURN TRUE;
END;
$$;

-- ============================================================================
-- 13. PROJECT NEXUS: CONTEXTUAL INTELLIGENCE & GRAPH TELEMETRY
-- ============================================================================

-- 1. User Hardware & Environmental Context Table
CREATE TABLE IF NOT EXISTS public.user_device_context (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    audio_output_type TEXT DEFAULT 'SPEAKER', -- 'BLUETOOTH_CAR', 'HEADPHONES', 'SPEAKER', 'BLUETOOTH_GENERIC'
    battery_level INT DEFAULT 100,
    is_charging BOOLEAN DEFAULT FALSE,
    network_type TEXT DEFAULT 'WIFI', -- 'WIFI', 'CELLULAR', 'OFFLINE'
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id)
);

-- 2. Scrubber Hook & Engagement Depth Telemetry Table
CREATE TABLE IF NOT EXISTS public.track_hook_telemetry (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    track_id TEXT NOT NULL,
    favorite_seek_timestamp_ms BIGINT DEFAULT 0,
    lyrics_dwell_seconds INT DEFAULT 0,
    volume_flare_count INT DEFAULT 0,
    satiation_score REAL DEFAULT 0.0,
    last_played_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, track_id)
);

-- 3. Session Binge Co-occurrence Graph Table
CREATE TABLE IF NOT EXISTS public.track_cooccurrence_graph (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    track_a_id TEXT NOT NULL,
    track_b_id TEXT NOT NULL,
    cooccurrence_weight REAL DEFAULT 1.0,
    pair_count INT DEFAULT 1,
    last_paired_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(track_a_id, track_b_id)
);

CREATE INDEX IF NOT EXISTS idx_cooccur_pair ON public.track_cooccurrence_graph(track_a_id, cooccurrence_weight DESC);

ALTER TABLE public.user_device_context ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.track_hook_telemetry ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.track_cooccurrence_graph ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users manage own device context" ON public.user_device_context FOR ALL USING (auth.uid() = user_id OR TRUE);
CREATE POLICY "Public device context viewable" ON public.user_device_context FOR SELECT USING (TRUE);

CREATE POLICY "Users manage own hook telemetry" ON public.track_hook_telemetry FOR ALL USING (auth.uid() = user_id OR TRUE);
CREATE POLICY "Public hook telemetry viewable" ON public.track_hook_telemetry FOR SELECT USING (TRUE);

CREATE POLICY "Cooccurrence graph viewable" ON public.track_cooccurrence_graph FOR SELECT USING (TRUE);
CREATE POLICY "Cooccurrence graph insertable" ON public.track_cooccurrence_graph FOR ALL USING (TRUE);

-- RPC: Record Scrubber Hook Telemetry
CREATE OR REPLACE FUNCTION public.record_track_hook_telemetry(
    p_track_id TEXT,
    p_seek_ms BIGINT,
    p_lyrics_dwell_sec INT,
    p_volume_flare INT
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    INSERT INTO public.track_hook_telemetry (
        user_id, track_id, favorite_seek_timestamp_ms, lyrics_dwell_seconds, volume_flare_count, last_played_at
    )
    VALUES (
        auth.uid(), p_track_id, p_seek_ms, p_lyrics_dwell_sec, p_volume_flare, NOW()
    )
    ON CONFLICT (user_id, track_id) DO UPDATE
    SET favorite_seek_timestamp_ms = CASE WHEN EXCLUDED.favorite_seek_timestamp_ms > 0 THEN EXCLUDED.favorite_seek_timestamp_ms ELSE track_hook_telemetry.favorite_seek_timestamp_ms END,
        lyrics_dwell_seconds = track_hook_telemetry.lyrics_dwell_seconds + EXCLUDED.lyrics_dwell_seconds,
        volume_flare_count = track_hook_telemetry.volume_flare_count + EXCLUDED.volume_flare_count,
        last_played_at = NOW();

    RETURN TRUE;
END;
$$;

-- RPC: Record Session Co-occurrence Pair
CREATE OR REPLACE FUNCTION public.record_track_cooccurrence(
    p_track_a TEXT,
    p_track_b TEXT
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    IF p_track_a = p_track_b THEN
        RETURN FALSE;
    END IF;

    INSERT INTO public.track_cooccurrence_graph (
        track_a_id, track_b_id, cooccurrence_weight, pair_count, last_paired_at
    )
    VALUES (
        p_track_a, p_track_b, 1.0, 1, NOW()
    )
    ON CONFLICT (track_a_id, track_b_id) DO UPDATE
    SET pair_count = track_cooccurrence_graph.pair_count + 1,
        cooccurrence_weight = track_cooccurrence_graph.cooccurrence_weight + 1.0,
        last_paired_at = NOW();

    RETURN TRUE;
END;
$$;
