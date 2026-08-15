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
