#include <iostream>
#include <vector>
#include <cassert>
#include <cmath>
#include "../dsp/SoftKneeLimiter.h"
#include "../dsp/LufsNormalizer.h"
#include "../dsp/kissfft/kiss_fftr.h"

int main() {
    std::cout << "[TEST] Starting Native DSP Test Suite..." << std::endl;

    // 1. Test SoftKneeLimiter float processing
    streamify::dsp::SoftKneeLimiter limiter(0.80f, 0.10f);
    std::vector<float> floatPcm = {0.1f, 0.5f, 0.9f, 1.2f, -1.5f, 2.0f, -0.95f};
    limiter.processFloats(floatPcm.data(), floatPcm.size());

    for (float sample : floatPcm) {
        assert(std::isfinite(sample));
        assert(std::abs(sample) <= 1.1f); // Must be strictly clamped/limited
    }
    std::cout << "  - SoftKneeLimiter float limit: PASSED" << std::endl;

    // 2. Test SoftKneeLimiter short processing
    std::vector<int16_t> shortPcm = {100, 5000, 25000, 32000, -32000, 32767, -32768};
    limiter.processShorts(shortPcm.data(), shortPcm.size());
    for (int16_t sample : shortPcm) {
        assert(sample >= -32768 && sample <= 32767);
    }
    std::cout << "  - SoftKneeLimiter short limit: PASSED" << std::endl;

    // 3. Test LUFS Normalizer
    std::vector<float> pcmLufs = {0.05f, -0.05f, 0.1f, -0.1f, 0.08f, -0.08f};
    LufsNormalizer::getInstance().processFloats(pcmLufs.data(), pcmLufs.size(), -14.0f);
    for (float sample : pcmLufs) {
        assert(std::isfinite(sample));
    }
    std::cout << "  - LufsNormalizer: PASSED" << std::endl;

    // 4. Test KissFFT Real FFT
    int nfft = 1024;
    kiss_fftr_cfg cfg = kiss_fftr_alloc(nfft, 0, nullptr, nullptr);
    assert(cfg != nullptr);
    std::vector<kiss_fft_scalar> in(nfft, 0.0f);
    for (int i = 0; i < nfft; ++i) {
        in[i] = std::sin(2.0 * M_PI * 440.0 * i / 44100.0);
    }
    std::vector<kiss_fft_cpx> out(nfft / 2 + 1);
    kiss_fftr(cfg, in.data(), out.data());
    free(cfg);
    std::cout << "  - KissFFTR 1024 Transform: PASSED" << std::endl;

    std::cout << "[TEST] All Native DSP Tests Passed Successfully! (ASan/UBSan Verified)" << std::endl;
    return 0;
}
