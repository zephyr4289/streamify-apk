//! gossip.rs — PlumTree Epidemic Broadcast Trees for the Jam mesh (Phase 2).
//!
//! Push-Lazy-Push multicast (Leitão, Pereira & Rodrigues, SRDS'07), adapted
//! for the Streamify wire format:
//!
//!   Eager push  — every new frame is forwarded immediately along the eager
//!                 tree edges: mutations (JamOpWire), PTP ticks and track
//!                 manifests land with one-hop latency.
//!   Lazy tree   — peers outside the eager tree get compact IHAVE
//!                 announcements (message-id batches) on [`GossipParams::
//!                 lazy_tick`]; if an eager copy was lost to Wi-Fi jitter,
//!                 the missing peer GRAFTs the announcer, which reinstates
//!                 the eager link and re-serves the payload.
//!   Pruning     — receiving an eager frame we already have means the link
//!                 is redundant; once the stream has gone QUIET, a few
//!                 redundant deliveries demote the sender to the lazy set
//!                 (hysteresis default 2). During bursts pruning is
//!                 suppressed: full-mesh eager redundancy is the loss
//!                 shield, and a graft reinstates any link a later burst
//!                 actually needs.
//!
//! PROTOCOL REFINEMENTS over the paper (the lead's brief explicitly grants
//! autonomy to refine):
//!   R1  Sequence-gap healing with a reorder window: `sequence` is a dense
//!       per-sender broadcast counter (wire deviation D3), so a receiver
//!       that holds seq N and N+2 knows N+1 is missing WITHOUT waiting for
//!       an IHAVE. The graft waits out `reorder_window` so in-flight
//!       stragglers quench it — only genuinely lost frames pay the heal.
//!   R2  Graft rotation + bounded retries: each retry targets a different
//!       neighbor (fair round-robin), so a single dead or lossy link cannot
//!       starve a heal. After `graft_max_tries` the id is abandoned
//!       (counted, never re-armed) instead of looping forever.
//!   R3  Graft-serving payload cache: delivered frames keep their raw bytes
//!       in a byte-budgeted FIFO (2 MiB default) so any peer — not just the
//!       original sender — can serve a GRAFT. Track manifests (the largest
//!       gossip frames) stay servable long enough for stragglers.
//!   R4  Origin tail flush: the LAST messages of a burst have no successor
//!       to reveal their gap, and pure-eager links never carry an IHAVE for
//!       them. After `tail_flush_delay` of broadcast silence the origin
//!       re-pushes its last `tail_flush_k` payloads once — a single
//!       one-way trip; holders dedupe, stragglers recover.
//!
//! The engine is a pure state machine: `now` is injected, all effects are
//! returned as [`Action`]s, and the mesh node executes them. That keeps the
//! entire epidemic protocol unit-testable with a synthetic clock and zero
//! sockets (see tests below, plus the 5-node chaos integration test).

use std::collections::{HashMap, HashSet, VecDeque};
use std::time::Duration;

use crate::p2p_mesh::{P2pPacketHeader, MSG_GOSSIP_GRAFT, MSG_GOSSIP_IHAVE};

/// Identity of one gossip message: (origin sender, broadcast sequence).
/// Dense per origin — that density is what makes gap healing (R1) work.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct MsgId {
    pub sender: u64,
    pub seq: u32,
}

/// Effect the mesh node must execute on the engine's behalf.
#[derive(Debug, Clone)]
pub enum Action {
    /// Relay a frame verbatim (same header, same origin, same sequence).
    ForwardRaw { to: u64, bytes: Vec<u8> },
    /// Emit an engine control frame (IHAVE / GRAFT); sequence = 0 (D3).
    Control {
        to: u64,
        msg_type: u8,
        payload: Vec<u8>,
    },
}

/// Result of ingesting one data frame.
#[derive(Debug, Clone, Default)]
pub struct Outcomes {
    /// True when the frame was new and must be delivered to the app layer.
    pub delivered_new: bool,
    pub actions: Vec<Action>,
}

/// Tunable PlumTree parameters (production defaults; tests tighten them).
#[derive(Debug, Clone)]
pub struct GossipParams {
    /// Lazy-set flush cadence: how often IHAVE batches leave the node.
    pub lazy_tick: Duration,
    /// Grace between learning a message is missing and GRAFTing — lets a
    /// racing eager copy win without paying the round trip.
    pub graft_delay: Duration,
    /// Backoff between graft retries for the same message id.
    pub graft_retry: Duration,
    /// Give up healing an id after this many graft attempts.
    pub graft_max_tries: u8,
    /// Message ids per IHAVE frame (12 bytes each + 2 byte header).
    pub ihave_batch: usize,
    /// Delivered-id dedupe capacity (ids; ~1000-op floods fit 8× over).
    pub cache_capacity: usize,
    /// Byte budget for the graft-serving raw-payload cache (R3).
    pub payload_cache_bytes: usize,
    /// Consecutive redundant eager deliveries before pruning a link.
    /// Pruning only fires while the stream is QUIET (see on_data): during
    /// bursts full-mesh eager redundancy is the loss shield.
    pub prune_redundancy_threshold: u32,
    /// Reordering absorption window for sequence-gap grafts (R1): a hole
    /// revealed by an out-of-order successor waits this long before a
    /// graft fires, so in-flight stragglers quench the heal. Set ≈ the
    /// network's worst one-way jitter.
    pub reorder_window: Duration,
    /// Origin tail flush (R4): after this much broadcast silence the
    /// origin re-pushes its last `tail_flush_k` payloads — a single
    /// one-way trip that heals the final messages of a burst (which have
    /// no successor to reveal their gap) without a graft round-trip.
    pub tail_flush_delay: Duration,
    /// How many of the origin's most recent broadcasts each flush round
    /// re-pushes.
    pub tail_flush_k: usize,
    /// Maximum tail-flush rounds per quiet epoch (each loss round covers
    /// another factor of the link loss probability).
    pub tail_flush_rounds: u8,
    /// After the flush rounds are spent, the origin keeps announcing its
    /// recent ids (batched IHAVEs — graft backstop) on every `lazy_tick`
    /// for this long after its last broadcast, then goes fully silent.
    pub tail_announce_window: Duration,
    /// R1b grace: the first frame from an unknown origin may be seq N
    /// either because we joined late OR because the burst head was lost /
    /// reordered. Graft-grace at most this many holes below the first-seen
    /// seq (recent-history catch-up); anything older is the CRDT fold's
    /// job, never gossip's.
    pub snap_grace_holes: usize,
    /// Delay before the R1b grace grafts fire (long — by then, in-flight
    /// stragglers and relay copies have long since landed).
    pub snap_grace_delay: Duration,
    /// Distinct peers a single graft attempt is sent to (parallel heal:
    /// one lost control frame no longer costs a full retry round).
    pub graft_fanout: usize,
    /// Copies of a graft SERVE frame (the heal's payload re-send). Heals
    /// are rare, so duplicating the serve collapses the retry-chain tail
    /// probability quadratically (P(both lost) = p²) at negligible cost.
    pub serve_redundancy: u8,
}

impl Default for GossipParams {
    fn default() -> Self {
        GossipParams {
            lazy_tick: Duration::from_millis(20),
            graft_delay: Duration::from_millis(5),
            graft_retry: Duration::from_millis(40),
            graft_max_tries: 12,
            ihave_batch: 96,
            cache_capacity: 16_384,
            payload_cache_bytes: 2 << 20,
            prune_redundancy_threshold: 2,
            reorder_window: Duration::from_millis(50),
            tail_flush_delay: Duration::from_millis(30),
            tail_flush_k: 16,
            tail_flush_rounds: 4,
            tail_announce_window: Duration::from_secs(2),
            snap_grace_holes: 256,
            snap_grace_delay: Duration::from_millis(300),
            graft_fanout: 2,
            serve_redundancy: 1,
        }
    }
}

/// Engine telemetry snapshot.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct GossipStats {
    pub broadcasts: u64,
    pub delivered: u64,
    pub dups: u64,
    pub eager_forwards: u64,
    pub lazy_announces: u64,
    pub ihaves_tx: u64,
    pub grafts_tx: u64,
    pub grafts_rx: u64,
    pub graft_heals: u64,
    pub prunes: u64,
    pub abandoned: u64,
    pub cache_evicts: u64,
    pub tail_flushes: u64,
    pub eager_peers: usize,
    pub lazy_peers: usize,
    pub pending_grafts: usize,
}

#[derive(Debug, Clone, Copy)]
struct PendingGraft {
    deadline_ns: i64,
    peer: u64,
    tries: u8,
}

/// PlumTree engine — one instance per mesh node.
pub struct GossipEngine {
    params: GossipParams,
    me: u64,
    neighbors: HashSet<u64>,
    eager: HashSet<u64>,
    lazy: HashSet<u64>,
    /// Delivered ids (dedupe + convergence bookkeeping).
    delivered: HashSet<MsgId>,
    order: VecDeque<MsgId>,
    /// Abandoned ids: healing gave up; never re-armed (bounded memory).
    abandoned: HashSet<MsgId>,
    /// Raw bytes of recently delivered frames, served on GRAFT (R3).
    payload_cache: HashMap<MsgId, Vec<u8>>,
    payload_order: VecDeque<MsgId>,
    payload_bytes: usize,
    /// Scheduled grafts keyed by the missing message id.
    pending: HashMap<MsgId, PendingGraft>,
    /// Per-lazy-peer announcement outbox, flushed on `lazy_tick`.
    outbox: HashMap<u64, Vec<MsgId>>,
    /// Timebase anchor for the lazy outbox: entries wait at least
    /// `lazy_tick` after the outbox transitions empty→non-empty before an
    /// IHAVE batch leaves the node.
    outbox_since_ns: i64,
    /// Consecutive redundant eager deliveries per peer (prune hysteresis).
    redundancy: HashMap<u64, u32>,
    /// Highest *contiguous* sequence seen per origin (gap detection, R1).
    watermark: HashMap<u64, u32>,
    /// Fair round-robin cursor for graft rotation (R2).
    rotation_cursor: u64,
    /// The origin's own recent broadcast ids, re-pushed by the tail flush.
    own_recent: VecDeque<MsgId>,
    /// Last time WE broadcast (origin-side clock for the tail flush).
    last_broadcast_ns: i64,
    /// Latch: tail flush rounds already fired for the current quiet epoch.
    tail_flush_rounds_fired: u8,
    last_tail_flush_ns: i64,
    last_tail_announce_ns: i64,
    /// Last engine activity (broadcast or delivery) — the quiet gate for
    /// pruning and the tail flush.
    last_activity_ns: i64,
    stats: GossipStats,
}

impl GossipEngine {
    pub fn new(me: u64, params: GossipParams) -> Self {
        GossipEngine {
            params,
            me,
            neighbors: HashSet::new(),
            eager: HashSet::new(),
            lazy: HashSet::new(),
            delivered: HashSet::new(),
            order: VecDeque::new(),
            abandoned: HashSet::new(),
            payload_cache: HashMap::new(),
            payload_order: VecDeque::new(),
            payload_bytes: 0,
            pending: HashMap::new(),
            outbox: HashMap::new(),
            outbox_since_ns: i64::MIN / 2,
            redundancy: HashMap::new(),
            watermark: HashMap::new(),
            rotation_cursor: 0,
            own_recent: VecDeque::new(),
            last_broadcast_ns: i64::MIN / 2,
            tail_flush_rounds_fired: 0,
            last_tail_flush_ns: i64::MIN / 2,
            last_tail_announce_ns: i64::MIN / 2,
            last_activity_ns: i64::MIN / 2,
            stats: GossipStats::default(),
        }
    }

    // ── membership ─────────────────────────────────────────────────────

    /// Idempotent. New links start eager (optimistic PlumTree bootstrap):
    /// the flood prunes them down to a tree as redundancy is observed.
    pub fn neighbor_up(&mut self, peer: u64) {
        self.neighbors.insert(peer);
        if !self.eager.contains(&peer) && !self.lazy.contains(&peer) {
            self.eager.insert(peer);
        }
    }

    pub fn neighbor_down(&mut self, peer: u64) {
        self.neighbors.remove(&peer);
        self.eager.remove(&peer);
        self.lazy.remove(&peer);
        self.outbox.remove(&peer);
        self.redundancy.remove(&peer);
        // Grafts aimed at the departed peer rotate onto another neighbor.
        let ids: Vec<MsgId> = self
            .pending
            .iter()
            .filter(|(_, pg)| pg.peer == peer)
            .map(|(id, _)| *id)
            .collect();
        for id in ids {
            // Rotation target computed before the pending-map borrow.
            let rotated = self.rotate_peer(peer);
            if let Some(pg) = self.pending.get_mut(&id) {
                pg.peer = rotated;
            }
        }
    }

    pub fn knows_peer(&self, peer: u64) -> bool {
        self.neighbors.contains(&peer)
    }

    /// Ops/test hook: demote a peer from the eager tree to the lazy set.
    /// (The flood path does this automatically via prune hysteresis; this
    /// exists so the lazy-announce behavior can be exercised in isolation.)
    pub fn demote_to_lazy(&mut self, peer: u64) {
        self.eager.remove(&peer);
        if self.neighbors.contains(&peer) {
            self.lazy.insert(peer);
        }
    }

    // ── broadcast / ingest ─────────────────────────────────────────────

    /// Origin-side broadcast: records the frame and fans it out.
    ///
    /// Origin fanout differs from relay fanout in ONE way: EVERY neighbor
    /// (eager and lazy) also gets the id queued for the lazy-cadence IHAVE
    /// batch. The origin is the authoritative holder of its own stream, so
    /// a receiver that lost the eager copy learns what is missing within
    /// one lazy_tick + one-way delay — an announce-based heal path that
    /// does not depend on a successor revealing the gap. `graft_delay`
    /// must cover the straggler window so this never fires spuriously.
    /// Relays announce only to their lazy sets, keeping steady-state
    /// announcement traffic proportional to origins, not peers.
    pub fn broadcast(&mut self, header: &P2pPacketHeader, raw: &[u8], now: i64) -> Vec<Action> {
        let id = MsgId {
            sender: u64::from_le_bytes(header.sender_id),
            seq: header.sequence,
        };
        self.own_recent.push_back(id);
        while self.own_recent.len() > 64 {
            self.own_recent.pop_front();
        }
        self.last_broadcast_ns = now;
        self.tail_flush_rounds_fired = 0; // new burst → flushes may fire again
        self.record_new(id, raw.to_vec(), now);
        self.stats.broadcasts += 1;
        self.note_sequence(id, None, now);

        let was_empty = self.outbox.is_empty();
        let peers: Vec<u64> = self.neighbors.iter().copied().collect();
        let mut actions = Vec::new();
        for p in peers {
            if p == self.me {
                continue;
            }
            if self.eager.contains(&p) {
                actions.push(Action::ForwardRaw {
                    to: p,
                    bytes: raw.to_vec(),
                });
                self.stats.eager_forwards += 1;
                // Origin-sync: eager peers get the announcement too.
                self.outbox.entry(p).or_default().push(id);
            } else if self.lazy.contains(&p) {
                self.outbox.entry(p).or_default().push(id);
            }
        }
        if was_empty && !self.outbox.is_empty() {
            self.outbox_since_ns = now;
        }
        actions
    }

    /// Relay-side ingest of a gossip-routed data frame.
    pub fn on_data(
        &mut self,
        from: u64,
        header: &P2pPacketHeader,
        raw: &[u8],
        now: i64,
    ) -> Outcomes {
        let id = MsgId {
            sender: u64::from_le_bytes(header.sender_id),
            seq: header.sequence,
        };

        if self.delivered.contains(&id) || self.abandoned.contains(&id) {
            self.stats.dups += 1;
            let actions = Vec::new();
            if self.eager.contains(&from) {
                // Quiet-gated pruning: during an active burst the full-mesh
                // eager redundancy IS the loss shield (a receiver missing a
                // frame on one link still has three more). Pruning fires
                // only once the stream has gone quiet, collapsing the mesh
                // to a tree for steady-state bandwidth. A graft reinstates
                // any link the next burst actually needs.
                let quiet = now.saturating_sub(self.last_activity_ns)
                    >= self.params.lazy_tick.as_nanos() as i64;
                if quiet {
                    let threshold = self.params.prune_redundancy_threshold;
                    let r = self.redundancy.entry(from).or_insert(0);
                    *r += 1;
                    if *r >= threshold.max(1) {
                        self.eager.remove(&from);
                        self.lazy.insert(from);
                        self.redundancy.insert(from, 0);
                        self.stats.prunes += 1;
                    }
                } else {
                    // Redundancy during a burst is welcome, not punished.
                    self.redundancy.insert(from, 0);
                }
            }
            return Outcomes {
                delivered_new: false,
                actions,
            };
        }

        self.record_new(id, raw.to_vec(), now);
        self.note_sequence(id, Some(from), now);
        let actions = self.fanout(id, raw, Some(from), now);
        Outcomes {
            delivered_new: true,
            actions,
        }
    }

    /// Ingest of a PlumTree control frame (IHAVE / GRAFT).
    pub fn on_control(&mut self, from: u64, msg_type: u8, payload: &[u8], now: i64) -> Vec<Action> {
        match msg_type {
            MSG_GOSSIP_IHAVE => {
                for id in parse_ihave_payload(payload) {
                    // IHAVEs are already lagged by the lazy cadence — a
                    // short graft_delay lets eager stragglers still win.
                    let deadline = now + self.params.graft_delay.as_nanos() as i64;
                    self.schedule_graft(id, from, deadline);
                }
                Vec::new()
            }
            MSG_GOSSIP_GRAFT => {
                // The requester grafts us back into its eager tree.
                self.lazy.remove(&from);
                self.eager.insert(from);
                self.redundancy.insert(from, 0);
                self.stats.grafts_rx += 1;
                let mut actions = Vec::new();
                if let Some(id) = parse_graft_payload(payload) {
                    if let Some(bytes) = self.payload_cache.get(&id) {
                        // Redundant serve: heals are rare, and one lost
                        // serve frame would cost the requester a full
                        // retry round (retry + two one-way trips).
                        for _ in 0..self.params.serve_redundancy.max(1) {
                            actions.push(Action::ForwardRaw {
                                to: from,
                                bytes: bytes.clone(),
                            });
                        }
                        self.stats.graft_heals += 1;
                    }
                }
                actions
            }
            _ => Vec::new(),
        }
    }

    /// Timer tick: flush lazy announcements, fire due grafts.
    pub fn on_tick(&mut self, now: i64) -> Vec<Action> {
        let mut actions = Vec::new();

        // 1. Lazy IHAVE flush — gated on the lazy_tick cadence so a burst
        // of broadcasts does not announce each message individually.
        if !self.outbox.is_empty()
            && now.saturating_sub(self.outbox_since_ns) >= self.params.lazy_tick.as_nanos() as i64
        {
            let outbox = std::mem::take(&mut self.outbox);
            for (peer, ids) in outbox {
                if ids.is_empty() || !self.neighbors.contains(&peer) {
                    continue;
                }
                for chunk in ids.chunks(self.params.ihave_batch.max(1)) {
                    actions.push(Action::Control {
                        to: peer,
                        msg_type: MSG_GOSSIP_IHAVE,
                        payload: build_ihave_payload(chunk),
                    });
                    self.stats.ihaves_tx += 1;
                    self.stats.lazy_announces += chunk.len() as u64;
                }
            }
        }

        // 2. Graft timers.
        let due: Vec<MsgId> = self
            .pending
            .iter()
            .filter(|(_, pg)| pg.deadline_ns <= now)
            .map(|(id, _)| *id)
            .collect();
        for id in due {
            let (peer, tries) = {
                let pg = self.pending.get(&id).expect("due id present");
                (pg.peer, pg.tries)
            };
            if tries >= self.params.graft_max_tries {
                self.pending.remove(&id);
                self.abandoned.insert(id);
                self.stats.abandoned += 1;
                continue;
            }
            // Parallel heal: fan the graft out to `graft_fanout` distinct
            // peers so one lost control frame does not cost a retry round.
            let mut targets: Vec<u64> = vec![peer];
            for _ in 1..self.params.graft_fanout.max(1) {
                let t = self.rotate_peer(*targets.last().unwrap_or(&peer));
                if !targets.contains(&t) {
                    targets.push(t);
                }
            }
            for t in &targets {
                actions.push(Action::Control {
                    to: *t,
                    msg_type: MSG_GOSSIP_GRAFT,
                    payload: build_graft_payload(&id),
                });
                self.stats.grafts_tx += 1;
            }
            // Rotation target computed before the pending-map borrow.
            let rotated = self.rotate_peer(*targets.last().unwrap_or(&peer));
            if let Some(pg) = self.pending.get_mut(&id) {
                pg.tries = tries + 1;
                pg.peer = rotated;
                pg.deadline_ns = now + self.params.graft_retry.as_nanos() as i64;
            }
        }

        // 3. Origin tail machinery (R4). The final messages of a burst have
        //    no successor to reveal their gap, and pure-eager links never
        //    carry an IHAVE for them. Two backstops, both origin-side and
        //    both bounded:
        //      a) FLUSH — for `tail_flush_rounds` rounds, spaced
        //         `tail_flush_delay`, re-push the last `tail_flush_k`
        //         payloads to every peer. One-way trips; holders dedupe,
        //         stragglers recover. Each extra round covers another
        //         factor of the link loss probability.
        //      b) ANNOUNCE — after the rounds are spent, keep batching
        //         own-recent ids into IHAVEs on every `lazy_tick` for
        //         `tail_announce_window`, letting receivers GRAFT for
        //         anything the flush rounds missed. Then: full silence.
        let broadcast_quiet = self.last_broadcast_ns > i64::MIN / 4
            && now.saturating_sub(self.last_broadcast_ns)
                >= self.params.tail_flush_delay.as_nanos() as i64;
        if broadcast_quiet && !self.neighbors.is_empty() && !self.own_recent.is_empty() {
            let flush_spacing_ok = now.saturating_sub(self.last_tail_flush_ns)
                >= self.params.tail_flush_delay.as_nanos() as i64;
            if self.tail_flush_rounds_fired < self.params.tail_flush_rounds && flush_spacing_ok {
                self.tail_flush_rounds_fired += 1;
                self.last_tail_flush_ns = now;
                self.stats.tail_flushes += 1;
                let k = self.params.tail_flush_k.max(1);
                let ids: Vec<MsgId> = self.own_recent.iter().rev().take(k).copied().collect();
                let peers: Vec<u64> = self.neighbors.iter().copied().collect();
                for id in ids {
                    if let Some(bytes) = self.payload_cache.get(&id) {
                        for &p in &peers {
                            if p != self.me {
                                actions.push(Action::ForwardRaw {
                                    to: p,
                                    bytes: bytes.clone(),
                                });
                            }
                        }
                    }
                }
            } else if self.tail_flush_rounds_fired >= self.params.tail_flush_rounds
                && now.saturating_sub(self.last_broadcast_ns)
                    < self.params.tail_announce_window.as_nanos() as i64
                && now.saturating_sub(self.last_tail_announce_ns)
                    >= self.params.lazy_tick.as_nanos() as i64
            {
                self.last_tail_announce_ns = now;
                let ids: Vec<MsgId> = self.own_recent.iter().copied().collect();
                let peers: Vec<u64> = self.neighbors.iter().copied().collect();
                for p in peers {
                    if p == self.me {
                        continue;
                    }
                    for chunk in ids.chunks(self.params.ihave_batch.max(1)) {
                        actions.push(Action::Control {
                            to: p,
                            msg_type: MSG_GOSSIP_IHAVE,
                            payload: build_ihave_payload(chunk),
                        });
                        self.stats.ihaves_tx += 1;
                    }
                }
            }
        }

        actions
    }

    // ── introspection for tests / telemetry ────────────────────────────

    pub fn is_delivered(&self, id: MsgId) -> bool {
        self.delivered.contains(&id)
    }

    pub fn delivered_count(&self) -> usize {
        self.delivered.len()
    }

    pub fn pending_graft_count(&self) -> usize {
        self.pending.len()
    }

    /// Test/diagnostic probe for one message id.
    pub fn probe(&self, id: MsgId) -> (bool, bool, bool, u32) {
        (
            self.delivered.contains(&id),
            self.pending.contains_key(&id),
            self.abandoned.contains(&id),
            self.watermark.get(&id.sender).copied().unwrap_or(0),
        )
    }

    pub fn stats(&self) -> GossipStats {
        let mut s = self.stats;
        s.eager_peers = self.eager.len();
        s.lazy_peers = self.lazy.len();
        s.pending_grafts = self.pending.len();
        s
    }

    // ───────────────────────── internal machinery ──────────────────────

    fn record_new(&mut self, id: MsgId, raw: Vec<u8>, now: i64) {
        self.delivered.insert(id);
        self.order.push_back(id);
        self.last_activity_ns = now;
        while self.delivered.len() > self.params.cache_capacity {
            match self.order.pop_front() {
                Some(old) => {
                    self.delivered.remove(&old);
                }
                None => break,
            }
        }
        self.pending.remove(&id);
        self.stats.delivered += 1;

        // Graft-serving payload cache (R3), byte-budgeted FIFO.
        if !self.payload_cache.contains_key(&id) {
            self.payload_bytes += raw.len();
            self.payload_cache.insert(id, raw);
            self.payload_order.push_back(id);
            while self.payload_bytes > self.params.payload_cache_bytes
                && self.payload_order.len() > 1
            {
                let old = self.payload_order.pop_front().expect("order non-empty");
                if let Some(b) = self.payload_cache.remove(&old) {
                    self.payload_bytes -= b.len();
                    self.stats.cache_evicts += 1;
                }
            }
        }
    }

    /// Sequence-gap healing (R1): schedule grafts for every hole a newly
    /// delivered (or originated) frame reveals below its sequence number.
    ///
    /// R1b — first-seen grace: the FIRST frame from an unknown origin may
    /// arrive at seq N because we joined the session late (prefix is the
    /// CRDT fold's problem) OR because the burst head was lost / reordered
    /// on every link (prefix is genuinely ours to heal). We cannot tell
    /// the two apart at the engine layer, so we graft-grace the most
    /// recent `snap_grace_holes` holes below the first-seen seq with a
    /// long delay, and ignore anything older.
    fn note_sequence(&mut self, id: MsgId, from: Option<u64>, now: i64) {
        if !self.watermark.contains_key(&id.sender) && id.seq > 1 {
            let grace = self.params.snap_grace_holes as u32;
            let lo = id.seq.saturating_sub(grace).max(1);
            if from.is_some() {
                let deadline = now + self.params.snap_grace_delay.as_nanos() as i64;
                for seq in lo..id.seq {
                    let hole = MsgId {
                        sender: id.sender,
                        seq,
                    };
                    if !self.delivered.contains(&hole) {
                        // Grace grafts also target the origin first.
                        self.schedule_graft(hole, id.sender, deadline);
                    }
                }
            }
            self.watermark.insert(id.sender, id.seq - 1);
            return;
        }
        let current = self.watermark.get(&id.sender).copied().unwrap_or(0);
        let mut holes: Vec<u32> = Vec::new();
        if id.seq == current + 1 {
            // Advance the contiguous watermark as far as the cache knows.
            let mut next = id.seq + 1;
            while self.delivered.contains(&MsgId {
                sender: id.sender,
                seq: next,
            }) {
                next += 1;
            }
            self.watermark.insert(id.sender, next - 1);
        } else if id.seq > current + 1 {
            holes = (current + 1..id.seq)
                .filter(|&s| {
                    !self.delivered.contains(&MsgId {
                        sender: id.sender,
                        seq: s,
                    })
                })
                .collect();
            self.watermark.insert(id.sender, id.seq.max(current));
        }
        // id.seq <= watermark: late straggler — nothing to reveal.

        // Grafts are scheduled only for relays (the originator already
        // holds everything it broadcast) — and only after the watermark
        // bookkeeping released its borrows.
        if from.is_some() {
            for seq in holes {
                // Reorder window (R1): in-flight stragglers quench the
                // graft before it fires — only genuinely lost frames pay
                // the round trip. First target is the ORIGIN (it provably
                // holds everything it broadcast); retries rotate.
                let deadline = now + self.params.reorder_window.as_nanos() as i64;
                self.schedule_graft(
                    MsgId {
                        sender: id.sender,
                        seq,
                    },
                    id.sender,
                    deadline,
                );
            }
        }
    }

    fn schedule_graft(&mut self, id: MsgId, peer: u64, deadline_ns: i64) {
        if self.delivered.contains(&id)
            || self.abandoned.contains(&id)
            || self.pending.contains_key(&id)
        {
            return;
        }
        self.pending.insert(
            id,
            PendingGraft {
                deadline_ns,
                peer,
                tries: 0,
            },
        );
    }

    fn fanout(&mut self, id: MsgId, raw: &[u8], exclude: Option<u64>, now: i64) -> Vec<Action> {
        let mut actions = Vec::new();
        let was_empty = self.outbox.is_empty();
        let mut outbox_touched = false;
        for &p in &self.neighbors {
            if Some(p) == exclude || p == self.me {
                continue;
            }
            if self.eager.contains(&p) {
                actions.push(Action::ForwardRaw {
                    to: p,
                    bytes: raw.to_vec(),
                });
                self.stats.eager_forwards += 1;
            } else if self.lazy.contains(&p) {
                self.outbox.entry(p).or_default().push(id);
                outbox_touched = true;
            }
        }
        if outbox_touched && was_empty {
            // Fresh outbox epoch → the lazy_tick window starts here.
            self.outbox_since_ns = now;
        }
        actions
    }

    /// Fair graft rotation (R2): cycles deterministically through ALL
    /// neighbors. A plain `find(|p| p != exclude)` over a HashSet picks the
    /// same neighbor every time (hash order is stable per map state), which
    /// can ping-pong grafts between two peers that both lack the message
    /// while the origin — who provably has it — is never asked.
    fn rotate_peer(&mut self, exclude: u64) -> u64 {
        let mut peers: Vec<u64> = self.neighbors.iter().copied().collect();
        if peers.is_empty() {
            return exclude;
        }
        if peers.len() == 1 {
            // Only one candidate left: even if it is the excluded peer it
            // beats silently dropping the heal.
            return peers[0];
        }
        peers.sort_unstable();
        let start = (self.rotation_cursor % peers.len() as u64) as usize;
        self.rotation_cursor = self.rotation_cursor.wrapping_add(1);
        for i in 0..peers.len() {
            let p = peers[(start + i) % peers.len()];
            if p != exclude {
                return p;
            }
        }
        exclude
    }
}

// ────────────────────────────────────────────── IHAVE / GRAFT wire codecs

/// IHAVE payload: `[u16 count][{u64 sender LE, u32 seq LE} × count]`.
pub fn build_ihave_payload(ids: &[MsgId]) -> Vec<u8> {
    let mut p = Vec::with_capacity(2 + ids.len() * 12);
    p.extend_from_slice(&(ids.len().min(u16::MAX as usize) as u16).to_le_bytes());
    for id in ids.iter().take(u16::MAX as usize) {
        p.extend_from_slice(&id.sender.to_le_bytes());
        p.extend_from_slice(&id.seq.to_le_bytes());
    }
    p
}

/// Parses an IHAVE payload; malformed trailing bytes are dropped, never
/// fatal (a half-read announcement still heals most of the tree).
pub fn parse_ihave_payload(payload: &[u8]) -> Vec<MsgId> {
    if payload.len() < 2 {
        return Vec::new();
    }
    let count = u16::from_le_bytes(payload[0..2].try_into().unwrap()) as usize;
    let entries = (payload.len() - 2) / 12;
    let n = count.min(entries);
    let mut ids = Vec::with_capacity(n);
    for i in 0..n {
        let base = 2 + i * 12;
        ids.push(MsgId {
            sender: u64::from_le_bytes(payload[base..base + 8].try_into().unwrap()),
            seq: u32::from_le_bytes(payload[base + 8..base + 12].try_into().unwrap()),
        });
    }
    ids
}

/// GRAFT payload: `[u64 sender LE][u32 seq LE]`.
pub fn build_graft_payload(id: &MsgId) -> Vec<u8> {
    let mut p = Vec::with_capacity(12);
    p.extend_from_slice(&id.sender.to_le_bytes());
    p.extend_from_slice(&id.seq.to_le_bytes());
    p
}

/// Parses a GRAFT payload (strict: exactly 12 bytes).
pub fn parse_graft_payload(payload: &[u8]) -> Option<MsgId> {
    if payload.len() != 12 {
        return None;
    }
    Some(MsgId {
        sender: u64::from_le_bytes(payload[0..8].try_into().unwrap()),
        seq: u32::from_le_bytes(payload[8..12].try_into().unwrap()),
    })
}

// ───────────────────────────────────────────────────────────── unit tests

#[cfg(test)]
mod tests {
    use super::*;
    use crate::p2p_mesh::{encode_packet, SessionId, MAGIC, MSG_CRDT_OP, PROTO_VERSION};

    const A: u64 = 0xAAAA;
    const B: u64 = 0xBBBB;
    const C: u64 = 0xCCCC;
    const D: u64 = 0xDDDD;

    fn tight_params() -> GossipParams {
        GossipParams {
            lazy_tick: Duration::from_millis(10),
            graft_delay: Duration::from_millis(5),
            graft_retry: Duration::from_millis(40),
            graft_max_tries: 3,
            ihave_batch: 8,
            cache_capacity: 512,
            payload_cache_bytes: 64 << 10,
            prune_redundancy_threshold: 2,
            reorder_window: Duration::from_millis(5),
            tail_flush_delay: Duration::from_millis(10),
            tail_flush_k: 4,
            tail_flush_rounds: 4,
            tail_announce_window: Duration::from_millis(50),
            snap_grace_holes: 8,
            snap_grace_delay: Duration::from_millis(10),
            graft_fanout: 1, // exact-action tests pin single-target grafts
            serve_redundancy: 1,
        }
    }

    fn frame(origin: u64, seq: u32, payload: &[u8]) -> (P2pPacketHeader, Vec<u8>) {
        let mut h = P2pPacketHeader {
            magic: MAGIC,
            version: PROTO_VERSION,
            msg_type: MSG_CRDT_OP,
            sender_id: origin.to_le_bytes(),
            session_id: SessionId::from_session_str("t").0,
            sequence: seq,
            timestamp_mono_ns: 0,
            payload_len: 0,
            checksum_fnv1a: 0,
        };
        let raw = encode_packet(&mut h, payload);
        (h, raw)
    }

    fn engine(me: u64, peers: &[u64]) -> GossipEngine {
        let mut e = GossipEngine::new(me, tight_params());
        for p in peers {
            e.neighbor_up(*p);
        }
        e
    }

    const NS_MS: i64 = 1_000_000;

    #[test]
    fn origin_tail_flush_repushes_last_k_after_quiet() {
        // A broadcasts 5 messages; the flush re-pushes only the last K=4
        // payloads, once, after `tail_flush_delay` of broadcast silence.
        let mut a = engine(A, &[B, C]);
        let mut raws = Vec::new();
        for s in 1..=5u32 {
            let (h, raw) = frame(A, s, b"payload");
            raws.push(raw);
            let _ = a.broadcast(&h, &raws[s as usize - 1], 0);
        }
        // During the burst: no flush (broadcast silence not reached).
        assert!(a.on_tick(5 * NS_MS).is_empty());

        let actions = a.on_tick(11 * NS_MS);
        let pushes: Vec<&Action> = actions
            .iter()
            .filter(|x| matches!(x, Action::ForwardRaw { .. }))
            .collect();
        // K=4 payloads × 2 peers, and seq 1 (beyond the K window) is NOT
        // re-pushed.
        assert_eq!(pushes.len(), 8, "K payloads × all peers");
        for a2 in pushes {
            match a2 {
                Action::ForwardRaw { to, bytes } => {
                    assert!(*to == B || *to == C);
                    assert!(raws.contains(bytes), "payload must come from the cache");
                    assert_ne!(bytes, &raws[0], "seq 1 is outside the flush window");
                }
                _ => unreachable!(),
            }
        }
        assert_eq!(a.stats().tail_flushes, 1);
        // The latch prevents re-flushing the same quiet epoch.
        assert!(a.on_tick(20 * NS_MS).is_empty());
    }

    #[test]
    fn eager_fanout_dedupe_and_quiet_gated_prune() {
        let mut a = engine(A, &[B, C, D]);
        let (h, raw) = frame(A, 1, b"op-1");
        let actions = a.broadcast(&h, &raw, 0);
        // Full-mesh bootstrap: everyone is eager on day one.
        assert_eq!(actions.len(), 3);

        let mut b = engine(B, &[A, C, D]);
        let out = b.on_data(A, &h, &raw, 0);
        assert!(out.delivered_new);
        // B relays to its other neighbors, excluding the source A.
        assert_eq!(out.actions.len(), 2);

        // Redundant copies DURING the burst: redundancy is welcome, the
        // prune is suppressed (quiet gate).
        let out = b.on_data(A, &h, &raw, 5 * NS_MS);
        assert!(!out.delivered_new);
        assert_eq!(b.stats().prunes, 0);
        let out = b.on_data(A, &h, &raw, 8 * NS_MS);
        assert!(!out.delivered_new);
        assert_eq!(b.stats().prunes, 0, "no pruning while the stream is hot");

        // Once quiet (≥ lazy_tick since the last delivery), the next
        // redundant copies prune A → lazy (hysteresis 2).
        let out = b.on_data(A, &h, &raw, 20 * NS_MS);
        assert!(!out.delivered_new);
        let out = b.on_data(A, &h, &raw, 21 * NS_MS);
        assert!(!out.delivered_new);
        assert_eq!(b.stats().prunes, 1);
        let s = b.stats();
        assert_eq!(s.eager_peers, 2, "A demoted to lazy; C,D remain eager");
    }

    #[test]
    fn ihave_graft_heal_roundtrip() {
        let mut a = engine(A, &[B]);
        let (h, raw) = frame(A, 7, b"manifest");
        let _ = a.broadcast(&h, &raw, 0);

        // B missed the eager copy; A's IHAVE arrives at t=0. Densify the
        // sender's stream below the heal target (seq 1..6) first so the
        // gap detector reveals no spurious pre-history holes (R1b keeps
        // this from mattering in production, but the unit test pins the
        // exact pending set).
        let mut b = engine(B, &[A]);
        for s in 1..7u32 {
            let (hf, rawf) = frame(A, s, b"filler");
            let _ = b.on_data(A, &hf, &rawf, 0);
        }
        let ihave = build_ihave_payload(&[MsgId { sender: A, seq: 7 }]);
        let actions = b.on_control(A, MSG_GOSSIP_IHAVE, &ihave, 0);
        assert!(actions.is_empty(), "graft must wait out graft_delay");
        assert_eq!(b.pending_graft_count(), 1);

        // At t = graft_delay the graft fires.
        let actions = b.on_tick(5 * NS_MS);
        assert_eq!(actions.len(), 1);
        match &actions[0] {
            Action::Control {
                to,
                msg_type,
                payload,
            } => {
                assert_eq!(*to, A);
                assert_eq!(*msg_type, MSG_GOSSIP_GRAFT);
                assert_eq!(
                    parse_graft_payload(payload),
                    Some(MsgId { sender: A, seq: 7 })
                );
            }
            _ => panic!("expected graft control"),
        }

        // A serves the payload from its graft cache and re-eagers B.
        let graft = build_graft_payload(&MsgId { sender: A, seq: 7 });
        let actions = a.on_control(B, MSG_GOSSIP_GRAFT, &graft, 5 * NS_MS);
        assert_eq!(actions.len(), 1);
        match &actions[0] {
            Action::ForwardRaw { to, bytes } => {
                assert_eq!(*to, B);
                assert_eq!(bytes, &raw);
            }
            _ => panic!("expected raw re-send"),
        }

        // B ingests the heal: delivered, no more pending grafts.
        let out = b.on_data(A, &h, &raw, 6 * NS_MS);
        assert!(out.delivered_new);
        assert_eq!(b.pending_graft_count(), 0);
        assert_eq!(a.stats().graft_heals, 1);
    }

    #[test]
    fn sequence_gaps_schedule_grafts_immediately() {
        // B receives (A,1) then (A,3) — the hole at 2 must be scheduled
        // WITHOUT any IHAVE (refinement R1).
        let mut b = engine(B, &[A, C]);
        let (h1, raw1) = frame(A, 1, b"op-1");
        let (h3, raw3) = frame(A, 3, b"op-3");
        assert!(b.on_data(A, &h1, &raw1, 0).delivered_new);
        assert!(b.on_data(A, &h3, &raw3, 10 * NS_MS).delivered_new);
        assert_eq!(b.pending_graft_count(), 1);

        let actions = b.on_tick(15 * NS_MS);
        assert!(actions.iter().any(|a| matches!(
            a,
            Action::Control { msg_type: MSG_GOSSIP_GRAFT, payload, .. }
                if parse_graft_payload(payload) == Some(MsgId { sender: A, seq: 2 })
        )));

        // Delivering the hole clears the pending graft.
        let (h2, raw2) = frame(A, 2, b"op-2");
        assert!(b.on_data(A, &h2, &raw2, 16 * NS_MS).delivered_new);
        assert_eq!(b.pending_graft_count(), 0);
    }

    #[test]
    fn lazy_flush_batches_and_respects_cadence() {
        let mut a = engine(A, &[B, C]);
        a.demote_to_lazy(C); // C pruned to the lazy set
        let (h1, raw1) = frame(A, 1, b"one");
        let (h2, raw2) = frame(A, 2, b"two");
        let _ = a.broadcast(&h1, &raw1, 0);
        let _ = a.broadcast(&h2, &raw2, 1);

        // Before the tick: nothing leaves.
        assert!(a.on_tick(0).is_empty());

        let actions = a.on_tick(10 * NS_MS);
        // Origin-sync announcements: eager B AND demoted C each get one
        // batched IHAVE with both ids.
        let ihaves: Vec<&Action> = actions
            .iter()
            .filter(|a| {
                matches!(
                    a,
                    Action::Control {
                        msg_type: MSG_GOSSIP_IHAVE,
                        ..
                    }
                )
            })
            .collect();
        assert_eq!(ihaves.len(), 2, "origin announces to eager AND lazy peers");
        let mut targets: Vec<u64> = Vec::new();
        for a2 in ihaves {
            match a2 {
                Action::Control { to, payload, .. } => {
                    assert_eq!(parse_ihave_payload(payload).len(), 2);
                    targets.push(*to);
                }
                _ => unreachable!(),
            }
        }
        targets.sort_unstable();
        assert_eq!(targets, vec![B, C]);
        // Second tick: the lazy outbox is drained (no more IHAVEs — the
        // origin tail flush's ForwardRaw pushes are a separate, bounded
        // mechanism with its own test).
        let actions = a.on_tick(20 * NS_MS);
        assert!(!actions.iter().any(|a| matches!(
            a,
            Action::Control {
                msg_type: MSG_GOSSIP_IHAVE,
                ..
            }
        )));
    }

    #[test]
    fn grafts_retry_rotate_and_abandon() {
        let mut b = engine(B, &[A, C]);
        b.schedule_graft(MsgId { sender: A, seq: 9 }, A, 0);
        let retry = 40 * NS_MS;
        let mut t = 5 * NS_MS;

        // First fire targets A.
        let a1 = b.on_tick(t);
        assert_eq!(a1.len(), 1);

        // Rotates to C on the retry (dead-link tolerance, R2).
        t += retry;
        let a2 = b.on_tick(t);
        match &a2[0] {
            Action::Control { to, .. } => assert_eq!(*to, C),
            _ => panic!(),
        }

        // Exhaust max_tries (3) → abandoned, engine goes quiet.
        t += retry;
        let _ = b.on_tick(t);
        t += retry;
        let a4 = b.on_tick(t);
        assert!(a4.is_empty(), "id must be abandoned after max tries");
        assert_eq!(b.stats().abandoned, 1);

        // The abandoned id is never re-armed by a late IHAVE.
        let ihave = build_ihave_payload(&[MsgId { sender: A, seq: 9 }]);
        let _ = b.on_control(A, MSG_GOSSIP_IHAVE, &ihave, t + 1);
        assert_eq!(b.pending_graft_count(), 0);
    }

    #[test]
    fn neighbor_down_reassigns_pending_grafts() {
        let mut b = engine(B, &[A, C]);
        b.schedule_graft(MsgId { sender: A, seq: 4 }, A, 0);
        b.neighbor_down(A);
        let actions = b.on_tick(5 * NS_MS);
        match &actions[0] {
            Action::Control { to, .. } => assert_eq!(*to, C),
            _ => panic!(),
        }
    }

    #[test]
    fn payload_cache_serves_manifest_sized_frames() {
        let mut a = engine(A, &[B]);
        let big: Vec<u8> = (0..4096u32).map(|i| (i % 256) as u8).collect();
        let (h, raw) = frame(A, 1, &big);
        let _ = a.broadcast(&h, &raw, 0);
        let graft = build_graft_payload(&MsgId { sender: A, seq: 1 });
        let actions = a.on_control(B, MSG_GOSSIP_GRAFT, &graft, 1);
        assert_eq!(actions.len(), 1);
    }

    #[test]
    fn ihave_wire_codec_roundtrip() {
        let ids = vec![
            MsgId {
                sender: 0x0102_0304_0506_0708,
                seq: 42,
            },
            MsgId {
                sender: u64::MAX,
                seq: u32::MAX,
            },
        ];
        let p = build_ihave_payload(&ids);
        assert_eq!(p.len(), 2 + 24);
        assert_eq!(parse_ihave_payload(&p), ids);
        // Truncated / junk payloads degrade to empty, never panic.
        assert!(parse_ihave_payload(&[]).is_empty());
        assert!(parse_ihave_payload(&[0xFF, 0xFF]).is_empty());
        assert!(parse_ihave_payload(&[0x02, 0x00, 0x01]).is_empty());
    }
}
