-- Streamify Multi-User Database Schema

CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT UNIQUE NOT NULL,
    pin_hash TEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_sessions (
    token TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS tracks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    filepath TEXT UNIQUE NOT NULL,
    title TEXT,
    artist TEXT,
    album TEXT DEFAULT 'Single',
    duration_sec INTEGER DEFAULT 180,
    bpm REAL DEFAULT 120.0,
    key TEXT DEFAULT 'C',
    vector_offset INTEGER DEFAULT -1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_liked_songs (
    user_id INTEGER NOT NULL,
    track_id INTEGER NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, track_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (track_id) REFERENCES tracks(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS transitions (
    user_id INTEGER NOT NULL DEFAULT 1,
    from_track_id INTEGER NOT NULL,
    to_track_id INTEGER NOT NULL,
    count INTEGER DEFAULT 1,
    PRIMARY KEY (user_id, from_track_id, to_track_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (from_track_id) REFERENCES tracks(id) ON DELETE CASCADE,
    FOREIGN KEY (to_track_id) REFERENCES tracks(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS skips (
    user_id INTEGER NOT NULL DEFAULT 1,
    from_track_id INTEGER NOT NULL,
    to_track_id INTEGER NOT NULL,
    count INTEGER DEFAULT 1,
    PRIMARY KEY (user_id, from_track_id, to_track_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (from_track_id) REFERENCES tracks(id) ON DELETE CASCADE,
    FOREIGN KEY (to_track_id) REFERENCES tracks(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS playlists (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL DEFAULT 1,
    name TEXT NOT NULL,
    description TEXT,
    cover_art TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS playlist_tracks (
    playlist_id INTEGER NOT NULL,
    track_id INTEGER NOT NULL,
    position INTEGER NOT NULL,
    PRIMARY KEY (playlist_id, track_id),
    FOREIGN KEY (playlist_id) REFERENCES playlists(id) ON DELETE CASCADE,
    FOREIGN KEY (track_id) REFERENCES tracks(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);
CREATE INDEX IF NOT EXISTS idx_sessions_token ON user_sessions(token);
CREATE INDEX IF NOT EXISTS idx_tracks_filepath ON tracks(filepath);
CREATE INDEX IF NOT EXISTS idx_transitions_user_from ON transitions(user_id, from_track_id);
CREATE INDEX IF NOT EXISTS idx_skips_user_from ON skips(user_id, from_track_id);
CREATE INDEX IF NOT EXISTS idx_playlists_user ON playlists(user_id);

