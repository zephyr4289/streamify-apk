-- SQLite Database Schema for Audio Processing Engine

CREATE TABLE IF NOT EXISTS tracks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    filepath TEXT UNIQUE NOT NULL,
    title TEXT,
    artist TEXT,
    bpm REAL,
    key TEXT,
    vector_offset INTEGER NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS transitions (
    from_track_id INTEGER NOT NULL,
    to_track_id INTEGER NOT NULL,
    count INTEGER DEFAULT 1,
    PRIMARY KEY (from_track_id, to_track_id),
    FOREIGN KEY (from_track_id) REFERENCES tracks(id) ON DELETE CASCADE,
    FOREIGN KEY (to_track_id) REFERENCES tracks(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS skips (
    from_track_id INTEGER NOT NULL,
    to_track_id INTEGER NOT NULL,
    count INTEGER DEFAULT 1,
    PRIMARY KEY (from_track_id, to_track_id),
    FOREIGN KEY (from_track_id) REFERENCES tracks(id) ON DELETE CASCADE,
    FOREIGN KEY (to_track_id) REFERENCES tracks(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_tracks_filepath ON tracks(filepath);
CREATE INDEX IF NOT EXISTS idx_transitions_from ON transitions(from_track_id);
CREATE INDEX IF NOT EXISTS idx_skips_from ON skips(from_track_id);
