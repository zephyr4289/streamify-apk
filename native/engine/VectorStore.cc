#include "VectorStore.h"
#include <cmath>
#if defined(__ARM_NEON)
#include <arm_neon.h>
#elif defined(__AVX__) || defined(__AVX2__)
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

#if defined(__ARM_NEON)
    for (int i = 0; i < num_vectors; ++i) {
        const float* v = data + i * dim_;
        
        // Prefetch next vector into L1 CPU cache
        if (i + 1 < num_vectors) {
            __builtin_prefetch(data + (i + 1) * dim_, 0, 1);
        }

        float32x4_t sum0 = vdupq_n_f32(0.0f);
        float32x4_t sum1 = vdupq_n_f32(0.0f);
        float32x4_t sum2 = vdupq_n_f32(0.0f);
        float32x4_t sum3 = vdupq_n_f32(0.0f);
        
        int j = 0;
        // 4x unrolled NEON SIMD loop (16 floats per step)
        for (; j <= dim_ - 16; j += 16) {
            sum0 = vmlaq_f32(sum0, vld1q_f32(q + j), vld1q_f32(v + j));
            sum1 = vmlaq_f32(sum1, vld1q_f32(q + j + 4), vld1q_f32(v + j + 4));
            sum2 = vmlaq_f32(sum2, vld1q_f32(q + j + 8), vld1q_f32(v + j + 8));
            sum3 = vmlaq_f32(sum3, vld1q_f32(q + j + 12), vld1q_f32(v + j + 12));
        }

        float32x4_t total_sum = vaddq_f32(vaddq_f32(sum0, sum1), vaddq_f32(sum2, sum3));
        for (; j <= dim_ - 4; j += 4) {
            total_sum = vmlaq_f32(total_sum, vld1q_f32(q + j), vld1q_f32(v + j));
        }
        
        float dot = vgetq_lane_f32(total_sum, 0) + vgetq_lane_f32(total_sum, 1) + 
                    vgetq_lane_f32(total_sum, 2) + vgetq_lane_f32(total_sum, 3);
                    
        for (; j < dim_; ++j) {
            dot += q[j] * v[j];
        }
        
        if (std::isnan(dot) || std::isinf(dot)) dot = -1.0f;
        results[i] = {i, dot};
    }
#elif defined(__AVX__) || defined(__AVX2__)
    for (int i = 0; i < num_vectors; ++i) {
        const float* v = data + i * dim_;
        __m256 sum_vec = _mm256_setzero_ps();
        
        int j = 0;
        for (; j <= dim_ - 8; j += 8) {
            __m256 a = _mm256_loadu_ps(q + j);
            __m256 b = _mm256_loadu_ps(v + j);
            sum_vec = _mm256_add_ps(sum_vec, _mm256_mul_ps(a, b));
        }
        
        float temp[8];
        _mm256_storeu_ps(temp, sum_vec);
        float dot = temp[0] + temp[1] + temp[2] + temp[3] + temp[4] + temp[5] + temp[6] + temp[7];
        
        for (; j < dim_; ++j) {
            dot += q[j] * v[j];
        }
        
        if (std::isnan(dot) || std::isinf(dot)) dot = -1.0f;
        results[i] = {i, dot};
    }
#else
    for (int i = 0; i < num_vectors; ++i) {
        const float* v = data + i * dim_;
        float dot = 0.0f;
        for (int j = 0; j < dim_; ++j) {
            dot += q[j] * v[j];
        }
        if (std::isnan(dot) || std::isinf(dot)) dot = -1.0f;
        results[i] = {i, dot};
    }
#endif

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
