use serde::{Deserialize, Serialize};

pub const SLYR_MAGIC: &[u8; 4] = b"SLYR";
pub const SLYR_VERSION: u16 = 1;

/// Global Header (32 bytes, 16-byte aligned)
#[repr(C, align(16))]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct SlyrHeader {
    pub magic: [u8; 4],
    pub version: u16,
    pub line_count: u16,
    pub syllable_count: u32,
    pub duration_ms: u32,
    pub padding: [u8; 16],
}

/// Line Header Entry (16 bytes, 16-byte aligned)
#[repr(C, align(16))]
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct SlyrLineHeader {
    pub start_ms: u32,
    pub end_ms: u32,
    pub syllable_start_idx: u16,
    pub syllable_count: u16,
    pub text_offset: u32,
}

/// Syllable Span Entry (16 bytes, 16-byte aligned)
#[repr(C, align(16))]
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct SlyrSyllableSpan {
    pub start_ms: u32,
    pub end_ms: u32,
    pub char_start: u16,
    pub char_len: u16,
    pub flags: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ParsedSyllable {
    pub text: String,
    pub start_ms: u32,
    pub end_ms: u32,
    pub flags: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ParsedLine {
    pub line_text: String,
    pub start_ms: u32,
    pub end_ms: u32,
    pub syllables: Vec<ParsedSyllable>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SlyrDocument {
    pub duration_ms: u32,
    pub lines: Vec<ParsedLine>,
}

pub struct LyricCompiler;

impl LyricCompiler {
    /// Compiles standard LRC or enhanced syllable-tagged LRC into a 16-byte aligned binary .slyr buffer
    pub fn compile_to_slyr(lrc_text: &str) -> Vec<u8> {
        let doc = Self::parse_lrc_document(lrc_text);
        Self::serialize_slyr(&doc)
    }

    /// Parses text LRC with support for both standard [mm:ss.xx] and enhanced <mm:ss.xx> syllable tags
    pub fn parse_lrc_document(lrc_text: &str) -> SlyrDocument {
        let mut lines = Vec::new();
        let re_line_ts = regex::Regex::new(r"^\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)$").unwrap();
        let re_syllable_tag = regex::Regex::new(r"<(\d{2}):(\d{2})\.(\d{2,3})>([^<]*)").unwrap();

        let mut max_duration = 0u32;

        for raw_line in lrc_text.lines() {
            let line_trimmed = raw_line.trim();
            if line_trimmed.is_empty() || line_trimmed.starts_with("[ti:") || line_trimmed.starts_with("[ar:") || line_trimmed.starts_with("[al:") {
                continue;
            }

            if let Some(caps) = re_line_ts.captures(line_trimmed) {
                let mins: u32 = caps.get(1).unwrap().as_str().parse().unwrap_or(0);
                let secs: u32 = caps.get(2).unwrap().as_str().parse().unwrap_or(0);
                let ms_str = caps.get(3).unwrap().as_str();
                let ms_val: u32 = if ms_str.len() == 2 {
                    ms_str.parse::<u32>().unwrap_or(0) * 10
                } else {
                    ms_str.parse().unwrap_or(0)
                };

                let line_start_ms = (mins * 60 + secs) * 1000 + ms_val;
                let rest_text = caps.get(4).unwrap().as_str();

                let mut syllables = Vec::new();
                let mut clean_line_text = String::new();

                // Check for enhanced syllable tags <mm:ss.xx>
                let syllable_matches: Vec<_> = re_syllable_tag.captures_iter(rest_text).collect();
                if !syllable_matches.is_empty() {
                    for (i, syl_cap) in syllable_matches.iter().enumerate() {
                        let s_mins: u32 = syl_cap.get(1).unwrap().as_str().parse().unwrap_or(0);
                        let s_secs: u32 = syl_cap.get(2).unwrap().as_str().parse().unwrap_or(0);
                        let s_ms_str = syl_cap.get(3).unwrap().as_str();
                        let s_ms_val: u32 = if s_ms_str.len() == 2 {
                            s_ms_str.parse::<u32>().unwrap_or(0) * 10
                        } else {
                            s_ms_str.parse().unwrap_or(0)
                        };

                        let s_start = (s_mins * 60 + s_secs) * 1000 + s_ms_val;
                        let s_text = syl_cap.get(4).unwrap().as_str().to_string();

                        let s_end = if i + 1 < syllable_matches.len() {
                            let next_cap = &syllable_matches[i + 1];
                            let n_mins: u32 = next_cap.get(1).unwrap().as_str().parse().unwrap_or(0);
                            let n_secs: u32 = next_cap.get(2).unwrap().as_str().parse().unwrap_or(0);
                            let n_ms_str = next_cap.get(3).unwrap().as_str();
                            let n_ms_val: u32 = if n_ms_str.len() == 2 {
                                n_ms_str.parse::<u32>().unwrap_or(0) * 10
                            } else {
                                n_ms_str.parse().unwrap_or(0)
                            };
                            (n_mins * 60 + n_secs) * 1000 + n_ms_val
                        } else {
                            s_start + 400
                        };

                        clean_line_text.push_str(&s_text);
                        syllables.push(ParsedSyllable {
                            text: s_text,
                            start_ms: s_start,
                            end_ms: s_end,
                            flags: 0,
                        });
                    }
                } else {
                    // Standard line-level LRC: interpolate words/syllables evenly
                    let cleaned = rest_text.trim();
                    clean_line_text = cleaned.to_string();

                    let words: Vec<&str> = cleaned.split_whitespace().collect();
                    if !words.is_empty() {
                        let default_line_duration = 3000u32;
                        let step = default_line_duration / words.len() as u32;

                        for (w_idx, w) in words.iter().enumerate() {
                            let w_start = line_start_ms + (w_idx as u32 * step);
                            let w_end = w_start + step;
                            let word_with_space = if w_idx + 1 < words.len() {
                                format!("{} ", w)
                            } else {
                                w.to_string()
                            };

                            syllables.push(ParsedSyllable {
                                text: word_with_space,
                                start_ms: w_start,
                                end_ms: w_end,
                                flags: 0,
                            });
                        }
                    }
                }

                let line_end_ms = syllables.last().map(|s| s.end_ms).unwrap_or(line_start_ms + 3000);
                if line_end_ms > max_duration {
                    max_duration = line_end_ms;
                }

                if !clean_line_text.is_empty() {
                    lines.push(ParsedLine {
                        line_text: clean_line_text,
                        start_ms: line_start_ms,
                        end_ms: line_end_ms,
                        syllables,
                    });
                }
            }
        }

        // Adjust line end timestamps based on subsequent line starts
        for i in 0..lines.len() {
            if i + 1 < lines.len() {
                let next_start = lines[i + 1].start_ms;
                if lines[i].end_ms > next_start {
                    lines[i].end_ms = next_start;
                }
            }
        }

        SlyrDocument {
            duration_ms: max_duration,
            lines,
        }
    }

    /// Serializes a SlyrDocument into a contiguous, 16-byte aligned binary buffer (.slyr)
    pub fn serialize_slyr(doc: &SlyrDocument) -> Vec<u8> {
        let line_count = doc.lines.len() as u16;
        let mut total_syllables = 0u32;
        for line in &doc.lines {
            total_syllables += line.syllables.len() as u32;
        }

        let header = SlyrHeader {
            magic: *SLYR_MAGIC,
            version: SLYR_VERSION,
            line_count,
            syllable_count: total_syllables,
            duration_ms: doc.duration_ms,
            padding: [0u8; 16],
        };

        let mut buffer = Vec::with_capacity(1024);

        // 1. Write Header (32 bytes, 16-byte aligned)
        let header_slice = unsafe {
            std::slice::from_raw_parts(
                &header as *const SlyrHeader as *const u8,
                std::mem::size_of::<SlyrHeader>(),
            )
        };
        buffer.extend_from_slice(header_slice);

        // 2. Prepare LineHeaders and SyllableSpans
        let mut line_headers = Vec::with_capacity(doc.lines.len());
        let mut syllable_spans = Vec::with_capacity(total_syllables as usize);
        let mut text_pool = Vec::new();

        let mut current_syllable_idx = 0u16;

        for line in &doc.lines {
            let text_offset = text_pool.len() as u32;
            let line_bytes = line.line_text.as_bytes();
            text_pool.extend_from_slice(line_bytes);
            text_pool.push(0u8); // null-terminator for safety

            let syl_start = current_syllable_idx;
            let syl_count = line.syllables.len() as u16;

            let mut char_cursor = 0u16;
            for syl in &line.syllables {
                let char_len = syl.text.encode_utf16().count() as u16;
                syllable_spans.push(SlyrSyllableSpan {
                    start_ms: syl.start_ms,
                    end_ms: syl.end_ms,
                    char_start: char_cursor,
                    char_len,
                    flags: syl.flags,
                });
                char_cursor += char_len;
                current_syllable_idx += 1;
            }

            line_headers.push(SlyrLineHeader {
                start_ms: line.start_ms,
                end_ms: line.end_ms,
                syllable_start_idx: syl_start,
                syllable_count: syl_count,
                text_offset,
            });
        }

        // 3. Write LineHeaders
        for lh in &line_headers {
            let slice = unsafe {
                std::slice::from_raw_parts(
                    lh as *const SlyrLineHeader as *const u8,
                    std::mem::size_of::<SlyrLineHeader>(),
                )
            };
            buffer.extend_from_slice(slice);
        }

        // 4. Write SyllableSpans
        for span in &syllable_spans {
            let slice = unsafe {
                std::slice::from_raw_parts(
                    span as *const SlyrSyllableSpan as *const u8,
                    std::mem::size_of::<SlyrSyllableSpan>(),
                )
            };
            buffer.extend_from_slice(slice);
        }

        // 5. Align to 16 bytes before writing TextPool
        while buffer.len() % 16 != 0 {
            buffer.push(0u8);
        }

        // 6. Write TextPool
        buffer.extend_from_slice(&text_pool);

        // Ensure final buffer length is 16-byte aligned
        while buffer.len() % 16 != 0 {
            buffer.push(0u8);
        }

        buffer
    }

    /// Finds the active line index and syllable index in O(log N) from raw .slyr memory
    pub unsafe fn find_active_positions(
        slyr_ptr: *const u8,
        slyr_len: usize,
        position_ms: u32,
    ) -> Option<(usize, usize)> {
        if slyr_ptr.is_null() || slyr_len < std::mem::size_of::<SlyrHeader>() {
            return None;
        }

        let header = &*(slyr_ptr as *const SlyrHeader);
        if &header.magic != SLYR_MAGIC || header.line_count == 0 {
            return None;
        }

        let line_count = header.line_count as usize;
        let line_headers_ptr = slyr_ptr.add(std::mem::size_of::<SlyrHeader>()) as *const SlyrLineHeader;
        let line_headers = std::slice::from_raw_parts(line_headers_ptr, line_count);

        // Binary search for active line
        let line_idx = match line_headers.binary_search_by_key(&position_ms, |lh| lh.start_ms) {
            Ok(idx) => idx,
            Err(0) => 0,
            Err(idx) => idx - 1,
        };

        let active_line = &line_headers[line_idx];
        if active_line.syllable_count == 0 {
            return Some((line_idx, 0));
        }

        let syllable_spans_offset = std::mem::size_of::<SlyrHeader>() + line_count * std::mem::size_of::<SlyrLineHeader>();
        let syllable_spans_ptr = slyr_ptr.add(syllable_spans_offset) as *const SlyrSyllableSpan;
        let syllable_spans = std::slice::from_raw_parts(syllable_spans_ptr, header.syllable_count as usize);

        let syl_start = active_line.syllable_start_idx as usize;
        let syl_end = syl_start + active_line.syllable_count as usize;
        let active_syllables = &syllable_spans[syl_start..syl_end.min(syllable_spans.len())];

        let syl_rel_idx = match active_syllables.binary_search_by_key(&position_ms, |s| s.start_ms) {
            Ok(idx) => idx,
            Err(0) => 0,
            Err(idx) => idx - 1,
        };

        Some((line_idx, syl_start + syl_rel_idx))
    }
}

