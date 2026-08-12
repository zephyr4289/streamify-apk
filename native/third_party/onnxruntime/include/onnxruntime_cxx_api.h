#ifndef ONNXRUNTIME_CXX_API_H
#define ONNXRUNTIME_CXX_API_H

#include <vector>
#include <string>
#include <cstdint>

#define ORT_LOGGING_LEVEL_WARNING 2
#define OrtArenaAllocator 0
#define OrtMemTypeDefault 0

enum GraphOptimizationLevel {
    ORT_ENABLE_ALL = 0
};

namespace Ort {

class Env {
public:
    Env() {}
    Env(int, const char*) {}
};

class SessionOptions {
public:
    SessionOptions() {}
    void SetIntraOpNumThreads(int) {}
    void SetGraphOptimizationLevel(GraphOptimizationLevel) {}
};

class Value {
public:
    template <typename T>
    static Value CreateTensor(int, T*, size_t, const int64_t*, size_t) {
        return Value();
    }
    
    template <typename T>
    T* GetTensorMutableData() {
        static T dummy[512] = {0};
        return dummy;
    }
};

class MemoryInfo {
public:
    static int CreateCpu(int, int) { return 0; }
};

struct RunOptions {
    void* ptr;
};

class Session {
public:
    Session(Env&, const char*, SessionOptions&) {}
    std::vector<Value> Run(RunOptions, const char**, Value*, int, const char**, int) {
        return {Value()};
    }
};

} // namespace Ort

#endif
