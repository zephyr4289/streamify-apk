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
) -> jstring {
    let sapisid_str: String = match env.get_string(&sapisid) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let origin_str: String = match env.get_string(&origin) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let mut out_buffer = [0u8; 512];
    let written = generate_sapisid_hash(
        sapisid_str.as_ptr(),
        sapisid_str.len(),
        origin_str.as_ptr(),
        origin_str.len(),
        out_buffer.as_mut_ptr(),
        out_buffer.len(),
    );

    if written > 0 {
        if let Ok(hash_str) = std::str::from_utf8(&out_buffer[..written as usize]) {
            if let Ok(jstr) = env.new_string(hash_str) {
                return jstr.into_raw();
            }
        }
    }
    std::ptr::null_mut()
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


