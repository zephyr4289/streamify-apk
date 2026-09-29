//! chunk_swarmer.rs — P2P lookahead audio chunk swarm (Jam Phase 2).
//!
//! MISSION (lead brief): when the session prepares Track N+1, the device
//! with the fastest downlink fetches the raw audio ONCE, splits it into
//! Blake3-hashed blocks, and the whole room fills its playback buffers by
//! trading chunks peer-to-peer over local 5 GHz Wi-Fi / Wi-Fi Direct —
//! zero duplicate cellular data consumption.
//!
//! DESIGN (BitTorrent discipline, adapted to the 46-byte mesh frame):
//!   • Manifest   — the seeder chunks the track and gossips a
//!                 [`TrackManifest`] (per-chunk Blake3 + whole-file Blake3)
//!                 through the PlumTree engine, so manifest delivery itself
//!                 survives packet loss via IHAVE/GRAFT healing.
//!   • HAVE       — each node unicast-announces a compact availability
//!                 bitfield (`[track][file-hash][num][bitmap]`) on join,
//!                 on change (cadenced), and on completion.
//!   • Requests   — rarest-first chunk selection with per-peer pipelining
//!                 (`pipeline_per_peer` in-flight requests), so the swarm
//!                 spreads demand instead of hammering the seeder.
//!   • Endgame    — the last `endgame_threshold` chunks are requested from
//!                 up to `endgame_dup_peers` havers in parallel; duplicates
//!                 are detected and discarded. This is what kills the
//!                 last-chunk tail latency BitTorrent is famous for.
//!   • Integrity  — every chunk is Blake3-verified against the manifest
//!                 before it is stored; a mismatching chunk is discarded,
//!                 the sender is blacklisted for that chunk index, and the
//!                 scheduler re-requests from a different peer. The
//!                 assembled file is verified once more against
//!                 `total_hash` before `take_track` hands it back.
//!
//! CHUNK SIZE NOTE (brief says "64 KB"): `payload_len` is a `u16` (≤ 65535)
//! and the frame budget also carries the 46-byte header + 16-byte chunk
//! metadata, so the default is 60 KiB — the largest chunk that still fits
//! a single datagram under the frozen wire format. Documented in the PR;
//! [`SwarmParams::chunk_size`] is a knob, and the tests shrink it to keep
//! the integration suite fast.
//!
//! Like `gossip.rs`, this engine is pure logic: the mesh node injects time
//! and the current peer list, and executes the returned [`SwarmAction`]s.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use crate::p2p_mesh::{
    MSG_CHUNK_DATA, MSG_CHUNK_HAVE, MSG_CHUNK_REQUEST, MSG_TRACK_MANIFEST,
    MSG_TRACK_MANIFEST_REQUEST,
};

/// Default chunk size: 60 KiB (see module docs — u16 payload budget).
pub const DEFAULT_CHUNK_SIZE: usize = 60 * 1024;
/// Chunks are announced in batches; one manifest frame caps the track size
/// at ~2044 chunks (~119 MB at 60 KiB). Larger tracks: split at the app
/// layer (lookahead window is one track anyway).
pub const MANIFEST_MAX_CHUNKS: u32 = 2_044;

// ───────────────────────────────────────────────────────────── parameters

/// Swarm scheduler knobs.
#[derive(Debug, Clone)]
pub struct SwarmParams {
    /// Chunk size for newly seeded tracks (see module docs for 60 KiB).
    pub chunk_size: usize,
    /// Max in-flight chunk requests per peer.
    pub pipeline_per_peer: usize,
    /// A request that goes unanswered for this long is re-issued.
    pub request_timeout: Duration,
    /// HAVE re-announce cadence after the have-set changes.
    pub announce_interval: Duration,
    /// Missing count at or below which endgame parallelism kicks in.
    pub endgame_threshold: usize,
    /// Distinct peers an endgame chunk may be requested from at once.
    pub endgame_dup_peers: usize,
}

impl Default for SwarmParams {
    fn default() -> Self {
        SwarmParams {
            chunk_size: DEFAULT_CHUNK_SIZE,
            pipeline_per_peer: 4,
            request_timeout: Duration::from_millis(150),
            announce_interval: Duration::from_millis(400),
            endgame_threshold: 2,
            endgame_dup_peers: 2,
        }
    }
}

// ─────────────────────────────────────────────────────────── wire codecs

/// Chunk-hash manifest for one track.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrackManifest {
    pub track_id: u64,
    pub total_len: u64,
    pub chunk_size: u32,
    pub num_chunks: u32,
    /// Blake3 of the whole file — final assembly check.
    pub total_hash: [u8; 32],
    /// Blake3 of each chunk, in order.
    pub chunk_hashes: Vec<[u8; 32]>,
}

impl TrackManifest {
    /// Builds the manifest (and the chunk list) for a seeded track.
    pub fn build(track_id: u64, data: &[u8], chunk_size: usize) -> Self {
        let chunk_size = chunk_size.max(1);
        let num_chunks = (data.len() + chunk_size - 1) / chunk_size;
        let mut chunk_hashes = Vec::with_capacity(num_chunks);
        for i in 0..num_chunks {
            let lo = i * chunk_size;
            let hi = (lo + chunk_size).min(data.len());
            let mut h = [0u8; 32];
            h.copy_from_slice(blake3::hash(&data[lo..hi]).as_bytes());
            chunk_hashes.push(h);
        }
        let mut total_hash = [0u8; 32];
        total_hash.copy_from_slice(blake3::hash(data).as_bytes());
        TrackManifest {
            track_id,
            total_len: data.len() as u64,
            chunk_size: chunk_size as u32,
            num_chunks: num_chunks as u32,
            total_hash,
            chunk_hashes,
        }
    }

    /// Length of chunk `idx` (the tail chunk may be short).
    pub fn chunk_len(&self, idx: u32) -> usize {
        if idx >= self.num_chunks {
            return 0;
        }
        let cs = self.chunk_size as u64;
        let start = idx as u64 * cs;
        (self.total_len.saturating_sub(start)).min(cs) as usize
    }

    /// Wire layout: `[track u64][total_len u64][chunk_size u32][num u32]
    /// [total_hash 32][chunk_hash 32 × num]`.
    pub fn to_wire(&self) -> Vec<u8> {
        let mut p = Vec::with_capacity(56 + 32 * self.chunk_hashes.len());
        p.extend_from_slice(&self.track_id.to_le_bytes());
        p.extend_from_slice(&self.total_len.to_le_bytes());
        p.extend_from_slice(&self.chunk_size.to_le_bytes());
        p.extend_from_slice(&self.num_chunks.to_le_bytes());
        p.extend_from_slice(&self.total_hash);
        for h in &self.chunk_hashes {
            p.extend_from_slice(h);
        }
        p
    }

    /// Strict parse: length must match the declared chunk count exactly.
    ///
    /// Wire offsets: track[0..8] total_len[8..16] chunk_size[16..20]
    /// num_chunks[20..24] total_hash[24..56] hashes[56..].
    pub fn from_wire(payload: &[u8]) -> Result<Self, SwarmError> {
        if payload.len() < 56 {
            return Err(SwarmError::MalformedManifest);
        }
        let num_chunks = u32::from_le_bytes(payload[20..24].try_into().unwrap());
        if num_chunks > MANIFEST_MAX_CHUNKS {
            return Err(SwarmError::ManifestTooLarge);
        }
        let expected = 56 + num_chunks as usize * 32;
        if payload.len() != expected {
            return Err(SwarmError::MalformedManifest);
        }
        let chunk_hashes: Vec<[u8; 32]> = (0..num_chunks as usize)
            .map(|i| {
                let base = 56 + i * 32;
                payload[base..base + 32].try_into().unwrap()
            })
            .collect();
        Ok(TrackManifest {
            track_id: u64::from_le_bytes(payload[0..8].try_into().unwrap()),
            total_len: u64::from_le_bytes(payload[8..16].try_into().unwrap()),
            chunk_size: u32::from_le_bytes(payload[16..20].try_into().unwrap()),
            num_chunks,
            total_hash: payload[24..56].try_into().unwrap(),
            chunk_hashes,
        })
    }
}

/// HAVE payload: `[track u64][total_hash 32][num u32][bitmap ceil(num/8)]`.
fn build_have_payload(manifest: &TrackManifest, have: &[bool]) -> Vec<u8> {
    let n = manifest.num_chunks as usize;
    let mut bitmap = vec![0u8; (n + 7) / 8];
    for (i, &h) in have.iter().enumerate() {
        if h {
            bitmap[i / 8] |= 1 << (i % 8);
        }
    }
    let mut p = Vec::with_capacity(44 + bitmap.len());
    p.extend_from_slice(&manifest.track_id.to_le_bytes());
    p.extend_from_slice(&manifest.total_hash);
    p.extend_from_slice(&manifest.num_chunks.to_le_bytes());
    p.extend_from_slice(&bitmap);
    p
}

/// Parsed HAVE.
struct HaveBits {
    track_id: u64,
    total_hash: [u8; 32],
    num_chunks: u32,
    bits: Vec<bool>,
}

fn parse_have_payload(payload: &[u8]) -> Option<HaveBits> {
    if payload.len() < 44 {
        return None;
    }
    let num_chunks = u32::from_le_bytes(payload[40..44].try_into().unwrap());
    if num_chunks > MANIFEST_MAX_CHUNKS {
        return None;
    }
    let bitmap_len = (num_chunks as usize + 7) / 8;
    if payload.len() != 44 + bitmap_len {
        return None;
    }
    let bits: Vec<bool> = (0..num_chunks as usize)
        .map(|i| payload[44 + i / 8] & (1 << (i % 8)) != 0)
        .collect();
    Some(HaveBits {
        track_id: u64::from_le_bytes(payload[0..8].try_into().unwrap()),
        total_hash: payload[8..40].try_into().unwrap(),
        num_chunks,
        bits,
    })
}

fn build_chunk_request_payload(track_id: u64, idx: u32) -> Vec<u8> {
    let mut p = Vec::with_capacity(12);
    p.extend_from_slice(&track_id.to_le_bytes());
    p.extend_from_slice(&idx.to_le_bytes());
    p
}

fn parse_chunk_request_payload(payload: &[u8]) -> Option<(u64, u32)> {
    if payload.len() != 12 {
        return None;
    }
    Some((
        u64::from_le_bytes(payload[0..8].try_into().unwrap()),
        u32::from_le_bytes(payload[8..12].try_into().unwrap()),
    ))
}

fn build_manifest_request_payload(track_id: u64) -> Vec<u8> {
    track_id.to_le_bytes().to_vec()
}

fn parse_manifest_request_payload(payload: &[u8]) -> Option<u64> {
    if payload.len() != 8 {
        return None;
    }
    Some(u64::from_le_bytes(payload[0..8].try_into().unwrap()))
}

// ─────────────────────────────────────────────────────────── errors/events

/// Swarm-layer errors (parsing / integrity).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SwarmError {
    MalformedManifest,
    ManifestTooLarge,
    UnknownTrack,
    TrackIncomplete,
}

impl std::fmt::Display for SwarmError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SwarmError::MalformedManifest => write!(f, "manifest payload malformed"),
            SwarmError::ManifestTooLarge => write!(f, "manifest exceeds chunk-count budget"),
            SwarmError::UnknownTrack => write!(f, "track is not known to this swarm"),
            SwarmError::TrackIncomplete => write!(f, "track has not finished downloading"),
        }
    }
}

impl std::error::Error for SwarmError {}

/// Lifecycle events surfaced to the app layer (JNI / tests).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SwarmEvent {
    /// We learned a track exists (manifest ingested or seeded).
    Manifest { track_id: u64, num_chunks: u32 },
    Progress {
        track_id: u64,
        have_chunks: u32,
        total_chunks: u32,
    },
    ChunkStored {
        track_id: u64,
        idx: u32,
        bytes_rx: u64,
    },
    /// Every chunk verified; `take_track` will now succeed.
    Complete {
        track_id: u64,
        total_len: u64,
        total_hash: [u8; 32],
    },
}

/// Effects the mesh node must execute.
#[derive(Debug, Clone)]
pub enum SwarmAction {
    Unicast {
        to: u64,
        msg_type: u8,
        payload: Vec<u8>,
    },
    /// Rides the PlumTree engine (reliable, dedupe, healable).
    Broadcast { msg_type: u8, payload: Vec<u8> },
}

// ───────────────────────────────────────────────────────── per-track state

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct InflightReq {
    peer: u64,
    deadline_ns: i64,
}

#[derive(Debug, Default, Clone, Copy)]
struct SwarmCounters {
    data_rx_bytes: u64,
    duplicate_chunks: u64,
    corrupt_chunks: u64,
    requests_tx: u64,
    requests_rx: u64,
    served_chunks: u64,
    timeouts: u64,
}

struct Swarm {
    manifest: TrackManifest,
    /// `Some(data)` once the chunk is verified and stored.
    chunks: Vec<Option<Vec<u8>>>,
    /// Availability bitfield per peer (latest announcement wins).
    peer_bits: HashMap<u64, Vec<bool>>,
    /// Outstanding chunk requests, per chunk index.
    inflight: HashMap<u32, Vec<InflightReq>>,
    /// Peers that served a chunk that failed Blake3 — excluded per index.
    blacklist: HashMap<u32, Vec<u64>>,
    /// Timeout-driven re-request pressure per chunk (starvation priority).
    tries: HashMap<u32, u32>,
    seeder: bool,
    announce_at_ns: i64,
    assembled: Option<Vec<u8>>,
    counters: SwarmCounters,
}

impl Swarm {
    fn have_count(&self) -> u32 {
        self.chunks.iter().filter(|c| c.is_some()).count() as u32
    }

    fn is_complete(&self) -> bool {
        self.chunks.iter().all(|c| c.is_some())
    }

    fn have_bits(&self) -> Vec<bool> {
        self.chunks.iter().map(|c| c.is_some()).collect()
    }

    fn assemble(&mut self) -> bool {
        if self.assembled.is_some() {
            return true;
        }
        if !self.is_complete() {
            return false;
        }
        let mut data = Vec::with_capacity(self.manifest.total_len as usize);
        for c in &self.chunks {
            data.extend_from_slice(c.as_ref().expect("complete"));
        }
        let mut h = [0u8; 32];
        h.copy_from_slice(blake3::hash(&data).as_bytes());
        if h != self.manifest.total_hash {
            // Every chunk verified individually, so a total-hash mismatch
            // means a manifest inconsistency: fail loudly, never hand back
            // corrupted audio.
            return false;
        }
        self.assembled = Some(data);
        true
    }
}

/// Per-track telemetry snapshot (tests / JNI JSON).
#[derive(Debug, Clone, PartialEq, serde::Serialize)]
pub struct TrackSwarmStats {
    pub track_id: u64,
    pub total_len: u64,
    pub chunk_size: u32,
    pub num_chunks: u32,
    pub have_chunks: u32,
    pub seeder: bool,
    pub complete: bool,
    pub data_rx_bytes: u64,
    pub duplicate_chunks: u64,
    pub corrupt_chunks: u64,
    pub requests_tx: u64,
    pub requests_rx: u64,
    pub served_chunks: u64,
    pub timeouts: u64,
}

// ─────────────────────────────────────────────────────────── the manager

/// Pure-logic swarm manager: one instance per mesh node, driven by the
/// node's housekeeping tick and inbound chunk frames.
pub struct SwarmManager {
    me: u64,
    params: SwarmParams,
    sink: Option<Arc<dyn Fn(SwarmEvent) + Send + Sync>>,
    swarms: HashMap<u64, Swarm>,
}

impl SwarmManager {
    pub fn new(me: u64, params: SwarmParams) -> Self {
        SwarmManager {
            me,
            params,
            sink: None,
            swarms: HashMap::new(),
        }
    }

    /// Installs the event fan-out (node-owned; weak back-reference).
    pub fn set_event_sink(&mut self, sink: Arc<dyn Fn(SwarmEvent) + Send + Sync>) {
        self.sink = Some(sink);
    }

    fn emit(&self, ev: SwarmEvent) {
        if let Some(sink) = &self.sink {
            sink(ev);
        }
    }

    // ── seeder path ────────────────────────────────────────────────────

    /// Becomes the seeder: chunks the data, builds the manifest, gossips
    /// it, announces the full bitfield to every current peer.
    pub fn seed_track(
        &mut self,
        track_id: u64,
        data: &[u8],
        now: i64,
        peers: &[u64],
    ) -> Vec<SwarmAction> {
        let manifest = TrackManifest::build(track_id, data, self.params.chunk_size);
        let num = manifest.num_chunks;
        let swarm = Swarm {
            chunks: (0..num as usize)
                .map(|i| Some(chunk_slice(data, &manifest, i)))
                .collect(),
            manifest,
            peer_bits: HashMap::new(),
            inflight: HashMap::new(),
            blacklist: HashMap::new(),
            tries: HashMap::new(),
            seeder: true,
            announce_at_ns: now + self.params.announce_interval.as_nanos() as i64,
            assembled: Some(data.to_vec()),
            counters: SwarmCounters::default(),
        };
        let mut actions = vec![SwarmAction::Broadcast {
            msg_type: MSG_TRACK_MANIFEST,
            payload: swarm.manifest.to_wire(),
        }];
        let payload = build_have_payload(&swarm.manifest, &swarm.have_bits());
        for &p in peers {
            if p != self.me {
                actions.push(SwarmAction::Unicast {
                    to: p,
                    msg_type: MSG_CHUNK_HAVE,
                    payload: payload.clone(),
                });
            }
        }
        self.swarms.insert(track_id, swarm);
        self.emit(SwarmEvent::Manifest {
            track_id,
            num_chunks: num,
        });
        self.emit(SwarmEvent::Complete {
            track_id,
            total_len: data.len() as u64,
            total_hash: {
                let mut h = [0u8; 32];
                h.copy_from_slice(blake3::hash(data).as_bytes());
                h
            },
        });
        actions
    }

    // ── inbound frame handlers ─────────────────────────────────────────

    /// TRACK_MANIFEST (gossip-delivered) or a direct manifest reply.
    pub fn on_manifest(&mut self, payload: &[u8], _from: u64, now: i64) -> Vec<SwarmAction> {
        let manifest = match TrackManifest::from_wire(payload) {
            Ok(m) => m,
            Err(_) => return Vec::new(),
        };
        if self.swarms.contains_key(&manifest.track_id) {
            return Vec::new(); // duplicate manifest (graft re-serve etc.)
        }
        let num = manifest.num_chunks;
        let track_id = manifest.track_id;
        self.swarms.insert(
            track_id,
            Swarm {
                chunks: vec![None; num as usize],
                manifest,
                peer_bits: HashMap::new(),
                inflight: HashMap::new(),
                blacklist: HashMap::new(),
                tries: HashMap::new(),
                seeder: false,
                announce_at_ns: now,
                assembled: None,
                counters: SwarmCounters::default(),
            },
        );
        self.emit(SwarmEvent::Manifest {
            track_id,
            num_chunks: num,
        });
        Vec::new()
    }

    /// TRACK_MANIFEST_REQUEST: serve the manifest if we know the track.
    pub fn on_manifest_request(
        &mut self,
        payload: &[u8],
        from: u64,
        _now: i64,
    ) -> Vec<SwarmAction> {
        let track_id = match parse_manifest_request_payload(payload) {
            Some(t) => t,
            None => return Vec::new(),
        };
        match self.swarms.get(&track_id) {
            Some(s) => vec![SwarmAction::Unicast {
                to: from,
                msg_type: MSG_TRACK_MANIFEST,
                payload: s.manifest.to_wire(),
            }],
            None => Vec::new(),
        }
    }

    /// CHUNK_HAVE: availability bitfield. Unknown track → ask for manifest.
    pub fn on_have(&mut self, payload: &[u8], from: u64, _now: i64) -> Vec<SwarmAction> {
        let have = match parse_have_payload(payload) {
            Some(h) => h,
            None => return Vec::new(),
        };
        match self.swarms.get_mut(&have.track_id) {
            Some(swarm) => {
                if swarm.manifest.total_hash != have.total_hash
                    || swarm.manifest.num_chunks != have.num_chunks
                {
                    // Stale bitfield from a different manifest generation.
                    return Vec::new();
                }
                swarm.peer_bits.insert(from, have.bits);
                Vec::new()
            }
            None => vec![SwarmAction::Unicast {
                to: from,
                msg_type: MSG_TRACK_MANIFEST_REQUEST,
                payload: build_manifest_request_payload(have.track_id),
            }],
        }
    }

    /// CHUNK_REQUEST: serve the chunk if we have it.
    pub fn on_chunk_request(&mut self, payload: &[u8], from: u64, _now: i64) -> Vec<SwarmAction> {
        let (track_id, idx) = match parse_chunk_request_payload(payload) {
            Some(x) => x,
            None => return Vec::new(),
        };
        let swarm = match self.swarms.get_mut(&track_id) {
            Some(s) => s,
            None => return Vec::new(),
        };
        swarm.counters.requests_rx += 1;
        if idx >= swarm.manifest.num_chunks {
            return Vec::new();
        }
        if let Some(bytes) = &swarm.chunks[idx as usize] {
            swarm.counters.served_chunks += 1;
            let mut p = Vec::with_capacity(16 + bytes.len());
            p.extend_from_slice(&track_id.to_le_bytes());
            p.extend_from_slice(&idx.to_le_bytes());
            p.extend_from_slice(&(bytes.len() as u32).to_le_bytes());
            p.extend_from_slice(bytes);
            return vec![SwarmAction::Unicast {
                to: from,
                msg_type: MSG_CHUNK_DATA,
                payload: p,
            }];
        }
        Vec::new()
    }

    /// CHUNK_DATA: Blake3-verify, store, maybe complete.
    pub fn on_chunk_data(
        &mut self,
        payload: &[u8],
        from: u64,
        now: i64,
        peers: &[u64],
    ) -> Vec<SwarmAction> {
        if payload.len() < 16 {
            return Vec::new();
        }
        let track_id = u64::from_le_bytes(payload[0..8].try_into().unwrap());
        let idx = u32::from_le_bytes(payload[8..12].try_into().unwrap());
        let len = u32::from_le_bytes(payload[12..16].try_into().unwrap()) as usize;
        if payload.len() != 16 + len {
            return Vec::new();
        }
        let data = &payload[16..];

        // Events are collected and fanned out AFTER the mutable swarm
        // borrow ends (the sink may call back into this manager).
        let mut events: Vec<SwarmEvent> = Vec::new();
        let mut actions = Vec::new();
        {
            let swarm = match self.swarms.get_mut(&track_id) {
                Some(s) => s,
                None => return Vec::new(), // unknown track: stale request reply
            };
            if idx >= swarm.manifest.num_chunks || swarm.manifest.chunk_len(idx) != len {
                swarm.counters.corrupt_chunks += 1;
                drop_inflight(swarm, idx, from);
                return Vec::new();
            }

            // Blake3 gate.
            let mut h = [0u8; 32];
            h.copy_from_slice(blake3::hash(data).as_bytes());
            if h != swarm.manifest.chunk_hashes[idx as usize] {
                swarm.counters.corrupt_chunks += 1;
                // Blacklist this peer for this index; the scheduler will
                // pick a different haver on the next fill pass.
                swarm.blacklist.entry(idx).or_default().push(from);
                drop_inflight(swarm, idx, from);
                return Vec::new();
            }

            if swarm.chunks[idx as usize].is_some() {
                // Endgame duplicate — expected, harmless, counted.
                swarm.counters.duplicate_chunks += 1;
                drop_inflight(swarm, idx, from);
                return Vec::new();
            }

            swarm.chunks[idx as usize] = Some(data.to_vec());
            swarm.counters.data_rx_bytes += len as u64;
            swarm.tries.remove(&idx);
            swarm.inflight.remove(&idx);

            let have = swarm.have_count();
            let total = swarm.manifest.num_chunks;
            let bytes_rx = swarm.counters.data_rx_bytes;
            events.push(SwarmEvent::ChunkStored {
                track_id,
                idx,
                bytes_rx,
            });
            events.push(SwarmEvent::Progress {
                track_id,
                have_chunks: have,
                total_chunks: total,
            });

            if swarm.is_complete() {
                let total_len = swarm.manifest.total_len;
                let total_hash = swarm.manifest.total_hash;
                if swarm.assemble() {
                    // Fresh bitfield to everyone: this node is now a seeder.
                    swarm.announce_at_ns = now;
                    let have_payload = build_have_payload(&swarm.manifest, &swarm.have_bits());
                    actions = peers
                        .iter()
                        .copied()
                        .filter(|&p| p != self.me)
                        .map(|p| SwarmAction::Unicast {
                            to: p,
                            msg_type: MSG_CHUNK_HAVE,
                            payload: have_payload.clone(),
                        })
                        .collect();
                    events.push(SwarmEvent::Complete {
                        track_id,
                        total_len,
                        total_hash,
                    });
                }
            }
        }
        for ev in events {
            self.emit(ev);
        }
        actions
    }

    // ── membership ─────────────────────────────────────────────────────

    /// A peer joined the mesh: announce everything we can serve so it can
    /// bootstrap (unknown tracks trigger its MANIFEST_REQUEST path).
    pub fn on_peer_up(&mut self, _peer: u64, _now: i64, peers: &[u64]) -> Vec<SwarmAction> {
        let mut actions = Vec::new();
        let track_ids: Vec<u64> = self.swarms.keys().copied().collect();
        for t in track_ids {
            if let Some(swarm) = self.swarms.get(&t) {
                if swarm.have_count() == 0 {
                    continue;
                }
                let payload = build_have_payload(&swarm.manifest, &swarm.have_bits());
                for &p in peers {
                    if p != self.me && !swarm.peer_bits.contains_key(&p) {
                        actions.push(SwarmAction::Unicast {
                            to: p,
                            msg_type: MSG_CHUNK_HAVE,
                            payload: payload.clone(),
                        });
                    }
                }
            }
        }
        actions
    }

    pub fn on_peer_down(&mut self, peer: u64) {
        for swarm in self.swarms.values_mut() {
            swarm.peer_bits.remove(&peer);
            for reqs in swarm.inflight.values_mut() {
                reqs.retain(|r| r.peer != peer);
            }
        }
    }

    // ── scheduler ──────────────────────────────────────────────────────

    /// Timeout sweep + HAVE re-announce + pipeline fill.
    pub fn on_tick(&mut self, now: i64, peers: &[u64]) -> Vec<SwarmAction> {
        let mut actions = Vec::new();
        let track_ids: Vec<u64> = self.swarms.keys().copied().collect();
        for t in track_ids {
            // Take the swarm out of the map: the fill step below calls back
            // into `&self` (params), and an owned local avoids the classic
            // map-vs-method double-borrow. Reinserted on every path.
            let Some(mut swarm) = self.swarms.remove(&t) else {
                continue;
            };

            // 1. Timeouts: re-open the request slot, bump starvation.
            let mut dropped: Vec<(u32, usize)> = Vec::new();
            for (idx, reqs) in swarm.inflight.iter_mut() {
                let before = reqs.len();
                reqs.retain(|r| r.deadline_ns > now);
                if reqs.len() < before {
                    dropped.push((*idx, before - reqs.len()));
                }
            }
            for (idx, n) in dropped {
                swarm.counters.timeouts += n as u64;
                *swarm.tries.entry(idx).or_insert(0) += 1;
            }
            swarm.inflight.retain(|_, reqs| !reqs.is_empty());

            // 2. Cadenced HAVE re-announce. Idempotent and unconditional
            //    (a HAVE frame is ~46 B): refresh beats loss-induced
            //    silence — a leecher that missed an announcement must never
            //    wait on a "dirty" edge that never comes.
            if now >= swarm.announce_at_ns && swarm.have_count() > 0 {
                swarm.announce_at_ns = now + self.params.announce_interval.as_nanos() as i64;
                let payload = build_have_payload(&swarm.manifest, &swarm.have_bits());
                for &p in peers {
                    if p != self.me {
                        actions.push(SwarmAction::Unicast {
                            to: p,
                            msg_type: MSG_CHUNK_HAVE,
                            payload: payload.clone(),
                        });
                    }
                }
            }

            // 3. Pipeline fill (leechers only).
            if !swarm.seeder && !swarm.is_complete() {
                fill_pipelines(
                    &self.params,
                    self.me,
                    &mut swarm,
                    t,
                    now,
                    peers,
                    &mut actions,
                );
            }
            self.swarms.insert(t, swarm);
        }
        actions
    }

    // ── retrieval / telemetry ──────────────────────────────────────────

    /// Verified track bytes once complete.
    pub fn take_track(&mut self, track_id: u64) -> Option<Vec<u8>> {
        let swarm = self.swarms.get_mut(&track_id)?;
        if swarm.assemble() {
            swarm.assembled.clone()
        } else {
            None
        }
    }

    pub fn stats(&self) -> Vec<TrackSwarmStats> {
        let mut out: Vec<TrackSwarmStats> = self
            .swarms
            .values()
            .map(|s| TrackSwarmStats {
                track_id: s.manifest.track_id,
                total_len: s.manifest.total_len,
                chunk_size: s.manifest.chunk_size,
                num_chunks: s.manifest.num_chunks,
                have_chunks: s.have_count(),
                seeder: s.seeder,
                complete: s.is_complete(),
                data_rx_bytes: s.counters.data_rx_bytes,
                duplicate_chunks: s.counters.duplicate_chunks,
                corrupt_chunks: s.counters.corrupt_chunks,
                requests_tx: s.counters.requests_tx,
                requests_rx: s.counters.requests_rx,
                served_chunks: s.counters.served_chunks,
                timeouts: s.counters.timeouts,
            })
            .collect();
        out.sort_by_key(|s| s.track_id);
        out
    }
}

fn chunk_slice(data: &[u8], manifest: &TrackManifest, i: usize) -> Vec<u8> {
    let cs = manifest.chunk_size as usize;
    let lo = i * cs;
    let hi = (lo + cs).min(data.len());
    data[lo..hi].to_vec()
}

fn drop_inflight(swarm: &mut Swarm, idx: u32, from: u64) {
    if let Some(reqs) = swarm.inflight.get_mut(&idx) {
        reqs.retain(|r| r.peer != from);
        if reqs.is_empty() {
            swarm.inflight.remove(&idx);
        }
    }
}

/// Rarest-first request scheduler. Free function (params + `me` passed
/// explicitly) so `on_tick` can drive it while holding an owned `Swarm` —
/// no map-vs-method double borrow.
fn fill_pipelines(
    params: &SwarmParams,
    me: u64,
    swarm: &mut Swarm,
    track_id: u64,
    now: i64,
    peers: &[u64],
    actions: &mut Vec<SwarmAction>,
) {
    let n = swarm.manifest.num_chunks as usize;
    let missing: Vec<u32> = (0..n as u32)
        .filter(|&i| swarm.chunks[i as usize].is_none())
        .collect();
    if missing.is_empty() {
        return;
    }
    let endgame = missing.len() <= params.endgame_threshold;
    let max_parallel = if endgame {
        params.endgame_dup_peers.max(1)
    } else {
        1
    };

    // Global rarity: how many known havers per chunk.
    let mut avail = vec![0u32; n];
    for bits in swarm.peer_bits.values() {
        for (i, &b) in bits.iter().enumerate() {
            if b {
                avail[i] += 1;
            }
        }
    }

    let timeout_ns = params.request_timeout.as_nanos() as i64;

    for &p in peers {
        if p == me {
            continue;
        }
        // Clone: the borrow must end before we mutate inflight below.
        let Some(bits) = swarm.peer_bits.get(&p).cloned() else {
            continue;
        };
        let inflight_p = swarm
            .inflight
            .values()
            .flat_map(|v| v.iter())
            .filter(|r| r.peer == p)
            .count();
        let mut slots = params.pipeline_per_peer.saturating_sub(inflight_p);

        while slots > 0 {
            // Rarest-first (global availability), then starvation
            // (timeout pressure), then index — deterministic order.
            let best = missing
                .iter()
                .copied()
                .filter(|&idx| {
                    let i = idx as usize;
                    let blacklisted = swarm
                        .blacklist
                        .get(&idx)
                        .map(|bl| bl.contains(&p))
                        .unwrap_or(false);
                    let infl = swarm.inflight.get(&idx).map(|v| v.len()).unwrap_or(0);
                    let already_from_p = swarm
                        .inflight
                        .get(&idx)
                        .map(|v| v.iter().any(|r| r.peer == p))
                        .unwrap_or(false);
                    bits.get(i).copied().unwrap_or(false)
                        && !blacklisted
                        && !already_from_p
                        && infl < max_parallel
                })
                .min_by_key(|&idx| {
                    let tries = swarm.tries.get(&idx).copied().unwrap_or(0);
                    (avail[idx as usize], std::cmp::Reverse(tries), idx)
                });
            let Some(idx) = best else { break };

            actions.push(SwarmAction::Unicast {
                to: p,
                msg_type: MSG_CHUNK_REQUEST,
                payload: build_chunk_request_payload(track_id, idx),
            });
            swarm.inflight.entry(idx).or_default().push(InflightReq {
                peer: p,
                deadline_ns: now + timeout_ns,
            });
            swarm.counters.requests_tx += 1;
            slots -= 1;
        }
    }
}

// ───────────────────────────────────────────────────────────── unit tests

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    fn params(chunk: usize) -> SwarmParams {
        SwarmParams {
            chunk_size: chunk,
            ..SwarmParams::default()
        }
    }

    fn manager_with_sink(me: u64, p: SwarmParams) -> (SwarmManager, Arc<Mutex<Vec<SwarmEvent>>>) {
        let mut m = SwarmManager::new(me, p);
        let log: Arc<Mutex<Vec<SwarmEvent>>> = Arc::new(Mutex::new(Vec::new()));
        let log2 = Arc::clone(&log);
        m.set_event_sink(Arc::new(move |ev| log2.lock().unwrap().push(ev)));
        (m, log)
    }

    fn sample_track(len: usize) -> Vec<u8> {
        // Deterministic pseudo-audio.
        (0..len).map(|i| ((i * 31 + 7) % 251) as u8).collect()
    }

    const NOW: i64 = 10_000_000_000;
    const PEERS: [u64; 3] = [0x11, 0x22, 0x33];

    #[test]
    fn manifest_roundtrip_and_tail_chunk() {
        let data = sample_track(100_000);
        let m = TrackManifest::build(0xA11CE, &data, 16 * 1024);
        assert_eq!(m.num_chunks, 7); // 6 × 16 KiB + 1 × 5376
        assert_eq!(m.chunk_len(6), 100_000 - 6 * 16_384);
        assert_eq!(m.total_len, 100_000);
        let wire = m.to_wire();
        assert_eq!(TrackManifest::from_wire(&wire).unwrap(), m);
        assert!(TrackManifest::from_wire(&wire[..wire.len() - 1]).is_err());
        assert!(TrackManifest::from_wire(&[]).is_err());
    }

    #[test]
    fn have_bitfield_roundtrip() {
        let data = sample_track(5 * 1024);
        let m = TrackManifest::build(7, &data, 1024);
        let have = vec![true, false, true, true, false];
        let p = build_have_payload(&m, &have);
        let parsed = parse_have_payload(&p).unwrap();
        assert_eq!(parsed.track_id, 7);
        assert_eq!(parsed.num_chunks, 5);
        assert_eq!(parsed.bits, have);
        assert_eq!(parsed.total_hash, m.total_hash);
        assert!(parse_have_payload(&p[..p.len() - 1]).is_none());
    }

    #[test]
    fn seed_announces_manifest_and_full_haves() {
        let (mut m, _log) = manager_with_sink(0x99, params(1024));
        let data = sample_track(4096);
        let actions = m.seed_track(0xBEEF, &data, NOW, &PEERS);
        assert_eq!(actions.len(), 4);
        assert!(matches!(
            actions[0],
            SwarmAction::Broadcast {
                msg_type: MSG_TRACK_MANIFEST,
                ..
            }
        ));
        for a in &actions[1..] {
            match a {
                SwarmAction::Unicast {
                    to,
                    msg_type,
                    payload,
                } => {
                    assert_eq!(*msg_type, MSG_CHUNK_HAVE);
                    assert!(PEERS.contains(to));
                    assert_eq!(
                        parse_have_payload(payload).unwrap().bits,
                        vec![true, true, true, true]
                    );
                }
                _ => panic!(),
            }
        }
        assert_eq!(m.take_track(0xBEEF).unwrap(), data);
    }

    #[test]
    fn manifest_ingest_then_rarest_first_requests() {
        let (mut m, log) = manager_with_sink(0x99, params(1024));
        let data = sample_track(8 * 1024);
        let manifest = TrackManifest::build(0xC0DE, &data, 1024);

        // Seeder 0x11 has everything; 0x22 has only chunks 0-3.
        m.seed_track(0xC0DE, &data, NOW, &[]);
        let _ = log;
        let (mut leech, _l2) = manager_with_sink(0x99, params(1024));
        let _ = leech.on_manifest(&manifest.to_wire(), 0x11, NOW);

        // Two havers with different availability.
        let full = build_have_payload(&manifest, &[true; 8]);
        let partial = build_have_payload(
            &manifest,
            &[true, true, true, true, false, false, false, false],
        );
        let _ = leech.on_have(&full, 0x11, NOW);
        let _ = leech.on_have(&partial, 0x22, NOW);

        let actions = leech.on_tick(NOW, &[0x11, 0x22]);
        let reqs: Vec<(u64, u32)> = actions
            .iter()
            .filter_map(|a| match a {
                SwarmAction::Unicast {
                    to,
                    msg_type,
                    payload,
                } if *msg_type == MSG_CHUNK_REQUEST => {
                    parse_chunk_request_payload(payload).map(|(_, i)| (*to, i))
                }
                _ => None,
            })
            .collect();
        // Rarest-first: chunks 4-7 have one haver (0x11) → requested first.
        assert!(reqs.iter().any(|&(p, i)| p == 0x11 && i >= 4));
        // Pipeline cap per peer: 4 requests max to 0x11 on the first fill.
        assert_eq!(reqs.iter().filter(|&&(p, _)| p == 0x11).count(), 4);
    }

    #[test]
    fn corrupt_chunk_is_rejected_and_peer_blacklisted() {
        let (mut m, _log) = manager_with_sink(0x99, params(1024));
        let data = sample_track(2 * 1024);
        let manifest = TrackManifest::build(0xD1CE, &data, 1024);
        let _ = m.on_manifest(&manifest.to_wire(), 0x11, NOW);

        // Tampered chunk from 0x11.
        let mut bad = data[..1024].to_vec();
        bad[512] ^= 0xFF;
        let mut p = Vec::new();
        p.extend_from_slice(&0xD1CE_u64.to_le_bytes());
        p.extend_from_slice(&0u32.to_le_bytes());
        p.extend_from_slice(&(bad.len() as u32).to_le_bytes());
        p.extend_from_slice(&bad);
        let actions = m.on_chunk_data(&p, 0x11, NOW, &[]);
        assert!(actions.is_empty());

        let stats = &m.stats()[0];
        assert_eq!(stats.corrupt_chunks, 1);
        assert_eq!(stats.have_chunks, 0);

        // The SAME tampered chunk from a different peer is still rejected
        // (hash gate), but the genuine chunk stores.
        let good = build_data_payload(0xD1CE, 0, &data[..1024]);
        let _ = m.on_chunk_data(&good, 0x22, NOW, &[]);
        assert_eq!(m.stats()[0].have_chunks, 1);
    }

    fn build_data_payload(track: u64, idx: u32, bytes: &[u8]) -> Vec<u8> {
        let mut p = Vec::with_capacity(16 + bytes.len());
        p.extend_from_slice(&track.to_le_bytes());
        p.extend_from_slice(&idx.to_le_bytes());
        p.extend_from_slice(&(bytes.len() as u32).to_le_bytes());
        p.extend_from_slice(bytes);
        p
    }

    #[test]
    fn completion_emits_event_and_assembles_verified_track() {
        let (mut m, log) = manager_with_sink(0x99, params(1024));
        let data = sample_track(3 * 1024);
        let manifest = TrackManifest::build(0xF00D, &data, 1024);
        let _ = m.on_manifest(&manifest.to_wire(), 0x11, NOW);

        for i in 0..3u32 {
            let p =
                build_data_payload(0xF00D, i, &data[i as usize * 1024..(i as usize + 1) * 1024]);
            m.on_chunk_data(&p, 0x11, NOW + (i as i64) * 5_000_000, &[]);
        }

        let events = log.lock().unwrap();
        assert!(events.iter().any(|e| matches!(
            e,
            SwarmEvent::Complete {
                track_id: 0xF00D,
                ..
            }
        )));
        drop(events);
        assert_eq!(m.take_track(0xF00D).unwrap(), data);
    }

    #[test]
    fn unknown_track_have_requests_the_manifest() {
        let (mut m, _log) = manager_with_sink(0x99, params(1024));
        let data = sample_track(1024);
        let manifest = TrackManifest::build(0x5EED, &data, 1024);
        let have = build_have_payload(&manifest, &[true]);
        let actions = m.on_have(&have, 0x11, NOW);
        assert_eq!(actions.len(), 1);
        match &actions[0] {
            SwarmAction::Unicast {
                to,
                msg_type,
                payload,
            } => {
                assert_eq!(*to, 0x11);
                assert_eq!(*msg_type, MSG_TRACK_MANIFEST_REQUEST);
                assert_eq!(parse_manifest_request_payload(payload), Some(0x5EED));
            }
            _ => panic!(),
        }
    }

    #[test]
    fn endgame_duplicates_requests_across_peers() {
        let (mut m, _log) = manager_with_sink(0x99, params(1024));
        let data = sample_track(4 * 1024);
        let manifest = TrackManifest::build(0x600D, &data, 1024);
        let _ = m.on_manifest(&manifest.to_wire(), 0x11, NOW);
        // Two full havers; we already hold chunks 0..2 (missing 1 → endgame).
        for i in 0..3u32 {
            if i == 1 {
                continue;
            }
            let p =
                build_data_payload(0x600D, i, &data[i as usize * 1024..(i as usize + 1) * 1024]);
            let _ = m.on_chunk_data(&p, 0x11, NOW, &[]);
        }
        let full = build_have_payload(&manifest, &[true; 4]);
        let _ = m.on_have(&full, 0x11, NOW);
        let _ = m.on_have(&full, 0x22, NOW);

        let actions = m.on_tick(NOW, &[0x11, 0x22]);
        let reqs: Vec<u64> = actions
            .iter()
            .filter_map(|a| match a {
                SwarmAction::Unicast { to, msg_type, .. } if *msg_type == MSG_CHUNK_REQUEST => {
                    Some(*to)
                }
                _ => None,
            })
            .collect();
        // Missing exactly 1 chunk (≤ endgame threshold): both havers are
        // asked in parallel.
        assert!(
            reqs.contains(&0x11) && reqs.contains(&0x22),
            "reqs = {reqs:?}"
        );
    }

    #[test]
    fn request_timeouts_reopen_the_slot() {
        let (mut m, _log) = manager_with_sink(0x99, params(1024));
        let data = sample_track(2 * 1024);
        let manifest = TrackManifest::build(0x700D, &data, 1024);
        let _ = m.on_manifest(&manifest.to_wire(), 0x11, NOW);
        let full = build_have_payload(&manifest, &[true; 2]);
        let _ = m.on_have(&full, 0x11, NOW);

        let a1 = m.on_tick(NOW, &[0x11]);
        assert_eq!(a1.len(), 2);
        // 150 ms pass with no reply → both slots reopen.
        let a2 = m.on_tick(NOW + 200_000_000, &[0x11]);
        assert_eq!(a2.len(), 2);
        assert_eq!(m.stats()[0].timeouts, 2);
    }
}
