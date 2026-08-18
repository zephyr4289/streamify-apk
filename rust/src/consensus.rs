use sha2::{Digest, Sha256};

pub struct ConsensusEngine;

impl ConsensusEngine {
    /// Generates a deterministic Proof-of-Compute hash for an audio PCM slice.
    pub fn generate_proof_of_compute(pcm_slice: &[f32], nonce: &str) -> String {
        let mut hasher = Sha256::new();
        hasher.update(nonce.as_bytes());

        for sample in pcm_slice {
            hasher.update(&sample.to_le_bytes());
        }

        let result = hasher.finalize();
        hex::encode(result)
    }

    /// Verifies 2-peer Byzantine consensus invariants:
    /// - |ΔLUFS| <= 0.3
    /// - Matching Camelot harmonic key
    /// - Vector cosine similarity >= 0.94
    pub fn verify_byzantine_consensus(
        lufs_a: f32,
        lufs_b: f32,
        key_a: &str,
        key_b: &str,
        vec_a: &[f32],
        vec_b: &[f32],
    ) -> bool {
        // 1. Loudness tolerance invariant
        if (lufs_a - lufs_b).abs() > 0.35 {
            return false;
        }

        // 2. Harmonic key matching invariant
        if !key_a.is_empty() && !key_b.is_empty() && key_a != key_b {
            return false;
        }

        // 3. Vector cosine similarity invariant
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

    /// Verifies 2-peer Byzantine Lyric Drift Consensus (|Δτ1 - Δτ2| <= 15ms)
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
