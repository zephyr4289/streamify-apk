-- ============================================================================
-- STREAMIFY FLAGSHIP SUPABASE SCHEMA
-- Author: Sireen Yadav (sireenyadav@gmail.com)
-- Features: Multi-user auth, RLS, Playlists, Likes, Realtime Jam sessions,
--           pgvector AI embeddings, Telemetry & Admin Command Center
-- ============================================================================

-- 1. Enable pgvector extension (for AI recommendation embeddings)
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 2. User Profiles Table
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    email TEXT UNIQUE NOT NULL,
    display_name TEXT,
    avatar_url TEXT,
    is_admin BOOLEAN DEFAULT FALSE,
    total_plays INT DEFAULT 0,
    listening_seconds BIGINT DEFAULT 0,
    last_active_at TIMESTAMPTZ DEFAULT NOW(),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 3. Cloud Track Catalog
CREATE TABLE IF NOT EXISTS public.tracks (
    id TEXT PRIMARY KEY, -- Hash or YouTube video ID / ISRC
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
    session_code TEXT UNIQUE NOT NULL,
    current_track_id TEXT,
    position_ms BIGINT DEFAULT 0,
    is_playing BOOLEAN DEFAULT FALSE,
    participant_ids UUID[] DEFAULT '{}',
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 8. Global Admin Telemetry & Broadcasts
CREATE TABLE IF NOT EXISTS public.admin_broadcasts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    message TEXT NOT NULL,
    author_email TEXT DEFAULT 'sireenyadav@gmail.com',
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

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
        (NEW.email = 'sireenyadav@gmail.com') -- Automatically grants Admin to sireenyadav@gmail.com
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
ALTER TABLE public.admin_broadcasts ENABLE ROW LEVEL SECURITY;

-- Helper function to check if caller is admin
CREATE OR REPLACE FUNCTION public.is_admin()
RETURNS BOOLEAN AS $$
BEGIN
    RETURN (auth.jwt()->>'email' = 'sireenyadav@gmail.com') OR
           EXISTS (SELECT 1 FROM public.profiles WHERE id = auth.uid() AND is_admin = TRUE);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Profiles: Public read, user edit own, admin full control
CREATE POLICY "Public profiles are viewable by everyone" ON public.profiles FOR SELECT USING (TRUE);
CREATE POLICY "Users can update own profile" ON public.profiles FOR UPDATE USING (auth.uid() = id);
CREATE POLICY "Admin has full profile access" ON public.profiles FOR ALL USING (public.is_admin());

-- Tracks: Public read/insert, admin full access
CREATE POLICY "Anyone can view tracks" ON public.tracks FOR SELECT USING (TRUE);
CREATE POLICY "Authenticated users can insert tracks" ON public.tracks FOR INSERT WITH CHECK (auth.role() = 'authenticated');
CREATE POLICY "Admin has full track access" ON public.tracks FOR ALL USING (public.is_admin());

-- Likes: Users read/modify own likes, admin read all
CREATE POLICY "Users can manage own likes" ON public.user_likes FOR ALL USING (auth.uid() = user_id);
CREATE POLICY "Admin can view all likes" ON public.user_likes FOR SELECT USING (public.is_admin());

-- Playlists: Public/collab read, owner write, admin full
CREATE POLICY "View public playlists" ON public.playlists FOR SELECT USING (is_public = TRUE OR auth.uid() = user_id OR auth.uid() = ANY(collaborator_ids));
CREATE POLICY "Manage own playlists" ON public.playlists FOR ALL USING (auth.uid() = user_id OR auth.uid() = ANY(collaborator_ids));
CREATE POLICY "Admin full playlist control" ON public.playlists FOR ALL USING (public.is_admin());

-- Playlist Tracks: Follow playlist access
CREATE POLICY "View playlist tracks" ON public.playlist_tracks FOR SELECT USING (TRUE);
CREATE POLICY "Modify playlist tracks" ON public.playlist_tracks FOR ALL USING (
    EXISTS (SELECT 1 FROM public.playlists WHERE id = playlist_id AND (user_id = auth.uid() OR auth.uid() = ANY(collaborator_ids)))
);

-- Sessions (Jam Mode): View and participate
CREATE POLICY "Anyone can view active listening sessions" ON public.listening_sessions FOR SELECT USING (TRUE);
CREATE POLICY "Hosts can manage their sessions" ON public.listening_sessions FOR ALL USING (auth.uid() = host_user_id);
CREATE POLICY "Participants can update sessions" ON public.listening_sessions FOR UPDATE USING (auth.uid() = ANY(participant_ids));

-- Broadcasts: Read by all, written only by Admin
CREATE POLICY "Anyone can read broadcasts" ON public.admin_broadcasts FOR SELECT USING (is_active = TRUE);
CREATE POLICY "Admin manages broadcasts" ON public.admin_broadcasts FOR ALL USING (public.is_admin());
