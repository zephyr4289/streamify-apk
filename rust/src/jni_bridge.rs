use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use crate::auth::generate_sapisid_hash;
use crate::repository::generate_cad_id;
use crate::resolver::resolve_track_cdn;

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeGenerateSapisidHash(
    mut env: JNIEnv,
    _class: JClass,
    sapisid: JString,
    origin: JString,
    out_buffer: JByteArray,
) -> jint {
    let sapisid_str: String = match env.get_string(&sapisid) {
        Ok(s) => s.into(),
        Err(_) => return -10,
    };
    let origin_str: String = match env.get_string(&origin) {
        Ok(s) => s.into(),
        Err(_) => return -10,
    };

    let mut out_elements = match env.get_array_elements(&out_buffer, jni::objects::ReleaseMode::CopyBack) {
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
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeGenerateCadId(
    mut env: JNIEnv,
    _class: JClass,
    title: JString,
    artist: JString,
    duration_sec: jint,
) -> jstring {
    let title_str: String = env.get_string(&title).map(|s| s.into()).unwrap_or_default();
    let artist_str: String = env.get_string(&artist).map(|s| s.into()).unwrap_or_default();
    let cad_id = generate_cad_id(&title_str, &artist_str, duration_sec as u32);
    env.new_string(cad_id).unwrap().into_raw()
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeIngestSpotifyTracks(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
    json_payload: JString,
) -> jint {
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
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeFetchVirtualShelf(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
    out_buffer: jni::objects::JObject,
) -> jint {
    let db_str: String = match env.get_string(&db_path) {
        Ok(s) => s.into(),
        Err(_) => return -10,
    };

    let buf_ptr = match env.get_direct_buffer_address(&out_buffer) {
        Ok(ptr) => ptr,
        Err(_) => return -10,
    };
    let capacity = env.get_direct_buffer_capacity(&out_buffer).unwrap_or(0);

    if buf_ptr.is_null() || capacity < 4 {
        return -10;
    }

    let repo = match crate::repository::TrackRepository::new(&db_str) {
        Ok(r) => r,
        Err(_) => return -1,
    };
    repo.fetch_virtual_shelf_to_buffer(buf_ptr, capacity)
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
    out_buffer: JByteArray,
) -> jint {
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

    let mut out_elements = match env.get_array_elements(&out_buffer, jni::objects::ReleaseMode::CopyBack) {
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

    crate::resolver::resolve_track_cdn(
        c_db.as_ptr(),
        c_cad.as_ptr(),
        c_isrc.as_ref().map_or(std::ptr::null(), |c| c.as_ptr()),
        c_title.as_ptr(),
        c_artist.as_ptr(),
        c_auth.as_ptr(),
        out_elements.as_mut_ptr() as *mut u8,
        out_elements.len(),
    )
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

    let mut access_elements = match env.get_array_elements(&out_access, jni::objects::ReleaseMode::CopyBack) {
        Ok(e) => e,
        Err(_) => return -10,
    };
    let mut refresh_elements = match env.get_array_elements(&out_refresh, jni::objects::ReleaseMode::CopyBack) {
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
        access_elements.len(),
        refresh_elements.as_mut_ptr() as *mut u8,
        refresh_elements.len(),
    )
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_spotifyIngestLibrary(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
    access_token: JString,
) -> jint {
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

    crate::spotify_ingest::spotify_ingest_library(
        c_db.as_ptr(),
        c_token.as_ptr(),
    )
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
    out_buf: JByteArray,
    out_buf_len: jint,
) -> jint {
    let video_id_elements = env
        .get_array_elements(&video_id_bytes, jni::objects::ReleaseMode::NoCopyBack)
        .ok();
    let isrc_elements = env
        .get_array_elements(&isrc_bytes, jni::objects::ReleaseMode::NoCopyBack)
        .ok();

    let title_elements = match env.get_array_elements(&title_bytes, jni::objects::ReleaseMode::NoCopyBack) {
        Ok(e) => e,
        Err(_) => return -2,
    };
    let artist_elements = match env.get_array_elements(&artist_bytes, jni::objects::ReleaseMode::NoCopyBack) {
        Ok(e) => e,
        Err(_) => return -2,
    };

    let mut out_elements = match env.get_array_elements(&out_buf, jni::objects::ReleaseMode::CopyBack) {
        Ok(e) => e,
        Err(_) => return -2,
    };

    resolve_track_cdn(
        video_id_elements
            .as_ref()
            .map_or(std::ptr::null(), |e| e.as_ptr() as *const u8),
        video_id_len as usize,
        isrc_elements
            .as_ref()
            .map_or(std::ptr::null(), |e| e.as_ptr() as *const u8),
        isrc_len as usize,
        title_elements.as_ptr() as *const u8,
        title_len as usize,
        artist_elements.as_ptr() as *const u8,
        artist_len as usize,
        out_elements.as_mut_ptr() as *mut u8,
        out_buf_len as usize,
    )
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeFindActiveSlyrLine(
    mut env: JNIEnv,
    _class: JClass,
    slyr_buffer: JByteArray,
    slyr_len: jint,
    playhead_ms: jint,
) -> jint {
    let elements = match env.get_array_elements(&slyr_buffer, jni::objects::ReleaseMode::NoCopyBack) {
        Ok(e) => e,
        Err(_) => return -1,
    };

    let slice = std::slice::from_raw_parts(elements.as_ptr() as *const u8, slyr_len as usize);
    crate::lyrics::SlyrCompiler::find_active_line(slice, playhead_ms as u32)
        .map(|idx| idx as jint)
        .unwrap_or(-1)
}

use crate::consensus::{ByzantineConsensusEngine, MeshCandidateSubmission};
use crate::ptp::{PtpEngine, PtpPacket};
use jni::objects::{JFloatArray, JLongArray, JString};
use jni::sys::{jboolean, jfloat, jlong};

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
    let packet = PtpPacket {
        sequence_id: seq_id as u32,
        t0_origin_send: t0 as u64,
        t1_host_receive: t1 as u64,
        t2_host_transmit: t2 as u64,
        t3_client_receive: t3 as u64,
    };

    let (offset_us, delay_us) = PtpEngine::calculate_offset_and_delay(&packet);
    let results = [offset_us as jlong, delay_us as jlong];
    let _ = env.set_long_array_region(&out_results, 0, &results);
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

    let v1_elems = match env.get_array_elements(&vec1, jni::objects::ReleaseMode::NoCopyBack) {
        Ok(e) => e,
        Err(_) => return 0,
    };
    let v2_elems = match env.get_array_elements(&vec2, jni::objects::ReleaseMode::NoCopyBack) {
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
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeInitDsp(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    crate::audio_dsp::init_audio_dsp() as jlong
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeFreeDsp(
    _env: JNIEnv,
    _class: JClass,
    state_ptr: jlong,
) {
    if state_ptr != 0 {
        crate::audio_dsp::free_audio_dsp(state_ptr as *mut crate::audio_dsp::DspState);
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeProcessDsp(
    mut env: JNIEnv,
    _class: JClass,
    state_ptr: jlong,
    input_buffer: jni::objects::JObject,
    output_buffer: jni::objects::JObject,
    num_frames: jint,
) -> jint {
    if state_ptr == 0 || num_frames <= 0 {
        return -1;
    }

    let input_ptr = match env.get_direct_buffer_address(&input_buffer) {
        Ok(p) => p as *const i16,
        Err(_) => return -10,
    };
    let output_ptr = match env.get_direct_buffer_address(&output_buffer) {
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
        num_frames as usize,
    )
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
    let db_str: String = match env.get_string(&db_path) {
        Ok(s) => s.into(),
        Err(_) => return -10,
    };
    let cad_str: String = match env.get_string(&current_cad_id) {
        Ok(s) => s.into(),
        Err(_) => return -10,
    };

    let mut cad_elements = match env.get_array_elements(&out_cad, jni::objects::ReleaseMode::CopyBack) {
        Ok(e) => e,
        Err(_) => return -10,
    };
    let mut vid_elements = match env.get_array_elements(&out_video, jni::objects::ReleaseMode::CopyBack) {
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
        cad_elements.len(),
        vid_elements.as_mut_ptr() as *mut u8,
        vid_elements.len(),
    )
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_spotifyDeltaSync(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
    access_token: JString,
    last_sync_timestamp: jlong,
) -> jint {
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
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeShutdown(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
) {
    if !db_path.is_null() {
        if let Ok(db_str) = env.get_string(&db_path) {
            let s: String = db_str.into();
            if let Ok(c_db) = std::ffi::CString::new(s) {
                crate::queue_engine::shutdown_engine(c_db.as_ptr());
            }
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeParseLrc(
    mut env: JNIEnv,
    _class: JClass,
    lrc_text: JString,
) -> jlong {
    if lrc_text.is_null() {
        return 0;
    }
    let lrc_str: String = match env.get_string(&lrc_text) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let bytes = lrc_str.as_bytes();
    crate::lyrics::parse_lrc_file(bytes.as_ptr() as *const std::os::raw::c_char, bytes.len()) as jlong
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeGetLyricIndex(
    _env: JNIEnv,
    _class: JClass,
    map_ptr: jlong,
    current_time_ms: jlong,
) -> jint {
    if map_ptr == 0 {
        return -1;
    }
    crate::lyrics::get_lyric_index(map_ptr as *mut crate::lyrics::LyricMap, current_time_ms as u64)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeFreeLyricMap(
    _env: JNIEnv,
    _class: JClass,
    map_ptr: jlong,
) {
    if map_ptr != 0 {
        crate::lyrics::free_lyric_map(map_ptr as *mut crate::lyrics::LyricMap);
    }
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
    if len <= 0 {
        return -1;
    }
    let input_elements = match env.get_array_elements(&input_buf, jni::objects::ReleaseMode::NoCopyBack) {
        Ok(e) => e,
        Err(_) => return -10,
    };
    let mut output_elements = match env.get_array_elements(&output_buf, jni::objects::ReleaseMode::CopyBack) {
        Ok(e) => e,
        Err(_) => return -10,
    };
    let key_elements = match env.get_array_elements(&key_buf, jni::objects::ReleaseMode::NoCopyBack) {
        Ok(e) => e,
        Err(_) => return -10,
    };

    crate::cache::crypt_audio_chunk(
        input_elements.as_ptr() as *const u8,
        output_elements.as_mut_ptr() as *mut u8,
        len as usize,
        key_elements.as_ptr() as *const u8,
        key_elements.len(),
        offset as u64,
    )
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeUpdateThermalStatus(
    _env: JNIEnv,
    _class: JClass,
    status: jint,
) -> jint {
    crate::governor::update_thermal_status(status)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeGetThermalStatus(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    crate::governor::get_thermal_status()
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeFlushDatabaseWal(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
) -> jint {
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
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeInitNormalizer(
    _env: JNIEnv,
    _class: JClass,
    target_rms: jfloat,
) -> jlong {
    crate::normalizer::init_normalizer(target_rms) as jlong
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeFreeNormalizer(
    _env: JNIEnv,
    _class: JClass,
    state_ptr: jlong,
) {
    if state_ptr != 0 {
        crate::normalizer::free_normalizer(state_ptr as *mut crate::normalizer::NormalizerState);
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeApplyNormalization(
    mut env: JNIEnv,
    _class: JClass,
    state_ptr: jlong,
    pcm_buffer: jni::objects::JObject,
    num_frames: jint,
) -> jint {
    if state_ptr == 0 || num_frames <= 0 {
        return -1;
    }

    let pcm_ptr = match env.get_direct_buffer_address(&pcm_buffer) {
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
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeSubmitSeekRequest(
    _env: JNIEnv,
    _class: JClass,
    position_ms: jlong,
) -> jint {
    crate::seek_guard::submit_seek_request(position_ms as i64)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeConsumePendingSeek(
    _env: JNIEnv,
    _class: JClass,
    debounce_ms: jlong,
) -> jlong {
    crate::seek_guard::consume_pending_seek(debounce_ms as i64)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeResetSeekGuard(
    _env: JNIEnv,
    _class: JClass,
) {
    crate::seek_guard::reset_seek_guard();
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeInitContinuumState(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    crate::continuum_engine::init_continuum_state() as jlong
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeFreeContinuumState(
    _env: JNIEnv,
    _class: JClass,
    state_ptr: jlong,
) {
    if state_ptr != 0 {
        crate::continuum_engine::free_continuum_state(state_ptr as *mut crate::continuum_engine::ContinuumState);
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeEvaluateContinuum(
    mut env: JNIEnv,
    _class: JClass,
    state_ptr: jlong,
    candidates: jni::objects::JFloatArray,
    out_scores: jni::objects::JFloatArray,
) -> jint {
    if state_ptr == 0 {
        return -1;
    }
    let cand_len = match env.get_array_length(&candidates) {
        Ok(l) => l as usize,
        Err(_) => return -10,
    };
    let score_len = match env.get_array_length(&out_scores) {
        Ok(l) => l as usize,
        Err(_) => return -10,
    };

    let candidate_count = cand_len / 128;
    if candidate_count == 0 || score_len < candidate_count {
        return -2;
    }

    let cand_elements = match env.get_array_elements(&candidates, jni::objects::ReleaseMode::NoCopyBack) {
        Ok(e) => e,
        Err(_) => return -10,
    };
    let mut score_elements = match env.get_array_elements(&out_scores, jni::objects::ReleaseMode::CopyBack) {
        Ok(e) => e,
        Err(_) => return -10,
    };

    crate::continuum_engine::evaluate_continuum_batch(
        state_ptr as *mut crate::continuum_engine::ContinuumState,
        cand_elements.as_ptr(),
        candidate_count,
        score_elements.as_mut_ptr(),
    )
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeCommitTrackToContinuum(
    mut env: JNIEnv,
    _class: JClass,
    state_ptr: jlong,
    track_vector: jni::objects::JFloatArray,
    artist_hash: jlong,
    track_hash: jlong,
    dwell_percentage: jfloat,
) -> jint {
    if state_ptr == 0 {
        return -1;
    }
    let vec_len = match env.get_array_length(&track_vector) {
        Ok(l) => l as usize,
        Err(_) => return -10,
    };
    if vec_len < 128 {
        return -2;
    }

    let vec_elements = match env.get_array_elements(&track_vector, jni::objects::ReleaseMode::NoCopyBack) {
        Ok(e) => e,
        Err(_) => return -10,
    };

    crate::continuum_engine::commit_track_to_continuum(
        state_ptr as *mut crate::continuum_engine::ContinuumState,
        vec_elements.as_ptr(),
        artist_hash as u64,
        track_hash as u64,
        dwell_percentage,
    )
}


