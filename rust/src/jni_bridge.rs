use jni::objects::{JByteArray, JByteBuffer, JClass, JFloatArray, JLongArray, JObject, JString, ReleaseMode};
use jni::sys::{jboolean, jfloat, jint, jlong, jstring};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

use crate::auth::generate_sapisid_hash;
use crate::consensus::{ByzantineConsensusEngine, MeshCandidateSubmission};
use crate::ptp::{PtpEngine, PtpPacket};
use crate::repository::generate_cad_id;
use crate::resolver::{resolve_track_cdn, resolve_track_cdn_url};

// ═══════════════════════════════════════════════════════════════════
// PANIC SHIELD CONTRACT
//
// Every JNI entry point below wraps its entire body in catch_unwind.
// A panic must NEVER unwind across the `extern "C"` boundary (that
// aborts the host Android process). Instead each function maps a panic
// onto its own error convention:
//   jint  -> negative code (-10 JNI error unless the fn documents else)
//   jlong -> 0 (null opaque handle)
//   jboolean -> 0 (false)
//   jstring  -> "" (never null: Kotlin side is non-null String)
//   ()    -> swallowed
// ═══════════════════════════════════════════════════════════════════

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeGenerateSapisidHash(
    mut env: JNIEnv,
    _class: JClass,
    sapisid: JString,
    origin: JString,
    out_buffer: JByteArray,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let sapisid_str: String = match env.get_string(&sapisid) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };
        let origin_str: String = match env.get_string(&origin) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };

        let mut out_elements =
            match env.get_array_elements(&out_buffer, ReleaseMode::CopyBack) {
                Ok(e) => e,
                Err(_) => return -10,
            };

        generate_sapisid_hash(
            sapisid_str.as_ptr(),
            sapisid_str.len(),
            origin_str.as_ptr(),
            origin_str.len(),
            out_elements.as_mut_ptr() as *mut u8,
            out_elements.len(),
        )
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeGenerateCadId(
    mut env: JNIEnv,
    _class: JClass,
    title: JString,
    artist: JString,
    duration_sec: jint,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let title_str: String = env.get_string(&title).map(|s| s.into()).unwrap_or_default();
        let artist_str: String = env.get_string(&artist).map(|s| s.into()).unwrap_or_default();
        let cad_id = generate_cad_id(&title_str, &artist_str, duration_sec.max(0) as u32);
        match env.new_string(cad_id) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }));
    // Never hand null back across a non-null Kotlin String contract: fall
    // back to an empty CAD-ID string, then to null only if even that fails.
    result.unwrap_or_else(|_| {
        catch_unwind(AssertUnwindSafe(|| {
            env.new_string("")
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut())
        }))
        .unwrap_or(std::ptr::null_mut())
    })
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeIngestSpotifyTracks(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
    json_payload: JString,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let db_str: String = match env.get_string(&db_path) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };
        let json_str: String = match env.get_string(&json_payload) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };

        let repo = match crate::repository::TrackRepository::new(&db_str) {
            Ok(r) => r,
            Err(_) => return -1,
        };
        repo.batch_upsert_spotify_tracks(&json_str)
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeFetchVirtualShelf(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
    out_buffer: JObject,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let db_str: String = match env.get_string(&db_path) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };

        let buf_ptr = match env.get_direct_buffer_address((&out_buffer).into()) {
            Ok(ptr) => ptr,
            Err(_) => return -10,
        };
        let capacity = env.get_direct_buffer_capacity((&out_buffer).into()).unwrap_or(0);

        if buf_ptr.is_null() || capacity < 4 {
            return -10;
        }

        let repo = match crate::repository::TrackRepository::new(&db_str) {
            Ok(r) => r,
            Err(_) => return -1,
        };
        repo.fetch_virtual_shelf_to_buffer(buf_ptr, capacity)
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeResolveTrack(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
    cad_id: JString,
    isrc: JString,
    title: JString,
    artist: JString,
    auth_header: JString,
    cookies_bytes: JByteArray,
    cookies_len: jint,
    out_buffer: JByteArray,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let db_str: String = match env.get_string(&db_path) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };
        let cad_str: String = match env.get_string(&cad_id) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };
        let isrc_str: Option<String> = if isrc.is_null() {
            None
        } else {
            env.get_string(&isrc).ok().map(|s| s.into())
        };
        let title_str: String = match env.get_string(&title) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };
        let artist_str: String = match env.get_string(&artist) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };
        let auth_str: String = if auth_header.is_null() {
            "".to_string()
        } else {
            env.get_string(&auth_header).map(|s| s.into()).unwrap_or_default()
        };
        let cookies_actual = env.get_array_length(&cookies_bytes).unwrap_or(0).max(0) as usize;
        let cookies_len = (cookies_len.max(0) as usize).min(cookies_actual);
        let cookies_elements =
            match env.get_array_elements(&cookies_bytes, ReleaseMode::NoCopyBack) {
                Ok(e) => Some(e),
                Err(_) => None,
            };

        let out_actual = env.get_array_length(&out_buffer).unwrap_or(0).max(0);
        let mut out_elements =
            match env.get_array_elements(&out_buffer, ReleaseMode::CopyBack) {
                Ok(e) => e,
                Err(_) => return -10,
            };

        let c_db = match std::ffi::CString::new(db_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };
        let c_cad = match std::ffi::CString::new(cad_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };
        let c_isrc = isrc_str.and_then(|s| std::ffi::CString::new(s).ok());
        let c_title = match std::ffi::CString::new(title_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };
        let c_artist = match std::ffi::CString::new(artist_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };
        let c_auth = match std::ffi::CString::new(auth_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };

        resolve_track_cdn(
            c_db.as_ptr(),
            c_cad.as_ptr(),
            c_isrc.as_ref().map_or(std::ptr::null(), |c| c.as_ptr()),
            c_title.as_ptr(),
            c_artist.as_ptr(),
            c_auth.as_ptr(),
            cookies_elements.as_ref().map_or(std::ptr::null(), |e| e.as_ptr()) as *const u8,
            cookies_len,
            out_elements.as_mut_ptr() as *mut u8,
            out_actual as usize,
        )
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_spotifyExchangePkce(
    mut env: JNIEnv,
    _class: JClass,
    code: JString,
    verifier: JString,
    redirect_uri: JString,
    client_id: JString,
    out_access: JByteArray,
    out_refresh: JByteArray,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let code_str: String = match env.get_string(&code) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };
        let verifier_str: String = match env.get_string(&verifier) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };
        let redirect_str: String = match env.get_string(&redirect_uri) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };
        let client_id_str: String = if client_id.is_null() {
            "37b8d4f407764d8dbda2f94356e792c3".to_string()
        } else {
            env.get_string(&client_id).map(|s| s.into()).unwrap_or_else(|_| "37b8d4f407764d8dbda2f94356e792c3".to_string())
        };

        let access_actual = env.get_array_length(&out_access).unwrap_or(0).max(0) as usize;
        let refresh_actual = env.get_array_length(&out_refresh).unwrap_or(0).max(0) as usize;

        let mut access_elements =
            match env.get_array_elements(&out_access, ReleaseMode::CopyBack) {
                Ok(e) => e,
                Err(_) => return -10,
            };
        let mut refresh_elements =
            match env.get_array_elements(&out_refresh, ReleaseMode::CopyBack) {
                Ok(e) => e,
                Err(_) => return -10,
            };

        let c_code = match std::ffi::CString::new(code_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };
        let c_verifier = match std::ffi::CString::new(verifier_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };
        let c_redirect = match std::ffi::CString::new(redirect_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };
        let c_client_id = match std::ffi::CString::new(client_id_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };

        crate::spotify_ingest::spotify_exchange_pkce(
            c_code.as_ptr(),
            c_verifier.as_ptr(),
            c_redirect.as_ptr(),
            c_client_id.as_ptr(),
            access_elements.as_mut_ptr() as *mut u8,
            access_actual,
            refresh_elements.as_mut_ptr() as *mut u8,
            refresh_actual,
        )
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_spotifyIngestLibrary(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
    access_token: JString,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let db_str: String = match env.get_string(&db_path) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };
        let token_str: String = match env.get_string(&access_token) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };

        let c_db = match std::ffi::CString::new(db_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };
        let c_token = match std::ffi::CString::new(token_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };

        crate::spotify_ingest::spotify_ingest_library(c_db.as_ptr(), c_token.as_ptr())
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeResolveTrackCdn(
    mut env: JNIEnv,
    _class: JClass,
    video_id_bytes: JByteArray,
    video_id_len: jint,
    isrc_bytes: JByteArray,
    isrc_len: jint,
    title_bytes: JByteArray,
    title_len: jint,
    artist_bytes: JByteArray,
    artist_len: jint,
    auth_bytes: JByteArray,
    auth_len: jint,
    cookies_bytes: JByteArray,
    cookies_len: jint,
    out_buf: JByteArray,
    out_buf_len: jint,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        // Clamp every declared length against the REAL JVM array length: a
        // declared length longer than the array would previously read/write
        // past its end (negative jint became a ~2^64 usize).
        let v_actual = env.get_array_length(&video_id_bytes).unwrap_or(0).max(0);
        let i_actual = env.get_array_length(&isrc_bytes).unwrap_or(0).max(0);
        let t_actual = env.get_array_length(&title_bytes).unwrap_or(0).max(0);
        let a_actual = env.get_array_length(&artist_bytes).unwrap_or(0).max(0);
        let h_actual = env.get_array_length(&auth_bytes).unwrap_or(0).max(0);
        let c_actual = env.get_array_length(&cookies_bytes).unwrap_or(0).max(0);

        let vlen = video_id_len.max(0).min(v_actual) as usize;
        let ilen = isrc_len.max(0).min(i_actual) as usize;
        let tlen = title_len.max(0).min(t_actual) as usize;
        let alen = artist_len.max(0).min(a_actual) as usize;
        let hlen = auth_len.max(0).min(h_actual) as usize;
        let clen = cookies_len.max(0).min(c_actual) as usize;

        if tlen == 0 && alen == 0 && vlen == 0 && ilen == 0 {
            return -2;
        }

        let video_id_elements =
            match env.get_array_elements(&video_id_bytes, ReleaseMode::NoCopyBack) {
                Ok(e) => Some(e),
                Err(_) => None,
            };
        let isrc_elements =
            match env.get_array_elements(&isrc_bytes, ReleaseMode::NoCopyBack) {
                Ok(e) => Some(e),
                Err(_) => None,
            };
        let title_elements =
            match env.get_array_elements(&title_bytes, ReleaseMode::NoCopyBack) {
                Ok(e) => Some(e),
                Err(_) => None,
            };
        let artist_elements =
            match env.get_array_elements(&artist_bytes, ReleaseMode::NoCopyBack) {
                Ok(e) => Some(e),
                Err(_) => None,
            };
        let auth_elements =
            match env.get_array_elements(&auth_bytes, ReleaseMode::NoCopyBack) {
                Ok(e) => Some(e),
                Err(_) => None,
            };
        let cookies_elements =
            match env.get_array_elements(&cookies_bytes, ReleaseMode::NoCopyBack) {
                Ok(e) => Some(e),
                Err(_) => None,
            };
        let mut out_elements = match env.get_array_elements(&out_buf, ReleaseMode::CopyBack) {
            Ok(e) => e,
            Err(_) => return -2,
        };

        // Trust the ACTUAL output buffer size, never the caller-declared one.
        let olen = (out_buf_len.max(0) as usize).min(out_elements.len());
        if olen == 0 {
            return -2;
        }

        resolve_track_cdn_url(
            video_id_elements.as_ref().map_or(std::ptr::null(), |e| e.as_ptr()) as *const u8,
            vlen.min(video_id_elements.as_ref().map_or(0, |e| e.len())),
            isrc_elements.as_ref().map_or(std::ptr::null(), |e| e.as_ptr()) as *const u8,
            ilen.min(isrc_elements.as_ref().map_or(0, |e| e.len())),
            title_elements.as_ref().map_or(std::ptr::null(), |e| e.as_ptr()) as *const u8,
            tlen.min(title_elements.as_ref().map_or(0, |e| e.len())),
            artist_elements.as_ref().map_or(std::ptr::null(), |e| e.as_ptr()) as *const u8,
            alen.min(artist_elements.as_ref().map_or(0, |e| e.len())),
            auth_elements.as_ref().map_or(std::ptr::null(), |e| e.as_ptr()) as *const u8,
            hlen,
            cookies_elements.as_ref().map_or(std::ptr::null(), |e| e.as_ptr()) as *const u8,
            clen,
            out_elements.as_mut_ptr() as *mut u8,
            olen,
        )
    }))
    .unwrap_or(-2)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeFindActiveSlyrLine(
    mut env: JNIEnv,
    _class: JClass,
    slyr_buffer: JByteArray,
    slyr_len: jint,
    playhead_ms: jint,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let actual = env.get_array_length(&slyr_buffer).unwrap_or(0).max(0) as usize;
        // The declared length was previously trusted over the real array
        // length -> OOB read. Clamp instead.
        let slen = (slyr_len.max(0) as usize).min(actual);
        if slen == 0 {
            return -1;
        }

        let elements = match env.get_array_elements(&slyr_buffer, ReleaseMode::NoCopyBack) {
            Ok(e) => e,
            Err(_) => return -1,
        };

        let slice = std::slice::from_raw_parts(elements.as_ptr() as *const u8, slen);
        crate::lyrics::SlyrCompiler::find_active_line(slice, playhead_ms.max(0) as u32)
            .map(|idx| idx as jint)
            .unwrap_or(-1)
    }))
    .unwrap_or(-1)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeCalculatePtp(
    mut env: JNIEnv,
    _class: JClass,
    seq_id: jint,
    t0: jlong,
    t1: jlong,
    t2: jlong,
    t3: jlong,
    out_results: JLongArray,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let packet = PtpPacket {
            sequence_id: seq_id.max(0) as u32,
            t0_origin_send: t0.max(0) as u64,
            t1_host_receive: t1.max(0) as u64,
            t2_host_transmit: t2.max(0) as u64,
            t3_client_receive: t3.max(0) as u64,
        };

        let (offset_us, delay_us) = PtpEngine::calculate_offset_and_delay(&packet);
        let results = [offset_us as jlong, delay_us as jlong];
        let _ = env.set_long_array_region(&out_results, 0, &results);
    }));
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeVerifyPeerConsensus(
    mut env: JNIEnv,
    _class: JClass,
    node1: JString,
    lufs1: jfloat,
    key1: JString,
    vec1: JFloatArray,
    _proof1: JByteArray,
    node2: JString,
    lufs2: jfloat,
    key2: JString,
    vec2: JFloatArray,
    _proof2: JByteArray,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let node1_str: String = match env.get_string(&node1) {
            Ok(s) => s.into(),
            Err(_) => return 0,
        };
        let node2_str: String = match env.get_string(&node2) {
            Ok(s) => s.into(),
            Err(_) => return 0,
        };
        let key1_str: String = match env.get_string(&key1) {
            Ok(s) => s.into(),
            Err(_) => return 0,
        };
        let key2_str: String = match env.get_string(&key2) {
            Ok(s) => s.into(),
            Err(_) => return 0,
        };

        let v1_elems = match env.get_array_elements(&vec1, ReleaseMode::NoCopyBack) {
            Ok(e) => e,
            Err(_) => return 0,
        };
        let v2_elems = match env.get_array_elements(&vec2, ReleaseMode::NoCopyBack) {
            Ok(e) => e,
            Err(_) => return 0,
        };

        if v1_elems.len() < 128 || v2_elems.len() < 128 {
            return 0;
        }

        let mut vector1 = [0.0f32; 128];
        let mut vector2 = [0.0f32; 128];
        vector1.copy_from_slice(&v1_elems[..128]);
        vector2.copy_from_slice(&v2_elems[..128]);

        let peer1 = MeshCandidateSubmission {
            node_id: node1_str,
            lufs: lufs1,
            camelot_key: key1_str,
            vector: vector1,
            proof_digest: [0u8; 32],
        };

        let peer2 = MeshCandidateSubmission {
            node_id: node2_str,
            lufs: lufs2,
            camelot_key: key2_str,
            vector: vector2,
            proof_digest: [0u8; 32],
        };

        if ByzantineConsensusEngine::verify_peer_consensus(&peer1, &peer2) {
            1
        } else {
            0
        }
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeInitDsp(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| unsafe { crate::audio_dsp::init_audio_dsp() })).unwrap_or(std::ptr::null_mut()) as jlong
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeFreeDsp(
    _env: JNIEnv,
    _class: JClass,
    state_ptr: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if state_ptr != 0 {
            crate::audio_dsp::free_audio_dsp(state_ptr as *mut crate::audio_dsp::DspState);
        }
    }));
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeProcessDsp(
    mut env: JNIEnv,
    _class: JClass,
    state_ptr: jlong,
    input_buffer: JObject,
    output_buffer: JObject,
    num_frames: jint,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        if state_ptr == 0 || num_frames <= 0 {
            return -1;
        }

        // Validate against the DIRECT BUFFER CAPACITY, not just non-nullness:
        // stereo interleaved, i16 in (2 B/sample), f32 out (4 B/sample).
        let frames = num_frames as usize;
        let need_in = frames * 2 * std::mem::size_of::<i16>();
        let need_out = frames * 2 * std::mem::size_of::<f32>();
        let in_cap = env.get_direct_buffer_capacity((&input_buffer).into()).unwrap_or(0);
        let out_cap = env.get_direct_buffer_capacity((&output_buffer).into()).unwrap_or(0);
        if in_cap < need_in || out_cap < need_out {
            return -2;
        }

        let input_ptr = match env.get_direct_buffer_address((&input_buffer).into()) {
            Ok(p) => p as *const i16,
            Err(_) => return -10,
        };
        let output_ptr = match env.get_direct_buffer_address((&output_buffer).into()) {
            Ok(p) => p as *mut f32,
            Err(_) => return -10,
        };

        if input_ptr.is_null() || output_ptr.is_null() {
            return -10;
        }

        crate::audio_dsp::process_audio_dsp(
            state_ptr as *mut crate::audio_dsp::DspState,
            input_ptr,
            output_ptr,
            frames,
        )
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeGetNextTrack(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
    current_cad_id: JString,
    is_shuffle: jboolean,
    out_cad: JByteArray,
    out_video: JByteArray,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let db_str: String = match env.get_string(&db_path) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };
        let cad_str: String = match env.get_string(&current_cad_id) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };

        let cad_actual = env.get_array_length(&out_cad).unwrap_or(0).max(0) as usize;
        let vid_actual = env.get_array_length(&out_video).unwrap_or(0).max(0) as usize;

        let mut cad_elements = match env.get_array_elements(&out_cad, ReleaseMode::CopyBack) {
            Ok(e) => e,
            Err(_) => return -10,
        };
        let mut vid_elements =
            match env.get_array_elements(&out_video, ReleaseMode::CopyBack) {
                Ok(e) => e,
                Err(_) => return -10,
            };

        let c_db = match std::ffi::CString::new(db_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };
        let c_cad = match std::ffi::CString::new(cad_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };

        crate::queue_engine::get_next_track(
            c_db.as_ptr(),
            c_cad.as_ptr(),
            if is_shuffle != 0 { 1 } else { 0 },
            cad_elements.as_mut_ptr() as *mut u8,
            cad_actual,
            vid_elements.as_mut_ptr() as *mut u8,
            vid_actual,
        )
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_spotifyDeltaSync(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
    access_token: JString,
    last_sync_timestamp: jlong,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let db_str: String = match env.get_string(&db_path) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };
        let token_str: String = match env.get_string(&access_token) {
            Ok(s) => s.into(),
            Err(_) => return -10,
        };

        let c_db = match std::ffi::CString::new(db_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };
        let c_token = match std::ffi::CString::new(token_str) {
            Ok(c) => c,
            Err(_) => return -10,
        };

        crate::queue_engine::spotify_delta_sync(
            c_db.as_ptr(),
            c_token.as_ptr(),
            last_sync_timestamp as i64,
        )
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeShutdown(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if !db_path.is_null() {
            if let Ok(db_str) = env.get_string(&db_path) {
                let s: String = db_str.into();
                if let Ok(c_db) = std::ffi::CString::new(s) {
                    crate::queue_engine::shutdown_engine(c_db.as_ptr());
                }
            }
        }
    }));
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeParseLrc(
    mut env: JNIEnv,
    _class: JClass,
    lrc_text: JString,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        if lrc_text.is_null() {
            return 0;
        }
        let lrc_str: String = match env.get_string(&lrc_text) {
            Ok(s) => s.into(),
            Err(_) => return 0,
        };
        let bytes = lrc_str.as_bytes();
        if bytes.is_empty() {
            return 0;
        }
        crate::lyrics::parse_lrc_file(bytes.as_ptr() as *const std::os::raw::c_char, bytes.len())
            as jlong
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeGetLyricIndex(
    _env: JNIEnv,
    _class: JClass,
    map_ptr: jlong,
    current_time_ms: jlong,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        if map_ptr == 0 {
            return -1;
        }
        crate::lyrics::get_lyric_index(
            map_ptr as *mut crate::lyrics::LyricMap,
            current_time_ms.max(0) as u64,
        )
    }))
    .unwrap_or(-1)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeFreeLyricMap(
    _env: JNIEnv,
    _class: JClass,
    map_ptr: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if map_ptr != 0 {
            crate::lyrics::free_lyric_map(map_ptr as *mut crate::lyrics::LyricMap);
        }
    }));
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeCryptCacheChunk(
    mut env: JNIEnv,
    _class: JClass,
    input_buf: JByteArray,
    output_buf: JByteArray,
    len: jint,
    key_buf: JByteArray,
    offset: jlong,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        if len <= 0 {
            return -1;
        }
        let n = len as usize;
        // Previously `len` was trusted over real array lengths -> OOB read AND
        // write, plus no aliasing/emptiness checks on the key material.
        let in_actual = env.get_array_length(&input_buf).unwrap_or(0).max(0) as usize;
        let out_actual = env.get_array_length(&output_buf).unwrap_or(0).max(0) as usize;
        let key_actual = env.get_array_length(&key_buf).unwrap_or(0).max(0) as usize;
        if in_actual < n || out_actual < n || key_actual == 0 || offset < 0 {
            return -2;
        }

        let input_elements =
            match env.get_array_elements(&input_buf, ReleaseMode::NoCopyBack) {
                Ok(e) => e,
                Err(_) => return -10,
            };
        let mut output_elements =
            match env.get_array_elements(&output_buf, ReleaseMode::CopyBack) {
                Ok(e) => e,
                Err(_) => return -10,
            };
        let key_elements = match env.get_array_elements(&key_buf, ReleaseMode::NoCopyBack) {
            Ok(e) => e,
            Err(_) => return -10,
        };

        crate::cache::crypt_audio_chunk(
            input_elements.as_ptr() as *const u8,
            output_elements.as_mut_ptr() as *mut u8,
            n,
            key_elements.as_ptr() as *const u8,
            key_elements.len(),
            offset as u64,
        )
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeUpdateThermalStatus(
    _env: JNIEnv,
    _class: JClass,
    status: jint,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| crate::governor::update_thermal_status(status)))
        .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeGetThermalStatus(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| unsafe { crate::governor::get_thermal_status() })).unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeFlushDatabaseWal(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        if db_path.is_null() {
            return -1;
        }
        let db_str: String = match env.get_string(&db_path) {
            Ok(s) => s.into(),
            Err(_) => return -1,
        };
        let c_db = match std::ffi::CString::new(db_str) {
            Ok(c) => c,
            Err(_) => return -1,
        };
        crate::governor::flush_database_wal(c_db.as_ptr())
    }))
    .unwrap_or(-1)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeInitNormalizer(
    _env: JNIEnv,
    _class: JClass,
    target_rms: jfloat,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        crate::normalizer::init_normalizer(target_rms)
    }))
    .unwrap_or(std::ptr::null_mut()) as jlong
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeFreeNormalizer(
    _env: JNIEnv,
    _class: JClass,
    state_ptr: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if state_ptr != 0 {
            crate::normalizer::free_normalizer(
                state_ptr as *mut crate::normalizer::NormalizerState,
            );
        }
    }));
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeApplyNormalization(
    mut env: JNIEnv,
    _class: JClass,
    state_ptr: jlong,
    pcm_buffer: JObject,
    num_frames: jint,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        if state_ptr == 0 || num_frames <= 0 {
            return -1;
        }

        // Stereo interleaved f32 in-place: validate against real capacity.
        let need = num_frames as usize * 2 * std::mem::size_of::<f32>();
        let cap = env.get_direct_buffer_capacity((&pcm_buffer).into()).unwrap_or(0);
        if cap < need {
            return -2;
        }

        let pcm_ptr = match env.get_direct_buffer_address((&pcm_buffer).into()) {
            Ok(p) => p as *mut f32,
            Err(_) => return -10,
        };

        if pcm_ptr.is_null() {
            return -10;
        }

        crate::normalizer::apply_normalization(
            state_ptr as *mut crate::normalizer::NormalizerState,
            pcm_ptr,
            num_frames as usize,
        )
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeProcessFusedAudio(
    mut env: JNIEnv,
    _class: JClass,
    state_ptr: jlong,
    normalizer_ptr: jlong,
    input_buffer: JObject,
    output_buffer: JObject,
    num_frames: jint,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        if state_ptr == 0 || num_frames <= 0 {
            return -1;
        }

        // Validate against DIRECT BUFFER CAPACITY, not just non-nullness:
        // stereo interleaved, i16 in (2 B/sample), f32 out (4 B/sample).
        let frames = num_frames as usize;
        let need_in = frames * 2 * std::mem::size_of::<i16>();
        let need_out = frames * 2 * std::mem::size_of::<f32>();
        let in_cap = env.get_direct_buffer_capacity((&input_buffer).into()).unwrap_or(0);
        let out_cap = env.get_direct_buffer_capacity((&output_buffer).into()).unwrap_or(0);
        if in_cap < need_in || out_cap < need_out {
            return -2;
        }

        let input_ptr = match env.get_direct_buffer_address((&input_buffer).into()) {
            Ok(p) => p as *const i16,
            Err(_) => return -10,
        };
        let output_ptr = match env.get_direct_buffer_address((&output_buffer).into()) {
            Ok(p) => p as *mut f32,
            Err(_) => return -10,
        };

        if input_ptr.is_null() || output_ptr.is_null() {
            return -10;
        }

        // ONE crossing: DSP conversion/EQ then RMS normalization operate
        // back-to-back on the same f32 output region — zero intermediate copies.
        let dsp_result = crate::audio_dsp::process_audio_dsp(
            state_ptr as *mut crate::audio_dsp::DspState,
            input_ptr,
            output_ptr,
            frames,
        );
        if dsp_result != 0 {
            return dsp_result;
        }
        if normalizer_ptr != 0 {
            crate::normalizer::apply_normalization(
                normalizer_ptr as *mut crate::normalizer::NormalizerState,
                output_ptr,
                frames,
            );
        }
        0
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeSubmitSeekRequest(
    _env: JNIEnv,
    _class: JClass,
    position_ms: jlong,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        crate::seek_guard::submit_seek_request(position_ms as i64)
    }))
    .unwrap_or(-1)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeConsumePendingSeek(
    _env: JNIEnv,
    _class: JClass,
    debounce_ms: jlong,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        crate::seek_guard::consume_pending_seek(debounce_ms as i64)
    }))
    .unwrap_or(-1)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeResetSeekGuard(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| unsafe { crate::seek_guard::reset_seek_guard() }));
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeInitContinuumState(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| unsafe { crate::continuum_engine::init_continuum_state() }))
        .unwrap_or(std::ptr::null_mut()) as jlong
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeFreeContinuumState(
    _env: JNIEnv,
    _class: JClass,
    state_ptr: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if state_ptr != 0 {
            crate::continuum_engine::free_continuum_state(
                state_ptr as *mut crate::continuum_engine::ContinuumState,
            );
        }
    }));
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeEvaluateContinuum(
    mut env: JNIEnv,
    _class: JClass,
    state_ptr: jlong,
    candidates: JFloatArray,
    out_scores: JFloatArray,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        if state_ptr == 0 {
            return -1;
        }
        let cand_len = match env.get_array_length(&candidates) {
            Ok(l) => l.max(0) as usize,
            Err(_) => return -10,
        };
        let score_len = match env.get_array_length(&out_scores) {
            Ok(l) => l.max(0) as usize,
            Err(_) => return -10,
        };

        let candidate_count = cand_len / 128;
        if candidate_count == 0 || score_len < candidate_count {
            return -2;
        }

        let cand_elements =
            match env.get_array_elements(&candidates, ReleaseMode::NoCopyBack) {
                Ok(e) => e,
                Err(_) => return -10,
            };
        let mut score_elements =
            match env.get_array_elements(&out_scores, ReleaseMode::CopyBack) {
                Ok(e) => e,
                Err(_) => return -10,
            };

        crate::continuum_engine::evaluate_continuum_batch(
            state_ptr as *mut crate::continuum_engine::ContinuumState,
            cand_elements.as_ptr(),
            candidate_count,
            score_elements.as_mut_ptr(),
        )
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeCommitTrackToContinuum(
    mut env: JNIEnv,
    _class: JClass,
    state_ptr: jlong,
    track_vector: JFloatArray,
    artist_hash: jlong,
    track_hash: jlong,
    dwell_percentage: jfloat,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        if state_ptr == 0 {
            return -1;
        }
        let vec_len = match env.get_array_length(&track_vector) {
            Ok(l) => l.max(0) as usize,
            Err(_) => return -10,
        };
        if vec_len < 128 {
            return -2;
        }

        let vec_elements =
            match env.get_array_elements(&track_vector, ReleaseMode::NoCopyBack) {
                Ok(e) => e,
                Err(_) => return -10,
            };

        crate::continuum_engine::commit_track_to_continuum(
            state_ptr as *mut crate::continuum_engine::ContinuumState,
            vec_elements.as_ptr(),
            artist_hash.max(0) as u64,
            track_hash.max(0) as u64,
            dwell_percentage.clamp(0.0, 100.0),
        )
    }))
    .unwrap_or(-10)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_stepAirDropPhysics(
    mut env: JNIEnv,
    _class: JClass,
    in_out_buffer: JFloatArray,
    target_x: jfloat,
    target_y: jfloat,
    initial_dist: jfloat,
    dt: jfloat,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let len = match env.get_array_length(&in_out_buffer) {
            Ok(l) => l.max(0) as usize,
            Err(_) => return,
        };
        if len < 14 {
            return;
        }

        let mut elements =
            match env.get_array_elements(&in_out_buffer, ReleaseMode::CopyBack) {
                Ok(e) => e,
                Err(_) => return,
            };

        let mut state = crate::airdrop::AirdropState {
            pos: [elements[0], elements[1], elements[2]],
            vel: [elements[3], elements[4], elements[5]],
            stretch_parallel: elements[6],
            stretch_perp: elements[7],
            rotation_rad: elements[8],
            pitch_deg: elements[9],
            roll_deg: elements[10],
            impact_progress: elements[11],
            is_docked: elements[12] > 0.5,
            is_ready_to_dock: elements[13] > 0.5,
            ..Default::default()
        };

        crate::airdrop::AirdropPhysicsEngine::step(
            &mut state,
            target_x,
            target_y,
            initial_dist,
            dt,
            128,
        );

        elements[0] = state.pos[0];
        elements[1] = state.pos[1];
        elements[2] = state.pos[2];
        elements[3] = state.vel[0];
        elements[4] = state.vel[1];
        elements[5] = state.vel[2];
        elements[6] = state.stretch_parallel;
        elements[7] = state.stretch_perp;
        elements[8] = state.rotation_rad;
        elements[9] = state.pitch_deg;
        elements[10] = state.roll_deg;
        elements[11] = state.impact_progress;
        elements[12] = if state.is_docked { 1.0 } else { 0.0 };
        // Writeback was previously missing entirely: Kotlin never saw the
        // updated readiness flag.
        elements[13] = if state.is_ready_to_dock { 1.0 } else { 0.0 };
    }));
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeExtractStreamInfo(
    mut env: JNIEnv,
    _class: JClass,
    response_bytes: JByteArray,
) -> jstring {
    let res = catch_unwind(AssertUnwindSafe(|| {
        let bytes = match env.convert_byte_array(&response_bytes) {
            Ok(b) => b,
            Err(_) => return None,
        };

        let raw_str = match std::str::from_utf8(&bytes) {
            Ok(s) => s,
            Err(_) => return None,
        };

        let stream_info = crate::json::InnertubeParser::extract_best_stream_info(raw_str)?;
        let json_out = serde_json::to_string(&stream_info).ok()?;
        env.new_string(json_out).ok().map(|js| js.into_raw())
    }));

    match res {
        Ok(Some(ptr)) => ptr,
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeExtractSessionTokens(
    mut env: JNIEnv,
    _class: JClass,
    html_bytes: JByteArray,
) -> jstring {
    let res = catch_unwind(AssertUnwindSafe(|| {
        let bytes = match env.convert_byte_array(&html_bytes) {
            Ok(b) => b,
            Err(_) => return None,
        };

        let raw_str = match std::str::from_utf8(&bytes) {
            Ok(s) => s,
            Err(_) => return None,
        };

        let tokens = crate::json::InnertubeParser::extract_session_tokens(raw_str)?;
        let json_out = serde_json::to_string(&tokens).ok()?;
        env.new_string(json_out).ok().map(|js| js.into_raw())
    }));

    match res {
        Ok(Some(ptr)) => ptr,
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeExtractStreamInfoDirect(
    mut env: JNIEnv,
    _class: JClass,
    direct_buffer: JByteBuffer,
    length: jint,
) -> jstring {
    let res = catch_unwind(AssertUnwindSafe(|| {
        if length <= 0 {
            return None;
        }

        let ptr = env.get_direct_buffer_address(&direct_buffer).ok()?;
        if ptr.is_null() {
            return None;
        }

        let slice = std::slice::from_raw_parts(ptr as *const u8, length as usize);
        let raw_str = match std::str::from_utf8(slice) {
            Ok(s) => s,
            Err(_) => return None,
        };

        let stream_info = crate::json::InnertubeParser::extract_best_stream_info(raw_str)?;
        let json_out = serde_json::to_string(&stream_info).ok()?;
        env.new_string(json_out).ok().map(|js| js.into_raw())
    }));

    match res {
        Ok(Some(ptr)) => ptr,
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeExtractSessionTokensDirect(
    mut env: JNIEnv,
    _class: JClass,
    direct_buffer: JByteBuffer,
    length: jint,
) -> jstring {
    let res = catch_unwind(AssertUnwindSafe(|| {
        if length <= 0 {
            return None;
        }

        let ptr = env.get_direct_buffer_address(&direct_buffer).ok()?;
        if ptr.is_null() {
            return None;
        }

        let slice = std::slice::from_raw_parts(ptr as *const u8, length as usize);
        let raw_str = match std::str::from_utf8(slice) {
            Ok(s) => s,
            Err(_) => return None,
        };

        let tokens = crate::json::InnertubeParser::extract_session_tokens(raw_str)?;
        let json_out = serde_json::to_string(&tokens).ok()?;
        env.new_string(json_out).ok().map(|js| js.into_raw())
    }));

    match res {
        Ok(Some(ptr)) => ptr,
        _ => std::ptr::null_mut(),
    }
}


