#include <cstdint>
#include <cstddef>
#include <cstring>
#include <vector>
#include "../dsp/SoftKneeLimiter.h"
#include "../dsp/LufsNormalizer.h"

extern "C" int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    if (size < 16) return 0;

    // 1. Fuzz float PCM limiter + normalizer.
    // memcpy into an aligned buffer: reinterpret_cast on arbitrary-offset
    // fuzz bytes is alignment-UB under UBSan.
    size_t numFloats = size / sizeof(float);
    if (numFloats > 0 && numFloats <= 4096) {
        std::vector<float> floatBuffer(numFloats);
        std::memcpy(floatBuffer.data(), data, numFloats * sizeof(float));
        streamify::dsp::SoftKneeLimiter limiter(0.85f, 0.15f);
        limiter.processFloats(floatBuffer.data(), static_cast<int>(floatBuffer.size()));

        // Local instance: shared-singleton state across cases kills reproducibility.
        LufsNormalizer normalizer;
        normalizer.processFloats(floatBuffer.data(), static_cast<int>(floatBuffer.size()), -14.0f);
    }

    // 2. Fuzz short PCM limiter
    size_t numShorts = size / sizeof(int16_t);
    if (numShorts > 0 && numShorts <= 4096) {
        std::vector<int16_t> shortBuffer(numShorts);
        std::memcpy(shortBuffer.data(), data, numShorts * sizeof(int16_t));
        streamify::dsp::SoftKneeLimiter limiter(0.85f, 0.15f);
        limiter.processShorts(shortBuffer.data(), static_cast<int>(shortBuffer.size()));
    }

    return 0;
}
