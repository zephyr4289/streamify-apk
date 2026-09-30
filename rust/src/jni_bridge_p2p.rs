//! jni_bridge_p2p.rs — Frozen JNI surface for the P2P mesh (Jam Phase 2).
//!
//! FROZEN CONTRACT (brief §5) — Engineer 3's Kotlin Orchestrator binds
//! against these exact five symbols on `com.streamify.app.data.NativeBridge`:
//!
//!   nativeP2pStart(sessionId, deviceId, enableLan, enableWebrtc): Boolean
//!   nativeP2pStop()
//!   nativeP2pBroadcast(msgType: Int, payload: ByteArray): Boolean
//!   nativeP2pSendToPeer(peerIdHex: String, msgType: Int, payload: ByteArray): Boolean
//!   nativeP2pGetConnectedPeerCount(): Int
//!
//! EXTENSIONS (additive, clearly marked, NOT part of the frozen contract —
//! safe to ignore; see PR "interface freeze" table):
//!   nativeP2pSwarmSeedTrack(trackId: Long, data: ByteArray): Boolean
//!   nativeP2pSwarmStats(trackId: Long): String (JSON, never null)
//!
//! HOUSE RULES observed (same discipline as jni_bridge.rs):
//!   • Every entry point is wrapped in `catch_unwind` — a panic must never
//!     unwind across the FFI boundary into ART.
//!   • Default returns on failure: jboolean → JNI_FALSE, jint → 0,
//!     jstring → "{}" (never null: the Kotlin side declares non-null String).
//!   • Lock-poisoned globals are recovered via `into_inner()`, not panics.
//!
//! RUNTIME MODEL: a dedicated 2-worker Tokio runtime is created on Start
//! and dropped on Stop. All five frozen entry points are synchronous and
//! non-blocking — broadcast/send enqueue onto the mesh's outbound stage;
//! peer count reads an atomic snapshot. The mesh binds 0.0.0.0:7777 with
//! SO_REUSEPORT (falling back to an ephemeral port if the fixed one is
//! held by a zombie process).
//!
//! MSG-TYPE GUARD: the JNI surface refuses to inject internal control
//! types (heartbeat, beacon, gossip, chunk frames) — protocol traffic is
//! not spoofable from the app layer. App-visible types are PTP_SYNC
//! (0x01) and CRDT_OP (0x02), plus future app-level extensions ≥ 0x20.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Arc, Mutex, PoisonError};

use jni::objects::{JByteArray, JClass, JString, ReleaseMode};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;
use tokio::runtime::Runtime;

use crate::p2p_mesh::{MeshConfig, MeshNode, PeerId, RESERVED_CONTROL_TYPES};

const JNI_TRUE: jboolean = 1;
const JNI_FALSE: jboolean = 0;

struct P2pRuntime {
    _rt: Runtime,
    node: Arc<MeshNode>,
}

static P2P: Mutex<Option<P2pRuntime>> = Mutex::new(None);

#[inline]
fn heal<T>(r: Result<T, PoisonError<T>>) -> T {
    r.unwrap_or_else(|e| e.into_inner())
}

fn stop_runtime() {
    let mut guard = heal(P2P.lock());
    if let Some(rt) = guard.take() {
        rt.node.shutdown();
    } // dropping `rt` parks and drops the Tokio runtime
}

fn with_node<T>(f: impl FnOnce(&Arc<MeshNode>) -> T) -> Option<T> {
    let guard = heal(P2P.lock());
    guard.as_ref().map(|r| f(&r.node))
}

/// Reads a Java byte array into an owned Vec (NoCopyBack borrow, copy out).
fn read_jbytes(env: &mut JNIEnv, arr: &JByteArray) -> Option<Vec<u8>> {
    let len = match env.get_array_length(arr) {
        Ok(l) if l >= 0 => l as usize,
        _ => return None,
    };
    if len == 0 {
        return Some(Vec::new());
    }
    let elems = match unsafe { env.get_array_elements(arr, ReleaseMode::NoCopyBack) } {
        Ok(e) => e,
        Err(_) => return None,
    };
    let slice = unsafe { std::slice::from_raw_parts(elems.as_ptr() as *const u8, len) };
    Some(slice.to_vec())
}

fn app_msg_type_ok(msg_type: jint) -> bool {
    if !(0..=255).contains(&msg_type) {
        return false;
    }
    !RESERVED_CONTROL_TYPES.contains(&(msg_type as u8))
}

// ═══════════════════════════════════════════════ frozen contract (§5) ═══

#[no_mangle]
pub extern "system" fn Java_com_streamify_app_data_NativeBridge_nativeP2pStart(
    mut env: JNIEnv,
    _class: JClass,
    session_id: JString,
    device_id: JString,
    enable_lan: jboolean,
    enable_webrtc: jboolean,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let session: String = match env.get_string(&session_id) {
            Ok(s) => s.into(),
            Err(_) => return JNI_FALSE,
        };
        let device: String = match env.get_string(&device_id) {
            Ok(s) => s.into(),
            Err(_) => return JNI_FALSE,
        };
        if session.trim().is_empty() || device.trim().is_empty() {
            return JNI_FALSE;
        }

        // Restart semantics: a second Start tears the previous mesh down.
        stop_runtime();

        let rt = match tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
        {
            Ok(rt) => rt,
            Err(_) => return JNI_FALSE,
        };

        let mut cfg = MeshConfig::new(&session, &device);
        cfg.enable_lan = enable_lan != 0;
        cfg.enable_webrtc = enable_webrtc != 0;

        let node = match rt.block_on(async { MeshNode::start(cfg) }) {
            Ok(n) => n,
            Err(_) => return JNI_FALSE,
        };

        *heal(P2P.lock()) = Some(P2pRuntime { _rt: rt, node });
        JNI_TRUE
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_streamify_app_data_NativeBridge_nativeP2pStop(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        stop_runtime();
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_streamify_app_data_NativeBridge_nativeP2pBroadcast(
    mut env: JNIEnv,
    _class: JClass,
    msg_type: jint,
    payload: JByteArray,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        if !app_msg_type_ok(msg_type) {
            return JNI_FALSE;
        }
        let bytes = match read_jbytes(&mut env, &payload) {
            Some(b) => b,
            None => return JNI_FALSE,
        };
        match with_node(|node| node.broadcast(msg_type as u8, &bytes)) {
            Some(Ok(_)) => JNI_TRUE,
            _ => JNI_FALSE,
        }
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_streamify_app_data_NativeBridge_nativeP2pSendToPeer(
    mut env: JNIEnv,
    _class: JClass,
    peer_id_hex: JString,
    msg_type: jint,
    payload: JByteArray,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        if !app_msg_type_ok(msg_type) {
            return JNI_FALSE;
        }
        let hex: String = match env.get_string(&peer_id_hex) {
            Ok(s) => s.into(),
            Err(_) => return JNI_FALSE,
        };
        let peer = match PeerId::from_hex(&hex) {
            Some(p) => p,
            None => return JNI_FALSE,
        };
        let bytes = match read_jbytes(&mut env, &payload) {
            Some(b) => b,
            None => return JNI_FALSE,
        };
        match with_node(|node| node.send_to_peer(peer, msg_type as u8, &bytes)) {
            Some(Ok(())) => JNI_TRUE,
            _ => JNI_FALSE,
        }
    }))
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_streamify_app_data_NativeBridge_nativeP2pGetConnectedPeerCount(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        with_node(|node| node.peer_count() as jint).unwrap_or(0)
    }))
    .unwrap_or(0)
}

// ════════════════════════════════════════ extensions (NOT frozen §5) ════
// Additive surface for the audio swarmer. Clearly marked so Engineer 3 can
// ignore them without breaking the frozen contract above.

/// Turns this device into the seeder for `trackId` (the "fastest device
/// fetched from the CDN" role): chunks the audio, gossips the Blake3
/// manifest, announces availability to every connected peer.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_data_NativeBridge_nativeP2pSwarmSeedTrack(
    mut env: JNIEnv,
    _class: JClass,
    track_id: jlong,
    data: JByteArray,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        if track_id < 0 {
            return JNI_FALSE;
        }
        let bytes = match read_jbytes(&mut env, &data) {
            Some(b) => b,
            None => return JNI_FALSE,
        };
        match with_node(|node| {
            node.swarm_seed_track(track_id as u64, &bytes);
            true
        }) {
            Some(true) => JNI_TRUE,
            _ => JNI_FALSE,
        }
    }))
    .unwrap_or(JNI_FALSE)
}

/// JSON telemetry for one track's swarm (never null; `{}` when unknown).
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_data_NativeBridge_nativeP2pSwarmStats(
    env: JNIEnv,
    _class: JClass,
    track_id: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let json = with_node(|node| {
            let stats: Vec<_> = node
                .swarm_stats()
                .into_iter()
                .filter(|s| s.track_id == track_id as u64)
                .collect();
            if stats.is_empty() {
                "{}".to_string()
            } else {
                serde_json::to_string(&stats[0]).unwrap_or_else(|_| "{}".to_string())
            }
        })
        .unwrap_or_else(|| "{}".to_string());

        match env.new_string(&json) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }))
    .unwrap_or(std::ptr::null_mut())
}
