use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};

type HmacSha256 = Hmac<Sha256>;

#[derive(Debug, Clone)]
pub struct AcousticProofPayload {
    pub track_id: String,
    pub subband_energies: [f32; 16],
    pub duration_sec: u32,
    pub nonce: u64,
}

#[derive(Debug, Clone)]
pub struct MeshCandidateSubmission {
    pub node_id: String,
    pub lufs: f32,
    pub camelot_key: String,
    pub vector: [f32; 128],
    pub proof_digest: [u8; 32],
}

pub struct ByzantineConsensusEngine;

impl ByzantineConsensusEngine {
    /// Generates HMAC-SHA256 Proof-of-Acoustic-Compute digest
    pub fn generate_proof(payload: &AcousticProofPayload, secret_key: &[u8]) -> [u8; 32] {
        let mut mac = HmacSha256::new_from_slice(secret_key).expect("HMAC can take key of any size");

        mac.update(payload.track_id.as_bytes());
        for energy in &payload.subband_energies {
            let quantized = (*energy * 1000.0) as i32;
            mac.update(&quantized.to_le_bytes());
        }
        mac.update(&payload.duration_sec.to_le_bytes());
        mac.update(&payload.nonce.to_le_bytes());

        let result = mac.finalize();
        let mut out = [0u8; 32];
        out.copy_from_slice(&result.into_bytes()[..32]);
        out
    }

    /// Verifies 2-Peer Byzantine Consensus Threshold:
    /// 1. Anti-collusion (distinct node IDs)
    /// 2. |ΔLUFS| <= 0.3
    /// 3. Matching Camelot Key
    /// 4. Cosine Similarity >= 0.94
    pub fn verify_peer_consensus(
        peer1: &MeshCandidateSubmission,
        peer2: &MeshCandidateSubmission,
    ) -> bool {
        // Rule 1: Anti-collusion (different submitting edge nodes)
        if peer1.node_id == peer2.node_id {
            return false;
        }

        // Rule 2: Integrated Loudness Variance Tolerance (|ΔLUFS| <= 0.3)
        if (peer1.lufs - peer2.lufs).abs() > 0.3 {
            return false;
        }

        // Rule 3: Exact Harmonic Key Match
        if peer1.camelot_key != peer2.camelot_key {
            return false;
        }

        // Rule 4: High-Dimensional Acoustic Embedding Similarity (Cosine Sim >= 0.94)
        let sim = compute_cosine_similarity_128(&peer1.vector, &peer2.vector);
        sim >= 0.94
    }
}

pub fn compute_cosine_similarity_128(a: &[f32; 128], b: &[f32; 128]) -> f32 {
    let mut dot = 0.0f32;
    let mut norm_a = 0.0f32;
    let mut norm_b = 0.0f32;

    for i in 0..128 {
        dot += a[i] * b[i];
        norm_a += a[i] * a[i];
        norm_b += b[i] * b[i];
    }

    let denom = (norm_a.sqrt() * norm_b.sqrt()).max(1e-6);
    dot / denom
}

// Backward-compatible ConsensusEngine helper
pub struct ConsensusEngine;

impl ConsensusEngine {
    pub fn generate_proof_of_compute(pcm_slice: &[f32], nonce: &str) -> String {
        let mut hasher = Sha256::new();
        hasher.update(nonce.as_bytes());

        for sample in pcm_slice {
            hasher.update(&sample.to_le_bytes());
        }

        let result = hasher.finalize();
        hex::encode(result)
    }

    pub fn verify_byzantine_consensus(
        lufs_a: f32,
        lufs_b: f32,
        key_a: &str,
        key_b: &str,
        vec_a: &[f32],
        vec_b: &[f32],
    ) -> bool {
        if (lufs_a - lufs_b).abs() > 0.35 {
            return false;
        }

        if !key_a.is_empty() && !key_b.is_empty() && key_a != key_b {
            return false;
        }

        if !vec_a.is_empty() && vec_a.len() == vec_b.len() {
            let sim = Self::cosine_similarity(vec_a, vec_b);
            if sim < 0.94 {
                return false;
            }
        }

        true
    }

    pub fn cosine_similarity(a: &[f32], b: &[f32]) -> f32 {
        let mut dot = 0.0f32;
        let mut norm_a = 0.0f32;
        let mut norm_b = 0.0f32;

        for (x, y) in a.iter().zip(b.iter()) {
            dot += x * y;
            norm_a += x * x;
            norm_b += y * y;
        }

        let denom = (norm_a.sqrt() * norm_b.sqrt()).max(1e-9);
        (dot / denom).clamp(-1.0, 1.0)
    }

    pub fn verify_lyric_drift_consensus(drift_a_ms: i32, drift_b_ms: i32) -> bool {
        (drift_a_ms - drift_b_ms).abs() <= 15
    }
}

mod hex {
    pub fn encode(bytes: impl AsRef<[u8]>) -> String {
        bytes
            .as_ref()
            .iter()
            .map(|b| format!("{:02x}", b))
            .collect()
    }
}
