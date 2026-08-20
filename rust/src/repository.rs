use rusqlite::{params, Connection};
use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};
use std::panic::catch_unwind;

#[derive(serde::Deserialize)]
pub struct RawSpotifyTrack {
    pub title: String,
    pub artist: String,
    pub duration_sec: u32,
    #[serde(default)]
    pub artwork_url: Option<String>,
    #[serde(default)]
    pub isrc: Option<String>,
    #[serde(default)]
    pub spotify_id: Option<String>,
}

pub struct TrackRepository {
    conn: Connection,
}

impl TrackRepository {
    pub fn new(path: &str) -> Result<Self, rusqlite::Error> {
        let conn = Connection::open(path)?;
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "synchronous", "NORMAL")?;
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS universal_tracks (
                cad_id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                duration_sec INTEGER NOT NULL,
                artwork_url TEXT,
                isrc_code TEXT,
                spotify_id TEXT,
                ytm_video_id TEXT,
                source_platform TEXT NOT NULL,
                is_liked INTEGER DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS idx_isrc ON universal_tracks(isrc_code);
            CREATE INDEX IF NOT EXISTS idx_spotify ON universal_tracks(spotify_id);
            CREATE INDEX IF NOT EXISTS idx_ytm ON universal_tracks(ytm_video_id);"
        )?;
        Ok(TrackRepository { conn })
    }

    /// Generates a Duration-Aware Canonical Hash (CAD-ID).
    /// Prevents "Starboy (Kygo Remix)" from colliding with "Starboy (Original)".
    #[inline(always)]
    pub fn generate_cad_id(title: &str, artist: &str, duration_sec: u32) -> String {
        let mut hasher = DefaultHasher::new();

        // Normalize title: lowercase, keep alphanumeric & parentheses (for remix tags)
        let clean_title = title
            .to_lowercase()
            .replace(|c: char| !c.is_alphanumeric() && c != '(', "");

        // Normalize artist: lowercase, strictly alphanumeric
        let clean_artist = artist
            .to_lowercase()
            .replace(|c: char| !c.is_alphanumeric(), "");

        clean_title.hash(&mut hasher);
        clean_artist.hash(&mut hasher);

        // Bucket duration by 3 seconds to absorb cross-platform master discrepancies
        let duration_bucket = duration_sec / 3;
        duration_bucket.hash(&mut hasher);

        format!("{:016x}", hasher.finish())
    }

    /// Batch upserts tracks in a single transaction.
    /// Accepts a JSON payload from Kotlin (1-time network cost, safe parsing).
    pub fn batch_upsert_spotify_tracks(&self, json_payload: &str) -> i32 {
        let result = catch_unwind(|| {
            let tracks: Vec<RawSpotifyTrack> = match serde_json::from_str(json_payload) {
                Ok(t) => t,
                Err(_) => return -1,
            };

            let tx = match self.conn.unchecked_transaction() {
                Ok(t) => t,
                Err(_) => return -1,
            };

            let mut count = 0;
            for track in tracks {
                let cad_id = Self::generate_cad_id(&track.title, &track.artist, track.duration_sec);

                let res = tx.execute(
                    "INSERT INTO universal_tracks 
                     (cad_id, title, artist, duration_sec, artwork_url, isrc_code, spotify_id, source_platform)
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 'SPOTIFY')
                     ON CONFLICT(cad_id) DO UPDATE SET 
                        spotify_id = COALESCE(excluded.spotify_id, universal_tracks.spotify_id),
                        isrc_code = COALESCE(excluded.isrc_code, universal_tracks.isrc_code),
                        artwork_url = COALESCE(excluded.artwork_url, universal_tracks.artwork_url)",
                    params![
                        cad_id,
                        track.title,
                        track.artist,
                        track.duration_sec,
                        track.artwork_url,
                        track.isrc,
                        track.spotify_id
                    ],
                );
                if res.is_ok() {
                    count += 1;
                }
            }

            if tx.commit().is_ok() {
                count
            } else {
                -1
            }
        });

        result.unwrap_or(-1)
    }

    /// Queries the shelf and writes directly to a Kotlin-provided DirectByteBuffer.
    /// ZERO JSON serialization. ZERO Kotlin String allocations in hot path.
    pub fn fetch_virtual_shelf_to_buffer(&self, out_buf: *mut u8, out_buf_len: usize) -> i32 {
        let result = catch_unwind(|| {
            if out_buf.is_null() || out_buf_len < 4 {
                return -1;
            }

            let mut stmt = match self.conn.prepare(
                "SELECT cad_id, title, artist, COALESCE(artwork_url, '') FROM universal_tracks LIMIT 500",
            ) {
                Ok(s) => s,
                Err(_) => return -1,
            };

            let rows = match stmt.query_map([], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, String>(3)?,
                ))
            }) {
                Ok(r) => r,
                Err(_) => return -1,
            };

            let mut offset = 4; // Reserve first 4 bytes for total count
            let mut track_count = 0u32;

            for row_res in rows {
                let row = match row_res {
                    Ok(r) => r,
                    Err(_) => continue,
                };
                let cad_id = row.0.as_bytes();
                let title = row.1.as_bytes();
                let artist = row.2.as_bytes();
                let artwork = row.3.as_bytes();

                let needed = 16 + cad_id.len() + title.len() + artist.len() + artwork.len();
                if offset + needed > out_buf_len {
                    break;
                }

                macro_rules! write_field {
                    ($field:expr) => {
                        let len = $field.len() as u32;
                        let len_bytes = len.to_le_bytes();
                        unsafe {
                            std::ptr::copy_nonoverlapping(len_bytes.as_ptr(), out_buf.add(offset), 4);
                            offset += 4;
                            if !($field).is_empty() {
                                std::ptr::copy_nonoverlapping(($field).as_ptr(), out_buf.add(offset), ($field).len());
                                offset += ($field).len();
                            }
                        }
                    };
                }

                write_field!(cad_id);
                write_field!(title);
                write_field!(artist);
                write_field!(artwork);

                track_count += 1;
            }

            let count_bytes = track_count.to_le_bytes();
            unsafe {
                std::ptr::copy_nonoverlapping(count_bytes.as_ptr(), out_buf, 4);
            }

            offset as i32
        });

        result.unwrap_or(-3)
    }
}

pub fn generate_cad_id(title: &str, artist: &str, duration_sec: u32) -> String {
    TrackRepository::generate_cad_id(title, artist, duration_sec)
}

#[no_mangle]
pub unsafe extern "C" fn batch_upsert_spotify_tracks(
    db_path: *const std::os::raw::c_char,
    json_payload: *const std::os::raw::c_char,
) -> i32 {
    let result = catch_unwind(|| {
        if db_path.is_null() || json_payload.is_null() {
            return -1;
        }
        let c_db = std::ffi::CStr::from_ptr(db_path);
        let c_json = std::ffi::CStr::from_ptr(json_payload);
        let db_str = match c_db.to_str() {
            Ok(s) => s,
            Err(_) => return -1,
        };
        let json_str = match c_json.to_str() {
            Ok(s) => s,
            Err(_) => return -1,
        };
        let repo = match TrackRepository::new(db_str) {
            Ok(r) => r,
            Err(_) => return -1,
        };
        repo.batch_upsert_spotify_tracks(json_str)
    });
    result.unwrap_or(-3)
}

#[no_mangle]
pub unsafe extern "C" fn fetch_virtual_shelf(
    db_path: *const std::os::raw::c_char,
    out_buf: *mut u8,
    out_buf_len: usize,
) -> i32 {
    let result = catch_unwind(|| {
        if db_path.is_null() || out_buf.is_null() || out_buf_len < 4 {
            return -1;
        }
        let c_db = std::ffi::CStr::from_ptr(db_path);
        let db_str = match c_db.to_str() {
            Ok(s) => s,
            Err(_) => return -1,
        };
        let repo = match TrackRepository::new(db_str) {
            Ok(r) => r,
            Err(_) => return -1,
        };
        repo.fetch_virtual_shelf_to_buffer(out_buf, out_buf_len)
    });
    result.unwrap_or(-3)
}
