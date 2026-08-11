#include "VectorStore.h"
#if defined(__AVX2__)
#include <immintrin.h>
#endif
#include <fstream>
#include <algorithm>
#include <iostream>
#include <mutex>
#include <shared_mutex>

VectorStore& VectorStore::getInstance() {
    static VectorStore instance;
    return instance;
}

bool VectorStore::init(const std::string& bin_path, int dim) {
    std::unique_lock<std::shared_mutex> lock(mutex_);
    bin_path_ = bin_path;
    dim_ = dim;
    vector_data_.clear();
    
    std::ifstream file(bin_path_, std::ios::binary | std::ios::ate);
    if (file.is_open()) {
        std::streamsize size = file.tellg();
        file.seekg(0, std::ios::beg);
        size_t total_floats = size / sizeof(float);
        size_t valid_floats = (total_floats / dim_) * dim_;
        vector_data_.resize(valid_floats);
        file.read(reinterpret_cast<char*>(vector_data_.data()), valid_floats * sizeof(float));
        std::cout << "[VectorStore] Loaded " << (vector_data_.size() / dim_) << " vectors from " << bin_path_ << std::endl;
    }
    return true;
}

void VectorStore::save() {
    std::ofstream file(bin_path_, std::ios::binary);
    if (file.is_open()) {
        file.write(reinterpret_cast<const char*>(vector_data_.data()), vector_data_.size() * sizeof(float));
    }
}

int VectorStore::addVector(const std::vector<float>& vec) {
    if (vec.size() != static_cast<size_t>(dim_)) {
        return -1;
    }
    std::unique_lock<std::shared_mutex> lock(mutex_);
    int offset = vector_data_.size() / dim_;
    vector_data_.insert(vector_data_.end(), vec.begin(), vec.end());
    
    // Safely append only the new vector slice to disk without truncating existing data
    std::ofstream file(bin_path_, std::ios::binary | std::ios::app);
    if (file.is_open()) {
        file.write(reinterpret_cast<const char*>(vec.data()), vec.size() * sizeof(float));
    }
    return offset;
}

std::vector<float> VectorStore::getVectorAt(int offset) {
    std::shared_lock<std::shared_mutex> lock(mutex_);
    if (offset < 0 || static_cast<size_t>(offset * dim_ + dim_) > vector_data_.size()) return {};
    auto start = vector_data_.begin() + offset * dim_;
    return std::vector<float>(start, start + dim_);
}

int VectorStore::getVectorCount() {
    std::shared_lock<std::shared_mutex> lock(mutex_);
    return vector_data_.size() / dim_;
}

std::vector<SearchResult> VectorStore::searchNearest(int target_offset, int top_k) {
    auto vec = getVectorAt(target_offset);
    if (vec.empty()) return {};
    return searchNearest(vec, top_k);
}

std::vector<SearchResult> VectorStore::searchNearest(const std::vector<float>& query_vec, int top_k) {
    std::shared_lock<std::shared_mutex> lock(mutex_);
    int num_vectors = vector_data_.size() / dim_;
    std::vector<SearchResult> results(num_vectors);

    if (num_vectors == 0 || query_vec.size() != static_cast<size_t>(dim_)) {
        return {};
    }

    const float* q = query_vec.data();
    const float* data = vector_data_.data();

#if defined(__AVX2__) && defined(__x86_64__)
    bool supports_avx2 = __builtin_cpu_supports("avx2");
    if (supports_avx2) {
        // Pure AVX2 Intrinsic Loop to calculate Cosine Similarity across all vectors
        for (int i = 0; i < num_vectors; ++i) {
            const float* v = data + i * dim_;
            __m256 sum_vec = _mm256_setzero_ps();
            
            for (int j = 0; j < dim_; j += 8) {
                __m256 a = _mm256_loadu_ps(q + j);
                __m256 b = _mm256_loadu_ps(v + j);
                __m256 dp = _mm256_dp_ps(a, b, 0xF1); 
                sum_vec = _mm256_add_ps(sum_vec, dp);
            }
            
            alignas(32) float arr[8];
            _mm256_storeu_ps(arr, sum_vec);
            float dot = arr[0] + arr[4];
            
            results[i] = {i, dot};
        }
    } else {
        // Runtime fallback for non-AVX2 CPUs (e.g. Intel Pentium)
        for (int i = 0; i < num_vectors; ++i) {
            const float* v = data + i * dim_;
            float dot = 0.0f;
            for (int j = 0; j < dim_; ++j) {
                dot += q[j] * v[j];
            }
            results[i] = {i, dot};
        }
    }
#else
    // Clean scalar fallback for ARM64 or non-x86 architectures
    for (int i = 0; i < num_vectors; ++i) {
        const float* v = data + i * dim_;
        float dot = 0.0f;
        for (int j = 0; j < dim_; ++j) {
            dot += q[j] * v[j];
        }
        results[i] = {i, dot};
    }
#endif

    // Sort to find the Top K nearest neighbors
    std::partial_sort(results.begin(), 
                      results.begin() + std::min(top_k, static_cast<int>(results.size())), 
                      results.end(), 
                      [](const SearchResult& a, const SearchResult& b) {
                          return a.similarity > b.similarity;
                      });
                      
    if (results.size() > static_cast<size_t>(top_k)) {
        results.resize(top_k);
    }
    
    return results;
}
