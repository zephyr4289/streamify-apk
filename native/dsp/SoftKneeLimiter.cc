#include "SoftKneeLimiter.h"
#include <cmath>
#include <algorithm>
#include <vector>

SoftKneeLimiter::SoftKneeLimiter(float threshold_db, float knee_width_db, float ratio)
    : threshold_db_(threshold_db), knee_width_db_(knee_width_db), ratio_(ratio), envelope_db_(-70.0f) {
    // 48kHz sample rate, 5ms attack, 50ms release
    attack_coeff_ = std::exp(-1.0f / (0.005f * 48000.0f));
    release_coeff_ = std::exp(-1.0f / (0.050f * 48000.0f));
}

float SoftKneeLimiter::computeGain(float input_db) {
    float t = threshold_db_;
    float w = knee_width_db_;
    float r = ratio_;

    if (input_db < t - w / 2.0f) {
        return 0.0f; // Linear zone (0 dB gain reduction)
    } else if (input_db <= t + w / 2.0f) {
        // 2nd-order polynomial soft knee zone
        float x = input_db - t + w / 2.0f;
        return (1.0f / r - 1.0f) * (x * x) / (2.0f * w);
    } else {
        // Hard limiting zone
        return (t + (input_db - t) / r) - input_db;
    }
}

void SoftKneeLimiter::processInterleavedSIMD(float* pcm_samples, size_t total_samples) {
    for (size_t i = 0; i < total_samples; ++i) {
        float abs_val = std::abs(pcm_samples[i]);
        float input_db = (abs_val > 1e-6f) ? 20.0f * std::log10(abs_val) : -120.0f;

        // Peak detector with ballistics
        if (input_db > envelope_db_) {
            envelope_db_ = input_db + attack_coeff_ * (envelope_db_ - input_db);
        } else {
            envelope_db_ = input_db + release_coeff_ * (envelope_db_ - input_db);
        }

        float gain_reduction_db = computeGain(envelope_db_);
        float linear_gain = std::pow(10.0f, gain_reduction_db / 20.0f);
        pcm_samples[i] *= linear_gain;
    }
}

void SoftKneeLimiter::reset() {
    envelope_db_ = -70.0f;
}

void SoftKneeLimiter::setParameters(float threshold, float kneeWidth) {
    threshold_db_ = threshold;
    knee_width_db_ = kneeWidth;
}

void SoftKneeLimiter::processFloats(float* buffer, int numSamples) {
    if (!buffer || numSamples <= 0) return;
    processInterleavedSIMD(buffer, static_cast<size_t>(numSamples));
}

void SoftKneeLimiter::processShorts(int16_t* buffer, int numSamples) {
    if (!buffer || numSamples <= 0) return;
    thread_local static std::vector<float> tls_buf;
    if (tls_buf.size() < static_cast<size_t>(numSamples)) {
        tls_buf.resize(numSamples);
    }
    for (int i = 0; i < numSamples; ++i) {
        tls_buf[i] = buffer[i] / 32768.0f;
    }
    processInterleavedSIMD(tls_buf.data(), static_cast<size_t>(numSamples));
    for (int i = 0; i < numSamples; ++i) {
        buffer[i] = static_cast<int16_t>(std::clamp(tls_buf[i] * 32767.0f, -32768.0f, 32767.0f));
    }
}
