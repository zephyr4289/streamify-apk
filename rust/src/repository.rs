use rusqlite::{params, Connection};
use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};

pub fn generate_cad_id(title: &str, artist: &str, duration_sec: u32) -> String {
    let clean_artist = artist.to_lowercase().replace(|c: char| !c.is_alphanumeric(), "");
    let mut hasher = DefaultHasher::new();
    let clean_title = title.to_lowercase().replace(|c: char| !c.is_alphanumeric() && c != '(', "");
    clean_title.hash(&mut hasher);
    clean_artist.hash(&mut hasher);
    (duration_sec / 3).hash(&mut hasher); // Absorbs 3s master duration differences
    format!("{:016x}", hasher.finish())
}

pub fn init_database(db_path: &str) -> Result<Connection, rusqlite::Error> {
    let conn = Connection::open(db_path)?;
    conn.execute_batch(
        "PRAGMA journal_mode = WAL;
         PRAGMA synchronous = NORMAL;
         CREATE TABLE IF NOT EXISTS universal_tracks (
             cad_id TEXT PRIMARY KEY,
             title TEXT NOT NULL,
             artist TEXT NOT NULL,
             album TEXT,
             spotify_id TEXT,
             isrc_code TEXT,
             ytm_video_id TEXT,
             source_platform TEXT NOT NULL,
             is_liked INTEGER DEFAULT 0,
             sync_timestamp INTEGER NOT NULL
         );
         CREATE INDEX IF NOT EXISTS idx_isrc ON universal_tracks(isrc_code);"
    )?;
    Ok(conn)
}
