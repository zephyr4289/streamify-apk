#ifndef RECOMMEND_ENGINE_H
#define RECOMMEND_ENGINE_H

#include <vector>
#include <mutex>

struct Recommendation {
    int trackId;
    float score;
};

class RecommendEngine {
public:
    static RecommendEngine& getInstance();

    void updateSessionVector(int trackId, float alpha = 0.45f);
    std::vector<Recommendation> getSessionRecommendations(int limit = 50);
    std::vector<Recommendation> getLongTermRecommendations(int userId = 1, int limit = 50);
    std::vector<Recommendation> getNextTracks(int currentTrackId, const std::vector<int>& recentHistory, int limit);

private:
    RecommendEngine() = default;
    ~RecommendEngine() = default;
    RecommendEngine(const RecommendEngine&) = delete;
    RecommendEngine& operator=(const RecommendEngine&) = delete;

    std::vector<float> session_vector_;
    std::mutex session_mutex_;
};

#endif // RECOMMEND_ENGINE_H
