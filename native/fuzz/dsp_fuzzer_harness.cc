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
        // Plant hostile float patterns deterministically.
        if (numFloats > 512) {
            pcm[7] = __builtin_nanf("");
            pcm[11] = __builtin_inff();
            pcm[13] = -__builtin_inff();
        }

        streamify::dsp::MasterChain chain;
        chain.configure(48000, 2);
        chain.setBalance(balance);
        chain.setLimiterCeiling(ceilingDb);
        chain.setMonoDownmix(mono);
        chain.setSilentBypass(bypass);
        const int frames = static_cast<int>(numFloats / 2);
        chain.process(pcm.data(), frames);
        for (float v : pcm) fuzz_check(__builtin_isfinite(v));
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
        if (numFloats > 64) {
            fin[31] = 0.0f;
            fin[33] = 0.0f;
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
        std::vector<float> in(numFloats);
        std::memcpy(in.data(), data, numFloats * sizeof(float));
        std::vector<float> out(numFloats, 0.0f);
        const int frames = static_cast<int>(numFloats / 2);
        const int r = res.process(in.data(), frames, out.data(), frames + 8);
        fuzz_check(r >= 0);
        if (!bypass) {
            // Linear kernel: all-finite input must produce all-finite output.
            bool allInFinite = true;
            for (float v : in) allInFinite = allInFinite && __builtin_isfinite(v);
            if (allInFinite) {
                for (size_t i = 0; i < out.size(); ++i) {
                    fuzz_check(__builtin_isfinite(out[i]));
                }
            }
        } else {
            // Bypass: bit-exact passthrough.
            fuzz_check(std::memcmp(out.data(), in.data(), in.size() * sizeof(float)) == 0);
        }
        res.SetSilentBypass(false);
    }

    return 0;
}
