#include "LyricAligner.h"
#include "kiss_fftr.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

LyricAligner& LyricAligner::getInstance() {
    static LyricAligner instance;
    return instance;
}

// 4th-order biquad cascade: 2nd-order High-Pass (300 Hz) + 2nd-order Low-Pass (3400 Hz)
void LyricAligner::applyVocalBandpass(const float* input, float* output, int count, float sampleRate) {
    if (count <= 0 || sampleRate <= 0.0f) return;

    // 1. Highpass at 300 Hz, Q = 0.707
    const float f_hp = 300.0f;
    const float q_hp = 0.7071f;
    const float w0_hp = 2.0f * M_PI * f_hp / sampleRate;
    const float alpha_hp = sinf(w0_hp) / (2.0f * q_hp);
    const float cos_w0_hp = cosf(w0_hp);

    const float b0_hp = (1.0f + cos_w0_hp) / 2.0f;
    const float b1_hp = -(1.0f + cos_w0_hp);
    const float b2_hp = (1.0f + cos_w0_hp) / 2.0f;
    const float a0_hp = 1.0f + alpha_hp;
    const float a1_hp = -2.0f * cos_w0_hp;
    const float a2_hp = 1.0f - alpha_hp;

    // 2. Lowpass at 3400 Hz, Q = 0.707
    const float f_lp = 3400.0f;
    const float q_lp = 0.7071f;
    const float w0_lp = 2.0f * M_PI * f_lp / sampleRate;
    const float alpha_lp = sinf(w0_lp) / (2.0f * q_lp);
    const float cos_w0_lp = cosf(w0_lp);

    const float b0_lp = (1.0f - cos_w0_lp) / 2.0f;
    const float b1_lp = 1.0f - cos_w0_lp;
    const float b2_lp = (1.0f - cos_w0_lp) / 2.0f;
    const float a0_lp = 1.0f + alpha_lp;
    const float a1_lp = -2.0f * cos_w0_lp;
    const float a2_lp = 1.0f - alpha_lp;

    float hp_x1 = 0.0f, hp_x2 = 0.0f, hp_y1 = 0.0f, hp_y2 = 0.0f;
    float lp_x1 = 0.0f, lp_x2 = 0.0f, lp_y1 = 0.0f, lp_y2 = 0.0f;

    for (int i = 0; i < count; ++i) {
        float x = input[i];

        // Highpass stage
        float y_hp = (b0_hp * x + b1_hp * hp_x1 + b2_hp * hp_x2 - a1_hp * hp_y1 - a2_hp * hp_y2) / a0_hp;
        hp_x2 = hp_x1;
        hp_x1 = x;
        hp_y2 = hp_y1;
        hp_y1 = y_hp;

        // Lowpass stage
        float y_lp = (b0_lp * y_hp + b1_lp * lp_x1 + b2_lp * lp_x2 - a1_lp * lp_y1 - a2_lp * lp_y2) / a0_lp;
        lp_x2 = lp_x1;
        lp_x1 = y_hp;
        lp_y2 = lp_y1;
        lp_y1 = y_lp;

        output[i] = y_lp;
    }
}

int32_t LyricAligner::calculateDriftMs(
    const float* pcm,
    int numSamples,
    int sampleRate,
    int channelCount,
    const uint32_t* textOnsetsMs,
    int onsetCount
) {
    if (!pcm || numSamples <= 0 || sampleRate <= 0 || !textOnsetsMs || onsetCount <= 0) {
        return 0;
    }

    // 1. Convert to mono PCM if stereo
    int monoSampleCount = (channelCount > 1) ? (numSamples / channelCount) : numSamples;
    std::vector<float> monoPcm(monoSampleCount);

    if (channelCount > 1) {
        for (int i = 0; i < monoSampleCount; ++i) {
            float sum = 0.0f;
            for (int c = 0; c < channelCount; ++c) {
                sum += pcm[i * channelCount + c];
            }
            monoPcm[i] = sum / static_cast<float>(channelCount);
        }
    } else {
        std::memcpy(monoPcm.data(), pcm, monoSampleCount * sizeof(float));
    }

    // 2. Apply Vocal Bandpass filter (300 Hz - 3.4 kHz)
    std::vector<float> vocalPcm(monoSampleCount);
    applyVocalBandpass(monoPcm.data(), vocalPcm.data(), monoSampleCount, static_cast<float>(sampleRate));

    // 3. Downsample vocal energy into 100 Hz buckets (10ms windows)
    const int samplesPerBucket = sampleRate / 100; // e.g. 441 samples per 10ms at 44.1kHz
    const int bucketCount = monoSampleCount / samplesPerBucket;
    if (bucketCount < 100) {
        return 0; // Audio too short for cross-correlation
    }

    std::vector<float> audioEnergy(bucketCount, 0.0f);
    for (int b = 0; b < bucketCount; ++b) {
        float energySum = 0.0f;
        int startSample = b * samplesPerBucket;
        for (int s = 0; s < samplesPerBucket; ++s) {
            float val = vocalPcm[startSample + s];
            energySum += val * val;
        }
        audioEnergy[b] = sqrtf(energySum / static_cast<float>(samplesPerBucket));
    }

    // 4. Construct text onset impulse train in 100 Hz buckets (10ms resolution)
    std::vector<float> textImpulses(bucketCount, 0.0f);
    for (int i = 0; i < onsetCount; ++i) {
        uint32_t onsetMs = textOnsetsMs[i];
        int bucketIdx = static_cast<int>(onsetMs / 10);
        if (bucketIdx >= 0 && bucketIdx < bucketCount) {
            // Apply slight Gaussian smoothing over ±2 buckets (±20ms) for robustness
            textImpulses[bucketIdx] += 1.0f;
            if (bucketIdx > 0) textImpulses[bucketIdx - 1] += 0.5f;
            if (bucketIdx + 1 < bucketCount) textImpulses[bucketIdx + 1] += 0.5f;
        }
    }

    // 5. Zero-pad to next power of 2 for KissFFT convolution (N >= 2 * bucketCount)
    int nfft = 1;
    while (nfft < 2 * bucketCount) {
        nfft <<= 1;
    }

    kiss_fftr_cfg forward_cfg = kiss_fftr_alloc(nfft, 0, nullptr, nullptr);
    kiss_fftr_cfg inverse_cfg = kiss_fftr_alloc(nfft, 1, nullptr, nullptr);

    if (!forward_cfg || !inverse_cfg) {
        if (forward_cfg) free(forward_cfg);
        if (inverse_cfg) free(inverse_cfg);
        return 0;
    }

    std::vector<float> paddedAudio(nfft, 0.0f);
    std::vector<float> paddedText(nfft, 0.0f);
    std::memcpy(paddedAudio.data(), audioEnergy.data(), bucketCount * sizeof(float));
    std::memcpy(paddedText.data(), textImpulses.data(), bucketCount * sizeof(float));

    int numFreqBins = nfft / 2 + 1;
    std::vector<kiss_fft_cpx> freqAudio(numFreqBins);
    std::vector<kiss_fft_cpx> freqText(numFreqBins);
    std::vector<kiss_fft_cpx> freqCorr(numFreqBins);

    // Forward FFT on Audio and Text
    kiss_fftr(forward_cfg, paddedAudio.data(), freqAudio.data());
    kiss_fftr(forward_cfg, paddedText.data(), freqText.data());

    // Wiener–Khinchin Cross-Correlation in frequency domain: Z[k] = X[k] * Y*[k]
    for (int k = 0; k < numFreqBins; ++k) {
        float x_r = freqAudio[k].r;
        float x_i = freqAudio[k].i;
        float y_r = freqText[k].r;
        float y_i = freqText[k].i;

        // X * conj(Y) = (x_r + j*x_i) * (y_r - j*y_i) = (x_r*y_r + x_i*y_i) + j*(x_i*y_r - x_r*y_i)
        freqCorr[k].r = (x_r * y_r + x_i * y_i);
        freqCorr[k].i = (x_i * y_r - x_r * y_i);
    }

    // Inverse FFT to get time-domain cross-correlation
    std::vector<float> timeCorr(nfft);
    kiss_fftri(inverse_cfg, freqCorr.data(), timeCorr.data());

    free(forward_cfg);
    free(inverse_cfg);

    // 6. Find peak lag in range [-15000ms, +15000ms] (i.e. ±1500 buckets)
    const int maxLagBuckets = 1500;
    float maxCorrelation = -1e9f;
    int bestLagBucket = 0;

    for (int lag = -maxLagBuckets; lag <= maxLagBuckets; ++lag) {
        int corrIdx = (lag < 0) ? (nfft + lag) : lag;
        if (corrIdx >= 0 && corrIdx < nfft) {
            float val = timeCorr[corrIdx];
            if (val > maxCorrelation) {
                maxCorrelation = val;
                bestLagBucket = lag;
            }
        }
    }

    // Convert bucket lag to milliseconds (each bucket = 10ms)
    int32_t driftMs = bestLagBucket * 10;
    return driftMs;
}
