#pragma once
#include <cstddef>
#include <cstdint>

class SoftKneeLimiter {
public:
    SoftKneeLimiter(float threshold_db = -0.5f, float knee_width_db = 2.0f, float ratio = 20.0f);
    void processInterleavedSIMD(float* pcm_samples, size_t total_samples);

    // Backward compatibility helpers
    void reset();
    void setParameters(float threshold, float kneeWidth);
    void processFloats(float* buffer, int numSamples);
    void processShorts(int16_t* buffer, int numSamples);

private:
    float threshold_db_;
    float knee_width_db_;
    float ratio_;
    float attack_coeff_;
    float release_coeff_;
    float envelope_db_;
    float computeGain(float input_db);
};

namespace streamify {
namespace dsp {
using SoftKneeLimiter = ::SoftKneeLimiter;
}
}
