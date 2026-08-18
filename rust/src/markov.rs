use std::collections::HashMap;

pub struct MarkovEngine {
    // 1st-order transition counts: Map<(from_id, to_id), count>
    first_order: HashMap<(i32, i32), u32>,
    // 2nd-order transition counts: Map<(a_id, b_id, c_id), count>
    second_order: HashMap<(i32, i32, i32), u32>,
    // Recent play timestamps for satiation decay: Map<track_id, Vec<u64>>
    play_history: HashMap<i32, Vec<u64>>,
}

impl MarkovEngine {
    pub fn new() -> Self {
        Self {
            first_order: HashMap::with_capacity(512),
            second_order: HashMap::with_capacity(512),
            play_history: HashMap::with_capacity(256),
        }
    }

    pub fn record_transition(&mut self, track_a: i32, track_b: i32, track_c: i32, timestamp_sec: u64) {
        if track_a > 0 && track_b > 0 {
            *self.first_order.entry((track_a, track_b)).or_insert(0) += 1;
        }
        if track_a > 0 && track_b > 0 && track_c > 0 {
            *self.second_order.entry((track_a, track_b, track_c)).or_insert(0) += 1;
        }
        if track_c > 0 {
            self.play_history.entry(track_c).or_insert_with(Vec::new).push(timestamp_sec);
        }
    }

    /// Computes blended 2nd-order + 1st-order Markov transition probability with Dirichlet smoothing
    pub fn get_transition_probability(&self, track_a: i32, track_b: i32, candidate_c: i32, alpha: f32) -> f32 {
        let count_2nd = *self.second_order.get(&(track_a, track_b, candidate_c)).unwrap_or(&0) as f32;
        let count_1st = *self.first_order.get(&(track_b, candidate_c)).unwrap_or(&0) as f32;

        let prob_2nd = if count_2nd > 0.0 { count_2nd / (count_2nd + 5.0) } else { 0.0 };
        let prob_1st = if count_1st > 0.0 { count_1st / (count_1st + 10.0) } else { 0.0 };

        (alpha * prob_2nd) + ((1.0 - alpha) * prob_1st)
    }

    /// Computes satiation penalty: exponential repetition suppression over 4-hour half-life
    pub fn compute_satiation_penalty(&self, track_id: i32, current_time_sec: u64) -> f32 {
        let history = match self.play_history.get(&track_id) {
            Some(h) => h,
            None => return 0.0,
        };

        let mut penalty = 0.0f32;
        let half_life_sec = 14400.0f32; // 4 hours

        for &ts in history {
            if ts <= current_time_sec {
                let age = (current_time_sec - ts) as f32;
                penalty += (-0.693 * age / half_life_sec).exp();
            }
        }

        penalty.clamp(0.0, 5.0)
    }
}
