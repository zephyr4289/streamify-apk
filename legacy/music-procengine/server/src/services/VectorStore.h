#ifndef VECTOR_STORE_H
#define VECTOR_STORE_H

#include <vector>
#include <string>
#include <shared_mutex>

struct SearchResult {
    int vector_offset;
    float similarity;
};

class VectorStore {
public:
    static VectorStore& getInstance();
    
    bool init(const std::string& bin_path, int dim = 512);
    int addVector(const std::vector<float>& vec);
    std::vector<SearchResult> searchNearest(const std::vector<float>& query_vec, int top_k = 20);
    std::vector<SearchResult> searchNearest(int target_offset, int top_k = 20);
    std::vector<float> getVectorAt(int offset);
    int getVectorCount();

private:
    VectorStore() = default;
    ~VectorStore() = default;
    VectorStore(const VectorStore&) = delete;
    VectorStore& operator=(const VectorStore&) = delete;
    
    std::string bin_path_;
    int dim_{512};
    std::vector<float> vector_data_;
    std::shared_mutex mutex_;
    
    void save();
};

#endif // VECTOR_STORE_H
