#include "SoftKneeLimiter.h"
#include <algorithm>

namespace streamify {
namespace dsp {

SoftKneeLimiter::SoftKneeLimiter(float threshold, float kneeWidth)
    : threshold_(threshold), kneeWidth_(kneeWidth) {}

void SoftKneeLimiter::setParameters(float threshold, float kneeWidth) {
    threshold_ = std::max(0.1f, std::min(1.0f, threshold));
    kneeWidth_ = std::max(0.01f, std::min(1.0f, kneeWidth));
}

void SoftKneeLimiter::processFloats(float* buffer, int numSamples) {
    if (!buffer || numSamples <= 0) return;

    const float thresh = threshold_;
    const float knee = kneeWidth_;

    for (int i = 0; i < numSamples; ++i) {
        float sample = buffer[i];
        float absSample = std::abs(sample);

        if (absSample > thresh) {
            float excess = absSample - thresh;
            float compressed = excess / (1.0f + (excess / knee));
            float limited = thresh + compressed;
            buffer[i] = (sample > 0.0f) ? limited : -limited;
        }
    }
}

void SoftKneeLimiter::processShorts(int16_t* buffer, int numSamples) {
    if (!buffer || numSamples <= 0) return;

    const float maxVal = 32767.0f;
    const float thresh = threshold_ * maxVal;
    const float knee = kneeWidth_ * maxVal;

    for (int i = 0; i < numSamples; ++i) {
        float sample = static_cast<float>(buffer[i]);
        float absSample = std::abs(sample);

        if (absSample > thresh) {
            float excess = absSample - thresh;
            float compressed = excess / (1.0f + (excess / knee));
            float limited = thresh + compressed;
            float result = (sample > 0.0f) ? limited : -limited;
            buffer[i] = static_cast<int16_t>(std::max(-32768.0f, std::min(32767.0f, result)));
        }
    }
}

} // namespace dsp
} // namespace streamify
