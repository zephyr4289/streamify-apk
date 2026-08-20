#pragma once
#include <cstddef>
#include <vector>

struct BiquadCoeffs {
    float b0, b1, b2, a1, a2;
};

class LufsNormalizer {
public:
    LufsNormalizer();
    void reset();

    // In-place K-weighting filter over contiguous 32-bit float PCM
    void processChannelSIMD(float* samples, size_t count);

    // Calculates integrated LUFS over framed buffer
    float computeIntegratedLufs(const float* const* channel_data, size_t num_channels, size_t num_samples);

    // Computes target dynamic gain factor: g = 10^((-14.0 - L_K) / 20)
    float calculateNormalizationGain(float integrated_lufs, float target_lufs = -14.0f);

    // Backward compatibility helpers
    static LufsNormalizer& getInstance();
    void processFloats(float* pcm, int length, float targetLufs = -14.0f);
    void processShorts(short* pcm, int length, float targetLufs = -14.0f);

private:
    BiquadCoeffs stage1_; // Pre-filter (high shelf)
    BiquadCoeffs stage2_; // RLB weighting (high pass)
    float s1_z1_ = 0.0f, s1_z2_ = 0.0f;
    float s2_z1_ = 0.0f, s2_z2_ = 0.0f;
};
