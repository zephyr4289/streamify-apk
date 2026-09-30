//! p2p_mesh.rs — Zero-server multi-transport P2P mesh router (Jam Phase 2).
//!
//! MISSION (lead brief): local-domain UDP fast path (<2 ms same-room), remote
//! WebRTC DataChannel path for cross-city peers, automatic smart route
//! switching between the two, and a frozen binary wire format shared by every
//! packet that traverses either transport.
//!
//! ── FROZEN WIRE FORMAT ("48-byte frame family") ──────────────────────────
//! The brief's authoritative struct is reproduced verbatim below. With
//! `#[repr(C, packed)]` the field layout serializes to exactly 46 bytes
//! (little-endian per field):
//!
//!   offset  field               len  notes
//!   0       magic u16            2    0x5354 ('S','T' → Streamify)
//!   2       version u8           1    0x03
//!   3       msg_type u8          1    0x01..0x0B (see MSG_* registry)
//!   4       sender_id [u8;8]     8    64-bit truncated Blake3 node ID
//!   12      session_id [u8;16]   16   128-bit room UUID
//!   28      sequence u32         4    gossip broadcast counter (see D3)
//!   32      timestamp_mono_ns i64 8    sender-local monotonic clock, ns
//!   40      payload_len u16      2    exact byte length of the payload
//!   42      checksum_fnv1a u32   4    FNV-1a/32 over the pre-checksum header
//!
//! AUDIT DEVIATIONS FROM THE BRIEF PROSE (deliberate, documented, each is a
//! one-line revert if the lead rules otherwise):
//!   D1  The prose says "48-Byte Packed Frame", but the frozen struct's
//!       fields sum to 46 bytes packed. The struct is the contract — a
//!       Kotlin transliteration of that struct yields the same 46 bytes —
//!       so the struct is implemented verbatim and pinned with a
//!       compile-time size assertion.
//!   D2  The prose says checksum "over [0..36)". That range stops mid-way
//!       through `timestamp_mono_ns` and leaves `payload_len` unprotected:
//!       a corrupted payload_len would pass integrity and desync the parser.
//!       We checksum the full pre-checksum header span [0..42), protecting
//!       strictly more bytes. Constant `CHECKSUM_COVERED_LEN` flips to 36
//!       in one line if Kotlin already shipped [0..36).
//!   D3  `sequence` is the *gossip broadcast* counter only (see
//!       `GOSSIP_ROUTED_TYPES`). Unicast/control frames carry sequence = 0.
//!       This keeps each sender's broadcast stream dense so receivers can
//!       detect sequence gaps and GRAFT-heal holes (gossip.rs) without false
//!       positives punched by unicast traffic.
//!
//! ── MESSAGE TYPE REGISTRY (0x01..0x05 frozen by the brief) ───────────────
//!   0x01 PTP_SYNC               gossip-routed  (jam_clock / PTP engine)
//!   0x02 CRDT_OP                gossip-routed  (JamOp wire payloads)
//!   0x03 CHUNK_DATA             swarm-routed   (chunk_swarmer)
//!   0x04 HEARTBEAT              control        (RTT probe / liveness)
//!   0x05 GOSSIP_IHAVE           control        (PlumTree lazy announcement)
//! Extension types owned by this subsystem (no conflict with the frozen 5):
//!   0x06 GOSSIP_GRAFT           control        (PlumTree tree heal / fetch)
//!   0x07 CHUNK_HAVE             swarm-routed   (bitfield availability)
//!   0x08 CHUNK_REQUEST          swarm-routed   (pull a missing chunk)
//!   0x09 BEACON                 control        (subnet peer discovery)
//!   0x0A TRACK_MANIFEST         gossip-routed  (chunk-hash table announce)
//!   0x0B TRACK_MANIFEST_REQUEST swarm-routed   (manifest bootstrap)
//!
//! ── TRANSPORT MODEL ──────────────────────────────────────────────────────
//! Local domain: one bound UDP socket (SO_REUSEADDR + SO_REUSEPORT, optional
//! SO_BROADCAST beacons on port 7777, optional multicast group join with
//! IP_MULTICAST_LOOP). Peers heard on UDP are `RouteDomain::Local` and are
//! reached directly — the <2 ms fast path. Remote domain: peers registered
//! through a [`RemoteTransport`] (WebRTC DataChannel adapter) are
//! `RouteDomain::Remote` and route through that encrypted channel. The
//! router classifies per peer, so a phone that roams from Wi-Fi to cellular
//! is re-classified when its UDP beacons stop arriving — no per-message
//! policy at the call sites.
//!
//! Chaos engineering is built in: per-peer [`LinkCondition`]s (loss + latency
//! window) are applied at the outbound stage, so integration tests reproduce
//! packet loss and Wi-Fi jitter on loopback without a TCP proxy.

use std::collections::{BinaryHeap, HashMap};
use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicI64, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock, PoisonError, RwLock};
use std::time::{Duration, Instant};

use rand::Rng;
use socket2::{Domain, Protocol, Socket, Type};
use tokio::net::UdpSocket;
use tokio::sync::{mpsc, watch};

use crate::chunk_swarmer::{SwarmAction, SwarmEvent, SwarmManager, SwarmParams, TrackSwarmStats};
use crate::gossip::{Action, GossipEngine, GossipParams, GossipStats, Outcomes};

// ─────────────────────────────────────────────────────────── protocol consts

/// Streamify mesh magic: 'S' << 8 | 'T' = 0x5354.
pub const MAGIC: u16 = 0x5354;
/// Wire protocol version (frozen by the brief).
pub const PROTO_VERSION: u8 = 0x03;
/// `size_of::<P2pPacketHeader>()` under `#[repr(C, packed)]` (deviation D1).
pub const HEADER_LEN: usize = 46;
/// Checksum coverage: every header byte preceding `checksum_fnv1a`
/// (deviation D2). Flip to 36 if the lead freezes [0..36).
pub const CHECKSUM_COVERED_LEN: usize = HEADER_LEN - 4;
/// Default LAN mesh port (brief §3.A).
pub const DEFAULT_MESH_PORT: u16 = 7777;
/// One frame per datagram; payload bounded by the `u16` length field.
pub const MAX_PAYLOAD_LEN: usize = u16::MAX as usize;
/// Kernel limit for a UDP datagram payload (IPv4).
pub const MAX_DATAGRAM: usize = 65_507;

// Frozen message types (0x01..0x05 per brief §4).
pub const MSG_PTP_SYNC: u8 = 0x01;
pub const MSG_CRDT_OP: u8 = 0x02;
pub const MSG_CHUNK_DATA: u8 = 0x03;
pub const MSG_HEARTBEAT: u8 = 0x04;
pub const MSG_GOSSIP_IHAVE: u8 = 0x05;
// Extension types owned by the mesh subsystem.
pub const MSG_GOSSIP_GRAFT: u8 = 0x06;
pub const MSG_CHUNK_HAVE: u8 = 0x07;
pub const MSG_CHUNK_REQUEST: u8 = 0x08;
pub const MSG_BEACON: u8 = 0x09;
pub const MSG_TRACK_MANIFEST: u8 = 0x0A;
pub const MSG_TRACK_MANIFEST_REQUEST: u8 = 0x0B;

/// Message types that flow through the PlumTree engine (dense `sequence`
/// stream, deviation D3). Everything else is unicast/control and bypasses
/// the gossip dedupe layer.
pub const GOSSIP_ROUTED_TYPES: [u8; 3] = [MSG_PTP_SYNC, MSG_CRDT_OP, MSG_TRACK_MANIFEST];

/// Internal control types the JNI surface refuses to inject from the app
/// layer — protocol traffic must never be spoofable from Kotlin.
pub const RESERVED_CONTROL_TYPES: [u8; 8] = [
    MSG_HEARTBEAT,
    MSG_GOSSIP_IHAVE,
    MSG_GOSSIP_GRAFT,
    MSG_CHUNK_DATA,
    MSG_CHUNK_HAVE,
    MSG_CHUNK_REQUEST,
    MSG_BEACON,
    MSG_TRACK_MANIFEST_REQUEST,
];

/// Capability bits advertised in beacons.
pub const CAP_LAN: u16 = 0x0001;
pub const CAP_WEBRTC: u16 = 0x0002;

// ─────────────────────────────────────────────────────────────── identity

/// 64-bit truncated Blake3 node identity (frozen header field).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct PeerId(pub u64);

impl PeerId {
    /// Derives the stable 64-bit node ID from a device identifier string.
    pub fn from_device_str(device: &str) -> Self {
        let h = blake3::hash(device.as_bytes());
        let mut b = [0u8; 8];
        b.copy_from_slice(&h.as_bytes()[..8]);
        PeerId(u64::from_le_bytes(b))
    }

    /// Stable 16-hex-char rendering (JNI `peer_id_hex` contract).
    pub fn to_hex(self) -> String {
        hex::encode(self.0.to_le_bytes())
    }

    /// Parses the 16-hex-char rendering produced by [`PeerId::to_hex`].
    pub fn from_hex(s: &str) -> Option<Self> {
        let b = hex::decode(s.trim()).ok()?;
        if b.len() != 8 {
            return None;
        }
        Some(PeerId(u64::from_le_bytes(b.try_into().ok()?)))
    }
}

impl std::fmt::Display for PeerId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.to_hex())
    }
}

/// 128-bit session identity (frozen header field) — Blake3 of the room UUID.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct SessionId(pub [u8; 16]);

impl SessionId {
    pub fn from_session_str(session: &str) -> Self {
        let h = blake3::hash(session.as_bytes());
        let mut b = [0u8; 16];
        b.copy_from_slice(&h.as_bytes()[..16]);
        SessionId(b)
    }
}

/// Process-lifetime monotonic anchor (same discipline as
/// `jam_clock::ANCHOR`): stable across the process, immune to wall-clock
/// steps. Cross-device skew is resolved by the PTP engine layered on
/// PTP_SYNC packets — raw mono timestamps are never compared across nodes.
static MONO_ANCHOR: OnceLock<Instant> = OnceLock::new();

/// Monotonic nanoseconds since the process anchor.
#[inline]
pub fn mono_ns() -> i64 {
    MONO_ANCHOR
        .get_or_init(Instant::now)
        .elapsed()
        .as_nanos()
        .min(i64::MAX as u128) as i64
}

// ─────────────────────────────────────────────────── frozen packet header

/// Frozen binary header (brief §4, verbatim field order and types).
///
/// `#[repr(C, packed)]` means field references would be unaligned; the type
/// is only handled by value and serialized field-wise little-endian via
/// [`P2pPacketHeader::to_bytes`] / [`P2pPacketHeader::from_bytes`] — the
/// same UB-free discipline `jam_crdt::JamOp` already follows.
#[repr(C, packed)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct P2pPacketHeader {
    pub magic: u16,             // 0x5354
    pub version: u8,            // 0x03
    pub msg_type: u8,           // MSG_*
    pub sender_id: [u8; 8],     // PeerId LE bytes
    pub session_id: [u8; 16],   // SessionId bytes
    pub sequence: u32,          // gossip broadcast counter (D3)
    pub timestamp_mono_ns: i64, // sender-local monotonic ns
    pub payload_len: u16,       // payload byte length
    pub checksum_fnv1a: u32,    // FNV-1a/32 over bytes [0..CHECKSUM_COVERED_LEN)
}

// Compile-time pin of the frozen layout — deviation D1 cannot regress
// silently.
const _: () = assert!(std::mem::size_of::<P2pPacketHeader>() == HEADER_LEN);

/// FNV-1a/32 (same parameters as `jam_crdt`'s op checksum).
#[inline]
pub fn fnv1a32(data: &[u8]) -> u32 {
    let mut h: u32 = 0x811c_9dc5;
    for &b in data {
        h ^= b as u32;
        h = h.wrapping_mul(0x0100_0193);
    }
    h
}

impl P2pPacketHeader {
    /// True when `sequence` carries gossip-stream meaning (D3).
    pub fn is_gossip_routed(msg_type: u8) -> bool {
        GOSSIP_ROUTED_TYPES.contains(&msg_type)
    }

    /// Field-wise little-endian serialization (no transmute, no UB).
    pub fn to_bytes(self) -> [u8; HEADER_LEN] {
        let mut b = [0u8; HEADER_LEN];
        b[0..2].copy_from_slice(&self.magic.to_le_bytes());
        b[2] = self.version;
        b[3] = self.msg_type;
        b[4..12].copy_from_slice(&self.sender_id);
        b[12..28].copy_from_slice(&self.session_id);
        b[28..32].copy_from_slice(&self.sequence.to_le_bytes());
        b[32..40].copy_from_slice(&self.timestamp_mono_ns.to_le_bytes());
        b[40..42].copy_from_slice(&self.payload_len.to_le_bytes());
        b[42..46].copy_from_slice(&self.checksum_fnv1a.to_le_bytes());
        b
    }

    /// Field-wise little-endian parse + shape validation (checksum pending).
    pub fn from_bytes(b: &[u8]) -> Result<Self, MeshError> {
        if b.len() < HEADER_LEN {
            return Err(MeshError::Truncated);
        }
        let hdr = P2pPacketHeader {
            magic: u16::from_le_bytes(b[0..2].try_into().unwrap()),
            version: b[2],
            msg_type: b[3],
            sender_id: b[4..12].try_into().unwrap(),
            session_id: b[12..28].try_into().unwrap(),
            sequence: u32::from_le_bytes(b[28..32].try_into().unwrap()),
            timestamp_mono_ns: i64::from_le_bytes(b[32..40].try_into().unwrap()),
            payload_len: u16::from_le_bytes(b[40..42].try_into().unwrap()),
            checksum_fnv1a: u32::from_le_bytes(b[42..46].try_into().unwrap()),
        };
        if hdr.magic != MAGIC {
            return Err(MeshError::BadMagic);
        }
        if hdr.version != PROTO_VERSION {
            return Err(MeshError::BadVersion);
        }
        Ok(hdr)
    }
}

/// One decoded inbound frame.
#[derive(Debug, Clone)]
pub struct DecodedPacket {
    pub header: P2pPacketHeader,
    pub payload: Vec<u8>,
    /// Raw datagram bytes (header + payload) — verbatim forwarding keeps
    /// intermediate relays from re-serializing (and re-numbering) frames.
    pub raw: Vec<u8>,
}

/// Encodes one datagram: seals the checksum into the header, appends payload.
pub fn encode_packet(header: &mut P2pPacketHeader, payload: &[u8]) -> Vec<u8> {
    header.payload_len = payload.len().min(MAX_PAYLOAD_LEN) as u16;
    let mut raw = Vec::with_capacity(HEADER_LEN + payload.len());
    raw.extend_from_slice(&header.to_bytes()[..CHECKSUM_COVERED_LEN]);
    // Checksum over the pre-checksum header span (deviation D2).
    header.checksum_fnv1a = fnv1a32(&raw);
    raw.extend_from_slice(&header.checksum_fnv1a.to_le_bytes());
    raw.extend_from_slice(payload);
    raw
}

/// Decodes and fully validates one datagram (magic, version, checksum,
/// length consistency). Session filtering is caller policy.
pub fn decode_packet(buf: &[u8]) -> Result<DecodedPacket, MeshError> {
    let header = P2pPacketHeader::from_bytes(buf)?;
    if fnv1a32(&buf[..CHECKSUM_COVERED_LEN]) != header.checksum_fnv1a {
        return Err(MeshError::ChecksumMismatch);
    }
    let payload_len = header.payload_len as usize;
    if buf.len() != HEADER_LEN + payload_len {
        return Err(MeshError::LengthMismatch {
            expected: HEADER_LEN + payload_len,
            actual: buf.len(),
        });
    }
    Ok(DecodedPacket {
        header,
        payload: buf[HEADER_LEN..].to_vec(),
        raw: buf.to_vec(),
    })
}

// ─────────────────────────────────────────────────────────────── errors

/// Mesh-layer error surface (hand-rolled to keep the dependency tree lean).
#[derive(Debug, Clone, PartialEq)]
pub enum MeshError {
    Truncated,
    BadMagic,
    BadVersion,
    ChecksumMismatch,
    LengthMismatch { expected: usize, actual: usize },
    SessionMismatch,
    PeerUnknown,
    NoRemoteTransport,
    PayloadTooLarge(usize),
}

impl std::fmt::Display for MeshError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            MeshError::Truncated => write!(f, "datagram shorter than the 46-byte header"),
            MeshError::BadMagic => write!(f, "magic mismatch (not a Streamify mesh frame)"),
            MeshError::BadVersion => write!(f, "protocol version mismatch"),
            MeshError::ChecksumMismatch => write!(f, "FNV-1a header checksum mismatch"),
            MeshError::LengthMismatch { expected, actual } => {
                write!(
                    f,
                    "payload_len disagrees with datagram ({expected} vs {actual})"
                )
            }
            MeshError::SessionMismatch => write!(f, "frame belongs to a different jam session"),
            MeshError::PeerUnknown => write!(f, "peer is not in the routing table"),
            MeshError::NoRemoteTransport => {
                write!(
                    f,
                    "remote route requested but no WebRTC transport is attached"
                )
            }
            MeshError::PayloadTooLarge(n) => write!(f, "payload {n} exceeds u16 frame budget"),
        }
    }
}

impl std::error::Error for MeshError {}

// ────────────────────────────────────────────────────────── link condition

/// Per-peer outbound link impairment (chaos engineering / lab reproduction
/// of Wi-Fi jitter). Applied before the socket: dropped frames never leave;
/// delayed frames leave through the timed heap, so per-link ordering is
/// best-effort exactly like real radio.
#[derive(Debug, Clone, Copy)]
pub struct LinkCondition {
    /// Drop probability in [0, 1].
    pub loss: f64,
    /// Uniform latency window (min, max). `(0, 0)` = no artificial delay.
    pub delay: (Duration, Duration),
}

impl Default for LinkCondition {
    fn default() -> Self {
        LinkCondition {
            loss: 0.0,
            delay: (Duration::ZERO, Duration::ZERO),
        }
    }
}

impl LinkCondition {
    /// Samples one send attempt: `(dropped, latency)`.
    pub fn sample(&self, rng: &mut impl Rng) -> (bool, Duration) {
        let dropped = self.loss > 0.0 && rng.gen_bool(self.loss.clamp(0.0, 1.0));
        let delay = if self.delay.1 > self.delay.0 {
            let lo = self.delay.0.as_millis() as u64;
            let hi = self.delay.1.as_millis() as u64;
            Duration::from_millis(rng.gen_range(lo..=hi))
        } else {
            self.delay.0
        };
        (dropped, delay)
    }
}

// ───────────────────────────────────────────────────────── routing table

/// Which transport a peer is reachable on.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RouteDomain {
    /// Direct UDP on the LAN / Wi-Fi Direct / same subnet (<2 ms path).
    Local,
    /// Encrypted WebRTC DataChannel over the open internet.
    Remote,
}

/// Remote-domain send hook — the WebRTC DataChannel adapter plugs in here.
/// Synchronous because a DataChannel `send()` is non-blocking; queuing
/// happens inside the adapter.
pub trait RemoteTransport: Send + Sync {
    fn send_to_peer(&self, peer: PeerId, bytes: &[u8]) -> Result<(), MeshError>;
    fn name(&self) -> &'static str;
}

#[derive(Debug)]
struct PeerEntry {
    addr: SocketAddr,
    domain: RouteDomain,
    caps: AtomicU64,
    rtt_ns: AtomicI64,
    last_seen_ns: AtomicI64,
    condition: Option<LinkCondition>,
}

impl PeerEntry {
    fn new(addr: SocketAddr, domain: RouteDomain, caps: u16, now: i64) -> Self {
        PeerEntry {
            addr,
            domain,
            caps: AtomicU64::new(caps as u64),
            rtt_ns: AtomicI64::new(0),
            last_seen_ns: AtomicI64::new(now),
            condition: None,
        }
    }
}

/// Routing-table row exposed to tests / telemetry.
#[derive(Debug, Clone)]
pub struct PeerInfo {
    pub id: PeerId,
    pub addr: SocketAddr,
    pub domain: RouteDomain,
    pub caps: u16,
    pub rtt_ms: Option<u64>,
}

/// A frame handed to the outbound stage.
struct Outbound {
    to: SocketAddr,
    bytes: Vec<u8>,
    delay: Duration,
}

// ─────────────────────────────────────────────────────────── configuration

/// Router + housekeeping knobs. Defaults are production values; the
/// integration tests shrink the timers to chaos-sim cadences.
#[derive(Debug, Clone)]
pub struct MeshConfig {
    pub session_id: String,
    pub device_id: String,
    /// Bind target; port 0 = ephemeral (tests). Production: 0.0.0.0:7777.
    pub listen_addr: String,
    /// Fall back to an ephemeral port when the fixed port is taken.
    pub allow_port_fallback: bool,
    /// Emit subnet broadcast beacons for automatic same-room discovery.
    pub discovery_broadcast: bool,
    pub broadcast_addr: String,
    /// Optional multicast group join (Wi-Fi LAN; IP_MULTICAST_LOOP enabled).
    pub multicast_group: Option<Ipv4Addr>,
    pub beacon_interval: Duration,
    pub heartbeat_interval: Duration,
    pub peer_timeout: Duration,
    pub recv_buffer_bytes: usize,
    pub enable_lan: bool,
    pub enable_webrtc: bool,
    pub gossip: GossipParams,
    pub swarm: SwarmParams,
}

impl MeshConfig {
    pub fn new(session_id: &str, device_id: &str) -> Self {
        MeshConfig {
            session_id: session_id.to_string(),
            device_id: device_id.to_string(),
            listen_addr: format!("0.0.0.0:{DEFAULT_MESH_PORT}"),
            allow_port_fallback: true,
            discovery_broadcast: true,
            broadcast_addr: format!("255.255.255.255:{DEFAULT_MESH_PORT}"),
            multicast_group: None,
            beacon_interval: Duration::from_millis(500),
            heartbeat_interval: Duration::from_millis(400),
            peer_timeout: Duration::from_millis(3_000),
            recv_buffer_bytes: 4 << 20,
            enable_lan: true,
            enable_webrtc: false,
            gossip: GossipParams::default(),
            swarm: SwarmParams::default(),
        }
    }

    /// Test profile: loopback bind, tight timers, no broadcast noise.
    pub fn loopback(session_id: &str, device_id: &str) -> Self {
        let mut c = MeshConfig::new(session_id, device_id);
        c.listen_addr = "127.0.0.1:0".to_string();
        c.allow_port_fallback = false;
        c.discovery_broadcast = false;
        c.beacon_interval = Duration::from_millis(25);
        c.heartbeat_interval = Duration::from_millis(100);
        c.peer_timeout = Duration::from_millis(2_000);
        c
    }
}

// ──────────────────────────────────────────────────────────────── stats

/// Atomic counter block (lock-free telemetry).
#[derive(Debug, Default)]
struct StatsCounters {
    tx_datagrams: AtomicU64,
    tx_bytes: AtomicU64,
    tx_loss_dropped: AtomicU64,
    tx_peer_unknown: AtomicU64,
    tx_no_transport: AtomicU64,
    rx_datagrams: AtomicU64,
    rx_bytes: AtomicU64,
    rx_checksum_reject: AtomicU64,
    rx_version_or_magic_reject: AtomicU64,
    rx_length_reject: AtomicU64,
    rx_session_mismatch: AtomicU64,
    rx_self_echo: AtomicU64,
    rx_unknown_link: AtomicU64,
    app_backpressure_drop: AtomicU64,
    peers_discovered: AtomicU64,
    peers_expired: AtomicU64,
}

/// Point-in-time snapshot for tests / JNI / PR evidence.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct MeshStats {
    pub tx_datagrams: u64,
    pub tx_bytes: u64,
    pub tx_loss_dropped: u64,
    pub tx_peer_unknown: u64,
    pub tx_no_transport: u64,
    pub rx_datagrams: u64,
    pub rx_bytes: u64,
    pub rx_checksum_reject: u64,
    pub rx_version_or_magic_reject: u64,
    pub rx_length_reject: u64,
    pub rx_session_mismatch: u64,
    pub rx_self_echo: u64,
    pub rx_unknown_link: u64,
    pub app_backpressure_drop: u64,
    pub peers_discovered: u64,
    pub peers_expired: u64,
}

/// One inbound application frame (delivered after gossip dedupe).
#[derive(Debug, Clone)]
pub struct InboundPacket {
    pub header: P2pPacketHeader,
    pub payload: Vec<u8>,
    pub from_peer: PeerId,
    pub from_addr: SocketAddr,
}

// ───────────────────────────────────────────────────────────── the node

/// Zero-server mesh node: one UDP transport, one PlumTree engine, one audio
/// swarm manager, and the routing glue between them.
///
/// All public APIs are synchronous — sends enqueue onto the outbound stage,
/// timers run on the Tokio runtime [`MeshNode::start`] was invoked within.
/// Background tasks hold `Weak` references, so dropping the last `Arc`
/// without calling [`MeshNode::shutdown`] still tears the loops down.
pub struct MeshNode {
    cfg: MeshConfig,
    id: PeerId,
    session: SessionId,
    sock: Arc<UdpSocket>,
    outbound_tx: mpsc::UnboundedSender<Outbound>,
    stop_tx: watch::Sender<bool>,
    broadcast_target: Option<SocketAddr>,
    peers: RwLock<HashMap<PeerId, PeerEntry>>,
    gossip: Mutex<GossipEngine>,
    swarm: Mutex<SwarmManager>,
    subs: Mutex<HashMap<u8, Vec<mpsc::Sender<InboundPacket>>>>,
    swarm_subs: Mutex<Vec<mpsc::Sender<SwarmEvent>>>,
    remote_transport: RwLock<Option<Arc<dyn RemoteTransport>>>,
    broadcast_seq: AtomicU32,
    last_beacon_ns: AtomicI64,
    last_heartbeat_ns: AtomicI64,
    last_sweep_ns: AtomicI64,
    stats: StatsCounters,
}

/// Recovers from mutex poisoning instead of panicking (house rule —
/// FFI-reachable paths must never cascade a panic).
#[inline]
fn heal<T>(r: Result<T, PoisonError<T>>) -> T {
    r.unwrap_or_else(|e| e.into_inner())
}

impl MeshNode {
    /// Binds the local transport and spawns the recv / sender /
    /// housekeeping loops. Must be called inside a Tokio runtime.
    pub fn start(cfg: MeshConfig) -> io::Result<Arc<MeshNode>> {
        assert!(
            tokio::runtime::Handle::try_current().is_ok(),
            "MeshNode::start must be called inside a Tokio runtime"
        );

        let id = PeerId::from_device_str(&cfg.device_id);
        let session = SessionId::from_session_str(&cfg.session_id);
        let broadcast_target = if cfg.discovery_broadcast {
            cfg.broadcast_addr.parse::<SocketAddr>().ok()
        } else {
            None
        };

        let sock = Arc::new(bind_udp(&cfg)?);
        if let Some(group) = cfg.multicast_group {
            // Best-effort: containers without a multicast-capable loopback
            // route must not fail startup — beacons still work.
            let _ = sock.join_multicast_v4(group, Ipv4Addr::UNSPECIFIED);
        }

        let (outbound_tx, outbound_rx) = mpsc::unbounded_channel::<Outbound>();
        let (stop_tx, stop_rx) = watch::channel(false);

        let gossip_engine = GossipEngine::new(id.0, cfg.gossip.clone());
        let swarm_manager = SwarmManager::new(id.0, cfg.swarm.clone());

        let node = Arc::new(MeshNode {
            cfg,
            id,
            session,
            sock: Arc::clone(&sock),
            outbound_tx,
            stop_tx: stop_tx.clone(),
            broadcast_target,
            peers: RwLock::new(HashMap::new()),
            gossip: Mutex::new(gossip_engine),
            swarm: Mutex::new(swarm_manager),
            subs: Mutex::new(HashMap::new()),
            swarm_subs: Mutex::new(Vec::new()),
            remote_transport: RwLock::new(None),
            broadcast_seq: AtomicU32::new(0),
            last_beacon_ns: AtomicI64::new(i64::MIN / 2),
            last_heartbeat_ns: AtomicI64::new(i64::MIN / 2),
            last_sweep_ns: AtomicI64::new(i64::MIN / 2),
            stats: StatsCounters::default(),
        });

        // Event sink fans swarm events out to subscribers via a weak
        // back-reference (the manager must not keep the node alive).
        let weak = Arc::downgrade(&node);
        let sink: Arc<dyn Fn(SwarmEvent) + Send + Sync> = Arc::new(move |ev: SwarmEvent| {
            if let Some(n) = weak.upgrade() {
                n.emit_swarm_event(ev);
            }
        });
        heal(node.swarm.lock()).set_event_sink(sink);

        tokio::spawn(recv_loop(
            Arc::clone(&sock),
            Arc::downgrade(&node),
            stop_rx.clone(),
        ));
        tokio::spawn(sender_loop(sock, outbound_rx, stop_rx.clone()));
        tokio::spawn(housekeeping_loop(Arc::downgrade(&node), stop_rx));

        Ok(node)
    }

    // ── identity / introspection ──────────────────────────────────────

    pub fn id(&self) -> PeerId {
        self.id
    }

    pub fn session(&self) -> SessionId {
        self.session
    }

    pub fn local_addr(&self) -> SocketAddr {
        self.sock
            .local_addr()
            .unwrap_or_else(|_| "0.0.0.0:0".parse().unwrap())
    }

    pub fn peer_count(&self) -> usize {
        heal(self.peers.read()).len()
    }

    pub fn stats(&self) -> MeshStats {
        let s = &self.stats;
        MeshStats {
            tx_datagrams: s.tx_datagrams.load(Ordering::Relaxed),
            tx_bytes: s.tx_bytes.load(Ordering::Relaxed),
            tx_loss_dropped: s.tx_loss_dropped.load(Ordering::Relaxed),
            tx_peer_unknown: s.tx_peer_unknown.load(Ordering::Relaxed),
            tx_no_transport: s.tx_no_transport.load(Ordering::Relaxed),
            rx_datagrams: s.rx_datagrams.load(Ordering::Relaxed),
            rx_bytes: s.rx_bytes.load(Ordering::Relaxed),
            rx_checksum_reject: s.rx_checksum_reject.load(Ordering::Relaxed),
            rx_version_or_magic_reject: s.rx_version_or_magic_reject.load(Ordering::Relaxed),
            rx_length_reject: s.rx_length_reject.load(Ordering::Relaxed),
            rx_session_mismatch: s.rx_session_mismatch.load(Ordering::Relaxed),
            rx_self_echo: s.rx_self_echo.load(Ordering::Relaxed),
            rx_unknown_link: s.rx_unknown_link.load(Ordering::Relaxed),
            app_backpressure_drop: s.app_backpressure_drop.load(Ordering::Relaxed),
            peers_discovered: s.peers_discovered.load(Ordering::Relaxed),
            peers_expired: s.peers_expired.load(Ordering::Relaxed),
        }
    }

    pub fn gossip_stats(&self) -> GossipStats {
        heal(self.gossip.lock()).stats()
    }

    /// Test/diagnostic probe into the gossip engine's view of one id.
    pub fn gossip_probe(&self, sender: u64, seq: u32) -> (bool, bool, bool, u32) {
        heal(self.gossip.lock()).probe(crate::gossip::MsgId { sender, seq })
    }

    pub fn swarm_stats(&self) -> Vec<TrackSwarmStats> {
        heal(self.swarm.lock()).stats()
    }

    /// Routing-table snapshot (tests, JNI diagnostics).
    pub fn peers_snapshot(&self) -> Vec<PeerInfo> {
        heal(self.peers.read())
            .iter()
            .map(|(id, e)| PeerInfo {
                id: *id,
                addr: e.addr,
                domain: e.domain,
                caps: e.caps.load(Ordering::Relaxed) as u16,
                rtt_ms: {
                    let ns = e.rtt_ns.load(Ordering::Relaxed);
                    (ns > 0).then(|| (ns as u64) / 1_000_000)
                },
            })
            .collect()
    }

    // ── peer management ───────────────────────────────────────────────

    /// Seeds a peer by address: fires one beacon at it, which triggers the
    /// auto-registration handshake (both sides learn each other within one
    /// round trip even without broadcast discovery).
    pub fn add_peer(&self, addr: SocketAddr) {
        let raw = self.build_beacon_packet();
        let _ = self.outbound_tx.send(Outbound {
            to: addr,
            bytes: raw,
            delay: Duration::ZERO,
        });
    }

    /// Installs a chaos-engineering link condition for one peer
    /// (`None` clears it). Applied to every outbound frame to that peer.
    pub fn set_link_condition(&self, peer: PeerId, cond: Option<LinkCondition>) {
        let mut map = heal(self.peers.write());
        if let Some(e) = map.get_mut(&peer) {
            e.condition = cond;
        }
    }

    /// Attaches the remote-domain (WebRTC DataChannel) transport.
    pub fn set_remote_transport(&self, t: Arc<dyn RemoteTransport>) {
        *heal(self.remote_transport.write()) = Some(t);
    }

    /// Registers a remote peer routed through the attached transport.
    pub fn add_remote_peer(&self, peer: PeerId) {
        let now = mono_ns();
        let mut map = heal(self.peers.write());
        map.entry(peer).or_insert_with(|| {
            PeerEntry::new(
                "0.0.0.0:0".parse().unwrap(),
                RouteDomain::Remote,
                CAP_WEBRTC,
                now,
            )
        });
        drop(map);
        self.on_peer_joined(peer);
    }

    fn register_or_refresh(
        &self,
        peer: PeerId,
        addr: SocketAddr,
        caps: Option<u16>,
        now: i64,
    ) -> bool {
        {
            let map = heal(self.peers.read());
            if let Some(e) = map.get(&peer) {
                e.last_seen_ns.store(now, Ordering::Relaxed);
                if let Some(c) = caps {
                    e.caps.store(c as u64, Ordering::Relaxed);
                }
                if e.addr == addr {
                    return false;
                }
            }
        }
        // Slow path: brand-new peer or address migration (roaming).
        let mut map = heal(self.peers.write());
        if let Some(e) = map.get_mut(&peer) {
            e.addr = addr;
            e.domain = RouteDomain::Local; // heard on UDP again → fast path
            e.last_seen_ns.store(now, Ordering::Relaxed);
            return false;
        }
        map.insert(
            peer,
            PeerEntry::new(addr, RouteDomain::Local, caps.unwrap_or(CAP_LAN), now),
        );
        self.stats.peers_discovered.fetch_add(1, Ordering::Relaxed);
        true
    }

    /// Resolves the IMMEDIATE link peer for a datagram source address.
    /// Gossip-routed frames carry the ORIGIN's id in the header — the
    /// epidemic tree is managed over links, so relays must be identified
    /// by their socket address, never by the header sender.
    fn link_peer_of_addr(&self, addr: SocketAddr) -> Option<PeerId> {
        let map = heal(self.peers.read());
        map.iter().find(|(_, e)| e.addr == addr).map(|(id, _)| *id)
    }

    fn on_peer_joined(&self, peer: PeerId) {
        {
            let mut g = heal(self.gossip.lock());
            g.neighbor_up(peer.0);
        }
        let peers = self.peer_ids_sorted();
        let now = mono_ns();
        let actions = {
            let mut s = heal(self.swarm.lock());
            s.on_peer_up(peer.0, now, &peers)
        };
        for a in actions {
            self.execute_swarm_action(a);
        }
        // One-beacon reply so the other side registers us within 1 RTT
        // even if it never heard our broadcast.
        self.send_beacon_to(peer);
    }

    fn peer_ids_sorted(&self) -> Vec<u64> {
        let mut ids: Vec<u64> = heal(self.peers.read()).keys().map(|p| p.0).collect();
        ids.sort_unstable();
        ids
    }

    // ── send / broadcast surface (synchronous, non-blocking) ──────────

    /// PlumTree broadcast: assigns the next dense sequence number, fans the
    /// frame out through the eager tree, and lazy-announces to the rest.
    /// Returns the assigned sequence number.
    pub fn broadcast(&self, msg_type: u8, payload: &[u8]) -> Result<u32, MeshError> {
        if payload.len() > MAX_PAYLOAD_LEN {
            return Err(MeshError::PayloadTooLarge(payload.len()));
        }
        let seq = self.broadcast_seq.fetch_add(1, Ordering::Relaxed) + 1;
        let mut h = self.build_header(msg_type, seq);
        let raw = encode_packet(&mut h, payload);
        let now = mono_ns();
        let actions = {
            let mut g = heal(self.gossip.lock());
            g.broadcast(&h, &raw, now)
        };
        for a in actions {
            self.execute_gossip_action(a);
        }
        Ok(seq)
    }

    /// Direct unicast to one peer (sequence = 0, deviation D3).
    pub fn send_to_peer(
        &self,
        peer: PeerId,
        msg_type: u8,
        payload: &[u8],
    ) -> Result<(), MeshError> {
        if payload.len() > MAX_PAYLOAD_LEN {
            return Err(MeshError::PayloadTooLarge(payload.len()));
        }
        {
            let map = heal(self.peers.read());
            if !map.contains_key(&peer) {
                return Err(MeshError::PeerUnknown);
            }
        }
        let mut h = self.build_header(msg_type, 0);
        let raw = encode_packet(&mut h, payload);
        self.send_raw(peer, &raw);
        Ok(())
    }

    // ── app subscriptions ─────────────────────────────────────────────

    /// Subscribes to gossip-delivered frames of one message type (e.g.
    /// `MSG_CRDT_OP`). Dead or full receivers never block the router.
    pub fn subscribe(&self, msg_type: u8, capacity: usize) -> mpsc::Receiver<InboundPacket> {
        let (tx, rx) = mpsc::channel(capacity.max(1));
        heal(self.subs.lock()).entry(msg_type).or_default().push(tx);
        rx
    }

    /// Subscribes to chunk-swarm lifecycle events.
    pub fn subscribe_swarm_events(&self, capacity: usize) -> mpsc::Receiver<SwarmEvent> {
        let (tx, rx) = mpsc::channel(capacity.max(1));
        heal(self.swarm_subs.lock()).push(tx);
        rx
    }

    // ── audio swarm surface ───────────────────────────────────────────

    /// Turns this node into the seeder for `track_id` (the "fastest device
    /// fetched from the CDN" role): chunks the data, builds the Blake3
    /// manifest, gossips it, and announces availability to every peer.
    pub fn swarm_seed_track(&self, track_id: u64, data: &[u8]) {
        let peers = self.peer_ids_sorted();
        let now = mono_ns();
        let actions = {
            let mut s = heal(self.swarm.lock());
            s.seed_track(track_id, data, now, &peers)
        };
        for a in actions {
            self.execute_swarm_action(a);
        }
    }

    /// Returns the verified track bytes once this node has every chunk.
    pub fn swarm_take_track(&self, track_id: u64) -> Option<Vec<u8>> {
        heal(self.swarm.lock()).take_track(track_id)
    }

    // ── lifecycle ─────────────────────────────────────────────────────

    /// Signals all loops to exit. Idempotent.
    pub fn shutdown(&self) {
        let _ = self.stop_tx.send(true);
    }

    // ───────────────────────── internal machinery ─────────────────────

    fn build_header(&self, msg_type: u8, sequence: u32) -> P2pPacketHeader {
        P2pPacketHeader {
            magic: MAGIC,
            version: PROTO_VERSION,
            msg_type,
            sender_id: self.id.0.to_le_bytes(),
            session_id: self.session.0,
            sequence,
            timestamp_mono_ns: mono_ns(),
            payload_len: 0,
            checksum_fnv1a: 0,
        }
    }

    fn build_beacon_packet(&self) -> Vec<u8> {
        let caps = (self.cfg.enable_lan.then_some(CAP_LAN).unwrap_or(0))
            | (self.cfg.enable_webrtc.then_some(CAP_WEBRTC).unwrap_or(0));
        let port = self.local_addr().port();
        let mut payload = Vec::with_capacity(12);
        payload.extend_from_slice(&self.id.0.to_le_bytes());
        payload.extend_from_slice(&caps.to_le_bytes());
        payload.extend_from_slice(&port.to_le_bytes());
        let mut h = self.build_header(MSG_BEACON, 0);
        encode_packet(&mut h, &payload)
    }

    fn send_beacon_to(&self, peer: PeerId) {
        let raw = self.build_beacon_packet();
        self.send_raw(peer, &raw);
    }

    /// The outbound stage: resolves the route, applies the link condition,
    /// and enqueues onto the sender task (remote domain goes through the
    /// attached DataChannel transport instead).
    fn send_raw(&self, peer: PeerId, bytes: &[u8]) {
        let (addr, domain, condition) = {
            let map = heal(self.peers.read());
            match map.get(&peer) {
                Some(e) => (e.addr, e.domain, e.condition),
                None => {
                    self.stats.tx_peer_unknown.fetch_add(1, Ordering::Relaxed);
                    return;
                }
            }
        };
        match domain {
            RouteDomain::Local => {
                if !self.cfg.enable_lan {
                    self.stats.tx_no_transport.fetch_add(1, Ordering::Relaxed);
                    return;
                }
                let (dropped, delay) = match condition {
                    Some(c) => {
                        let mut rng = rand::thread_rng();
                        c.sample(&mut rng)
                    }
                    None => (false, Duration::ZERO),
                };
                if dropped {
                    self.stats.tx_loss_dropped.fetch_add(1, Ordering::Relaxed);
                    return;
                }
                let _ = self.outbound_tx.send(Outbound {
                    to: addr,
                    bytes: bytes.to_vec(),
                    delay,
                });
                self.stats.tx_datagrams.fetch_add(1, Ordering::Relaxed);
                self.stats
                    .tx_bytes
                    .fetch_add(bytes.len() as u64, Ordering::Relaxed);
            }
            RouteDomain::Remote => {
                let guard = heal(self.remote_transport.read());
                match guard.as_ref() {
                    Some(t) => match t.send_to_peer(peer, bytes) {
                        Ok(()) => {
                            self.stats.tx_datagrams.fetch_add(1, Ordering::Relaxed);
                            self.stats
                                .tx_bytes
                                .fetch_add(bytes.len() as u64, Ordering::Relaxed);
                        }
                        Err(_) => {
                            self.stats.tx_no_transport.fetch_add(1, Ordering::Relaxed);
                        }
                    },
                    None => {
                        self.stats.tx_no_transport.fetch_add(1, Ordering::Relaxed);
                    }
                }
            }
        }
    }

    fn execute_gossip_action(&self, a: Action) {
        match a {
            Action::ForwardRaw { to, bytes } => self.send_raw(PeerId(to), &bytes),
            Action::Control {
                to,
                msg_type,
                payload,
            } => {
                let mut h = self.build_header(msg_type, 0);
                let raw = encode_packet(&mut h, &payload);
                self.send_raw(PeerId(to), &raw);
            }
        }
    }

    fn execute_swarm_action(&self, a: SwarmAction) {
        match a {
            SwarmAction::Unicast {
                to,
                msg_type,
                payload,
            } => {
                let mut h = self.build_header(msg_type, 0);
                let raw = encode_packet(&mut h, &payload);
                self.send_raw(PeerId(to), &raw);
            }
            SwarmAction::Broadcast { msg_type, payload } => {
                let _ = self.broadcast(msg_type, &payload);
            }
        }
    }

    /// Datagram ingress: validate → register peer → route by message type.
    fn handle_datagram(&self, buf: &[u8], from: SocketAddr) {
        self.stats.rx_datagrams.fetch_add(1, Ordering::Relaxed);
        self.stats
            .rx_bytes
            .fetch_add(buf.len() as u64, Ordering::Relaxed);

        let dp = match decode_packet(buf) {
            Ok(dp) => dp,
            Err(MeshError::BadMagic | MeshError::BadVersion) => {
                self.stats
                    .rx_version_or_magic_reject
                    .fetch_add(1, Ordering::Relaxed);
                return;
            }
            Err(MeshError::ChecksumMismatch) => {
                self.stats
                    .rx_checksum_reject
                    .fetch_add(1, Ordering::Relaxed);
                return;
            }
            Err(_) => {
                self.stats.rx_length_reject.fetch_add(1, Ordering::Relaxed);
                return;
            }
        };

        if dp.header.session_id != self.session.0 {
            self.stats
                .rx_session_mismatch
                .fetch_add(1, Ordering::Relaxed);
            return;
        }
        let sender = PeerId(u64::from_le_bytes(dp.header.sender_id));
        if sender == self.id {
            // Multicast loop / broadcast echo of our own frame.
            self.stats.rx_self_echo.fetch_add(1, Ordering::Relaxed);
            return;
        }
        let now = mono_ns();

        match dp.header.msg_type {
            MSG_BEACON => {
                let caps = parse_beacon_caps(&dp.payload);
                if self.register_or_refresh(sender, from, caps, now) {
                    self.on_peer_joined(sender);
                }
            }
            MSG_HEARTBEAT => {
                self.register_or_refresh(sender, from, None, now);
                self.handle_heartbeat(&dp.payload, sender);
            }
            MSG_GOSSIP_IHAVE | MSG_GOSSIP_GRAFT => {
                // Control frames are always sent directly by the peer whose
                // id is in the header.
                self.register_or_refresh(sender, from, None, now);
                let actions = {
                    let mut g = heal(self.gossip.lock());
                    g.on_control(sender.0, dp.header.msg_type, &dp.payload, now)
                };
                for a in actions {
                    self.execute_gossip_action(a);
                }
            }
            MSG_CHUNK_HAVE | MSG_CHUNK_REQUEST | MSG_CHUNK_DATA | MSG_TRACK_MANIFEST_REQUEST => {
                self.register_or_refresh(sender, from, None, now);
                let peers = self.peer_ids_sorted();
                let actions = {
                    let mut s = heal(self.swarm.lock());
                    match dp.header.msg_type {
                        MSG_CHUNK_HAVE => s.on_have(&dp.payload, sender.0, now),
                        MSG_CHUNK_REQUEST => s.on_chunk_request(&dp.payload, sender.0, now),
                        MSG_CHUNK_DATA => s.on_chunk_data(&dp.payload, sender.0, now, &peers),
                        _ => s.on_manifest_request(&dp.payload, sender.0, now),
                    }
                };
                for a in actions {
                    self.execute_swarm_action(a);
                }
            }
            // Gossip-routed data (PTP_SYNC, CRDT_OP, TRACK_MANIFEST, and
            // any future type — unknown types still ride the tree). These
            // frames may be RELAYED: the header carries the origin's id,
            // but the epidemic tree is managed over the immediate link —
            // resolved here from the datagram's source address. Frames from
            // unregistered sources are dropped: membership is established
            // by the beacon handshake, never by relayed data.
            _ => {
                let Some(link) = self.link_peer_of_addr(from) else {
                    self.stats.rx_unknown_link.fetch_add(1, Ordering::Relaxed);
                    return;
                };
                {
                    let map = heal(self.peers.read());
                    if let Some(e) = map.get(&link) {
                        e.last_seen_ns.store(now, Ordering::Relaxed);
                    }
                }
                let outcomes: Outcomes = {
                    let mut g = heal(self.gossip.lock());
                    g.on_data(link.0, &dp.header, &dp.raw, now)
                };
                if outcomes.delivered_new {
                    if dp.header.msg_type == MSG_TRACK_MANIFEST {
                        let actions = {
                            let mut s = heal(self.swarm.lock());
                            s.on_manifest(&dp.payload, sender.0, now)
                        };
                        for a in actions {
                            self.execute_swarm_action(a);
                        }
                    } else {
                        self.dispatch_to_apps(&dp, sender, from);
                    }
                }
                for a in outcomes.actions {
                    self.execute_gossip_action(a);
                }
            }
        }
    }

    fn handle_heartbeat(&self, payload: &[u8], from_peer: PeerId) {
        if payload.len() < 9 {
            return;
        }
        let mode = payload[0];
        let t1 = u64::from_le_bytes(payload[1..9].try_into().unwrap());
        match mode {
            0 => {
                // Echo the probe back so the originator can compute RTT.
                let mut reply = Vec::with_capacity(17);
                reply.push(1u8);
                reply.extend_from_slice(&t1.to_le_bytes());
                reply.extend_from_slice(&(mono_ns() as u64).to_le_bytes());
                let mut h = self.build_header(MSG_HEARTBEAT, 0);
                let raw = encode_packet(&mut h, &reply);
                self.send_raw(from_peer, &raw);
            }
            1 => {
                let rtt_ns = (mono_ns() as i64).saturating_sub(t1 as i64).max(0);
                let map = heal(self.peers.read());
                if let Some(e) = map.get(&from_peer) {
                    e.rtt_ns.store(rtt_ns, Ordering::Relaxed);
                }
            }
            _ => {}
        }
    }

    fn dispatch_to_apps(&self, dp: &DecodedPacket, sender: PeerId, from: SocketAddr) {
        let mut subs = heal(self.subs.lock());
        if let Some(list) = subs.get_mut(&dp.header.msg_type) {
            let pkt = InboundPacket {
                header: dp.header,
                payload: dp.payload.clone(),
                from_peer: sender,
                from_addr: from,
            };
            list.retain(|s| match s.try_send(pkt.clone()) {
                Ok(()) => true,
                Err(mpsc::error::TrySendError::Closed(_)) => false,
                Err(mpsc::error::TrySendError::Full(_)) => {
                    self.stats
                        .app_backpressure_drop
                        .fetch_add(1, Ordering::Relaxed);
                    true // slow consumer: drop frame, keep subscription
                }
            });
        }
    }

    fn emit_swarm_event(&self, ev: SwarmEvent) {
        let mut subs = heal(self.swarm_subs.lock());
        subs.retain(|s| match s.try_send(ev.clone()) {
            Ok(()) => true,
            Err(mpsc::error::TrySendError::Closed(_)) => false,
            Err(mpsc::error::TrySendError::Full(_)) => true,
        });
    }

    /// One housekeeping pass: gossip lazy flush + graft timers, swarm
    /// scheduler sweep, beacon / heartbeat / liveness cadences.
    fn housekeeping_once(&self, now: i64) {
        // PlumTree lazy IHAVE flush + due graft timers.
        let actions = {
            let mut g = heal(self.gossip.lock());
            g.on_tick(now)
        };
        for a in actions {
            self.execute_gossip_action(a);
        }

        // Swarm scheduler (timeouts + pipeline fill).
        let peers = self.peer_ids_sorted();
        let actions = {
            let mut s = heal(self.swarm.lock());
            s.on_tick(now, &peers)
        };
        for a in actions {
            self.execute_swarm_action(a);
        }

        let beacon_ns = self.cfg.beacon_interval.as_nanos() as i64;
        let heartbeat_ns = self.cfg.heartbeat_interval.as_nanos() as i64;
        let sweep_ns = (self.cfg.peer_timeout.as_nanos() as i64).min(1_000_000_000);

        if now.saturating_sub(self.last_beacon_ns.load(Ordering::Relaxed)) >= beacon_ns {
            self.last_beacon_ns.store(now, Ordering::Relaxed);
            self.send_beacons();
        }
        if now.saturating_sub(self.last_heartbeat_ns.load(Ordering::Relaxed)) >= heartbeat_ns {
            self.last_heartbeat_ns.store(now, Ordering::Relaxed);
            self.send_heartbeats(now);
        }
        if now.saturating_sub(self.last_sweep_ns.load(Ordering::Relaxed)) >= sweep_ns {
            self.last_sweep_ns.store(now, Ordering::Relaxed);
            self.sweep_expired_peers(now);
        }
    }

    fn send_beacons(&self) {
        let raw = self.build_beacon_packet();
        if let Some(bcast) = self.broadcast_target {
            let _ = self.outbound_tx.send(Outbound {
                to: bcast,
                bytes: raw.clone(),
                delay: Duration::ZERO,
            });
        }
        for peer in self.peer_ids_sorted() {
            self.send_beacon_to(PeerId(peer));
        }
    }

    fn send_heartbeats(&self, now: i64) {
        let mut payload = Vec::with_capacity(9);
        payload.push(0u8);
        payload.extend_from_slice(&(now as u64).to_le_bytes());
        for peer in self.peer_ids_sorted() {
            let mut h = self.build_header(MSG_HEARTBEAT, 0);
            let raw = encode_packet(&mut h, &payload);
            self.send_raw(PeerId(peer), &raw);
        }
    }

    fn sweep_expired_peers(&self, now: i64) {
        let timeout_ns = self.cfg.peer_timeout.as_nanos() as i64;
        let expired: Vec<PeerId> = {
            let mut map = heal(self.peers.write());
            let expired: Vec<PeerId> = map
                .iter()
                .filter(|(_, e)| {
                    now.saturating_sub(e.last_seen_ns.load(Ordering::Relaxed)) > timeout_ns
                })
                .map(|(id, _)| *id)
                .collect();
            for id in &expired {
                map.remove(id);
            }
            expired
        };
        if expired.is_empty() {
            return;
        }
        self.stats
            .peers_expired
            .fetch_add(expired.len() as u64, Ordering::Relaxed);
        let mut g = heal(self.gossip.lock());
        for id in &expired {
            g.neighbor_down(id.0);
        }
        let mut s = heal(self.swarm.lock());
        for id in &expired {
            s.on_peer_down(id.0);
        }
    }
}

fn parse_beacon_caps(payload: &[u8]) -> Option<u16> {
    if payload.len() < 12 {
        return None;
    }
    Some(u16::from_le_bytes(payload[8..10].try_into().unwrap()))
}

// ─────────────────────────────────────────────────────── background loops

async fn recv_loop(
    sock: Arc<UdpSocket>,
    node: std::sync::Weak<MeshNode>,
    mut stop_rx: watch::Receiver<bool>,
) {
    let mut buf = vec![0u8; MAX_DATAGRAM];
    loop {
        tokio::select! {
            _ = stop_rx.changed() => break,
            r = sock.recv_from(&mut buf) => match r {
                Ok((n, from)) => {
                    match node.upgrade() {
                        Some(node) => node.handle_datagram(&buf[..n], from),
                        None => break,
                    }
                }
                Err(e) => {
                    // ICMP port-unreachable surfaces as ConnectionReset on
                    // Linux UDP sockets — tolerate it and keep serving.
                    if e.kind() != std::io::ErrorKind::ConnectionReset {
                        tokio::time::sleep(Duration::from_millis(1)).await;
                    }
                }
            }
        }
    }
}

/// Outbound stage: zero-delay frames go straight to the socket; conditioned
/// frames sit in a deadline heap flushed on a 1 ms cadence (sub-tick of the
/// 5–50 ms simulated latency window).
async fn sender_loop(
    sock: Arc<UdpSocket>,
    mut outbound_rx: mpsc::UnboundedReceiver<Outbound>,
    mut stop_rx: watch::Receiver<bool>,
) {
    // (deadline, destination, bytes) — Vec<u8> ordering is deterministic.
    let mut heap: BinaryHeap<std::cmp::Reverse<(Instant, SocketAddr, Vec<u8>)>> = BinaryHeap::new();
    loop {
        tokio::select! {
            _ = stop_rx.changed() => break,
            _ = tokio::time::sleep(Duration::from_millis(1)), if !heap.is_empty() => {
                let now = Instant::now();
                while heap
                    .peek()
                    .map(|std::cmp::Reverse((at, _, _))| *at <= now)
                    .unwrap_or(false)
                {
                    let std::cmp::Reverse((_, to, bytes)) = heap.pop().unwrap();
                    let _ = sock.send_to(&bytes, to).await;
                }
            }
            m = outbound_rx.recv() => match m {
                None => break,
                Some(o) => {
                    if o.delay.is_zero() {
                        let _ = sock.send_to(&o.bytes, o.to).await;
                    } else {
                        heap.push(std::cmp::Reverse((Instant::now() + o.delay, o.to, o.bytes)));
                    }
                }
            }
        }
    }
}

async fn housekeeping_loop(node: std::sync::Weak<MeshNode>, mut stop_rx: watch::Receiver<bool>) {
    let mut interval = tokio::time::interval(Duration::from_millis(5));
    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    loop {
        tokio::select! {
            _ = stop_rx.changed() => break,
            _ = interval.tick() => {
                let Some(node) = node.upgrade() else { break };
                node.housekeeping_once(mono_ns());
            }
        }
    }
}

// ───────────────────────────────────────────────────── socket bootstrap

fn bind_udp(cfg: &MeshConfig) -> io::Result<UdpSocket> {
    let parse = |s: &str| -> io::Result<SocketAddr> {
        s.parse::<SocketAddr>()
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))
    };
    let target = parse(&cfg.listen_addr)?;
    match build_socket(&target, cfg) {
        Ok(s) => Ok(s),
        Err(e) => {
            if cfg.allow_port_fallback && target.port() != 0 {
                let ephemeral = SocketAddr::new(target.ip(), 0);
                build_socket(&ephemeral, cfg)
            } else {
                Err(e)
            }
        }
    }
}

fn build_socket(addr: &SocketAddr, cfg: &MeshConfig) -> io::Result<UdpSocket> {
    let sock = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))?;
    // Two Streamify processes (or an app restart racing the old process)
    // must be able to rebind the mesh port.
    #[cfg(unix)]
    sock.set_reuse_address(true)?;
    #[cfg(unix)]
    sock.set_reuse_port(true)?;
    sock.set_broadcast(true)?;
    if let Some(group) = cfg.multicast_group {
        // IP_MULTICAST_LOOP stays on: every mesh member is both sender and
        // receiver on the group.
        sock.set_multicast_loop_v4(true)?;
        let _ = sock.join_multicast_v4(&group, &Ipv4Addr::UNSPECIFIED);
    }
    // Large receive buffer: a 1000-op gossip flood bursts ~4000 datagrams
    // per node; the default ~200 KB rcvbuf would overflow and add REAL
    // loss on top of the simulated one.
    let _ = sock.set_recv_buffer_size(cfg.recv_buffer_bytes);
    sock.set_nonblocking(true)?;
    sock.bind(&socket2::SockAddr::from(*addr))?;
    let std_sock: std::net::UdpSocket = sock.into();
    UdpSocket::from_std(std_sock)
}

// ───────────────────────────────────────────────────────────── unit tests

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_header(msg_type: u8, seq: u32) -> P2pPacketHeader {
        P2pPacketHeader {
            magic: MAGIC,
            version: PROTO_VERSION,
            msg_type,
            sender_id: PeerId::from_device_str("pixel-9-pro").0.to_le_bytes(),
            session_id: SessionId::from_session_str("room-uuid-42").0,
            sequence: seq,
            timestamp_mono_ns: 1_234_567_891_011_i64,
            payload_len: 0,
            checksum_fnv1a: 0,
        }
    }

    #[test]
    fn frozen_header_size_is_pinned() {
        // Deviation D1: the packed frozen struct serializes to 46 bytes.
        assert_eq!(std::mem::size_of::<P2pPacketHeader>(), 46);
        assert_eq!(HEADER_LEN, 46);
        assert_eq!(CHECKSUM_COVERED_LEN, 42);
    }

    #[test]
    fn header_roundtrips_all_fields() {
        let h = sample_header(MSG_CRDT_OP, 0xC0FFEE);
        let bytes = h.to_bytes();
        let back = P2pPacketHeader::from_bytes(&bytes).unwrap();
        assert_eq!(back, h);
        assert_eq!(bytes.len(), HEADER_LEN);
    }

    #[test]
    fn fnv1a_known_vectors() {
        // Reference vectors from the FNV-1a 32-bit test suite.
        assert_eq!(fnv1a32(b""), 0x811c_9dc5);
        assert_eq!(fnv1a32(b"a"), 0xe40c_292c);
        assert_eq!(fnv1a32(b"foobar"), 0xbf9c_f968);
    }

    #[test]
    fn packet_roundtrip_with_payload() {
        let mut h = sample_header(MSG_TRACK_MANIFEST, 7);
        let payload: Vec<u8> = (0..300u32).map(|i| (i % 251) as u8).collect();
        let raw = encode_packet(&mut h, &payload);
        let dp = decode_packet(&raw).unwrap();
        // Packed-struct discipline: copy fields to locals before comparing
        // (references into packed fields are unaligned — never taken).
        let seq = dp.header.sequence;
        let raw_len = dp.raw.len();
        assert_eq!(seq, 7);
        assert_eq!(dp.payload, payload);
        assert_eq!(raw_len, HEADER_LEN + 300);
    }

    #[test]
    fn decode_rejects_corruption_and_truncation() {
        let mut h = sample_header(MSG_CRDT_OP, 1);
        let payload = b"jam-op-wire".to_vec();
        let mut raw = encode_packet(&mut h, &payload);

        // Truncated datagram.
        assert_eq!(
            decode_packet(&raw[..raw.len() - 1]).unwrap_err(),
            MeshError::LengthMismatch {
                expected: raw.len(),
                actual: raw.len() - 1
            }
        );

        // Any flipped bit inside the covered header span must trip the
        // checksum (deviation D2 makes payload_len protected).
        for i in [0usize, 3, 20, 30, 39, 41] {
            let mut corrupted = raw.clone();
            corrupted[i] ^= 0x01;
            assert!(
                matches!(
                    decode_packet(&corrupted),
                    Err(MeshError::ChecksumMismatch | MeshError::BadMagic | MeshError::BadVersion)
                ),
                "corruption at offset {i} must be rejected"
            );
        }

        // Flipped checksum field itself.
        let mut corrupted = raw.clone();
        corrupted[44] ^= 0xFF;
        assert_eq!(
            decode_packet(&corrupted).unwrap_err(),
            MeshError::ChecksumMismatch
        );

        // Truncated below the header.
        assert_eq!(decode_packet(&raw[..10]).unwrap_err(), MeshError::Truncated);

        // Payload bytes are NOT checksum-covered at the mesh layer (chunk
        // payloads carry their own Blake3 hashes; CRDT ops carry FNV).
        let _ = &mut raw;
    }

    #[test]
    fn peer_and_session_ids_are_stable_and_hex_roundtrip() {
        let a = PeerId::from_device_str("device-A");
        let b = PeerId::from_device_str("device-B");
        assert_ne!(a, b);
        assert_eq!(a, PeerId::from_device_str("device-A"));
        assert_eq!(PeerId::from_hex(&a.to_hex()), Some(a));
        assert_eq!(PeerId::from_hex("zz"), None);
        assert_eq!(PeerId::from_hex("00112233445566778899"), None);

        let s1 = SessionId::from_session_str("session-X");
        assert_eq!(s1, SessionId::from_session_str("session-X"));
        assert_ne!(s1.0, SessionId::from_session_str("session-Y").0);
    }

    #[test]
    fn link_condition_samples_within_window() {
        let mut rng = rand::thread_rng();
        let c = LinkCondition {
            loss: 0.0,
            delay: (Duration::from_millis(5), Duration::from_millis(50)),
        };
        for _ in 0..200 {
            let (dropped, d) = c.sample(&mut rng);
            assert!(!dropped);
            assert!(d >= Duration::from_millis(5) && d <= Duration::from_millis(50));
        }
        let always = LinkCondition {
            loss: 1.0,
            delay: (Duration::ZERO, Duration::ZERO),
        };
        for _ in 0..50 {
            assert!(always.sample(&mut rng).0);
        }
    }

    #[test]
    fn gossip_routed_type_classification() {
        assert!(P2pPacketHeader::is_gossip_routed(MSG_CRDT_OP));
        assert!(P2pPacketHeader::is_gossip_routed(MSG_PTP_SYNC));
        assert!(P2pPacketHeader::is_gossip_routed(MSG_TRACK_MANIFEST));
        assert!(!P2pPacketHeader::is_gossip_routed(MSG_CHUNK_DATA));
        assert!(!P2pPacketHeader::is_gossip_routed(MSG_GOSSIP_IHAVE));
    }
}
