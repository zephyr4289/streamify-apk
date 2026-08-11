#include "VectorSearchService.h"
#include <fstream>
#include <iostream>
#include <cmath>
#include <algorithm>

VectorSearchService& VectorSearchService::getInstance() {
    static VectorSearchService instance;
    return instance;
}

bool VectorSearchService::init(const std::string& bin_path, const std::string& faiss_index_path, int dim) {
    bin_path_ = bin_path;
    faiss_index_path_ = faiss_index_path;
    dim_ = dim;
    return loadVectors();
}

bool VectorSearchService::loadVectors() {
    struct stat st;
    if (stat(bin_path_.c_str(), &st) == 0) {
        std::shared_lock<std::shared_mutex> rlock(vector_mutex_);
        if (st.st_mtime == last_mtime_ && !vector_data_.empty()) return true;
    }

    std::unique_lock<std::shared_mutex> wlock(vector_mutex_);
    if (stat(bin_path_.c_str(), &st) == 0) {
        if (st.st_mtime == last_mtime_ && !vector_data_.empty()) return true;
        last_mtime_ = st.st_mtime;
    }

    std::ifstream file(bin_path_, std::ios::binary | std::ios::ate);
    if (!file.is_open()) {
        // Fallback to faiss index if binary file not found
        std::ifstream faiss_file(faiss_index_path_, std::ios::binary | std::ios::ate);
        if (!faiss_file.is_open()) {
            std::cerr << "[VectorSearchService] Neither vectors.bin nor index.faiss found at path." << std::endl;
            return false;
        }
        file = std::move(faiss_file);
    }

    std::streamsize fileSize = file.tellg();
    file.seekg(0, std::ios::beg);

    if (fileSize <= 0 || fileSize % (sizeof(float) * dim_) != 0) {
        // Check if header exists (e.g. FAISS_LITE header)
        if (fileSize > 18) {
            char header[10];
            file.read(header, 10);
            if (std::string(header, 10) == "FAISS_LITE") {
                uint32_t num_vecs = 0, d = 0;
                file.read(reinterpret_cast<char*>(&num_vecs), sizeof(uint32_t));
                file.read(reinterpret_cast<char*>(&d), sizeof(uint32_t));
                dim_ = d;
                fileSize = num_vecs * dim_ * sizeof(float);
            } else {
                file.seekg(0, std::ios::beg);
            }
        }
    }

    size_t floatCount = fileSize / sizeof(float);
    vector_data_.resize(floatCount);
    file.read(reinterpret_cast<char*>(vector_data_.data()), fileSize);

    total_vectors_ = floatCount / dim_;
    std::cout << "[VectorSearchService] Loaded " << total_vectors_ << " vectors of dimension " << dim_ << std::endl;
    return true;
}

int VectorSearchService::getVectorCount() {
    std::shared_lock<std::shared_mutex> lock(vector_mutex_);
    return total_vectors_;
}

float VectorSearchService::computeDotProduct(const float* vecA, const float* vecB, int dim) {
    float dot = 0.0f;
    for (int i = 0; i < dim; ++i) {
        dot += vecA[i] * vecB[i];
    }
    return dot;
}

std::vector<float> VectorSearchService::getVectorAt(int offset) {
    std::shared_lock<std::shared_mutex> lock(vector_mutex_);
    if (offset < 0 || offset >= total_vectors_) {
        return {};
    }
    const float* ptr = vector_data_.data() + (offset * dim_);
    return std::vector<float>(ptr, ptr + dim_);
}

std::vector<SearchResult> VectorSearchService::searchNearest(int target_offset, int top_k) {
    loadVectors();

    std::shared_lock<std::shared_mutex> lock(vector_mutex_);
    if (target_offset < 0 || target_offset >= total_vectors_) {
        return {};
    }

    const float* target_ptr = vector_data_.data() + (target_offset * dim_);
    std::vector<SearchResult> results;
    results.reserve(total_vectors_);

    for (int i = 0; i < total_vectors_; ++i) {
        const float* current_ptr = vector_data_.data() + (i * dim_);
        float sim = computeDotProduct(target_ptr, current_ptr, dim_);
        results.push_back({i, sim});
    }

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

std::vector<SearchResult> VectorSearchService::searchNearestByVector(const std::vector<float>& query_vec, int top_k) {
    loadVectors();

    std::shared_lock<std::shared_mutex> lock(vector_mutex_);
    if (query_vec.size() != static_cast<size_t>(dim_) || total_vectors_ == 0) {
        return {};
    }

    std::vector<SearchResult> results;
    results.reserve(total_vectors_);

    for (int i = 0; i < total_vectors_; ++i) {
        const float* current_ptr = vector_data_.data() + (i * dim_);
        float sim = computeDotProduct(query_vec.data(), current_ptr, dim_);
        results.push_back({i, sim});
    }

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
