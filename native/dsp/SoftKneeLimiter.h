#pragma once

#include <cstdint>
#include <cmath>
#include <vector>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

namespace streamify {
namespace dsp {

class SoftKneeLimiter {
public:
    SoftKneeLimiter(float threshold = 0.90f, float kneeWidth = 0.15f);

    void setParameters(float threshold, float kneeWidth);

    // In-place limiter for 32-bit float PCM buffers
    void processFloats(float* buffer, int numSamples);

    // Ultra-fast in-place limiter for 16-bit signed PCM buffers using 12-bit LUT & SIMD
    void processShorts(int16_t* buffer, int numSamples);

private:
    void rebuildLut();

    float threshold_;
    float kneeWidth_;
    static constexpr int LUT_SIZE = 4096;
    int16_t lutShort_[LUT_SIZE + 1];
};

} // namespace dsp
} // namespace streamify
