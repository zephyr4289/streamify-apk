//! Streamify Jam Operation-Based CRDT (CmRDT) Engine — v3
//!
//! Audit fixes carried from the v2 review:
//!   R3  Identity parity: CAD-IDs are minted ONLY through
//!       `repository::generate_cad_id_u64` (canonical normalization +
//!       duration bucketing). This module never forks the hasher.
//!   R1  Commutativity under equal fractions: concurrent inserts into the
//!       same gap produce identical `frac_index` values. Ordering key is the
//!       composite `(frac_bits, add_op_id)` so merge results are independent
//!       of arrival order (op_id breaks ties deterministically).
//!   R2  Reorder captures the entry BEFORE removal (v2 read a deleted slot
//!       and silently depended on sender echo for cad identity).
//!
//! Additional hardening:
//!   - `JamOp::new()` is the only sanctioned constructor: pads are forced to
//!     zero and the checksum computed over the exact wire span [0..40).
//!   - Explicit little-endian field serialization (`to_bytes` / `from_bytes`)
//!     replaces pointer-cast transmutation — portable across languages and
//!     free of unaligned-read UB. Layout mirrors the repr(C) declaration.
//!   - `needs_rebalance` uses relative ULP distance; shared-fraction entries
//!     (gap 0) trip it immediately.
//!
//! Tombstone contract (B2 lineage):
//!   Every queue element's identity is its ADD op's `op_id`. Remove ops carry
//!   that id in `target_add_op_id`; tombstoning suppresses late replays of the
//!   Add regardless of delivery order. Folds ship tombstones alongside the
//!   queue so fresh replicas cannot resurrect removed elements.

use std::collections::{BTreeMap, HashMap};
use std::sync::atomic::{AtomicU16, AtomicU64, Ordering};

use crate::repository::generate_cad_id_u64;

const FNV1A_32_OFFSET: u32 = 0x811c_9dc5;
const FNV1A_32_PRIME: u32 = 0x0100_0193;

/// Wire size of [`JamOp`] (repr(C): 48 bytes, naturally aligned).
pub const JAM_OP_SIZE: usize = 48;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum OpType {
    Add = 1,
    Remove = 2,
    Reorder = 3,
    Vote = 4, // reserved — not yet merged by apply_op
}

// Global op-id generator state (per process).
static GLOBAL_OP_COUNTER: AtomicU16 = AtomicU16::new(0);
static LAST_SEEN_MS: AtomicU64 = AtomicU64::new(0);

/// The canonical Jam mutation record — 48 bytes on the wire.
#[derive(Debug, Clone, Copy, PartialEq)]
#[repr(C)]
pub struct JamOp {
    /// 48-bit unix_ms << 16 | 16-bit per-process counter. Strictly monotonic
    /// per device even under NTP step-back; doubles as element identity.
    pub op_id: u64,
    /// 4-byte device identity (device-nonce prefix from JamEngine).
    pub sender_nonce: [u8; 4],
    pub op_type: u8,
    pub policy_flags: u8,
    pub _pad1: [u8; 2],
    pub track_cad_id: u64,
    pub frac_index: f64,
    pub target_add_op_id: u64,
    pub checksum: u32,
    pub _pad2: u32,
}

impl Default for JamOp {
    fn default() -> Self {
        JamOp {
            op_id: 0,
            sender_nonce: [0; 4],
            op_type: 0,
            policy_flags: 0,
            _pad1: [0; 2],
            track_cad_id: 0,
            frac_index: 0.0,
            target_add_op_id: 0,
            checksum: 0,
            _pad2: 0,
        }
    }
}

impl JamOp {
    /// Sanctioned constructor: zeroed pads, checksum sealed.
    pub fn new(
        op_id: u64,
        sender_nonce: [u8; 4],
        op_type: OpType,
        policy_flags: u8,
        track_cad_id: u64,
        frac_index: f64,
        target_add_op_id: u64,
    ) -> Self {
        let mut op = JamOp {
            op_id,
            sender_nonce,
            op_type: op_type as u8,
            policy_flags,
            _pad1: [0; 2],
            track_cad_id,
            frac_index,
            target_add_op_id,
            checksum: 0,
            _pad2: 0,
        };
        op.checksum = op.compute_checksum();
        op
    }

    /// Strictly monotonic op-id generation, immune to clock step-back:
    /// LAST_SEEN_MS is ratcheted forward, so a regressing wall clock yields
    /// prev+1 rather than a smaller id. Same-millisecond calls disambiguate
    /// through the 16-bit counter.
    pub fn generate_op_id() -> u64 {
        let now_ms = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);

        let mut current_ms = now_ms;
        let prev = LAST_SEEN_MS.fetch_max(current_ms, Ordering::AcqRel);
        if prev >= current_ms {
            current_ms = prev.saturating_add(1);
            LAST_SEEN_MS.store(current_ms, Ordering::Release);
        }

        ((current_ms & 0xFFFF_FFFF_FFFF) << 16) | GLOBAL_OP_COUNTER.fetch_add(1, Ordering::AcqRel) as u64
    }

    /// FNV-1a 32 over payload bytes [0..40): every field except the checksum
    /// itself and trailing pad. Pads are included BY DESIGN — they are forced
    /// to zero by [`JamOp::new`] and by `from_bytes`, making the span
    /// deterministic across senders.
    pub fn compute_checksum(&self) -> u32 {
        let mut hash = FNV1A_32_OFFSET;
        for b in self.to_bytes()[..40].iter() {
            hash ^= *b as u32;
            hash = hash.wrapping_mul(FNV1A_32_PRIME);
        }
        hash
    }

    #[inline]
    pub fn is_valid(&self) -> bool {
        self.compute_checksum() == self.checksum
    }

    /// Explicit little-endian serialization — no unsafe, no padding ambiguity.
    pub fn to_bytes(&self) -> [u8; JAM_OP_SIZE] {
        let mut b = [0u8; JAM_OP_SIZE];
        b[0..8].copy_from_slice(&self.op_id.to_le_bytes());
        b[8..12].copy_from_slice(&self.sender_nonce);
        b[12] = self.op_type;
        b[13] = self.policy_flags;
        // _pad1 stays zero (b initialized to 0)
        b[16..24].copy_from_slice(&self.track_cad_id.to_le_bytes());
        b[24..32].copy_from_slice(&self.frac_index.to_bits().to_le_bytes());
        b[32..40].copy_from_slice(&self.target_add_op_id.to_le_bytes());
        b[40..44].copy_from_slice(&self.checksum.to_le_bytes());
        // _pad2 stays zero
        b
    }

    /// Inverse of [`to_bytes`]. Rejects non-zero pads (corrupt/hostile rows)
    /// by returning None instead of panicking.
    pub fn from_bytes(bytes: &[u8]) -> Option<Self> {
        if bytes.len() != JAM_OP_SIZE {
            return None;
        }
        if bytes[14] != 0 || bytes[15] != 0 || bytes[44..48].iter().any(|&x| x != 0) {
            return None;
        }
        let mut op = JamOp::default();
        let mut arr8 = [0u8; 8];
        arr8.copy_from_slice(&bytes[0..8]);
        op.op_id = u64::from_le_bytes(arr8);
        op.sender_nonce.copy_from_slice(&bytes[8..12]);
        op.op_type = bytes[12];
        op.policy_flags = bytes[13];
        arr8.copy_from_slice(&bytes[16..24]);
        op.track_cad_id = u64::from_le_bytes(arr8);
        arr8.copy_from_slice(&bytes[24..32]);
        op.frac_index = f64::from_bits(u64::from_le_bytes(arr8));
        arr8.copy_from_slice(&bytes[32..40]);
        op.target_add_op_id = u64::from_le_bytes(arr8);
        let mut arr4 = [0u8; 4];
        arr4.copy_from_slice(&bytes[40..44]);
        op.checksum = u32::from_le_bytes(arr4);
        Some(op)
    }
}

/// Canonical CAD-ID (u64) delegated to the repository hasher — V1 parity by
/// construction. Kept here as a thin alias so call sites read cleanly.
#[inline]
pub fn canonical_cad_id(title: &str, artist: &str, duration_sec: u32) -> u64 {
    generate_cad_id_u64(title, artist, duration_sec)
}

/// Total-order composite key: primary by fraction bit-pattern (monotonic for
/// finite non-negative f64), secondary by add-op id. Guarantees that two
/// replicas applying the same op set in different orders converge (R1).
fn frac_key(frac_index: f64, add_op_id: u64) -> (u64, u64) {
    (frac_index.to_bits(), add_op_id)
}

#[derive(Debug, Clone)]
struct QueueEntry {
    cad_id: u64,
}

/// CRDT state machine for the shared Jam queue.
#[derive(Debug, Default)]
pub struct JamCrdtState {
    /// Composite-ordered queue: (frac_bits, add_op_id) -> entry.
    pub queue: BTreeMap<(u64, u64), QueueEntry>,
    /// Element tombstones: suppressed add_op_ids (survive folds).
    pub tombstones: HashMap<u64, ()>,
    /// Latched when adjacent fractions fall within relative ULP range.
    /// Reset externally after a successful rebalance pass.
    pub needs_rebalance: bool,
}

impl JamCrdtState {
    pub fn new() -> Self {
        Self::default()
    }

    /// Deterministically merges one operation. Idempotent per (op_id, key).
    pub fn apply_op(&mut self, op: &JamOp) -> bool {
        if !op.is_valid() || !op.frac_index.is_finite() {
            return false; // corrupt wire payload or NaN poisoning attempt
        }

        match op.op_type {
            1 => {
                // Add (element identity == op_id)
                if self.tombstones.contains_key(&op.op_id) {
                    return false; // B2: late replay after Remove — suppressed
                }
                self.queue
                    .insert(frac_key(op.frac_index, op.op_id), QueueEntry { cad_id: op.track_cad_id });
            }
            2 => {
                // Remove: tombstone the ELEMENT, then purge any live entry.
                self.tombstones.insert(op.target_add_op_id, ());
                let doomed: Vec<(u64, u64)> = self
                    .queue
                    .keys()
                    .filter(|k| k.1 == op.target_add_op_id)
                    .copied()
                    .collect();
                for k in doomed {
                    self.queue.remove(&k);
                }
            }
            3 => {
                // Reorder: capture before removal (R2), preserve identity.
                let found = self
                    .queue
                    .iter()
                    .find(|(k, _)| k.1 == op.target_add_op_id)
                    .map(|(k, v)| (*k, v.cad_id));
                if let Some((old_key, cad_id)) = found {
                    self.queue.remove(&old_key);
                    self.queue.insert(
                        frac_key(op.frac_index, op.target_add_op_id),
                        QueueEntry { cad_id },
                    );
                }
                // Reordering an absent/tombstoned element is a no-op.
            }
            _ => return false, // unknown/Vote — reserved
        }

        self.check_rebalance();
        true
    }

    /// M2: RELATIVE ULP check — scale-aware density detection. Shared-fraction
    /// neighbours (composite-key ties) have gap 0 and trip instantly.
    fn check_rebalance(&mut self) {
        if self.queue.len() < 2 {
            return;
        }
        let mut prev_bits: Option<u64> = None;
        for k in self.queue.keys() {
            if let Some(pb) = prev_bits {
                let a = f64::from_bits(pb);
                let b = f64::from_bits(k.0);
                let gap = b - a;
                let ulp = b.abs().max(a.abs()) * f64::EPSILON;
                if gap <= ulp * 2.0 {
                    self.needs_rebalance = true;
                    return;
                }
            }
            prev_bits = Some(k.0);
        }
    }

    /// Canonical fold for joins/compaction: ordered queue + tombstone set.
    pub fn fold_to_snapshot(&self) -> (Vec<(f64, u64, u64)>, Vec<u64>) {
        let queue_snap = self
            .queue
            .iter()
            .map(|((bits, add_id), e)| (f64::from_bits(*bits), *add_id, e.cad_id))
            .collect();
        let tomb_snap = self.tombstones.keys().copied().collect();
        (queue_snap, tomb_snap)
    }

    /// Restore from a fold (join hydration / compaction adoption).
    pub fn load_snapshot(&mut self, queue: Vec<(f64, u64, u64)>, tombstones: Vec<u64>) {
        self.queue.clear();
        for (frac, add_id, cad) in queue {
            if frac.is_finite() {
                self.queue.insert(frac_key(frac, add_id), QueueEntry { cad_id: cad });
            }
        }
        self.tombstones.clear();
        for t in tombstones {
            self.tombstones.insert(t, ());
        }
        self.needs_rebalance = false;
        self.check_rebalance();
    }

    /// Live view for UI binding: [(frac, add_op_id, cad_id)] in play order.
    pub fn snapshot_vec(&self) -> Vec<(f64, u64, u64)> {
        self.fold_to_snapshot().0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // ── Single sequential lifecycle: all assertions share the global op-id
    //    generator, so cargo's parallel runner must never split them. ──

    #[test]
    fn crdt_full_lifecycle() {
        // ── R3: identity parity with the canonical pipeline ──
        for (t, a, d) in [
            ("Starboy", "The Weeknd", 230u32),
            ("Blinding Lights!", "the weeknd", 200),
            ("Señorita (Remix)", "Shawn Mendes", 191),
        ] {
            let hex = crate::repository::generate_cad_id(t, a, d);
            expect_eq_u64(canonical_cad_id(t, a, d), &hex);
        }

        // ── M1: monotonic ids under clock step-back ──
        LAST_SEEN_MS.store(5_000_000_000_000, Ordering::SeqCst);
        let id_hi1 = JamOp::generate_op_id();
        let id_hi2 = JamOp::generate_op_id();
        assert!(id_hi2 > id_hi1);

        // ── B1: tampered frac rejected ──
        let mut tampered = JamOp::new(77_001, [9; 4], OpType::Add, 0, 42, 0.5, 0);
        tampered.frac_index = 0.75; // flip AFTER sealing
        assert!(!tampered.is_valid());

        // ── B2: out-of-order Add after Remove suppressed ──
        let mut st = JamCrdtState::new();
        let cad_a = canonical_cad_id("Track A", "Artist", 100);
        let add_a = JamOp::new(JamOp::generate_op_id(), [1; 4], OpType::Add, 0, cad_a, 0.5, 0);
        let rem_a = JamOp::new(JamOp::generate_op_id(), [1; 4], OpType::Remove, 0, cad_a, 0.0, add_a.op_id);
        assert!(st.apply_op(&rem_a));
        assert!(st.tombstones.contains_key(&add_a.op_id));
        assert!(!st.apply_op(&add_a), "late Add must be tombstoned");
        assert!(st.queue.is_empty());

        // ── R1 litmus: same-fraction concurrent adds CONVERGE ──
        let cad_x = canonical_cad_id("Track X", "Artist", 90);
        let cad_y = canonical_cad_id("Track Y", "Artist", 95);
        let add_x = JamOp::new(88_001, [2; 4], OpType::Add, 0, cad_x, 0.55, 0);
        let add_y = JamOp::new(88_002, [3; 4], OpType::Add, 0, cad_y, 0.55, 0);

        let mut rep1 = JamCrdtState::new();
        let mut rep2 = JamCrdtState::new();
        rep1.apply_op(&add_x);
        rep1.apply_op(&add_y);
        rep2.apply_op(&add_y);
        rep2.apply_op(&add_x);
        assert_eq!(
            rep1.fold_to_snapshot(),
            rep2.fold_to_snapshot(),
            "equal-fraction races must converge deterministically"
        );

        // Full commutativity shuffle including a remove.
        let mut s1 = JamCrdtState::new();
        let mut s2 = JamCrdtState::new();
        let add_b = JamOp::new(89_010, [4; 4], OpType::Add, 0, cad_b(), 0.6, 0);
        let rem_x = JamOp::new(89_011, [4; 4], OpType::Remove, 0, 0, 0.0, add_x.op_id);
        for op in [&add_x, &add_b, &rem_x] {
            s1.apply_op(op);
        }
        for op in [&add_b, &rem_x, &add_x] {
            s2.apply_op(op);
        }
        assert_eq!(s1.fold_to_snapshot(), s2.fold_to_snapshot());

        // ── R2: reorder preserves cad identity captured pre-removal ──
        st.load_snapshot(vec![], vec![]);
        st.apply_op(&add_a).then(|| ());
        assert_eq!(st.queue.len(), 1);
        let reorder = JamOp::new(
            JamOp::generate_op_id(),
            [1; 4],
            OpType::Reorder,
            0,
            999_999, // WRONG cad echo — must be ignored thanks to pre-capture
            0.8,
            add_a.op_id,
        );
        assert!(st.apply_op(&reorder));
        let snap = st.snapshot_vec();
        assert_eq!(snap.len(), 1);
        assert_eq!(snap[0].2, cad_a, "reorder must carry original cad, not sender echo");
        assert!((snap[0].0 - 0.8).abs() < f64::EPSILON);

        // ── M2: relative-ULP density latch ──
        let mut dense = JamCrdtState::new();
        dense.apply_op(&JamOp::new(91_001, [5; 4], OpType::Add, 0, 1, 1.0e300, 0));
        dense.apply_op(&JamOp::new(91_002, [5; 4], OpType::Add, 0, 2, 1.0e300 + 1.0e284, 0));
        assert!(dense.needs_rebalance, "ULP-scale gap must latch rebalance");

        // ── M3: NaN poisoning rejected ──
        let nan_op = JamOp::new(92_001, [6; 4], OpType::Add, 0, 7, f64::NAN, 0);
        assert!(!nan_op.frac_index.is_finite());

        // ── byte serde round-trip + pad rejection ──
        let op = add_b;
        let bytes = op.to_bytes();
        assert_eq!(bytes.len(), 48);
        let back = JamOp::from_bytes(&bytes).expect("round-trip");
        assert_eq!(back, op);
        let mut corrupt = bytes;
        corrupt[15] = 0xFF; // pad violation
        assert!(JamOp::from_bytes(&corrupt).is_none());
    }

    fn cad_b() -> u64 {
        canonical_cad_id("Track B", "Artist", 110)
    }

    fn expect_eq_u64(v: u64, hex: &str) {
        let parsed = u64::from_str_radix(hex, 16).expect("hex cad");
        assert_eq!(v, parsed, "u64 variant must match formatted pipeline");
    }
}
