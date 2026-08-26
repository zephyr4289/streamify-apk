#include "LufsNormalizer.h"
#include <cmath>
#include <algorithm>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

LufsNormalizer& LufsNormalizer::getInstance() {
    static LufsNormalizer instance;
    return instance;
}

LufsNormalizer::LufsNormalizer() {
    // ITU-R BS.1770-4 48kHz Filter Coefficients
    stage1_ = { 1.53512485958697f, -2.69169618940638f, 1.19839281085285f, -1.69065929318241f, 0.73248077421585f };
    stage2_ = { 1.0f, -2.0f, 1.0f, -1.99004745483398f, 0.99007225036621f };
    reset();
}

void LufsNormalizer::reset() {
    s1_z1_ = 0.0f;
    s1_z2_ = 0.0f;
    s2_z1_ = 0.0f;
    s2_z2_ = 0.0f;
}

void LufsNormalizer::processChannelSIMD(float* samples, size_t count) {
    // Direct Form II Transposed Biquad Execution
    for (size_t i = 0; i < count; ++i) {
        float in = samples[i];
        // Stage 1: High Shelf
        float out1 = stage1_.b0 * in + s1_z1_;
        s1_z1_ = stage1_.b1 * in - stage1_.a1 * out1 + s1_z2_;
        s1_z2_ = stage1_.b2 * in - stage1_.a2 * out1;

        // Stage 2: RLB High Pass
        float out2 = stage2_.b0 * out1 + s2_z1_;
        s2_z1_ = stage2_.b1 * out1 - stage2_.a1 * out2 + s2_z2_;
        s2_z2_ = stage2_.b2 * out1 - stage2_.a2 * out2;

        samples[i] = out2;
    }
}

float LufsNormalizer::computeIntegratedLufs(const float* const* channel_data, size_t num_channels, size_t num_samples) {
    if (num_channels == 0 || num_samples == 0) return -70.0f;

    double total_sum = 0.0;
    for (size_t ch = 0; ch < num_channels; ++ch) {
        const float* ptr = channel_data[ch];
        size_t i = 0;
        double channel_sum = 0.0;

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
        float32x4_t sum_vec = vdupq_n_f32(0.0f);
        for (; i + 4 <= num_samples; i += 4) {
            float32x4_t v = vld1q_f32(ptr + i);
            sum_vec = vmlaq_f32(sum_vec, v, v);
        }
        channel_sum += vgetq_lane_f32(sum_vec, 0) + vgetq_lane_f32(sum_vec, 1) +
                       vgetq_lane_f32(sum_vec, 2) + vgetq_lane_f32(sum_vec, 3);
#endif

        for (; i < num_samples; ++i) {
            channel_sum += ptr[i] * ptr[i];
        }

        // Channel weighting: Left/Right = 1.0 (0dB), Center = 1.0, Surround = 1.41 (+1.5dB)
        float channel_weight = (ch < 2) ? 1.0f : 1.41f;
        total_sum += channel_weight * (channel_sum / static_cast<double>(num_samples));
    }

    if (total_sum <= 1e-12) return -70.0f;
    return static_cast<float>(-0.691 + 10.0 * std::log10(total_sum));
}

float LufsNormalizer::calculateNormalizationGain(float integrated_lufs, float target_lufs) {
    if (integrated_lufs <= -70.0f) return 1.0f;
    float gain_db = target_lufs - integrated_lufs;
    // Clamp gain adjustments within [-12dB, +12dB] to prevent excessive noise boost
    gain_db = std::clamp(gain_db, -12.0f, 12.0f);
    return std::pow(10.0f, gain_db / 20.0f);
}

void LufsNormalizer::processFloats(float* pcm, int length, float targetLufs) {
    if (!pcm || length <= 0) return;
    const float* channels[] = { pcm };
    float lufs = computeIntegratedLufs(channels, 1, static_cast<size_t>(length));
    float gain = calculateNormalizationGain(lufs, targetLufs);
    for (int i = 0; i < length; ++i) {
        pcm[i] = std::clamp(pcm[i] * gain, -0.99f, 0.99f);
    }
}

void LufsNormalizer::processShorts(short* pcm, int length, float targetLufs) {
    if (!pcm || length <= 0) return;
    thread_local static std::vector<float> tls_buf;
    if (tls_buf.size() < static_cast<size_t>(length)) {
        tls_buf.resize(length);
    }
    for (int i = 0; i < length; ++i) {
        tls_buf[i] = pcm[i] / 32768.0f;
    }
    processFloats(tls_buf.data(), length, targetLufs);
    for (int i = 0; i < length; ++i) {
        pcm[i] = static_cast<short>(std::clamp(tls_buf[i] * 32767.0f, -32768.0f, 32767.0f));
    }
}
