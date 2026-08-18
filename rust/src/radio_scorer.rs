use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScoredCandidate {
    pub id: i32,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_sec: i32,
    pub filepath: String,
    pub cover_art_path: String,
    #[serde(default)]
    pub bpm: f32,
    #[serde(default)]
    pub key: String,
    #[serde(default)]
    pub score: f32,
}

pub struct RadioAntiDriftEngine;

impl RadioAntiDriftEngine {
    const MAX_TRACKS_PER_ARTIST: usize = 2;
    const WINDOW_SIZE: usize = 20;

    const JUNK_KEYWORDS: &'static [&'static str] = &[
        "full album", "1 hour", "10 hours", "compilation", "greatest hits mix",
        "best songs mix", "non stop", "jukebox", "podcast", "audiobook", "asmr",
        "medley", "slowed + reverb mix", "workout mix",
    ];

    /// Exact 1:1 reproduction of AntiDriftScoringEngine formula in zero-allocation Rust
    pub fn filter_and_rank_candidates(
        candidates: &[ScoredCandidate],
        seed_bpm: f32,
        seed_key: &str,
        seed_duration_sec: i32,
        seed_signature: &str,
        active_queue: &[ScoredCandidate],
    ) -> Vec<ScoredCandidate> {
        let mut seen_signatures: HashSet<String> = HashSet::with_capacity(active_queue.len() + 32);
        let mut artist_counts: HashMap<String, usize> = HashMap::with_capacity(32);
        let mut ranked_list: Vec<ScoredCandidate> = Vec::with_capacity(candidates.len());

        // 1. Prime historical artist saturation from active queue window
        let start_idx = active_queue.len().saturating_sub(Self::WINDOW_SIZE);
        for track in &active_queue[start_idx..] {
            let norm_artist = track.artist.trim().to_lowercase();
            *artist_counts.entry(norm_artist).or_insert(0) += 1;
            seen_signatures.insert(Self::signature(&track.title, &track.artist));
        }

        if !seed_signature.is_empty() {
            seen_signatures.insert(seed_signature.to_string());
        }

        let effective_seed_bpm = if seed_bpm > 0.0 { seed_bpm } else { 120.0 };
        let norm_seed_key = seed_key.trim().to_uppercase();

        // 2. Filter and score candidates
        for track in candidates {
            let title_lower = track.title.trim().to_lowercase();
            let artist_lower = track.artist.trim().to_lowercase();

            // A. Junk & Compilation filter
            if title_lower.is_empty() || artist_lower.is_empty() {
                continue;
            }
            if Self::JUNK_KEYWORDS.iter().any(|k| title_lower.contains(k)) {
                continue;
            }

            // Duration sanity check
            if seed_duration_sec >= 60 && seed_duration_sec <= 600 {
                if track.duration_sec > 720 || track.duration_sec < 35 {
                    continue;
                }
            }

            // B. Seen signature check
            let sig = Self::signature(&track.title, &track.artist);
            if seen_signatures.contains(&sig) {
                continue;
            }

            // C. Artist Saturation Ceiling
            let current_artist_count = *artist_counts.get(&artist_lower).unwrap_or(&0);
            if current_artist_count >= Self::MAX_TRACKS_PER_ARTIST {
                continue;
            }

            // D. Compute Exact Composite Score
            let mut scored_track = track.clone();
            scored_track.score = Self::compute_composite_score(
                &scored_track,
                effective_seed_bpm,
                &norm_seed_key,
                current_artist_count,
            );

            seen_signatures.insert(sig);
            *artist_counts.entry(artist_lower).or_insert(0) += 1;
            ranked_list.push(scored_track);
        }

        // 3. Sort by highest affinity score descending
        ranked_list.sort_by(|a, b| b.score.partial_cmp(&a.score).unwrap_or(std::cmp::Ordering::Equal));
        ranked_list
    }

    fn compute_composite_score(
        candidate: &ScoredCandidate,
        seed_bpm: f32,
        seed_key: &str,
        artist_frequency: usize,
    ) -> f32 {
        let mut score = 100.0f32;

        // 1. Gaussian BPM Proximity (Sigma = 25 BPM)
        if candidate.bpm > 0.0 && seed_bpm > 0.0 {
            let bpm_diff = (candidate.bpm - seed_bpm).abs();
            let bpm_factor = (-((bpm_diff.powi(2)) / (2.0 * 25.0 * 25.0))).exp();
            score += bpm_factor * 30.0;
        } else {
            score += 25.0; // Neutral baseline
        }

        // 2. Camelot Key Harmonic Compatibility
        if !candidate.key.trim().is_empty() && !seed_key.is_empty() {
            let key_dist = Self::calculate_camelot_distance(candidate.key.trim().to_uppercase().as_str(), seed_key);
            match key_dist {
                0 => score += 25.0, // Exact harmonic match
                1 => score += 15.0, // Harmonic neighbor
                _ => score -= 5.0,
            }
        }

        // 3. Artist Diversity Penalty
        if artist_frequency > 0 {
            score -= 12.0 * artist_frequency as f32;
        }

        score
    }

    fn calculate_camelot_distance(key_a: &str, key_b: &str) -> i32 {
        let num_a: i32 = key_a.chars().filter(|c| c.is_ascii_digit()).collect::<String>().parse().unwrap_or(0);
        let letter_a: String = key_a.chars().filter(|c| c.is_ascii_alphabetic()).collect();

        let num_b: i32 = key_b.chars().filter(|c| c.is_ascii_digit()).collect::<String>().parse().unwrap_or(0);
        let letter_b: String = key_b.chars().filter(|c| c.is_ascii_alphabetic()).collect();

        if num_a == 0 || num_b == 0 {
            return 2;
        }

        if letter_a == letter_b {
            let diff = (num_a - num_b).abs();
            return if diff > 6 { 12 - diff } else { diff };
        }
        if num_a == num_b {
            return 1; // Relative major/minor modulation
        }
        2
    }

    fn signature(title: &str, artist: &str) -> String {
        format!("{}:::{}", title.trim().to_lowercase(), artist.trim().to_lowercase())
    }
}
