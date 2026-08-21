use reqwest::Client;
use serde_json::Value;
use std::ffi::CStr;
use std::panic::catch_unwind;
use std::sync::OnceLock;
use tokio::runtime::Runtime;
use rusqlite::Connection;

static HTTP_CLIENT: OnceLock<Client> = OnceLock::new();
static TOKIO_RUNTIME: OnceLock<Runtime> = OnceLock::new();

pub fn get_runtime() -> &'static Runtime {
    TOKIO_RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .expect("Failed to initialize Streamify Tokio Runtime")
    })
}

pub fn get_client() -> &'static Client {
    HTTP_CLIENT.get_or_init(|| {
        Client::builder()
            .user_agent("Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36")
            .pool_idle_timeout(Some(std::time::Duration::from_secs(30)))
            .pool_max_idle_per_host(10)
            .tcp_nodelay(true)
            .build()
            .unwrap_or_else(|_| Client::new())
    })
}

/// The FFI entry point. Blocks the calling thread until resolution completes.
/// Returns the length of the videoId written to out_buf, or negative error code.
#[no_mangle]
pub unsafe extern "C" fn resolve_track_cdn(
    db_path_ptr: *const std::os::raw::c_char,
    cad_id_ptr: *const std::os::raw::c_char,
    isrc_ptr: *const std::os::raw::c_char,
    title_ptr: *const std::os::raw::c_char,
    artist_ptr: *const std::os::raw::c_char,
    auth_header_ptr: *const std::os::raw::c_char,
    out_buf: *mut u8,
    out_buf_len: usize,
) -> i32 {
    let result = catch_unwind(|| {
        if db_path_ptr.is_null() || cad_id_ptr.is_null() || title_ptr.is_null() || artist_ptr.is_null() || out_buf.is_null() {
            return -1;
        }

        let db_path = CStr::from_ptr(db_path_ptr).to_str().unwrap_or("");
        let cad_id = CStr::from_ptr(cad_id_ptr).to_str().unwrap_or("");
        let title = CStr::from_ptr(title_ptr).to_str().unwrap_or("");
        let artist = CStr::from_ptr(artist_ptr).to_str().unwrap_or("");
        let auth_header = if auth_header_ptr.is_null() {
            ""
        } else {
            CStr::from_ptr(auth_header_ptr).to_str().unwrap_or("")
        };

        let isrc = if isrc_ptr.is_null() {
            None
        } else {
            CStr::from_ptr(isrc_ptr).to_str().ok().filter(|s| !s.is_empty())
        };

        let rt = get_runtime();

        rt.block_on(async {
            match execute_resolution(db_path, cad_id, isrc, title, artist, auth_header).await {
                Ok(video_id) => {
                    let bytes = video_id.as_bytes();
                    if bytes.len() > out_buf_len {
                        return -2;
                    }
                    std::ptr::copy_nonoverlapping(bytes.as_ptr(), out_buf, bytes.len());
                    bytes.len() as i32
                }
                Err(_) => -4,
            }
        })
    });

    result.unwrap_or(-3)
}

/// One parsed search result from the Innertube shelf, carrying enough metadata
/// to prove the candidate is the SAME SONG before its videoId is trusted.
#[derive(Clone, Debug)]
pub struct SearchCandidate {
    pub video_id: String,
    pub title: String,
    pub artist: String,
    pub duration_sec: u32,
}

/// The 3-tier async resolution logic
pub async fn execute_resolution(
    db_path: &str,
    cad_id: &str,
    isrc: Option<&str>,
    title: &str,
    artist: &str,
    auth_header: &str,
) -> Result<String, ()> {
    // Self-heal legacy databases (re-key old DefaultHasher CAD-IDs onto the
    // canonical FNV scheme) before trusting any cached lookup.
    crate::repository::ensure_db_migrated(db_path);

    // TIER 1: Check Local SQLite Cache (Zero network cost)
    if !db_path.is_empty() && !cad_id.is_empty() {
        if let Some(video_id) = check_local_cache(db_path, cad_id) {
            return Ok(video_id);
        }
    }

    // TIER 2: Innertube ISRC Exact Match (authoritative catalog query)
    if let Some(isrc_code) = isrc {
        let clean_isrc = isrc_code.trim();
        if !clean_isrc.is_empty() {
            let query = format!("isrc:{}", clean_isrc);
            if let Some(candidate) = innertube_search_candidates(&query, auth_header)
                .await
                .into_iter()
                .find(|c| is_valid_video_id(&c.video_id))
            {
                if !db_path.is_empty() && !cad_id.is_empty() {
                    bind_video_id_to_db(
                        db_path,
                        cad_id,
                        title,
                        artist,
                        &candidate.video_id.clone(),
                    );
                }
                return Ok(candidate.video_id);
            }
        }
    }

    // TIER 3: Verified Fuzzy Match (Title + Artist).
    // Every candidate must prove same-song identity before its videoId is
    // accepted or persisted. Blind first-result acceptance is forbidden.
    let query = format!("{} - {}", title.trim(), artist.trim());
    let candidates = innertube_search_candidates(&query, auth_header).await;
    for candidate in candidates {
        if !is_valid_video_id(&candidate.video_id) {
            continue;
        }
        if titles_match(title, &candidate.title) && artists_match(artist, &candidate.artist) {
            if !db_path.is_empty() && !cad_id.is_empty() {
                bind_video_id_to_db(db_path, cad_id, title, artist, &candidate.video_id);
            }
            return Ok(candidate.video_id);
        }
    }

    Err(())
}

/// Queries YouTube Music Innertube API and parses every shelf result into a
/// verified-able SearchCandidate (videoId + title + artist + duration).
pub async fn innertube_search_candidates(query: &str, auth_header: &str) -> Vec<SearchCandidate> {
    let client = get_client();
    let url = "https://music.youtube.com/youtubei/v1/search?alt=json&key=AIzaSyC9XL3ZjWddXya6X74uM32vM1tl8R0kC8";

    let payload = serde_json::json!({
        "context": {
            "client": {
                "clientName": "WEB_REMIX",
                "clientVersion": "1.20240401.01.00",
                "hl": "en",
                "gl": "US"
            }
        },
        "query": query
    });

    let mut req = client.post(url)
        .header("Content-Type", "application/json")
        .header("Origin", "https://music.youtube.com")
        .header("Referer", "https://music.youtube.com/");

    if !auth_header.is_empty() {
        let auth_val = if auth_header.starts_with("SAPISIDHASH ") {
            auth_header.to_string()
        } else {
            format!("SAPISIDHASH {}", auth_header)
        };
        req = req.header("Authorization", auth_val);
    }

    let response = match req.json(&payload).send().await {
        Ok(r) => r,
        Err(_) => return Vec::new(),
    };
    let json: Value = match response.json().await {
        Ok(j) => j,
        Err(_) => return Vec::new(),
    };

    parse_search_candidates(&json)
}

fn is_valid_video_id(video_id: &str) -> bool {
    video_id.len() == 11
        && video_id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

/// Recursively collects every musicResponsiveListItemRenderer object in the tree.
fn collect_list_item_renderers(node: &Value, out: &mut Vec<&Value>) {
    match node {
        Value::Object(map) => {
            for (key, value) in map {
                if key == "musicResponsiveListItemRenderer" {
                    out.push(value);
                }
                collect_list_item_renderers(value, out);
            }
        }
        Value::Array(items) => {
            for item in items {
                collect_list_item_renderers(item, out);
            }
        }
        _ => {}
    }
}

/// Joins all text runs of a flex/fixed column cell into one string.
fn column_text(cell: &Value) -> String {
    let mut out = String::new();
    let default_runs = Value::Null;
    let runs = cell
        .pointer("/text/runs")
        .and_then(Value::as_array)
        .unwrap_or(&default_runs);
    for run in runs {
        if let Some(text) = run.get("text").and_then(Value::as_str) {
            out.push_str(text);
        }
    }
    out
}

fn parse_duration_token(token: &str) -> Option<u32> {
    let t = token.trim();
    let parts: Vec<&str> = t.split(':').collect();
    if parts.len() < 2 || parts.len() > 3 {
        return None;
    }
    let mut total: u32 = 0;
    for p in &parts {
        if p.is_empty() || !p.bytes().all(|b| b.is_ascii_digit()) || p.len() > 2 {
            return None;
        }
        total = total.saturating_mul(60).saturating_add(p.parse::<u32>().ok()?);
    }
    if total == 0 { None } else { Some(total) }
}

fn extract_duration_sec(haystacks: &[&str]) -> Option<u32> {
    let mut last: Option<u32> = None;
    for hay in haystacks {
        for token in hay.split(|c: char| c == '•' || c.is_whitespace() || c == '[' || c == ']') {
            if let Some(sec) = parse_duration_token(token) {
                last = Some(sec);
            }
        }
    }
    last
}

fn parse_search_candidates(json: &Value) -> Vec<SearchCandidate> {
    let mut renderers: Vec<&Value> = Vec::new();
    collect_list_item_renderers(json, &mut renderers);

    let mut candidates = Vec::with_capacity(renderers.len());
    let mut seen_ids = std::collections::HashSet::new();

    for renderer in renderers {
        let video_id = renderer
            .pointer("/playlistItemData/videoId")
            .and_then(Value::as_str)
            .or_else(|| {
                renderer
                    .pointer("/navigationEndpoint/watchEndpoint/videoId")
                    .and_then(Value::as_str)
            })
            .or_else(|| {
                renderer
                    .pointer("/overlay/musicItemThumbnailOverlayRenderer/content/musicPlayButtonRenderer/playNavigationEndpoint/watchEndpoint/videoId")
                    .and_then(Value::as_str)
            });
        let video_id = match video_id {
            Some(v) => v.to_string(),
            None => continue,
        };
        if !seen_ids.insert(video_id.clone()) {
            continue;
        }

        // Title = first flex column; artist/subtitle/duration from the rest.
        let mut columns: Vec<String> = Vec::new();
        if let Some(flex_columns) = renderer
            .pointer("/flexColumns")
            .and_then(Value::as_array)
        {
            for col in flex_columns {
                if let Some(cell) = col.get("musicResponsiveListItemFlexCellRenderer") {
                    columns.push(column_text(cell));
                }
            }
        }
        let title = columns.first().cloned().unwrap_or_default();

        let mut fixed_parts: Vec<String> = Vec::new();
        if let Some(fixed_columns) = renderer
            .pointer("/fixedColumns")
            .and_then(Value::as_array)
        {
            for col in fixed_columns {
                if let Some(cell) = col.get("musicResponsiveListItemFixedColumnRenderer") {
                    fixed_parts.push(column_text(cell));
                }
            }
        }

        // Artist: second flex column when present (songs shelf), else first
        // bullet-separated token of the combined subtitle text.
        let subtitle_tail: String = columns
            .iter()
            .skip(1)
            .cloned()
            .collect::<Vec<String>>()
            .join(" • ");
        let combined_subtitle = if !fixed_parts.is_empty() {
            format!("{} • {}", subtitle_tail, fixed_parts.join(" • "))
        } else {
            subtitle_tail
        };
        let artist = combined_subtitle
            .split('•')
            .next()
            .unwrap_or("")
            .trim()
            .to_string();

        let duration_sec =
            extract_duration_sec(&[combined_subtitle.as_str(), fixed_parts.join(" ").as_str()])
                .unwrap_or(0);

        candidates.push(SearchCandidate {
            video_id,
            title,
            artist,
            duration_sec,
        });
    }

    candidates
}

// --- Identity verification gate (same-song proof before acceptance) ---

fn clean_identity_text(input: &str) -> String {
    let lowered = input.to_lowercase();
    let filtered: String = lowered
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || c.is_whitespace())
        .collect();
    filtered.split_whitespace().collect::<Vec<&str>>().join(" ")
}

fn strip_artist_noise(artist: &str) -> String {
    clean_identity_text(
        &artist
            .to_lowercase()
            .replace("- topic", "")
            .replace("vevo", "")
            .replace(" - official", ""),
    )
}

fn titles_match(query_title: &str, candidate_title: &str) -> bool {
    let q = clean_identity_text(query_title);
    let c = clean_identity_text(candidate_title);
    if q.is_empty() || c.is_empty() {
        return false;
    }
    if q == c {
        return true;
    }
    strsim::jaro_winkler(&q, &c) >= 0.72
}

fn artists_match(query_artist: &str, candidate_artist: &str) -> bool {
    let q = strip_artist_noise(query_artist);
    let c = strip_artist_noise(candidate_artist);
    if q.is_empty() {
        return true;
    }
    if c.is_empty() {
        return false;
    }
    q == c || c.contains(&q) || q.contains(&c)
}

// --- SQLite Cache Helpers ---
fn check_local_cache(db_path: &str, cad_id: &str) -> Option<String> {
    let conn = Connection::open(db_path).ok()?;
    let mut stmt = conn.prepare("SELECT ytm_video_id FROM universal_tracks WHERE cad_id = ?1").ok()?;
    let result = stmt.query_map([cad_id], |row| row.get::<_, String>(0)).ok()?.next()?.ok();
    result.filter(|s| !s.is_empty())
}

/// Persists a verified videoId binding. Upsert semantics: the legacy code ran a
/// bare UPDATE that silently matched 0 rows whenever the CAD-ID row did not yet
/// exist — meaning correct resolutions were never cached and every play re-ran
/// the fuzzy lottery. Now the row is created on first binding.
fn bind_video_id_to_db(db_path: &str, cad_id: &str, title: &str, artist: &str, video_id: &str) {
    if let Ok(conn) = Connection::open(db_path) {
        let _ = conn.pragma_update(None, "journal_mode", "WAL");
        let _ = conn.execute(
            "INSERT INTO universal_tracks (cad_id, title, artist, duration_sec, ytm_video_id, source_platform)
             VALUES (?1, ?2, ?3, 0, ?4, 'resolver')
             ON CONFLICT(cad_id) DO UPDATE SET
                ytm_video_id = COALESCE(NULLIF(universal_tracks.ytm_video_id, ''), excluded.ytm_video_id)",
            rusqlite::params![cad_id, title, artist, video_id],
        );
    }
}

pub async fn fetch_innertube_cdn(client: &Client, video_id: &str) -> Result<String, String> {
    let clean_id = video_id.trim();
    if clean_id.is_empty() {
        return Err("Empty video ID".to_string());
    }

    // 1. Android Music Target
    let url = "https://music.youtube.com/youtubei/v1/player";
    let body = serde_json::json!({
        "context": {
            "client": {
                "clientName": "ANDROID_MUSIC",
                "clientVersion": "6.42.52",
                "hl": "en",
                "gl": "US"
            }
        },
        "videoId": clean_id,
        "contentCheckOk": true,
        "racyCheckOk": true
    });

    if let Ok(res) = client
        .post(url)
        .header("Content-Type", "application/json")
        .header("X-YouTube-Client-Name", "21")
        .header("X-YouTube-Client-Version", "6.42.52")
        .json(&body)
        .send()
        .await
    {
        if let Ok(json) = res.json::<Value>().await {
            if let Some(url_str) = extract_best_audio_url(&json) {
                return Ok(url_str);
            }
        }
    }

    // 2. Fallback to Android Official Target
    let url_android = "https://www.youtube.com/youtubei/v1/player";
    let body_android = serde_json::json!({
        "context": {
            "client": {
                "clientName": "ANDROID",
                "clientVersion": "21.26.364",
                "androidSdkVersion": 30,
                "osName": "Android",
                "osVersion": "11",
                "hl": "en",
                "gl": "US"
            }
        },
        "videoId": clean_id,
        "contentCheckOk": true,
        "racyCheckOk": true
    });

    if let Ok(res) = client
        .post(url_android)
        .header("Content-Type", "application/json")
        .header("X-YouTube-Client-Name", "3")
        .header("X-YouTube-Client-Version", "21.26.364")
        .json(&body_android)
        .send()
        .await
    {
        if let Ok(json) = res.json::<Value>().await {
            if let Some(url_str) = extract_best_audio_url(&json) {
                return Ok(url_str);
            }
        }
    }

    // 3. Fallback to Android VR
    let body_vr = serde_json::json!({
        "context": {
            "client": {
                "clientName": "ANDROID_VR",
                "clientVersion": "1.60.19",
                "deviceMake": "Oculus",
                "deviceModel": "Quest 3",
                "osName": "Android",
                "osVersion": "12",
                "hl": "en",
                "gl": "US"
            }
        },
        "videoId": clean_id,
        "contentCheckOk": true,
        "racyCheckOk": true
    });

    if let Ok(res) = client
        .post(url_android)
        .header("Content-Type", "application/json")
        .header("X-YouTube-Client-Name", "28")
        .header("X-YouTube-Client-Version", "1.60.19")
        .json(&body_vr)
        .send()
        .await
    {
        if let Ok(json) = res.json::<Value>().await {
            if let Some(url_str) = extract_best_audio_url(&json) {
                return Ok(url_str);
            }
        }
    }

    Err("Failed to extract playable audio CDN URL".to_string())
}

fn extract_best_audio_url(json: &Value) -> Option<String> {
    // 1. Try direct adaptiveFormats inspection prioritizing audio
    if let Some(formats) = json.pointer("/streamingData/adaptiveFormats").and_then(|v| v.as_array()) {
        let mut best_audio: Option<(&str, u64)> = None;

        for fmt in formats {
            let mime = fmt.get("mimeType").and_then(|v| v.as_str()).unwrap_or("");
            if mime.starts_with("audio/") {
                if let Some(url) = fmt.get("url").and_then(|v| v.as_str()) {
                    let bitrate = fmt.get("bitrate").and_then(|v| v.as_u64()).unwrap_or(0);
                    if best_audio.map_or(true, |(_, b)| bitrate > b) {
                        best_audio = Some((url, bitrate));
                    }
                }
            }
        }

        if let Some((url, _)) = best_audio {
            return Some(url.to_string());
        }

        // Fallback to any valid direct url in formats
        for fmt in formats {
            if let Some(url) = fmt.get("url").and_then(|v| v.as_str()) {
                return Some(url.to_string());
            }
        }
    }

    // 2. Fallback to combined streamingData.formats
    if let Some(formats) = json.pointer("/streamingData/formats").and_then(|v| v.as_array()) {
        for fmt in formats {
            if let Some(url) = fmt.get("url").and_then(|v| v.as_str()) {
                return Some(url.to_string());
            }
        }
    }

    None
}

#[no_mangle]
pub unsafe extern "C" fn resolve_track_cdn(
    video_id_ptr: *const u8,
    video_id_len: usize,
    isrc_ptr: *const u8,
    isrc_len: usize,
    title_ptr: *const u8,
    title_len: usize,
    artist_ptr: *const u8,
    artist_len: usize,
    out_buf: *mut u8,
    out_buf_len: usize,
) -> i32 {
    let result = std::panic::catch_unwind(|| {
        let video_id = if video_id_ptr.is_null() || video_id_len == 0 {
            None
        } else {
            std::str::from_utf8(std::slice::from_raw_parts(video_id_ptr, video_id_len)).ok()
        };

        let isrc = if isrc_ptr.is_null() || isrc_len == 0 {
            None
        } else {
            std::str::from_utf8(std::slice::from_raw_parts(isrc_ptr, isrc_len)).ok()
        };

        let title = match std::str::from_utf8(std::slice::from_raw_parts(title_ptr, title_len)) {
            Ok(t) => t,
            Err(_) => return -2,
        };

        let artist = match std::str::from_utf8(std::slice::from_raw_parts(artist_ptr, artist_len)) {
            Ok(a) => a,
            Err(_) => return -2,
        };

        let rt = get_runtime();
        let cdn_url = match rt.block_on(execute_resolution(video_id, isrc, title, artist)) {
            Ok(url) => url,
            Err(_) => return -2, // Upstream network / parsing failure
        };

        let bytes = cdn_url.as_bytes();
        if bytes.len() > out_buf_len {
            return -1; // Buffer too small
        }

        std::ptr::copy_nonoverlapping(bytes.as_ptr(), out_buf, bytes.len());
        bytes.len() as i32
    });

    result.unwrap_or(-3)
}

pub struct StreamResolver;

impl StreamResolver {
    const INNERTUBE_PLAYER_URL: &'static str = "https://www.youtube.com/youtubei/v1/player";
    const INNERTUBE_SEARCH_URL: &'static str = "https://music.youtube.com/youtubei/v1/search";

    /// Resolves direct playable CDN stream URLs synchronously
    pub fn resolve_stream(video_id: &str) -> Result<Vec<ResolvedStreamFormat>, String> {
        let rt = get_runtime();
        let client = get_client();
        rt.block_on(async {
            let cdn_url = fetch_innertube_cdn(client, video_id).await?;
            Ok(vec![ResolvedStreamFormat {
                itag: 140,
                mime_type: "audio/mp4".to_string(),
                bitrate: 128000,
                url: cdn_url,
                content_length: 0,
                audio_quality: "AUDIO_QUALITY_MEDIUM".to_string(),
                is_audio: true,
                is_video: false,
            }])
        })
    }

    /// Searches YouTube Music and returns parsed candidates
    pub fn search_music(query: &str, limit: usize) -> Result<Vec<ParsedCandidate>, String> {
        let body = serde_json::json!({
            "context": {
                "client": {
                    "clientName": "WEB_REMIX",
                    "clientVersion": "1.20230515.01.00",
                    "hl": "en",
                    "gl": "US"
                }
            },
            "query": query,
            "params": "egWKAQIIAWoMEAMQBBAJEAoQBRAV"
        });

        let resp = ureq::post(Self::INNERTUBE_SEARCH_URL)
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .set("Content-Type", "application/json")
            .send_json(body)
            .map_err(|e| format!("Search request failed: {}", e))?;

        let text = resp
            .into_string()
            .map_err(|e| format!("Failed to read search response: {}", e))?;

        let mut candidates = InnertubeParser::parse_candidates(&text);
        if candidates.len() > limit {
            candidates.truncate(limit);
        }
        Ok(candidates)
    }

    /// Imports a public Spotify playlist or album URL and extracts track items
    pub fn import_spotify_tracks(spotify_url: &str) -> Result<Vec<ParsedCandidate>, String> {
        let clean_url = spotify_url.trim();
        let (item_type, item_id) = Self::parse_spotify_uri(clean_url)
            .ok_or_else(|| "Invalid Spotify URL or URI".to_string())?;

        let embed_url = format!("https://open.spotify.com/embed/{}/{}", item_type, item_id);
        let resp = ureq::get(&embed_url)
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .call()
            .map_err(|e| format!("Spotify embed request failed: {}", e))?;

        let html = resp
            .into_string()
            .map_err(|e| format!("Failed to read embed HTML: {}", e))?;

        let re = regex::Regex::new(r#"<script id="(?:session|__NEXT_DATA__|resource)" type="application/json">([^<]+)</script>"#)
            .map_err(|e| e.to_string())?;

        let mut tracks = Vec::new();
        if let Some(captures) = re.captures(&html) {
            if let Some(json_match) = captures.get(1) {
                let parsed: Value = serde_json::from_str(json_match.as_str())
                    .map_err(|e| format!("Failed to parse embedded Spotify JSON: {}", e))?;

                let track_list = parsed
                    .pointer("/props/pageProps/state/data/entity/trackList")
                    .or_else(|| parsed.pointer("/props/state/data/entity/trackList"))
                    .or_else(|| parsed.pointer("/entity/trackList"))
                    .or_else(|| parsed.pointer("/trackList"))
                    .and_then(|v| v.as_array());

                if let Some(items) = track_list {
                    for (idx, item) in items.iter().enumerate() {
                        let title = item.get("title").or_else(|| item.get("name")).and_then(|v| v.as_str()).unwrap_or("Unknown Title");
                        let subtitle = item.get("subtitle").or_else(|| item.get("artist")).and_then(|v| v.as_str()).unwrap_or("Unknown Artist");
                        let duration_ms = item.get("duration").and_then(|v| v.as_u64()).unwrap_or(0) as u32;

                        tracks.push(ParsedCandidate {
                            id: format!("spotify_{}_{}", item_id, idx),
                            title: title.to_string(),
                            artist: subtitle.to_string(),
                            album: "Spotify Import".to_string(),
                            duration_sec: duration_ms / 1000,
                            thumbnail_url: "".to_string(),
                            score: 100,
                        });
                    }
                }
            }
        }

        Ok(tracks)
    }

    fn parse_spotify_uri(url: &str) -> Option<(&str, &str)> {
        if url.starts_with("spotify:") {
            let parts: Vec<&str> = url.split(':').collect();
            if parts.len() >= 3 {
                return Some((parts[1], parts[2]));
            }
        }

        if url.contains("open.spotify.com") {
            let path = url.split("open.spotify.com/").nth(1)?;
            let parts: Vec<&str> = path.split('?').next()?.split('/').collect();
            if parts.len() >= 2 {
                return Some((parts[0], parts[1]));
            }
        }

        None
    }
}
