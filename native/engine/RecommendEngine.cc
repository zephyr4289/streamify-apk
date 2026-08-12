#include "RecommendEngine.h"
#include "StreamifyDB.h"
#include "VectorStore.h"
#include <unordered_set>
#include <algorithm>
#include <cmath>

RecommendEngine& RecommendEngine::getInstance() {
    static RecommendEngine instance;
    return instance;
}

std::vector<Recommendation> RecommendEngine::getNextTracks(int currentTrackId, const std::vector<int>& recentHistory, int limit) {
    auto& db = StreamifyDB::getInstance();
    auto curTrack = db.getTrackById(currentTrackId);
    if (!curTrack) return {};

    std::unordered_set<int> excluded_tracks(recentHistory.begin(), recentHistory.end());
    excluded_tracks.insert(currentTrackId);

    auto& vecStore = VectorStore::getInstance();
    int target_offset = curTrack->vector_offset;

    std::vector<float> session_vec = vecStore.getVectorAt(target_offset);
    if (!session_vec.empty()) {
        for (float& val : session_vec) val *= 0.70f;
        
        float weights[] = {0.20f, 0.10f};
        for (size_t h = 0; h < recentHistory.size() && h < 2; ++h) {
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
    } else {
        nearestResults = vecStore.searchNearest(target_offset, 100);
    }

    // Skip/Transition DB queries for behavioral scores aren't available yet in StreamifyDB port. 
    // We will stub them as 0, or we could add them to StreamifyDB.
    // For now, let's keep it simple and just rank by similarity and BPM.
    
    std::vector<Recommendation> candidates;
    const float beta = 0.25f;

    for (const auto& res : nearestResults) {
        // Need to find track by vector_offset. We don't have getTrackByVectorOffset yet.
        // I will assume vector_offset == trackId to simplify, or write getTrackByVectorOffset.
        // But for now, we'll assume vector_offset IS the trackId - 1 or something, wait: the DB has a vector_offset column.
        // I'll add a helper to StreamifyDB:
        auto candidateTrack = db.getTrackByVectorOffset(res.vector_offset);
        if (!candidateTrack) continue;

        int track_id = candidateTrack->id;
        if (excluded_tracks.count(track_id) > 0) continue;

        float cosine_sim = res.similarity;
        float bpm_score = 1.0f;
        if (curTrack->bpm > 0 && candidateTrack->bpm > 0) {
            float bpm_diff = std::abs(curTrack->bpm - candidateTrack->bpm);
            float ratio = bpm_diff / curTrack->bpm;
            bpm_score = std::max(0.0f, 1.0f - (ratio * 5.0f)); 
        }

        float final_score = cosine_sim + (beta * bpm_score);
        candidates.push_back({track_id, final_score});
    }

    std::sort(candidates.begin(), candidates.end(), [](const Recommendation& a, const Recommendation& b) {
        return a.score > b.score;
    });

    if (candidates.size() > static_cast<size_t>(limit)) {
        candidates.resize(limit);
    }

    return candidates;
}
