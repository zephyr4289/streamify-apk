#ifndef RECOMMEND_ENGINE_H
#define RECOMMEND_ENGINE_H

#include <vector>

struct Recommendation {
    int trackId;
    float score;
};

class RecommendEngine {
public:
    static RecommendEngine& getInstance();

    std::vector<Recommendation> getNextTracks(int currentTrackId, const std::vector<int>& recentHistory, int limit);

private:
    RecommendEngine() = default;
    ~RecommendEngine() = default;
    RecommendEngine(const RecommendEngine&) = delete;
    RecommendEngine& operator=(const RecommendEngine&) = delete;
};

#endif // RECOMMEND_ENGINE_H
