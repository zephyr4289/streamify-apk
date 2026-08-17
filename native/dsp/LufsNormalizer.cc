#include "LufsNormalizer.h"
#include <cmath>
#include <algorithm>

#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

LufsNormalizer& LufsNormalizer::getInstance() {
    static LufsNormalizer instance;
    return instance;
}

void LufsNormalizer::processFloats(float* pcm, int length, float targetLufs) {
    if (!pcm || length <= 0) return;

    // 1. Calculate RMS energy
    float sumSq = 0.0f;
    int i = 0;

#if defined(__ARM_NEON) || defined(__aarch64__)
    float32x4_t v_sum = vdupq_n_f32(0.0f);
    for (; i <= length - 4; i += 4) {
        float32x4_t sample_vec = vld1q_f32(pcm + i);
        v_sum = vmlaq_f32(v_sum, sample_vec, sample_vec);
    }
    float32x2_t sum2 = vpadd_f32(vget_low_f32(v_sum), vget_high_f32(v_sum));
    sumSq += vget_lane_f32(sum2, 0) + vget_lane_f32(sum2, 1);
#endif

    for (; i < length; ++i) {
        sumSq += pcm[i] * pcm[i];
    }

    float currentRms = std::sqrt(sumSq / static_cast<float>(length));
    if (currentRms < 1e-6f) return; // Silent buffer

    // 2. Convert to LUFS approximation
    float currentLufs = 20.0f * std::log10(currentRms + 1e-7f);

    // 3. Compute gain adjustment in dB (clamped between -12dB and +12dB)
    float gainDb = targetLufs - currentLufs;
    gainDb = std::clamp(gainDb, -12.0f, 12.0f);
    float linearGain = std::pow(10.0f, gainDb / 20.0f);

    // 4. Apply linear gain with soft-limit
    int j = 0;
#if defined(__ARM_NEON) || defined(__aarch64__)
    float32x4_t v_gain = vdupq_n_f32(linearGain);
    float32x4_t v_max = vdupq_n_f32(0.99f);
    float32x4_t v_min = vdupq_n_f32(-0.99f);

    for (; j <= length - 4; j += 4) {
        float32x4_t sample_vec = vld1q_f32(pcm + j);
        float32x4_t gained_vec = vmulq_f32(sample_vec, v_gain);
        gained_vec = vmaxq_f32(vminq_f32(gained_vec, v_max), v_min);
        vst1q_f32(pcm + j, gained_vec);
    }
#endif

    for (; j < length; ++j) {
        float gained = pcm[j] * linearGain;
        pcm[j] = std::clamp(gained, -0.99f, 0.99f);
    }
}

void LufsNormalizer::processShorts(int16_t* pcm, int length, float targetLufs) {
    if (!pcm || length <= 0) return;

    thread_local static std::vector<float> tls_float_buf;
    if (tls_float_buf.size() < static_cast<size_t>(length)) {
        tls_float_buf.resize(length);
    }

    for (int i = 0; i < length; ++i) {
        tls_float_buf[i] = pcm[i] / 32768.0f;
    }

    processFloats(tls_float_buf.data(), length, targetLufs);

    for (int i = 0; i < length; ++i) {
        float clamped = std::clamp(tls_float_buf[i] * 32767.0f, -32768.0f, 32767.0f);
        pcm[i] = static_cast<int16_t>(clamped);
    }
}
