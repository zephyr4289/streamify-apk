//! Streamify Jam Governor v3 — pure election & death-pivot math (P8/P9/P3.3).
//!
//! Units standardized to MILLISECONDS everywhere (matches Phase-2 columns).
//! Epoch authority lives in the SERVER (`jam_takeover` returns the fencing
//! token); this unit deliberately contains no epoch math.
//!
//! Pure functions only — no globals, no FFI state, parallel-safe tests.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PivotResult {
    /// Extrapolated position in ms; safe to hard-seek.
    Ok(i64),
    /// Extrapolation reached/passed track end → normal advance instead.
    BeyondEnd,
    /// Track identity differs from the dead host's → full TRACK_CHANGE first.
    Mismatch,
}

pub struct JamGovernor;

impl JamGovernor {
    /// U3: deterministic successor election. Members are UUID strings;
    /// comparison is on LOWERCASE FIXED-WIDTH HEX — byte-order equivalent
    /// across every device, immune to locale collation.
    ///
    /// `host_lease_expired == false` → current host retains authority.
    /// Otherwise the lowest non-host member wins; `None` when the host was
    /// alone (room should end rather than elect a ghost).
    pub fn elect_successor(
        participant_ids: &[String],
        host_id: &str,
        host_lease_expired: bool,
    ) -> Option<String> {
        if !host_lease_expired {
            return Some(host_id.to_lowercase());
        }

        let mut min_id: Option<String> = None;
        for id in participant_ids {
            if id.as_str() == host_id {
                continue; // dead host excluded
            }
            let lower = id.to_lowercase();
            match &min_id {
                Some(cur) if lower >= *cur => {}
                _ => min_id = Some(lower),
            }
        }
        min_id
    }

    /// 3.3 Death Pivot: extrapolate the dead host's last known trajectory to
    /// "now" so guests experience zero discontinuity when authority moves.
    ///
    /// * U5 — `track_matches == false` → [`PivotResult::Mismatch`] (caller
    ///   must run a full TRACK_CHANGE before claiming authority).
    /// * Negative elapsed (clock skew / reordered stamps) → hold at the last
    ///   known position rather than rewinding.
    /// * U6 — result at/after track end → [`PivotResult::BeyondEnd`] (caller
    ///   performs a normal advance; pivot skipped).
    pub fn extrapolate_pivot(
        last_known_pos_ms: i64,
        last_tick_mono_ms: i64,
        current_synced_mono_ms: i64,
        track_duration_ms: i64,
        track_matches: bool,
    ) -> PivotResult {
        if !track_matches {
            return PivotResult::Mismatch;
        }

        let elapsed = current_synced_mono_ms - last_tick_mono_ms;
        if elapsed < 0 {
            return PivotResult::Ok(last_known_pos_ms.max(0));
        }

        let extrapolated = last_known_pos_ms + elapsed;

        if track_duration_ms > 0 && extrapolated >= track_duration_ms {
            PivotResult::BeyondEnd
        } else {
            PivotResult::Ok(extrapolated.max(0))
        }
    }

    /// Advisory check used by guests BEFORE calling the server RPC: am I the
    /// member the hybrid contract would prefer right now?
    /// (Server remains the sole grantor; this only avoids doomed RPC calls.)
    pub fn is_advisory_successor(
        participant_ids: &[String],
        host_id: &str,
        self_id: &str,
        self_recently_seen: bool,
    ) -> bool {
        match Self::elect_successor(participant_ids, host_id, true) {
            None => false,
            Some(successor) => successor == self_id.to_lowercase() && self_recently_seen,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MEMBERS: [&str; 4] = ["b3d1c2", "a1b2c3", "f4e5d6", "c4d5e6"];

    #[test]
    fn election_full_matrix() {
        // Healthy lease → host retains.
        assert_eq!(
            JamGovernor::elect_successor(&MEMBERS.map(String::from), "b3d1c2", false),
            Some("b3d1c2".to_string())
        );

        // Expired → lowest non-host wins.
        assert_eq!(
            JamGovernor::elect_successor(&MEMBERS.map(String::from), "b3d1c2", true),
            Some("a1b2c3".to_string())
        );

        // Host was already lowest → next lowest inherits.
        assert_eq!(
            JamGovernor::elect_successor(&MEMBERS.map(String::from), "a1b2c3", true),
            Some("b3d1c2".to_string())
        );

        // Host was alone → room ends (no ghost authority).
        let solo = vec!["b3d1c2".to_string()];
        assert_eq!(JamGovernor::elect_successor(&solo, "b3d1c2", true), None);

        // Case-insensitivity: mixed-case entries compare byte-order-safe.
        let mixed = vec!["B3D1C2".to_string(), "A1B2C3".to_string()];
        assert_eq!(
            JamGovernor::elect_successor(&mixed, "B3D1C2", true),
            Some("a1b2c3".to_string())
        );
    }

    #[test]
    fn advisory_matches_election() {
        assert!(JamGovernor::is_advisory_successor(
            &MEMBERS.map(String::from),
            "b3d1c2",
            "A1B2C3",
            true
        ));
        assert!(!JamGovernor::is_advisory_successor(
            &MEMBERS.map(String::from),
            "b3d1c2",
            "f4e5d6",
            true
        ));
        // Stale self (not recently seen) never advises a claim.
        assert!(!JamGovernor::is_advisory_successor(
            &MEMBERS.map(String::from),
            "b3d1c2",
            "a1b2c3",
            false
        ));
    }

    #[test]
    fn pivot_full_matrix() {
        let duration = 200_000i64;

        // Perfect forward extrapolation.
        assert_eq!(
            JamGovernor::extrapolate_pivot(10_000, 0, 5_000, duration, true),
            PivotResult::Ok(15_000)
        );

        // Exact boundary → normal advance.
        assert_eq!(
            JamGovernor::extrapolate_pivot(195_000, 0, 5_000, duration, true),
            PivotResult::BeyondEnd
        );

        // Far past end → normal advance.
        assert_eq!(
            JamGovernor::extrapolate_pivot(180_000, 0, 30_000_000, duration, true),
            PivotResult::BeyondEnd
        );

        // Negative skew → hold at last known position.
        assert_eq!(
            JamGovernor::extrapolate_pivot(10_000, 5_000, 4_000, duration, true),
            PivotResult::Ok(10_000)
        );

        // Unknown-duration tracks (0) never report BeyondEnd.
        assert_eq!(
            JamGovernor::extrapolate_pivot(150_000, 0, 160_000, 0, true),
            PivotResult::Ok(310_000)
        );

        // Track mismatch short-circuits everything.
        assert_eq!(
            JamGovernor::extrapolate_pivot(0, 0, 0, duration, false),
            PivotResult::Mismatch
        );
    }
}
