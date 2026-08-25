use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ParsedCandidate {
    pub id: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_sec: u32,
    pub thumbnail_url: String,
    pub score: u8,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResolvedStreamFormat {
    pub url: String,
    pub mime_type: String,
    pub bitrate: u32,
    pub duration_sec: u32,
    pub is_audio_only: bool,
}

pub struct InnertubeParser;

impl InnertubeParser {
    /// Extracts track candidates from Innertube next/search raw JSON without recursive heap storms.
    pub fn parse_candidates(raw_json: &str) -> Vec<ParsedCandidate> {
        let root: Value = match serde_json::from_str(raw_json) {
            Ok(v) => v,
            Err(_) => return Vec::new(),
        };

        let mut results = Vec::with_capacity(32);
        Self::collect_video_renderers(&root, &mut results);
        results
    }

    fn collect_video_renderers(val: &Value, results: &mut Vec<ParsedCandidate>) {
        match val {
            Value::Object(map) => {
                if let Some(renderer) = map.get("playlistPanelVideoRenderer") {
                    if let Some(candidate) = Self::extract_playlist_panel_video(renderer) {
                        results.push(candidate);
                    }
                } else if let Some(renderer) = map.get("musicResponsiveListItemRenderer") {
                    if let Some(candidate) = Self::extract_music_responsive_item(renderer) {
                        results.push(candidate);
                    }
                } else if let Some(renderer) = map.get("videoRenderer") {
                    if let Some(candidate) = Self::extract_video_renderer(renderer) {
                        results.push(candidate);
                    }
                } else {
                    for (_k, v) in map {
                        Self::collect_video_renderers(v, results);
                    }
                }
            }
            Value::Array(arr) => {
                for item in arr {
                    Self::collect_video_renderers(item, results);
                }
            }
            _ => {}
        }
    }

    fn extract_playlist_panel_video(node: &Value) -> Option<ParsedCandidate> {
        let video_id = node.get("videoId")?.as_str()?.to_string();
        if video_id.is_empty() {
            return None;
        }

        let title = node
            .pointer("/title/runs/0/text")
            .or_else(|| node.pointer("/title/simpleText"))
            .and_then(|v| v.as_str())
            .unwrap_or("Unknown Title")
            .to_string();

        let artist = node
            .pointer("/longBylineText/runs/0/text")
            .or_else(|| node.pointer("/shortBylineText/runs/0/text"))
            .and_then(|v| v.as_str())
            .unwrap_or("Unknown Artist")
            .to_string();

        let duration_text = node
            .pointer("/lengthText/runs/0/text")
            .or_else(|| node.pointer("/lengthText/simpleText"))
            .and_then(|v| v.as_str())
            .unwrap_or("0:00");

        let duration_sec = Self::parse_duration_str(duration_text);

        let thumbnail_url = node
            .pointer("/thumbnail/thumbnails")
            .and_then(|v| v.as_array())
            .and_then(|arr| arr.last())
            .and_then(|last| last.get("url"))
            .and_then(|u| u.as_str())
            .unwrap_or("")
            .to_string();

        Some(ParsedCandidate {
            id: video_id,
            title,
            artist,
            album: "Streamify Radio".to_string(),
            duration_sec,
            thumbnail_url,
            score: 100,
        })
    }

    fn extract_music_responsive_item(node: &Value) -> Option<ParsedCandidate> {
        let video_id = node
            .pointer("/playlistItemData/videoId")
            .or_else(|| node.pointer("/doubleTapCommand/watchEndpoint/videoId"))
            .or_else(|| node.pointer("/playNavigationEndpoint/watchEndpoint/videoId"))
            .and_then(|v| v.as_str())?
            .to_string();

        let title = node
            .pointer("/flexColumns/0/musicResponsiveListItemFlexColumnRenderer/text/runs/0/text")
            .and_then(|v| v.as_str())
            .unwrap_or("Unknown Title")
            .to_string();

        let artist = node
            .pointer("/flexColumns/1/musicResponsiveListItemFlexColumnRenderer/text/runs/0/text")
            .and_then(|v| v.as_str())
            .unwrap_or("Unknown Artist")
            .to_string();

        let thumbnail_url = node
            .pointer("/thumbnail/musicThumbnailRenderer/thumbnail/thumbnails")
            .and_then(|v| v.as_array())
            .and_then(|arr| arr.last())
            .and_then(|last| last.get("url"))
            .and_then(|u| u.as_str())
            .unwrap_or("")
            .to_string();

        Some(ParsedCandidate {
            id: video_id,
            title,
            artist,
            album: "YouTube Music".to_string(),
            duration_sec: 210,
            thumbnail_url,
            score: 95,
        })
    }

    fn extract_video_renderer(node: &Value) -> Option<ParsedCandidate> {
        let video_id = node.get("videoId")?.as_str()?.to_string();
        let title = node
            .pointer("/title/runs/0/text")
            .and_then(|v| v.as_str())
            .unwrap_or("Unknown Title")
            .to_string();

        let artist = node
            .pointer("/ownerText/runs/0/text")
            .and_then(|v| v.as_str())
            .unwrap_or("Unknown Artist")
            .to_string();

        let duration_text = node
            .pointer("/lengthText/simpleText")
            .and_then(|v| v.as_str())
            .unwrap_or("0:00");

        let duration_sec = Self::parse_duration_str(duration_text);

        let thumbnail_url = node
            .pointer("/thumbnail/thumbnails")
            .and_then(|v| v.as_array())
            .and_then(|arr| arr.last())
            .and_then(|last| last.get("url"))
            .and_then(|u| u.as_str())
            .unwrap_or("")
            .to_string();

        Some(ParsedCandidate {
            id: video_id,
            title,
            artist,
            album: "YouTube".to_string(),
            duration_sec,
            thumbnail_url,
            score: 90,
        })
    }

    /// Extracts direct playable CDN streams from Innertube player response
    pub fn parse_player_streams(raw_json: &str) -> Vec<ResolvedStreamFormat> {
        let root: Value = match serde_json::from_str(raw_json) {
            Ok(v) => v,
            Err(_) => return Vec::new(),
        };

        let mut streams = Vec::new();
        let adaptive = root.pointer("/streamingData/adaptiveFormats").and_then(|v| v.as_array());

        if let Some(formats) = adaptive {
            for f in formats {
                if let Some(url) = Self::extract_format_url(f) {
                    let mime_type = f.get("mimeType").and_then(|m| m.as_str()).unwrap_or("");
                    let bitrate = f.get("bitrate").and_then(|b| b.as_u64()).unwrap_or(0) as u32;
                    let is_audio = mime_type.starts_with("audio/");
                    let approx_duration_ms = f
                        .get("approxDurationMs")
                        .and_then(|d| d.as_str())
                        .and_then(|s| s.parse::<u32>().ok())
                        .unwrap_or(0);

                    streams.push(ResolvedStreamFormat {
                        url,
                        mime_type: mime_type.to_string(),
                        bitrate,
                        duration_sec: approx_duration_ms / 1000,
                        is_audio_only: is_audio,
                    });
                }
            }
        }

        // Standard progressive formats fallback
        if streams.is_empty() {
            if let Some(formats) = root.pointer("/streamingData/formats").and_then(|v| v.as_array()) {
                for f in formats {
                    if let Some(url) = Self::extract_format_url(f) {
                        let mime_type = f.get("mimeType").and_then(|m| m.as_str()).unwrap_or("");
                        let bitrate = f.get("bitrate").and_then(|b| b.as_u64()).unwrap_or(0) as u32;
                        let is_audio = mime_type.starts_with("audio/");
                        let approx_duration_ms = f
                            .get("approxDurationMs")
                            .and_then(|d| d.as_str())
                            .and_then(|s| s.parse::<u32>().ok())
                            .unwrap_or(0);

                        streams.push(ResolvedStreamFormat {
                            url,
                            mime_type: mime_type.to_string(),
                            bitrate,
                            duration_sec: approx_duration_ms / 1000,
                            is_audio_only: is_audio,
                        });
                    }
                }
            }
        }

        // Sort: audio-only first, then highest bitrate
        streams.sort_by(|a, b| {
            b.is_audio_only
                .cmp(&a.is_audio_only)
                .then_with(|| b.bitrate.cmp(&a.bitrate))
        });

        streams
    }

    fn extract_format_url(f: &Value) -> Option<String> {
        if let Some(url) = f.get("url").and_then(|u| u.as_str()) {
            if !url.is_empty() {
                return Some(url.to_string());
            }
        }
        None
    }

    pub fn parse_duration_str(s: &str) -> u32 {
        let parts: Vec<&str> = s.split(':').collect();
        match parts.len() {
            1 => parts[0].parse::<u32>().unwrap_or(0),
            2 => {
                let mins = parts[0].parse::<u32>().unwrap_or(0);
                let secs = parts[1].parse::<u32>().unwrap_or(0);
                mins * 60 + secs
            }
            3 => {
                let hours = parts[0].parse::<u32>().unwrap_or(0);
                let mins = parts[1].parse::<u32>().unwrap_or(0);
                let secs = parts[2].parse::<u32>().unwrap_or(0);
                hours * 3600 + mins * 60 + secs
            }
            _ => 0,
        }
    }

    pub fn extract_best_stream_info(raw_json: &str) -> Option<ExtractedStreamInfo> {
        let root: Value = serde_json::from_str(raw_json).ok()?;

        let loudness_db = root
            .pointer("/playerConfig/audioConfig/loudnessDb")
            .and_then(|v| v.as_f64())
            .map(|f| f as f32);

        let mut formats_list = Vec::new();
        if let Some(adaptive) = root.pointer("/streamingData/adaptiveFormats").and_then(|v| v.as_array()) {
            formats_list.extend(adaptive.iter().cloned());
        }
        if let Some(formats) = root.pointer("/streamingData/formats").and_then(|v| v.as_array()) {
            formats_list.extend(formats.iter().cloned());
        }

        let mut candidate_streams = Vec::new();
        for f in &formats_list {
            if let Some(url) = Self::extract_format_url(f) {
                let mime_type = f.get("mimeType").and_then(|m| m.as_str()).unwrap_or("").to_string();
                let bitrate = f.get("bitrate").and_then(|b| b.as_u64()).unwrap_or(0) as u32;
                let itag = f.get("itag").and_then(|i| i.as_u64()).unwrap_or(0) as u32;
                let is_audio = mime_type.starts_with("audio/");
                let approx_duration_ms = f
                    .get("approxDurationMs")
                    .and_then(|d| d.as_str())
                    .and_then(|s| s.parse::<u32>().ok())
                    .unwrap_or(0);

                let is_opus = mime_type.contains("opus") || itag == 251;
                let is_aac = mime_type.contains("mp4a") || itag == 140;

                // Priority score: Audio Opus > Audio AAC > other audio > video
                let priority: u32 = if is_opus {
                    300_000 + bitrate
                } else if is_aac {
                    200_000 + bitrate
                } else if is_audio {
                    100_000 + bitrate
                } else {
                    bitrate
                };

                let expire_epoch = url
                    .split('?')
                    .nth(1)
                    .unwrap_or("")
                    .split('&')
                    .find(|p| p.starts_with("expire="))
                    .and_then(|p| p.strip_prefix("expire="))
                    .and_then(|e| e.parse::<u64>().ok())
                    .unwrap_or(0);

                candidate_streams.push((
                    priority,
                    ExtractedStreamInfo {
                        stream_url: url,
                        mime_type,
                        bitrate,
                        duration_sec: approx_duration_ms / 1000,
                        loudness_db,
                        expiration_epoch: expire_epoch,
                    },
                ));
            }
        }

        candidate_streams.sort_by(|a, b| b.0.cmp(&a.0));
        candidate_streams.into_iter().next().map(|(_, stream)| stream)
    }

    pub fn extract_session_tokens(html: &str) -> Option<ExtractedSessionTokens> {
        let visitor_id = regex::Regex::new(r#""visitorData"\s*:\s*"([^"]+)""#)
            .ok()?
            .captures(html)
            .and_then(|cap| cap.get(1))
            .map(|m| m.as_str().to_string())
            .unwrap_or_default();

        let sts = regex::Regex::new(r#""signatureTimestamp"\s*:\s*(\d+)"#)
            .ok()?
            .captures(html)
            .and_then(|cap| cap.get(1))
            .and_then(|m| m.as_str().parse::<u32>().ok());

        Some(ExtractedSessionTokens {
            visitor_id,
            signature_timestamp: sts,
        })
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExtractedStreamInfo {
    pub stream_url: String,
    pub mime_type: String,
    pub bitrate: u32,
    pub duration_sec: u32,
    pub loudness_db: Option<f32>,
    pub expiration_epoch: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExtractedSessionTokens {
    pub visitor_id: String,
    pub signature_timestamp: Option<u32>,
}
