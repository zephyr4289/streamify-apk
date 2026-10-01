#include <algorithm>
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
// Phase-2 additions (BEHIND.md #15/#20/#25/#26/#39).
#include "../math/CandidateHasher.h"
#include "../math/CircadianCurves.h"
#include "../math/HarmonicTransitionEngine.h"

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

    // 8. Phase-2: candidate dedup kernels — deep structural invariants over
    //    two fuzz-derived u64 streams (heavy-collision domain half the time
    //    so both the table fast path and dup-heavy paths stay exercised).
    {
        const size_t numU64 = size / sizeof(uint64_t);
        if (numU64 >= 8) {
            size_t half = numU64 / 2;
            if (half > 96) half = 96;              // bound the O(n^2) checks
            std::vector<uint64_t> a(half);
            std::vector<uint64_t> b(std::min(numU64 - half, static_cast<size_t>(96)));
            std::memcpy(a.data(), data, a.size() * sizeof(uint64_t));
            std::memcpy(b.data(), data + a.size() * sizeof(uint64_t),
                        b.size() * sizeof(uint64_t));
            if ((data[0] & 1) != 0) {
                for (auto& v : a) v %= 7;
                for (auto& v : b) v %= 7;
            }
            const auto nA = static_cast<int32_t>(a.size());
            const auto nB = static_cast<int32_t>(b.size());
            auto memberOf = [](const std::vector<uint64_t>& v, uint64_t h) {
                for (uint64_t x : v)
                    if (x == h) return true;
                return false;
            };

            std::vector<uint8_t> ma(a.size()), mb(b.size());
            const int32_t common = streamify::math::computeIntersectionMasks(
                a.data(), nA, b.data(), nB, ma.data(), mb.data());
            fuzz_check(common >= 0 && common <= nA);
            for (int32_t i = 0; i < nA; ++i) {
                fuzz_check(ma[static_cast<size_t>(i)] ==
                           (memberOf(b, a[static_cast<size_t>(i)]) ? 1 : 0));
            }
            for (int32_t j = 0; j < nB; ++j) {
                fuzz_check(mb[static_cast<size_t>(j)] ==
                           (memberOf(a, b[static_cast<size_t>(j)]) ? 1 : 0));
            }

            // Full-capacity union + invariants.
            std::vector<uint64_t> u(static_cast<size_t>(nA) + nB, 0xAB);
            std::vector<uint8_t> ov(u.size(), 7);
            const int32_t n = streamify::math::computeUniqueUnion(
                a.data(), nA, b.data(), nB, u.data(), ov.data(),
                static_cast<int32_t>(u.size()));
            fuzz_check(n >= 0 && static_cast<size_t>(n) <= u.size());
            // (i) no duplicates in the union.
            for (int32_t i = 0; i < n; ++i) {
                for (int32_t j = i + 1; j < n; ++j) {
                    fuzz_check(u[static_cast<size_t>(i)] != u[static_cast<size_t>(j)]);
                }
            }
            // (ii) every union element belongs to A or B; overlap flag
            //      set iff it belongs to BOTH.
            for (int32_t i = 0; i < n; ++i) {
                const uint64_t h = u[static_cast<size_t>(i)];
                const bool inA = memberOf(a, h);
                const bool inB = memberOf(b, h);
                fuzz_check(inA || inB);
                fuzz_check(ov[static_cast<size_t>(i)] == ((inA && inB) ? 1 : 0));
            }
            // (iii) every unique A value and every B-only value is present.
            for (int32_t i = 0; i < nA; ++i) {
                bool first = true;
                for (int32_t k = 0; k < i; ++k) {
                    if (a[static_cast<size_t>(k)] == a[static_cast<size_t>(i)]) {
                        first = false;
                        break;
                    }
                }
                if (first) fuzz_check(memberOf(u, a[static_cast<size_t>(i)]));
            }
            for (int32_t j = 0; j < nB; ++j) {
                const uint64_t h = b[static_cast<size_t>(j)];
                if (!memberOf(a, h)) fuzz_check(memberOf(u, h));
            }
            // (iv) size probe (NULL output) returns the SAME required size.
            fuzz_check(streamify::math::computeUniqueUnion(
                           a.data(), nA, b.data(), nB, nullptr, nullptr, 0) == n);
            // (v) truncation: capacity n-1 still returns n and matches the
            //     first n-1 entries of the full write.
            if (n > 0) {
                std::vector<uint64_t> ut(u.size(), 0xAB);
                const int32_t nt = streamify::math::computeUniqueUnion(
                    a.data(), nA, b.data(), nB, ut.data(), nullptr, n - 1);
                fuzz_check(nt == n);
                fuzz_check(std::memcmp(ut.data(), u.data(),
                                       static_cast<size_t>(n - 1) * sizeof(uint64_t)) == 0);
            }
        }
    }

    // 9. Phase-2: transition scoring — hostile keys and RAW float BPMs
    //    (fuzz bytes are frequently NaN/Inf/huge bit patterns; the engine
    //    must stay finite and in [0,1] for ALL of them).
    if (size >= 28) {
        int32_t keyA;
        int32_t keyB;
        std::memcpy(&keyA, data, 4);
        std::memcpy(&keyB, data + 4, 4);
        float bpmA;
        float bpmB;
        std::memcpy(&bpmA, data + 8, 4);
        std::memcpy(&bpmB, data + 12, 4);
        const bool minorA = (data[16] & 1) != 0;
        const bool minorB = (data[17] & 1) != 0;

        const float s = streamify::math::transitionCompatibility(
            keyA, minorA, bpmA, keyB, minorB, bpmB);
        fuzz_check(__builtin_isfinite(s) && s >= 0.0f && s <= 1.0f);
        const float k = streamify::math::keyTransitionScore(keyA, minorA, keyB,
                                                            minorB);
        fuzz_check(__builtin_isfinite(k) && k >= 0.0f && k <= 1.0f);
        const float bpm = streamify::math::bpmTransitionScore(bpmA, bpmB);
        fuzz_check(__builtin_isfinite(bpm) && bpm >= 0.0f && bpm <= 1.0f);
    }

    // 10. Phase-2: circadian curves — hostile local hours (NaN/Inf/huge),
    //     plus a finite-domain periodicity spot check (t == t + 24).
    if (size >= 36) {
        float hour;
        std::memcpy(&hour, data + 20, 4);
        const auto w = streamify::math::circadianWeights(hour);
        fuzz_check(__builtin_isfinite(w.energy) && w.energy >= 0.0f &&
                   w.energy <= 1.0f);
        fuzz_check(__builtin_isfinite(w.acousticness) && w.acousticness >= 0.0f &&
                   w.acousticness <= 1.0f);
        fuzz_check(__builtin_isfinite(w.valence) && w.valence >= 0.0f &&
                   w.valence <= 1.0f);
        // Periodicity identity t == t + 24 holds wherever float arithmetic
        // can represent the shift exactly: at |hour| >= ~5e8 the ULP spacing
        // exceeds 24, hour+24 rounds to hour+32, and fmod legitimately
        // differs (a float-precision reality, not an engine defect). Guard
        // to +-1e8 (ULP <= 8, 24 = 3 ULP — exact).
        if (__builtin_isfinite(hour) && hour > -1e8f && hour < 1e8f) {
            const auto w2 = streamify::math::circadianWeights(hour + 24.0f);
            const float de = w2.energy - w.energy;
            const float da = w2.acousticness - w.acousticness;
            const float dv = w2.valence - w.valence;
            fuzz_check(de * de + da * da + dv * dv < 1e-6f);
        }
    }

    // 11. Phase-2: candidate hashing — determinism, production == reference,
    //     bulk == single, across arbitrary byte spans (any length 0..64).
    if (size >= 24) {
        const uint32_t spanLen = data[24] % 65;
        if (static_cast<size_t>(1 + spanLen) <= size) {
            const char* span = reinterpret_cast<const char*>(data) + 1;
            const uint64_t h1 = streamify::math::hashCandidateId(span, spanLen);
            const uint64_t h2 = streamify::math::hashCandidateId(span, spanLen);
            fuzz_check(h1 == h2);
            fuzz_check(h1 == streamify::math::hashCandidateIdReference(span,
                                                                       spanLen));
            streamify::math::CandidateIdSpan spans[2] = {{span, spanLen},
                                                          {span, spanLen}};
            uint64_t out[2] = {1, 2};
            streamify::math::hashCandidateIdsBulk(spans, 2, out);
            fuzz_check(out[0] == h1 && out[1] == h1);
        }
    }

    return 0;
}
