use serde::{Deserialize, Serialize};

pub const SLYR_MAGIC: u32 = 0x534C5952; // "SLYR"
pub const SLYR_VERSION: u16 = 1;

#[repr(C, align(16))]
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct SlyrHeader {
    pub magic: u32,             // 0x534C5952
    pub version: u16,           // 1
    pub line_count: u16,        // Total lyric lines
    pub syllable_count: u32,    // Total syllable segments
    pub text_pool_len: u32,     // Byte length of UTF-8 string pool
    pub vocal_offset_ms: i32,   // Auto-calibrated KissFFT drift offset (Δτ*)
    pub flags: u32,             // Bit 0: Has Syllables, Bit 1: Is Explicit
    pub reserved: [u8; 8],      // 16-byte boundary padding
}

#[repr(C, align(16))]
#[derive(Debug, Clone, Copy, Default, PartialEq, Serialize, Deserialize)]
pub struct SlyrLineHeader {
    pub start_time_ms: u32,     // Line onset
    pub end_time_ms: u32,       // Line offset
    pub syllable_start_idx: u16,// Index into syllable table
    pub syllable_count: u16,    // Number of syllables in line
    pub text_offset: u32,       // Byte offset into UTF-8 text pool
}

#[repr(C, align(16))]
#[derive(Debug, Clone, Copy, Default, PartialEq, Serialize, Deserialize)]
pub struct SlyrSyllableSpan {
    pub start_time_ms: u32,     // Syllable start
    pub end_time_ms: u32,       // Syllable end
    pub char_start: u16,        // Relative character start inside line text
    pub char_len: u16,          // Character length
    pub flags: u32,             // Bit 0: Background vocal, Bit 1: Melisma
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RawParsedLine {
    pub start_time_ms: u32,
    pub end_time_ms: u32,
    pub text: String,
    pub syllables: Vec<(u32, u32, u16, u16)>, // (start_ms, end_ms, char_start, char_len)
}

pub struct SlyrCompiler;

impl SlyrCompiler {
    /// Compiles parsed multi-track text into a contiguous 16-byte aligned binary buffer
    pub fn compile(lines: &[RawParsedLine], vocal_offset_ms: i32) -> Vec<u8> {
        let mut text_pool = Vec::new();
        let mut line_headers = Vec::with_capacity(lines.len());
        let mut syllable_spans = Vec::new();

        for line in lines {
            let text_offset = text_pool.len() as u32;
            let text_bytes = line.text.as_bytes();
            text_pool.extend_from_slice(text_bytes);
            text_pool.push(0); // Null terminator

            let syllable_start_idx = syllable_spans.len() as u16;
            let syllable_count = line.syllables.len() as u16;

            for &(s_start, s_end, c_start, c_len) in &line.syllables {
                syllable_spans.push(SlyrSyllableSpan {
                    start_time_ms: s_start,
                    end_time_ms: s_end,
                    char_start: c_start,
                    char_len: c_len,
                    flags: 0,
                });
            }

            line_headers.push(SlyrLineHeader {
                start_time_ms: line.start_time_ms,
                end_time_ms: line.end_time_ms,
                syllable_start_idx,
                syllable_count,
                text_offset,
            });
        }

        let header = SlyrHeader {
            magic: SLYR_MAGIC,
            version: SLYR_VERSION,
            line_count: line_headers.len() as u16,
            syllable_count: syllable_spans.len() as u32,
            text_pool_len: text_pool.len() as u32,
            vocal_offset_ms,
            flags: if !syllable_spans.is_empty() { 1 } else { 0 },
            reserved: [0; 8],
        };

        // Serialize into memory buffer with exact 16-byte struct alignment
        let mut buffer = Vec::new();
        unsafe {
            let header_slice = std::slice::from_raw_parts(
                &header as *const _ as *const u8,
                std::mem::size_of::<SlyrHeader>(),
            );
            buffer.extend_from_slice(header_slice);

            let lines_slice = std::slice::from_raw_parts(
                line_headers.as_ptr() as *const u8,
                line_headers.len() * std::mem::size_of::<SlyrLineHeader>(),
            );
            buffer.extend_from_slice(lines_slice);

            let syllables_slice = std::slice::from_raw_parts(
                syllable_spans.as_ptr() as *const u8,
                syllable_spans.len() * std::mem::size_of::<SlyrSyllableSpan>(),
            );
            buffer.extend_from_slice(syllables_slice);
        }

        buffer.extend_from_slice(&text_pool);
        while buffer.len() % 16 != 0 {
            buffer.push(0);
        }
        buffer
    }

    /// O(log N) binary search lookup for active line and syllable
    pub fn find_active_line(buffer: &[u8], playhead_ms: u32) -> Option<usize> {
        if buffer.len() < std::mem::size_of::<SlyrHeader>() {
            return None;
        }

        let header = unsafe { &*(buffer.as_ptr() as *const SlyrHeader) };
        if header.magic != SLYR_MAGIC {
            return None;
        }

        let adjusted_time = (playhead_ms as i32 + header.vocal_offset_ms).max(0) as u32;
        let line_offset = std::mem::size_of::<SlyrHeader>();
        let total_lines = header.line_count as usize;

        if buffer.len() < line_offset + total_lines * std::mem::size_of::<SlyrLineHeader>() {
            return None;
        }

        let lines = unsafe {
            std::slice::from_raw_parts(
                buffer[line_offset..].as_ptr() as *const SlyrLineHeader,
                total_lines,
            )
        };

        lines
            .binary_search_by(|line| {
                if adjusted_time < line.start_time_ms {
                    std::cmp::Ordering::Greater
                } else if adjusted_time > line.end_time_ms {
                    std::cmp::Ordering::Less
                } else {
                    std::cmp::Ordering::Equal
                }
            })
            .ok()
    }
}

// Backward-compatible LyricCompiler helper for string LRC conversion
pub struct LyricCompiler;

impl LyricCompiler {
    pub fn compile_to_slyr(lrc_text: &str) -> Vec<u8> {
        let mut raw_lines = Vec::new();
        let re_line = regex::Regex::new(r"^\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)$").unwrap();

        for line_str in lrc_text.lines() {
            let trimmed = line_str.trim();
            if trimmed.is_empty() || trimmed.starts_with("[ti:") || trimmed.starts_with("[ar:") {
                continue;
            }
            if let Some(caps) = re_line.captures(trimmed) {
                let m: u32 = caps[1].parse().unwrap_or(0);
                let s: u32 = caps[2].parse().unwrap_or(0);
                let ms: u32 = if caps[3].len() == 2 {
                    caps[3].parse::<u32>().unwrap_or(0) * 10
                } else {
                    caps[3].parse().unwrap_or(0)
                };
                let start_ms = (m * 60 + s) * 1000 + ms;
                let text = caps[4].trim().to_string();
                if !text.is_empty() {
                    let end_ms = start_ms + 3000;
                    raw_lines.push(RawParsedLine {
                        start_time_ms: start_ms,
                        end_time_ms: end_ms,
                        text,
                        syllables: Vec::new(),
                    });
                }
            }
        }

        for i in 0..raw_lines.len() {
            if i + 1 < raw_lines.len() {
                let next_start = raw_lines[i + 1].start_time_ms;
                raw_lines[i].end_time_ms = next_start;
            }
        }

        SlyrCompiler::compile(&raw_lines, 0)
    }
}
