use std::collections::HashSet;

#[derive(Clone, Debug)]
pub struct CandidateTrack {
    pub cad_id: String,
    pub title: String,
    pub artist: String,
    pub spotify_vibe_score: f32, // [0.0 - 1.0]
    pub yt_novelty_score: f32,   // [0.0 - 1.0]
    pub camelot_key: String,     // e.g., "8B"
    pub last_played_epoch: u64,
}

pub struct QueueOptimizer {
    recent_artists: HashSet<String>,
}

impl QueueOptimizer {
    pub fn new() -> Self {
        Self {
            recent_artists: HashSet::new(),
        }
    }

    /// Pure 50/50 Scoring: 0.50 * Spotify + 0.50 * YouTube - Satiation + Harmonic Bonus
    pub fn score_and_rank(
        &mut self,
        current_time_epoch: u64,
        mut candidates: Vec<CandidateTrack>,
        active_camelot_key: &str,
    ) -> Vec<CandidateTrack> {
        candidates.sort_by(|a, b| {
            let score_a = self.calculate_composite_score(a, active_camelot_key, current_time_epoch);
            let score_b = self.calculate_composite_score(b, active_camelot_key, current_time_epoch);
            score_b.partial_cmp(&score_a).unwrap_or(std::cmp::Ordering::Equal)
        });

        // Track artists to prevent immediate saturation
        if let Some(top) = candidates.first() {
            self.recent_artists.insert(top.artist.to_lowercase());
        }

        candidates
    }

    fn calculate_composite_score(&self, track: &CandidateTrack, active_key: &str, now: u64) -> f32 {
        let vibe_part = 0.50 * track.spotify_vibe_score;
        let novelty_part = 0.50 * track.yt_novelty_score;

        // Exponential Satiation Penalty (tau = 3.5 hours = 12,600s)
        let delta_t = now.saturating_sub(track.last_played_epoch) as f32;
        let satiation_penalty = (-delta_t / 12600.0).exp() * 0.40;

        // Harmonic bonus for adjacent/identical Camelot wheels (e.g., 8B -> 8B, 8A, 9B, 7B)
        let harmonic_bonus = if track.camelot_key.eq_ignore_ascii_case(active_key) {
            0.08
        } else if self.is_camelot_neighbor(&track.camelot_key, active_key) {
            0.04
        } else {
            0.0
        };

        vibe_part + novelty_part - satiation_penalty + harmonic_bonus
    }

    fn is_camelot_neighbor(&self, k1: &str, k2: &str) -> bool {
        if k1.len() < 2 || k2.len() < 2 {
            return false;
        }

        let num1: u8 = k1[..k1.len() - 1].parse().unwrap_or(0);
        let letter1 = k1.chars().last().unwrap_or(' ').to_ascii_uppercase();

        let num2: u8 = k2[..k2.len() - 1].parse().unwrap_or(0);
        let letter2 = k2.chars().last().unwrap_or(' ').to_ascii_uppercase();

        if num1 == 0 || num2 == 0 {
            return false;
        }

        (num1 == num2 && letter1 != letter2)
            || ((num1 as i16 - num2 as i16).abs() == 1 && letter1 == letter2)
            || ((num1 == 1 && num2 == 12) || (num1 == 12 && num2 == 1)) && letter1 == letter2
    }
}
