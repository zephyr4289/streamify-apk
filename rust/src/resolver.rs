use crate::json::{InnertubeParser, ParsedCandidate, ResolvedStreamFormat};
use serde_json::Value;

pub struct StreamResolver;

impl StreamResolver {
    const INNERTUBE_PLAYER_URL: &'static str = "https://www.youtube.com/youtubei/v1/player";
    const INNERTUBE_SEARCH_URL: &'static str = "https://music.youtube.com/youtubei/v1/search";
    const USER_AGENT_VR: &'static str = "Mozilla/5.0 (Linux; Android 12; Quest 3) AppleWebKit/537.36 (KHTML, like Gecko) OculusBrowser/33.0.0.19.46.568453472 SamsungBrowser/4.0 Chrome/122.0.6261.139 Mobile VR Safari/537.36";
    const USER_AGENT_IOS: &'static str = "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X; en_US)";

    /// Resolves direct playable CDN stream URLs for a given YouTube video ID.
    pub fn resolve_stream(video_id: &str) -> Result<Vec<ResolvedStreamFormat>, String> {
        let clean_id = video_id.trim();
        if clean_id.is_empty() {
            return Err("Empty video ID".to_string());
        }

        // 1. Try ANDROID_VR target (Direct unencrypted Opus/AAC streams)
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
            "playbackContext": {
                "contentPlaybackContext": {
                    "signatureTimestamp": 19850
                }
            }
        });

        if let Ok(resp) = ureq::post(Self::INNERTUBE_PLAYER_URL)
            .set("User-Agent", Self::USER_AGENT_VR)
            .set("X-YouTube-Client-Name", "28")
            .set("X-YouTube-Client-Version", "1.60.19")
            .set("Content-Type", "application/json")
            .send_json(body_vr)
        {
            if let Ok(text) = resp.into_string() {
                let streams = InnertubeParser::parse_player_streams(&text);
                if !streams.is_empty() {
                    return Ok(streams);
                }
            }
        }

        // 2. Fallback to IOS client target
        let body_ios = serde_json::json!({
            "context": {
                "client": {
                    "clientName": "IOS",
                    "clientVersion": "19.29.1",
                    "deviceMake": "Apple",
                    "deviceModel": "iPhone16,2",
                    "osName": "iOS",
                    "osVersion": "17.5.1.21F90",
                    "hl": "en",
                    "gl": "US"
                }
            },
            "videoId": clean_id,
            "playbackContext": {
                "contentPlaybackContext": {
                    "signatureTimestamp": 19850
                }
            }
        });

        if let Ok(resp) = ureq::post(Self::INNERTUBE_PLAYER_URL)
            .set("User-Agent", Self::USER_AGENT_IOS)
            .set("X-YouTube-Client-Name", "5")
            .set("X-YouTube-Client-Version", "19.29.1")
            .set("Content-Type", "application/json")
            .send_json(body_ios)
        {
            if let Ok(text) = resp.into_string() {
                let streams = InnertubeParser::parse_player_streams(&text);
                if !streams.is_empty() {
                    return Ok(streams);
                }
            }
        }

        // 3. Fallback to WEB_REMIX client
        Self::resolve_stream_web_remix(clean_id)
    }

    fn resolve_stream_web_remix(video_id: &str) -> Result<Vec<ResolvedStreamFormat>, String> {
        let body = serde_json::json!({
            "context": {
                "client": {
                    "clientName": "WEB_REMIX",
                    "clientVersion": "1.20240815.01.00",
                    "hl": "en",
                    "gl": "US"
                }
            },
            "videoId": video_id
        });

        let resp = ureq::post(Self::INNERTUBE_PLAYER_URL)
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
            .set("Origin", "https://music.youtube.com")
            .set("Referer", "https://music.youtube.com/")
            .set("X-YouTube-Client-Name", "67")
            .set("X-YouTube-Client-Version", "1.20240815.01.00")
            .set("Content-Type", "application/json")
            .send_json(body)
            .map_err(|e| format!("Web remix fallback failed: {}", e))?;

        let text = resp
            .into_string()
            .map_err(|e| format!("Failed to read web remix response: {}", e))?;

        let streams = InnertubeParser::parse_player_streams(&text);
        if streams.is_empty() {
            return Err("No playable stream formats found".to_string());
        }
        Ok(streams)
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
            "params": "egWKAQIIAWoMEAMQBBAJEAoQBRAV" // Filter: Songs
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

        // Extract JSON embedded in HTML
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
