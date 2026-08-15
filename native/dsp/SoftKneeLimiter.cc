#include "SoftKneeLimiter.h"
#include <algorithm>
#include <cmath>

namespace streamify {
namespace dsp {

SoftKneeLimiter::SoftKneeLimiter(float threshold, float kneeWidth)
    : threshold_(threshold), kneeWidth_(kneeWidth) {
    rebuildLut();
}

void SoftKneeLimiter::setParameters(float threshold, float kneeWidth) {
    threshold_ = std::max(0.1f, std::min(1.0f, threshold));
    kneeWidth_ = std::max(0.01f, std::min(1.0f, kneeWidth));
    rebuildLut();
}

void SoftKneeLimiter::rebuildLut() {
    const float maxVal = 32767.0f;
    const float thresh = threshold_ * maxVal;
    const float knee = kneeWidth_ * maxVal;

    for (int i = 0; i <= LUT_SIZE; ++i) {
        float absVal = (static_cast<float>(i) / static_cast<float>(LUT_SIZE)) * maxVal;
        if (absVal > thresh) {
            float excess = absVal - thresh;
            float compressed = excess / (1.0f + (excess / knee));
            float limited = thresh + compressed;
            lutShort_[i] = static_cast<int16_t>(std::min(32767.0f, limited));
        } else {
            lutShort_[i] = static_cast<int16_t>(absVal);
        }
    }
}

void SoftKneeLimiter::processFloats(float* buffer, int numSamples) {
    if (!buffer || numSamples <= 0) return;

    const float thresh = threshold_;
    const float knee = kneeWidth_;
    const float invKnee = 1.0f / knee;

    for (int i = 0; i < numSamples; ++i) {
        float sample = buffer[i];
        float absSample = std::abs(sample);

        if (absSample > thresh) {
            float excess = absSample - thresh;
            float compressed = excess / (1.0f + (excess * invKnee));
            float limited = thresh + compressed;
            buffer[i] = (sample > 0.0f) ? limited : -limited;
        }
    }
}

void SoftKneeLimiter::processShorts(int16_t* buffer, int numSamples) {
    if (!buffer || numSamples <= 0) return;

    const int16_t thresholdShort = static_cast<int16_t>(threshold_ * 32767.0f);

    int i = 0;
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
    // Fast-path NEON peak detection
    int16x8_t vThresh = vdupq_n_s16(thresholdShort);
    for (; i <= numSamples - 8; i += 8) {
        int16x8_t vIn = vld1q_s16(buffer + i);
        int16x8_t vAbs = vabsq_s16(vIn);
        uint16x8_t vMask = vcgtq_s16(vAbs, vThresh);

        // Check if any sample exceeds threshold across 8 samples
        uint64x2_t vMask64 = vreinterpretq_u64_u16(vMask);
        if (vgetq_lane_u64(vMask64, 0) == 0 && vgetq_lane_u64(vMask64, 1) == 0) {
            // No limiting needed for this entire 8-sample block
            continue;
        }

        // Apply fast 12-bit LUT
        for (int j = 0; j < 8; ++j) {
            int16_t sample = buffer[i + j];
            int16_t absSample = (sample < 0) ? static_cast<int16_t>(-sample) : sample;
            if (absSample > thresholdShort) {
                int lutIdx = (static_cast<int>(absSample) * LUT_SIZE) >> 15;
                int16_t limited = lutShort_[lutIdx];
                buffer[i + j] = (sample < 0) ? static_cast<int16_t>(-limited) : limited;
            }
        }
    }
#endif

    // Process remaining samples
    for (; i < numSamples; ++i) {
        int16_t sample = buffer[i];
        int16_t absSample = (sample < 0) ? static_cast<int16_t>(-sample) : sample;

        if (absSample > thresholdShort) {
            int lutIdx = (static_cast<int>(absSample) * LUT_SIZE) >> 15;
            int16_t limited = lutShort_[lutIdx];
            buffer[i] = (sample < 0) ? static_cast<int16_t>(-limited) : limited;
        }
    }
}

} // namespace dsp
} // namespace streamify
