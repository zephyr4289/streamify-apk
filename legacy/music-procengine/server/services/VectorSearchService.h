#ifndef VECTOR_SEARCH_SERVICE_H
#define VECTOR_SEARCH_SERVICE_H

#include <string>
#include <vector>
#include <shared_mutex>
#include <sys/stat.h>
#include <utility>

struct SearchResult {
    int vector_offset;
    float similarity;
};

class VectorSearchService {
public:
    static VectorSearchService& getInstance();

    bool init(const std::string& bin_path, const std::string& faiss_index_path, int dim = 512);
    bool loadVectors();
    int getVectorCount();

    std::vector<float> getVectorAt(int offset);
    std::vector<SearchResult> searchNearest(int target_offset, int top_k = 20);
    std::vector<SearchResult> searchNearestByVector(const std::vector<float>& query_vec, int top_k = 20);

private:
    VectorSearchService() = default;
    ~VectorSearchService() = default;
    VectorSearchService(const VectorSearchService&) = delete;
    VectorSearchService& operator=(const VectorSearchService&) = delete;

    float computeDotProduct(const float* vecA, const float* vecB, int dim);

    std::string bin_path_;
    std::string faiss_index_path_;
    int dim_{512};
    
    std::vector<float> vector_data_;
    int total_vectors_{0};
    std::shared_mutex vector_mutex_;
    time_t last_mtime_{0};
};

#endif // VECTOR_SEARCH_SERVICE_H
