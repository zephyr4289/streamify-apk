#include <cstdint>
#include <cstddef>
#include <vector>
#include "../dsp/SoftKneeLimiter.h"
#include "../dsp/LufsNormalizer.h"
#include "../dsp/kissfft/kiss_fftr.h"

extern "C" int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    if (size < 16) return 0;

    // 1. Fuzz float PCM limiter
    size_t numFloats = size / sizeof(float);
    if (numFloats > 0 && numFloats <= 4096) {
        std::vector<float> floatBuffer(reinterpret_cast<const float*>(data), reinterpret_cast<const float*>(data) + numFloats);
        streamify::dsp::SoftKneeLimiter limiter(0.85f, 0.15f);
        limiter.processFloats(floatBuffer.data(), static_cast<int>(floatBuffer.size()));
        
        LufsNormalizer::getInstance().processFloats(floatBuffer.data(), static_cast<int>(floatBuffer.size()), -14.0f);
    }

    // 2. Fuzz short PCM limiter
    size_t numShorts = size / sizeof(int16_t);
    if (numShorts > 0 && numShorts <= 4096) {
        std::vector<int16_t> shortBuffer(reinterpret_cast<const int16_t*>(data), reinterpret_cast<const int16_t*>(data) + numShorts);
        streamify::dsp::SoftKneeLimiter limiter(0.85f, 0.15f);
        limiter.processShorts(shortBuffer.data(), static_cast<int>(shortBuffer.size()));
    }

    return 0;
}
