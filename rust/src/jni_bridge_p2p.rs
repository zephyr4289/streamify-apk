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
//! ═══════════════════════════════════════════════════════════════════════
//! PHASE 1 FROZEN CONTRACT (feat/phase1-rust-mesh-32peers, directive §5) —
//! Engineer 3's Kotlin Mesh Orchestrator binds against these exact symbols
//! on `com.streamify.app.mesh.NativeMeshEngine`:
//!
//!   setTopology(topology: Int)                                  // 0=Multi,1=Single
//!   startLanBeacon(roomId: String)
//!   stopLanBeacon()
//!   setMemberAcl(peerPubkey: ByteArray, permissions: Int)
//!   kickPeer(peerPubkey: ByteArray, ban: Boolean)
//!
//! PHASE 1 EXTENSIONS (additive, clearly marked, NOT frozen):
//!   nativeMeshSubmitTransportIntent(kind: Int, body: ByteArray): Boolean
//!   nativeMeshPollLanRooms(): String (JSON, never null)
//!   nativeMeshGovernanceStats(): String (JSON, never null)
//!   nativeMeshDeclareHost(): Boolean
//! ═══════════════════════════════════════════════════════════════════════
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

// ═══════════════════════════════════════════════════════════════════════
// PHASE 1 — FROZEN CONTRACT (directive §5): com.streamify.app.mesh.
// NativeMeshEngine. The five signatures below are byte-exact with the
// directive; all are void-returning (Kotlin `external fun` unit calls).
// ═══════════════════════════════════════════════════════════════════════

/// `setTopology(topology: Int)` — 0 = MultiRender, 1 = SingleRender.
/// Unknown values are ignored (the engine keeps its current topology).
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_setTopology(
    _env: JNIEnv,
    _class: JClass,
    topology: jint,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Some(t) = crate::jam_governor::Topology::from_jint(topology) {
            with_node(|node| {
                node.set_topology(t);
            });
        }
    }));
}

/// `startLanBeacon(roomId: String)` — begins broadcasting the room
/// session descriptor (RoomID | HostPubKey | Epoch | Capacity |
/// MemberCount) in every beacon on the LAN subnet, and declares this
/// device the room host (room-creation semantics, directive B).
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_startLanBeacon(
    mut env: JNIEnv,
    _class: JClass,
    room_id: JString,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let room: String = match env.get_string(&room_id) {
            Ok(s) => s.into(),
            Err(_) => return,
        };
        if room.trim().is_empty() {
            return;
        }
        with_node(|node| {
            node.start_lan_beacon(&room);
        });
    }));
}

/// `stopLanBeacon()` — stops advertising the room descriptor (the node
/// remains in the mesh; beacons revert to the legacy 12-byte form).
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_stopLanBeacon(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        with_node(|node| node.stop_lan_beacon());
    }));
}

/// `setMemberAcl(peerPubkey: ByteArray, permissions: Int)` — host-only:
/// binds permission bits (0x01 playback / 0x02 volume / 0x04 co-host) to
/// a member's 32-byte ephemeral pubkey, locally AND via the signed
/// ACL_UPDATE wire frame so every replica enforces it at ingress.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_setMemberAcl(
    mut env: JNIEnv,
    _class: JClass,
    peer_pubkey: JByteArray,
    permissions: jint,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let bytes = match read_jbytes(&mut env, &peer_pubkey) {
            Some(b) => b,
            None => return,
        };
        let mut pk = [0u8; 32];
        if bytes.len() != 32 {
            return; // pubkey identity is exactly 32 bytes (Ed25519)
        }
        pk.copy_from_slice(&bytes);
        let bits = (permissions as u8) & 0x07; // only the three defined bits
        with_node(|node| node.set_member_acl(pk, bits));
    }));
}

/// `kickPeer(peerPubkey: ByteArray, ban: Boolean)` — host-only: evicts the
/// targeted member via the signed KICK_DIRECTIVE; `ban` blacklists the
/// pubkey for the session duration (re-join attempts are refused at the
/// beacon boundary on every replica).
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_kickPeer(
    mut env: JNIEnv,
    _class: JClass,
    peer_pubkey: JByteArray,
    ban: jboolean,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let bytes = match read_jbytes(&mut env, &peer_pubkey) {
            Some(b) => b,
            None => return,
        };
        let mut pk = [0u8; 32];
        if bytes.len() != 32 {
            return;
        }
        pk.copy_from_slice(&bytes);
        with_node(|node| node.kick_peer(pk, ban != 0));
    }));
}

// ═════════════════════════════ PHASE 1 extensions (NOT frozen §5) ══════
// Additive surface for the mesh orchestrator. Clearly marked so
// Engineer 3 can ignore them without breaking the frozen contract above.

/// Submits a transport control intent (Play/Pause/Seek/Skip/SkipPrev/
/// QueueReorder/Volume — kinds 0..6). The engine signs it with this
/// node's ephemeral key, fences it to the current epoch, and routes it
/// per topology (SingleRender → unicast to host; MultiRender → gossip).
/// `body` is 16 bytes (kind-specific, e.g. position_ms u64 LE).
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_nativeMeshSubmitTransportIntent(
    mut env: JNIEnv,
    _class: JClass,
    kind: jint,
    body: JByteArray,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(kind) = crate::jam_governor::IntentKind::from_u8(kind.clamp(0, 255) as u8) else {
            return JNI_FALSE;
        };
        let bytes = match read_jbytes(&mut env, &body) {
            Some(b) => b,
            None => return JNI_FALSE,
        };
        let mut b = [0u8; 16];
        if bytes.len() > 16 {
            return JNI_FALSE;
        }
        b[..bytes.len()].copy_from_slice(&bytes);
        match with_node(|node| node.submit_transport_intent(kind, b)) {
            Some(Ok(())) => JNI_TRUE,
            _ => JNI_FALSE,
        }
    }))
    .unwrap_or(JNI_FALSE)
}

/// Polls the LAN room discovery registry (directive B): JSON array of
/// nearby live Jam rooms, newest first, never null (`[]` when empty or
/// the mesh is not running). Field names are the Kotlin contract.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_nativeMeshPollLanRooms(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let json = with_node(|node| {
            let rooms: Vec<serde_json::Value> = node
                .lan_rooms()
                .into_iter()
                .map(|r| {
                    serde_json::json!({
                        "roomId": hex::encode(r.room_id),
                        "hostPubkey": hex::encode(r.host_pubkey),
                        "epoch": r.epoch,
                        "capacity": r.capacity,
                        "memberCount": r.member_count,
                        "hostAddr": r.from_addr.to_string(),
                        "port": r.port,
                    })
                })
                .collect();
            serde_json::to_string(&rooms).unwrap_or_else(|_| "[]".to_string())
        })
        .unwrap_or_else(|| "[]".to_string());

        match env.new_string(&json) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// Governance telemetry JSON (never null): identity, host, epoch,
/// topology, blacklist size, and the wire-boundary rejection counters.
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_nativeMeshGovernanceStats(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let json = with_node(|node| {
            let snap = node.governance_snapshot();
            let m = node.stats();
            serde_json::json!({
                "mePubkey": hex::encode(snap.me_pubkey),
                "hostPubkey": hex::encode(snap.host_pubkey),
                "isHost": snap.is_host,
                "epoch": snap.epoch,
                "topology": if snap.topology == crate::jam_governor::Topology::SingleRender { "SingleRender" } else { "MultiRender" },
                "memberCount": snap.member_count,
                "capacity": snap.capacity,
                "blacklistLen": snap.blacklist_len,
                "ptpSuppressed": m.ptp_suppressed,
                "sigRejects": m.gov_sig_rejects,
                "epochRejects": m.gov_epoch_rejects,
                "aclRejects": m.gov_acl_rejects,
                "rateLimited": m.gov_rate_limited,
                "replays": m.gov_replays,
                "blacklistRejects": m.gov_blacklist_rejects,
                "kicksApplied": m.gov_kicks_applied,
                "intentsCommitted": m.gov_intents_committed,
                "intentsForwarded": m.gov_intents_forwarded,
                "elections": m.gov_elections,
            })
            .to_string()
        })
        .unwrap_or_else(|| "{}".to_string());

        match env.new_string(&json) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// Self-declares this device the room host without starting a LAN beacon
/// (used when the room was created via QR/paste pairing instead of the
/// zero-friction LAN flow).
#[no_mangle]
pub extern "system" fn Java_com_streamify_app_mesh_NativeMeshEngine_nativeMeshDeclareHost(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        match with_node(|node| {
            node.declare_host();
            true
        }) {
            Some(true) => JNI_TRUE,
            _ => JNI_FALSE,
        }
    }))
    .unwrap_or(JNI_FALSE)
}
