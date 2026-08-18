use crate::consensus::ConsensusEngine;
use crate::json::InnertubeParser;
use crate::lyrics::LyricCompiler;
use crate::ptp::PtpFilter;
use crate::resolver::StreamResolver;
use crate::tagger::AudioMetadataEngine;
use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Mutex;

static GLOBAL_PTP_FILTER: Mutex<Option<PtpFilter>> = Mutex::new(None);

#[no_mangle]
pub unsafe extern "C" fn rust_free_string(s: *mut c_char) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if !s.is_null() {
            drop(CString::from_raw(s));
        }
    }));
}

#[no_mangle]
pub unsafe extern "C" fn rust_parse_innertube_candidates(
    json_ptr: *const u8,
    json_len: usize,
) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if json_ptr.is_null() || json_len == 0 {
            return std::ptr::null_mut();
        }

        let slice = std::slice::from_raw_parts(json_ptr, json_len);
        let text = match std::str::from_utf8(slice) {
            Ok(t) => t,
            Err(_) => return std::ptr::null_mut(),
        };

        let candidates = InnertubeParser::parse_candidates(text);
        let json_output = match serde_json::to_string(&candidates) {
            Ok(j) => j,
            Err(_) => return std::ptr::null_mut(),
        };

        CString::new(json_output).map(|c| c.into_raw()).unwrap_or(std::ptr::null_mut())
    }));

    result.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn rust_resolve_stream_url(video_id_ptr: *const c_char) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if video_id_ptr.is_null() {
            return std::ptr::null_mut();
        }

        let video_id = match CStr::from_ptr(video_id_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };

        match StreamResolver::resolve_stream(video_id) {
            Ok(streams) => {
                let json_str = serde_json::to_string(&streams).unwrap_or_default();
                CString::new(json_str).map(|c| c.into_raw()).unwrap_or(std::ptr::null_mut())
            }
            Err(_) => std::ptr::null_mut(),
        }
    }));

    result.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn rust_write_audio_metadata(
    file_path_ptr: *const c_char,
    title_ptr: *const c_char,
    artist_ptr: *const c_char,
    album_ptr: *const c_char,
    cover_art_ptr: *const c_char,
    lyrics_ptr: *const c_char,
) -> i32 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if file_path_ptr.is_null() || title_ptr.is_null() || artist_ptr.is_null() {
            return -1;
        }

        let file_path = match CStr::from_ptr(file_path_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return -2,
        };

        let title = match CStr::from_ptr(title_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return -3,
        };

        let artist = match CStr::from_ptr(artist_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return -4,
        };

        let album = if !album_ptr.is_null() {
            CStr::from_ptr(album_ptr).to_str().unwrap_or("Streamify")
        } else {
            "Streamify"
        };

        let cover_art = if !cover_art_ptr.is_null() {
            CStr::from_ptr(cover_art_ptr).to_str().ok()
        } else {
            None
        };

        let lyrics = if !lyrics_ptr.is_null() {
            CStr::from_ptr(lyrics_ptr).to_str().ok()
        } else {
            None
        };

        match AudioMetadataEngine::write_metadata(file_path, title, artist, album, cover_art, lyrics) {
            Ok(_) => 0,
            Err(_) => -5,
        }
    }));

    result.unwrap_or(-999)
}

#[no_mangle]
pub unsafe extern "C" fn rust_compile_lyrics(
    lrc_ptr: *const u8,
    lrc_len: usize,
) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if lrc_ptr.is_null() || lrc_len == 0 {
            return std::ptr::null_mut();
        }

        let slice = std::slice::from_raw_parts(lrc_ptr, lrc_len);
        let text = match std::str::from_utf8(slice) {
            Ok(t) => t,
            Err(_) => return std::ptr::null_mut(),
        };

        let compiled = LyricCompiler::compile_lrc(text);
        let json_output = match serde_json::to_string(&compiled) {
            Ok(j) => j,
            Err(_) => return std::ptr::null_mut(),
        };

        CString::new(json_output).map(|c| c.into_raw()).unwrap_or(std::ptr::null_mut())
    }));

    result.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn rust_generate_proof_of_compute(
    pcm_ptr: *const f32,
    pcm_len: usize,
    nonce_ptr: *const c_char,
) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if pcm_ptr.is_null() || pcm_len == 0 || nonce_ptr.is_null() {
            return std::ptr::null_mut();
        }

        let nonce = match CStr::from_ptr(nonce_ptr).to_str() {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };

        let pcm_slice = std::slice::from_raw_parts(pcm_ptr, pcm_len);
        let proof = ConsensusEngine::generate_proof_of_compute(pcm_slice, nonce);

        CString::new(proof).map(|c| c.into_raw()).unwrap_or(std::ptr::null_mut())
    }));

    result.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn rust_process_ptp_timestamps(
    t0: i64,
    t1: i64,
    t2: i64,
    t3: i64,
) -> i64 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let mut guard = match GLOBAL_PTP_FILTER.lock() {
            Ok(g) => g,
            Err(poisoned) => poisoned.into_inner(),
        };
        let filter = guard.get_or_insert_with(|| PtpFilter::new(0.25));
        filter.process_timestamps(t0, t1, t2, t3)
    }));

    result.unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn rust_compile_to_slyr(
    lrc_ptr: *const u8,
    lrc_len: usize,
    out_len: *mut usize,
) -> *mut u8 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if lrc_ptr.is_null() || lrc_len == 0 || out_len.is_null() {
            return std::ptr::null_mut();
        }

        let slice = std::slice::from_raw_parts(lrc_ptr, lrc_len);
        let text = match std::str::from_utf8(slice) {
            Ok(t) => t,
            Err(_) => return std::ptr::null_mut(),
        };

        let mut slyr_bytes = LyricCompiler::compile_to_slyr(text);
        *out_len = slyr_bytes.len();
        let ptr = slyr_bytes.as_mut_ptr();
        std::mem::forget(slyr_bytes);
        ptr
    }));

    result.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn rust_free_slyr_buffer(buf: *mut u8, len: usize) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if !buf.is_null() && len > 0 {
            drop(Vec::from_raw_parts(buf, len, len));
        }
    }));
}

#[no_mangle]
pub unsafe extern "C" fn rust_find_active_slyr_positions(
    slyr_ptr: *const u8,
    slyr_len: usize,
    position_ms: u32,
    out_line_idx: *mut u32,
    out_syl_idx: *mut u32,
) -> i32 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if slyr_ptr.is_null() || slyr_len == 0 || out_line_idx.is_null() || out_syl_idx.is_null() {
            return -1;
        }

        match LyricCompiler::find_active_positions(slyr_ptr, slyr_len, position_ms) {
            Some((line_idx, syl_idx)) => {
                *out_line_idx = line_idx as u32;
                *out_syl_idx = syl_idx as u32;
                0
            }
            None => -2,
        }
    }));

    result.unwrap_or(-999)
}
