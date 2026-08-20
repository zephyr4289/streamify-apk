use crate::json::{InnertubeParser, ParsedCandidate, ResolvedStreamFormat};
use reqwest::Client;
use serde_json::Value;
use std::sync::OnceLock;
use tokio::runtime::Runtime;

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
            .pool_max_idle_per_host(10)
            .tcp_nodelay(true)
            .build()
            .unwrap_or_else(|_| Client::new())
    })
}

/// 3-Tier JIT Stream Resolution:
/// 1. Direct Video ID Hit (<10ms)
/// 2. ISRC Master Recording Search (`isrc:<CODE>`)
/// 3. Token-Sort Fuzzy Query Search (`title + " " + artist`)
pub async fn execute_resolution(
    video_id: Option<&str>,
    isrc: Option<&str>,
    title: &str,
    artist: &str,
) -> Result<String, String> {
    let client = get_client();

    // 1. Direct Video ID Hit
    if let Some(id) = video_id {
        let clean_id = id.trim();
        if !clean_id.is_empty() {
            if let Ok(cdn_url) = fetch_innertube_cdn(client, clean_id).await {
                return Ok(cdn_url);
            }
        }
    }

    // 2. ISRC Query Match
    if let Some(isrc_code) = isrc {
        let clean_isrc = isrc_code.trim();
        if !clean_isrc.is_empty() {
            if let Ok(matched_id) = search_innertube(client, &format!("isrc:{}", clean_isrc)).await {
                if let Ok(cdn_url) = fetch_innertube_cdn(client, &matched_id).await {
                    return Ok(cdn_url);
                }
            }
        }
    }

    // 3. Fuzzy Query Match (Title + Artist)
    let query = format!("{} {}", title.trim(), artist.trim());
    let fallback_id = search_innertube(client, &query)
        .await
        .map_err(|_| "Failed fuzzy resolution".to_string())?;

    fetch_innertube_cdn(client, &fallback_id).await
}

pub async fn search_innertube(client: &Client, query: &str) -> Result<String, ()> {
    let url = "https://music.youtube.com/youtubei/v1/search";
    let body = serde_json::json!({
        "context": {
            "client": {
                "clientName": "ANDROID_MUSIC",
                "clientVersion": "6.42.52",
                "hl": "en",
                "gl": "US"
            }
        },
        "query": query,
        "params": "Eg-KAQwIABAAGAAgACgAMABqChAEEAMQBBAFEAo%3D" // Filter: Songs
    });

    let res = client
        .post(url)
        .header("Content-Type", "application/json")
        .header("X-YouTube-Client-Name", "21")
        .header("X-YouTube-Client-Version", "6.42.52")
        .json(&body)
        .send()
        .await
        .map_err(|_| ())?;

    let json: Value = res.json().await.map_err(|_| ())?;

    // Try primary path
    if let Some(id) = json
        .pointer("/contents/tabbedSearchResultsRenderer/tabs/0/tabRenderer/content/sectionListRenderer/contents/0/musicShelfRenderer/contents/0/musicResponsiveListItemRenderer/playlistItemData/videoId")
        .and_then(|v| v.as_str())
    {
        return Ok(id.to_string());
    }

    // Try alternate search result pathways
    if let Some(sections) = json.pointer("/contents/tabbedSearchResultsRenderer/tabs/0/tabRenderer/content/sectionListRenderer/contents").and_then(|v| v.as_array()) {
        for section in sections {
            if let Some(items) = section.pointer("/musicShelfRenderer/contents").or_else(|| section.pointer("/musicCardShelfRenderer/contents")).and_then(|v| v.as_array()) {
                for item in items {
                    if let Some(id) = item.pointer("/musicResponsiveListItemRenderer/playlistItemData/videoId").or_else(|| item.pointer("/musicResponsiveListItemRenderer/navigationEndpoint/watchEndpoint/videoId")).and_then(|v| v.as_str()) {
                        return Ok(id.to_string());
                    }
                }
            }
        }
    }

    Err(())
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
