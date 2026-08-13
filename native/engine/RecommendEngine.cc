#include "RecommendEngine.h"
#include "StreamifyDB.h"
#include "VectorStore.h"
#include <unordered_set>
#include <algorithm>
#include <cmath>
#include <cctype>

static bool equalsIgnoreCase(const std::string& a, const std::string& b) {
    if (a.length() != b.length()) return false;
    for (size_t i = 0; i < a.length(); ++i) {
        if (std::tolower(static_cast<unsigned char>(a[i])) != std::tolower(static_cast<unsigned char>(b[i]))) {
            return false;
        }
    }
    return true;
}

RecommendEngine& RecommendEngine::getInstance() {
    static RecommendEngine instance;
    return instance;
}

std::vector<Recommendation> RecommendEngine::getNextTracks(int currentTrackId, const std::vector<int>& recentHistory, int limit) {
    auto& db = StreamifyDB::getInstance();
    auto curTrack = db.getTrackById(currentTrackId);
    if (!curTrack) {
        // Cold-start Fallback: If current track not found by ID, return top tracks in DB
        auto allTracks = db.getAllTracks();
        std::vector<Recommendation> fallback;
        for (const auto& t : allTracks) {
            if (t.id != currentTrackId) {
                fallback.push_back({t.id, 1.0f});
                if (fallback.size() >= static_cast<size_t>(limit)) break;
            }
        }
        return fallback;
    }

    std::unordered_set<int> excluded_tracks(recentHistory.begin(), recentHistory.end());
    excluded_tracks.insert(currentTrackId);

    // Fetch user liked tracks for affinity boosting (user_id = 1)
    std::vector<int> likedIdsVec = db.getUserLikedTrackIds(1);
    std::unordered_set<int> liked_set(likedIdsVec.begin(), likedIdsVec.end());

    auto& vecStore = VectorStore::getInstance();
    int target_offset = curTrack->vector_offset;

    std::vector<float> session_vec = vecStore.getVectorAt(target_offset);
    if (!session_vec.empty()) {
        for (float& val : session_vec) val *= 0.70f;
        
        float weights[] = {0.45f, 0.15f, 0.05f}; // temporal decay
        for (size_t h = 0; h < recentHistory.size() && h < 3; ++h) {
            int hist_id = recentHistory[h];
            if (hist_id == currentTrackId) continue;
            auto histTrack = db.getTrackById(hist_id);
            if (histTrack) {
                auto v = vecStore.getVectorAt(histTrack->vector_offset);
                if (v.size() == session_vec.size()) {
                    for (size_t i = 0; i < session_vec.size(); ++i) {
                        session_vec[i] += weights[h] * v[i];
                    }
                }
            }
        }
        float norm = 0.0f;
        for (float val : session_vec) norm += val * val;
        if (norm > 1e-9f) {
            norm = std::sqrt(norm);
            for (float& val : session_vec) val /= norm;
        }
    }

    std::vector<SearchResult> nearestResults;
    if (!session_vec.empty()) {
        nearestResults = vecStore.searchNearest(session_vec, 100);
    } else if (target_offset >= 0) {
        nearestResults = vecStore.searchNearest(target_offset, 100);
    }

    std::vector<Recommendation> candidates;
    const float beta_bpm = 0.20f;
    const float artist_boost = 0.25f;
    const float key_boost = 0.15f;
    const float liked_boost = 0.35f;
    const float skip_penalty_factor = 0.15f;

    std::unordered_set<int> candidate_ids_added;

    if (!nearestResults.empty()) {
        for (const auto& res : nearestResults) {
            auto candidateTrack = db.getTrackByVectorOffset(res.vector_offset);
            if (!candidateTrack) continue;

            int track_id = candidateTrack->id;
            if (excluded_tracks.count(track_id) > 0) continue;
            if (candidate_ids_added.count(track_id) > 0) continue;

            float cosine_sim = res.similarity;
            
            // BPM Tempo Match
            float bpm_score = 1.0f;
            if (curTrack->bpm > 0 && candidateTrack->bpm > 0) {
                float bpm_diff = std::abs(curTrack->bpm - candidateTrack->bpm);
                float ratio = bpm_diff / curTrack->bpm;
                bpm_score = std::max(0.0f, 1.0f - (ratio * 5.0f)); 
            }

            // Artist Similarity Bonus
            float a_boost = 0.0f;
            if (!curTrack->artist.empty() && equalsIgnoreCase(curTrack->artist, candidateTrack->artist)) {
                a_boost = artist_boost;
            }

            // Key Compatibility Bonus
            float k_boost = 0.0f;
            if (!curTrack->key.empty() && equalsIgnoreCase(curTrack->key, candidateTrack->key)) {
                k_boost = key_boost;
            }

            // Liked Song Affinity Boost
            float l_boost = 0.0f;
            if (liked_set.count(track_id) > 0) {
                l_boost = liked_boost;
            }

            // Transition Probability Boost & Skip Penalty
            float transition_prob = db.getTransitionProbability(1, currentTrackId, track_id);
            int skip_count = db.getTrackTotalSkipCount(1, track_id);
            float skip_penalty = std::min(1.0f, skip_count * skip_penalty_factor);

            float final_score = cosine_sim + (beta_bpm * bpm_score) + a_boost + k_boost + l_boost + (transition_prob * 0.30f) - skip_penalty;
            candidates.push_back({track_id, final_score});
            candidate_ids_added.insert(track_id);
        }
    }

    // COLD-START / METADATA FALLBACK:
    if (candidates.size() < static_cast<size_t>(limit)) {
        auto allTracks = db.getAllTracks();
        for (const auto& candidateTrack : allTracks) {
            int track_id = candidateTrack.id;
            if (excluded_tracks.count(track_id) > 0) continue;
            if (candidate_ids_added.count(track_id) > 0) continue;

            float base_score = 0.50f;
            if (!curTrack->artist.empty() && equalsIgnoreCase(curTrack->artist, candidateTrack.artist)) {
                base_score += artist_boost;
            }
            if (!curTrack->key.empty() && equalsIgnoreCase(curTrack->key, candidateTrack.key)) {
                base_score += key_boost;
            }
            if (liked_set.count(track_id) > 0) {
                base_score += liked_boost;
            }

            candidates.push_back({track_id, base_score});
            candidate_ids_added.insert(track_id);
        }
    }

    // Sort by final score descending
    std::sort(candidates.begin(), candidates.end(), [](const Recommendation& a, const Recommendation& b) {
        return a.score > b.score;
    });

    // Diversity Injection
    std::vector<Recommendation> diverse_candidates;
    std::unordered_set<std::string> artists_in_top;
    
    for (const auto& rec : candidates) {
        auto cTrack = db.getTrackById(rec.trackId);
        if (!cTrack) continue;
        
        std::string lowerArtist = cTrack->artist;
        std::transform(lowerArtist.begin(), lowerArtist.end(), lowerArtist.begin(), ::tolower);
        
        if (diverse_candidates.size() < 5) {
            if (artists_in_top.size() > 0 && artists_in_top.count(lowerArtist) > 0) {
                // If we already have this artist and we need more diversity, we might skip it 
                // but let's just make sure the 5th item is a different artist if the first 4 are same.
                if (diverse_candidates.size() == 4 && artists_in_top.size() == 1) {
                    continue; // skip this track, find another artist
                }
            }
        }
        
        diverse_candidates.push_back(rec);
        artists_in_top.insert(lowerArtist);
        
        if (diverse_candidates.size() >= static_cast<size_t>(limit)) break;
    }
    
    // Fallback if diversity filtering removed too many
    while (diverse_candidates.size() < static_cast<size_t>(limit) && diverse_candidates.size() < candidates.size()) {
        for (const auto& rec : candidates) {
            auto it = std::find_if(diverse_candidates.begin(), diverse_candidates.end(),
                [&](const Recommendation& r) { return r.trackId == rec.trackId; });
            if (it == diverse_candidates.end()) {
                diverse_candidates.push_back(rec);
            }
            if (diverse_candidates.size() >= static_cast<size_t>(limit)) break;
        }
    }

    return diverse_candidates;
}
