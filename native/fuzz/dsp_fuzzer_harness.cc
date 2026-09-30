#include <cstdint>
#include <cstddef>
#include <cstring>
#include <vector>

#include "../dsp/SoftKneeLimiter.h"
#include "../dsp/LufsNormalizer.h"
// Phase-1 additions (BEHIND.md #11/#13/#39/#41).
#include "../dsp/AcousticPhaseResampler.h"
#include "../dsp/ChannelOps.h"
#include "../dsp/MasterChain.h"
#include "../mix/AudioRingBuffer.h"
#include "../mix/PeerMixPool.h"

// LibFuzzer abort()s are reported as crashes; use this for invariants.
static inline void fuzz_check(bool ok) {
    if (!ok) __builtin_trap();
}

extern "C" int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    if (size < 16) return 0;

    // 1. Fuzz float PCM limiter + normalizer.
    // memcpy into an aligned buffer: reinterpret_cast on arbitrary-offset
    // fuzz bytes is alignment-UB under UBSan.
    size_t numFloats = size / sizeof(float);
    if (numFloats > 0 && numFloats <= 4096) {
        std::vector<float> floatBuffer(numFloats);
        std::memcpy(floatBuffer.data(), data, numFloats * sizeof(float));
        streamify::dsp::SoftKneeLimiter limiter(0.85f, 0.15f);
        limiter.processFloats(floatBuffer.data(), static_cast<int>(floatBuffer.size()));
        for (float v : floatBuffer) fuzz_check(__builtin_isfinite(v));

        // Local instance: shared-singleton state across cases kills reproducibility.
        LufsNormalizer normalizer;
        normalizer.processFloats(floatBuffer.data(), static_cast<int>(floatBuffer.size()), -14.0f);
        for (float v : floatBuffer) fuzz_check(__builtin_isfinite(v));
    }

    // 2. Fuzz short PCM limiter
    size_t numShorts = size / sizeof(int16_t);
    if (numShorts > 0 && numShorts <= 4096) {
        std::vector<int16_t> shortBuffer(numShorts);
        std::memcpy(shortBuffer.data(), data, numShorts * sizeof(int16_t));
        streamify::dsp::SoftKneeLimiter limiter(0.85f, 0.15f);
        limiter.processShorts(shortBuffer.data(), static_cast<int>(shortBuffer.size()));
    }

    // 3. Phase-1: MasterChain (LUFS glide + balance/mono + true-peak ceiling).
    if (numFloats >= 4 && numFloats <= 4096) {
        const uint8_t* b = data;
        float balance;
        std::memcpy(&balance, b, 4);
        float ceilingDb;
        std::memcpy(&ceilingDb, b + 4, 4);
        const bool mono = (b[8] & 1) != 0;
        const bool bypass = (b[8] & 2) != 0;

        std::vector<float> pcm(numFloats);
        std::memcpy(pcm.data(), data, numFloats * sizeof(float));

        streamify::dsp::MasterChain chain;
        chain.configure(48000, 2);
        chain.setBalance(balance);
        chain.setLimiterCeiling(ceilingDb);
        chain.setMonoDownmix(mono);
        chain.setSilentBypass(bypass);
        const int frames = static_cast<int>(numFloats / 2);
        if (bypass) {
            // Silent bypass: the PROCESSED region (frames * 2 floats) must
            // come back bit-identical; the odd tail float is not touched by
            // contract. Fuzz bytes may legitimately BE NaN/Inf patterns.
            std::vector<float> before = pcm;
            chain.process(pcm.data(), frames);
            fuzz_check(std::memcmp(pcm.data(), before.data(),
                                   static_cast<size_t>(frames) * 2 * sizeof(float)) == 0);
        } else {
            // Plant hostile float patterns deterministically.
            if (numFloats > 512) {
                pcm[7] = __builtin_nanf("");
                pcm[11] = __builtin_inff();
                pcm[13] = -__builtin_inff();
            }
            chain.process(pcm.data(), frames);
            for (int i = 0; i < frames * 2; ++i) {   // processed region only;
                fuzz_check(__builtin_isfinite(pcm[static_cast<size_t>(i)]));  // the odd tail
            }                                              // float is untouched
        }                                                   // by contract
    }

    // 4. Phase-1: ChannelOps — linear stage: finite input stays finite.
    if (numFloats >= 8) {
        float balance;
        std::memcpy(&balance, data, 4);
        streamify::dsp::ChannelOps ops;
        ops.setBalance(balance);
        ops.setMonoDownmix((data[4] & 1) != 0);
        std::vector<float> pcm(numFloats);
        std::memcpy(pcm.data(), data, numFloats * sizeof(float));
        if (numFloats > 64) {
            pcm[31] = __builtin_nanf("");
            pcm[33] = __builtin_inff();
        }
        std::vector<float> fin(numFloats);
        std::memcpy(fin.data(), data, numFloats * sizeof(float));
        // Fuzz floats can be Inf/NaN/near-FLT_MAX (x*sqrt2 would overflow).
        // The invariant under test is the stage's LOGIC: build a finite,
        // non-overflowing variant and assert it stays finite after the gain.
        for (auto& v : fin) {
            if (!__builtin_isfinite(v)) v = 0.0f;
            else if (v > 1e36f) v = 1e36f;
            else if (v < -1e36f) v = -1e36f;
        }
        ops.processInterleaved(fin.data(), numFloats / 2);
        for (float v : fin) fuzz_check(__builtin_isfinite(v));
    }

    // 5. Phase-1: AudioRingBuffer write/read/peek/accumulate.
    if (numFloats >= 16) {
        const int chan = 1 + (data[0] % 8);
        const size_t cap = 16 + (data[1] % 512);
        streamify::mix::AudioRingBuffer rb;
        if (rb.init(chan, cap)) {
            std::vector<float> src(numFloats);
            std::memcpy(src.data(), data, numFloats * sizeof(float));
            const size_t frames = numFloats / chan;
            (void)rb.write(src.data(), frames);
            std::vector<float> dst(numFloats, 0.0f);
            const size_t got = rb.read(dst.data(), frames);
            fuzz_check(got <= frames);
            float g;
            std::memcpy(&g, data + 8, 4);
            (void)rb.accumulate(dst.data(), got, g);
            std::vector<float> peek(numFloats, 0.0f);
            (void)rb.peek(peek.data(), got);
            rb.reset();
        }
    }

    // 6. Phase-1: PeerMixPool (up to 8 peers, saturation-bounded output).
    if (numFloats >= 32) {
        streamify::mix::PeerMixPool pool;
        if (pool.init(48000, 2, 512)) {
            const int peers = 1 + (data[2] % 8);
            std::vector<float> src(256 * 2);
            std::memcpy(src.data(), data, std::min(numFloats, size_t(512)) * sizeof(float));
            for (int i = 0; i < peers; ++i) {
                const int slot = pool.attachPeer();
                if (slot < 0) break;
                float g;
                std::memcpy(&g, data + 12 + i * 4, 4);
                pool.setPeerGain(slot, g);
                const size_t n = 64 + (data[3 + i] % 192);
                fuzz_check(pool.peerWrite(slot, src.data(), n));
            }
            std::vector<float> out(256 * 2, 0.0f);
            streamify::mix::PeerMixPool::MixStats st;
            pool.mix(out.data(), 256, &st);
            for (float v : out) {
                fuzz_check(__builtin_isfinite(v));
                fuzz_check(v <= 1.0000002f && v >= -1.0000002f);
            }
        }
    }

    // 7. Phase-1: resampler silent-bypass transitions + active kernel.
    if (numFloats >= 64 && numFloats <= 4096) {
        streamify::dsp::AcousticPhaseResampler res;
        int64_t drift;
        std::memcpy(&drift, data, 8);
        res.setTargetDriftNanosPerSecond(drift);
        const bool bypass = (data[8] & 1) != 0;
        res.SetSilentBypass(bypass);
        const int frames = static_cast<int>(numFloats / 2);

        // (a) ACTIVE kernel: finite, non-overflowing input stays finite.
        {
            std::vector<float> inA(numFloats);
            std::memcpy(inA.data(), data, numFloats * sizeof(float));
            for (auto& v : inA) {
                if (!__builtin_isfinite(v)) v = 0.0f;
                else if (v > 1e36f) v = 1e36f;
                else if (v < -1e36f) v = -1e36f;
            }
            std::vector<float> outA(numFloats, 0.0f);
            const int r = res.process(inA.data(), frames, outA.data(), frames + 8);
            fuzz_check(r >= 0);
            for (int i = 0; i < r * 2; ++i) {          // only the produced region
                fuzz_check(__builtin_isfinite(outA[static_cast<size_t>(i)]));
            }
        }
        // (b) BYPASS: bit-exact passthrough of the RAW bytes (NaN/Inf by
        // design — the resampler must not touch a single sample).
        {
            res.SetSilentBypass(true);
            std::vector<float> inB(numFloats);
            std::memcpy(inB.data(), data, numFloats * sizeof(float));
            std::vector<float> outB(numFloats, 0.0f);
            const int r = res.process(inB.data(), frames, outB.data(), frames + 8);
            fuzz_check(r == frames);
            fuzz_check(std::memcmp(outB.data(), inB.data(),
                                   static_cast<size_t>(frames) * 2 * sizeof(float)) == 0);
            res.SetSilentBypass(false);
        }
    }

    return 0;
}
