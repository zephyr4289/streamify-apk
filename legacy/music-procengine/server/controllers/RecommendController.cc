#include "RecommendController.h"
#include "../services/DatabaseService.h"
#include "../src/services/VectorStore.h"
#include <json/json.h>
#include <sstream>
#include <unordered_set>
#include <algorithm>
#include <cmath>

static std::vector<int> parseRecentHistory(const std::string& str) {
    std::vector<int> list;
    std::stringstream ss(str);
    std::string item;
    while (std::getline(ss, item, ',')) {
        if (!item.empty()) {
            try {
                int id = std::stoi(item);
                if (std::find(list.begin(), list.end(), id) == list.end()) {
                    list.push_back(id);
                }
            } catch (...) {}
        }
    }
    return list;
}

void RecommendController::getRecommendations(const drogon::HttpRequestPtr& req,
                                             std::function<void(const drogon::HttpResponsePtr&)>&& callback) {
    const auto& params = req->getParameters();
    auto cur_it = params.find("current_track_id");
    if (cur_it == params.end()) {
        Json::Value errorJson;
        errorJson["error"] = "Missing required query parameter: current_track_id";
        auto resp = drogon::HttpResponse::newHttpJsonObjectResponse(errorJson);
        resp->setStatusCode(drogon::k400BadRequest);
        callback(resp);
        return;
    }

    int current_track_id = 0;
    try {
        current_track_id = std::stoi(cur_it->second);
    } catch (...) {
        Json::Value errorJson;
        errorJson["error"] = "Invalid current_track_id parameter";
        auto resp = drogon::HttpResponse::newHttpJsonObjectResponse(errorJson);
        resp->setStatusCode(drogon::k400BadRequest);
        callback(resp);
        return;
    }

    int limit = 5;
    auto lim_it = params.find("limit");
    if (lim_it != params.end()) {
        try {
            limit = std::max(1, std::stoi(lim_it->second));
        } catch (...) {}
    }

    std::vector<int> recent_history;
    auto rec_it = params.find("recent_history");
    if (rec_it != params.end()) {
        recent_history = parseRecentHistory(rec_it->second);
    }
    // Also add current_track_id to excluded set
    std::unordered_set<int> excluded_tracks(recent_history.begin(), recent_history.end());
    excluded_tracks.insert(current_track_id);

    auto& db = DatabaseService::getInstance();
    auto curTrack = db.getTrackById(current_track_id);
    if (!curTrack) {
        Json::Value errorJson;
        errorJson["error"] = "Specified current_track_id does not exist";
        auto resp = drogon::HttpResponse::newHttpJsonObjectResponse(errorJson);
        resp->setStatusCode(drogon::k404NotFound);
        callback(resp);
        return;
    }

    auto& vecService = VectorStore::getInstance();
    int target_offset = curTrack->vector_offset;

    // Stage 1: Retrieval (Candidate Generation) using Session Vector
    // Build a Session Vector with exponential decay: 0.70 * current + 0.20 * prev_1 + 0.10 * prev_2
    std::vector<float> session_vec = vecService.getVectorAt(target_offset);
    if (!session_vec.empty()) {
        for (float& val : session_vec) val *= 0.70f;
        
        float weights[] = {0.20f, 0.10f};
        for (size_t h = 0; h < recent_history.size() && h < 2; ++h) {
            int hist_id = recent_history[h];
            if (hist_id == current_track_id) continue;
            auto histTrack = db.getTrackById(hist_id);
            if (histTrack) {
                auto v = vecService.getVectorAt(histTrack->vector_offset);
                if (v.size() == session_vec.size()) {
                    for (size_t i = 0; i < session_vec.size(); ++i) {
                        session_vec[i] += weights[h] * v[i];
                    }
                }
            }
        }
        // L2 Normalize the combined session vector
        float norm = 0.0f;
        for (float val : session_vec) norm += val * val;
        if (norm > 1e-9f) {
            norm = std::sqrt(norm);
            for (float& val : session_vec) val /= norm;
        }
    }

    // Query Vector Index for top 100 nearest neighbors to the Session Vector
    std::vector<SearchResult> nearestResults;
    if (!session_vec.empty()) {
        nearestResults = vecService.searchNearest(session_vec, 100);
    } else {
        nearestResults = vecService.searchNearest(target_offset, 100); // Fallback
    }

    // Fetch transition counts from SQLite for current_track_id (Behavioral)
    auto transitionCounts = db.getTransitionCountsFrom(current_track_id);
    int total_transitions = 0;
    for (const auto& [to_id, count] : transitionCounts) {
        total_transitions += count;
    }

    // Fetch skip counts from SQLite for current_track_id (Negative Feedback)
    auto skipCounts = db.getSkipCountsFrom(current_track_id);
    int total_skips = 0;
    for (const auto& [to_id, count] : skipCounts) {
        total_skips += count;
    }

    const float alpha = 0.35f; // Behavioral weight
    const float beta = 0.25f;  // BPM/Harmonic flow weight
    const float gamma = 0.50f; // Skip penalty weight

    struct Candidate {
        int track_id;
        std::string title;
        std::string artist;
        double score;
    };

    std::vector<Candidate> candidates;

    // Stage 2: Ranking (The "Extreme Accuracy" Filter)
    for (const auto& res : nearestResults) {
        auto candidateTrack = db.getTrackByVectorOffset(res.vector_offset);
        if (!candidateTrack) continue;

        int track_id = candidateTrack->id;
        // Filter out recent history
        if (excluded_tracks.count(track_id) > 0) {
            continue;
        }

        // 1. Content Similarity Score
        float cosine_sim = res.similarity;

        // 2. Behavioral Transition Probability Score
        float norm_trans_prob = 0.0f;
        auto trans_it = transitionCounts.find(track_id);
        if (trans_it != transitionCounts.end() && total_transitions > 0) {
            norm_trans_prob = static_cast<float>(trans_it->second) / static_cast<float>(total_transitions);
        }

        // 3. Skip Penalty Score
        float skip_penalty = 0.0f;
        auto skip_it = skipCounts.find(track_id);
        if (skip_it != skipCounts.end() && total_skips > 0) {
            skip_penalty = static_cast<float>(skip_it->second) / static_cast<float>(total_skips);
        }

        // 4. Rhythmic Flow Score (BPM Proximity)
        float bpm_score = 1.0f;
        if (curTrack->bpm > 0 && candidateTrack->bpm > 0) {
            float bpm_diff = std::abs(curTrack->bpm - candidateTrack->bpm);
            // Penalize differences > 5 BPM, smoothly decay
            float ratio = bpm_diff / curTrack->bpm;
            bpm_score = std::max(0.0f, 1.0f - (ratio * 5.0f)); 
        }

        // Final Composite Ranking Score
        float final_score = cosine_sim + (alpha * norm_trans_prob) + (beta * bpm_score) - (gamma * skip_penalty);

        candidates.push_back({
            track_id,
            candidateTrack->title,
            candidateTrack->artist,
            round(final_score * 10000.0) / 10000.0
        });
    }

    // Sort by FinalScore descending
    std::sort(candidates.begin(), candidates.end(), [](const Candidate& a, const Candidate& b) {
        return a.score > b.score;
    });

    if (candidates.size() > static_cast<size_t>(limit)) {
        candidates.resize(limit);
    }

    // Step 4: Return JSON response array
    Json::Value root(Json::arrayValue);
    for (const auto& cand : candidates) {
        Json::Value item;
        item["id"] = cand.track_id;
        item["title"] = cand.title;
        item["artist"] = cand.artist;
        item["score"] = cand.score;
        root.append(item);
    }

    auto resp = drogon::HttpResponse::newHttpJsonObjectResponse(root);
    resp->setStatusCode(drogon::k200OK);
    callback(resp);
}
