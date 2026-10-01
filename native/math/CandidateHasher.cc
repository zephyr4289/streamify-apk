// ============================================================================
//  CandidateHasher.cc — NEON bulk candidate-ID hashing (Phase 2)
// ============================================================================
//
//  See CandidateHasher.h for the design contract. The stripe kernel below is
//  the bit-exact NEON twin of mixStripe(): same operations, same order —
//  integer modular arithmetic makes the equivalence structural (u32 x u32
//  products cannot overflow, u64 adds commute), not incidental.
// ============================================================================

#include "CandidateHasher.h"

#include <cstring>

#include "../util/NeonCompat.h"

#if STREAMIFY_HAVE_NEON
#include <arm_neon.h>
#endif

namespace streamify {
namespace math {
namespace {

// XXH3 / XXH64 prime constants (xxHash, BSD-2 licensed reference values).
constexpr uint64_t kPrime1 = 0x9E3779B185EEDA5Bull;
constexpr uint64_t kPrime2 = 0xC2B2AE3D27D4EB4Full;
constexpr uint64_t kPrime3 = 0x165667B19E3779F9ull;
constexpr uint64_t kPrime4 = 0x85EBCA77C2B2AE63ull;
constexpr uint64_t kPrime5 = 0x27D4EB2F165667C5ull;

inline uint64_t rotl64(uint64_t x, int r) {
    return (x << r) | (x >> (64 - r));
}

// XXH64 finalizer — the avalanche of the whole construction.
inline uint64_t avalanche64(uint64_t x) {
    x ^= x >> 33;
    x *= kPrime2;
    x ^= x >> 29;
    x *= kPrime3;
    x ^= x >> 32;
    return x;
}

inline uint64_t read64(const char* p) {
    uint64_t v;
    std::memcpy(&v, p, sizeof(v));   // unaligned-safe, UB-free
    return v;
}

inline uint32_t read32(const char* p) {
    uint32_t v;
    std::memcpy(&v, p, sizeof(v));
    return v;
}

// Murmur3-64-style tail mixing, shared by the short path and the long path's
// trailing (< 16) bytes. Advances p / rem; mutates h in place.
inline void mixTail(const char*& p, uint32_t& rem, uint64_t& h) {
    while (rem >= 8) {
        uint64_t k = read64(p);
        k *= kPrime2;
        k = rotl64(k, 31);
        k *= kPrime1;
        h ^= k;
        h = rotl64(h, 27) * kPrime1 + kPrime4;
        p += 8;
        rem -= 8;
    }
    if (rem >= 4) {
        h ^= static_cast<uint64_t>(read32(p)) * kPrime1;
        h = rotl64(h, 23) * kPrime2 + kPrime3;
        p += 4;
        rem -= 4;
    }
    while (rem > 0) {
        h ^= static_cast<uint64_t>(static_cast<uint8_t>(*p++)) * kPrime5;
        h = rotl64(h, 11) * kPrime1;
        --rem;
    }
}

// XXH3-style 32x32 lane-product stripe mix — exact scalar mirror of the NEON
// kernel in hashLongNeon() (identical operation order => bit-identical).
inline void mixStripe(uint64_t& acc0, uint64_t& acc1, uint64_t d0, uint64_t d1,
                      uint64_t k0, uint64_t k1) {
    const uint64_t dk0 = d0 ^ k0;
    const uint64_t dk1 = d1 ^ k1;
    acc0 += static_cast<uint64_t>(static_cast<uint32_t>(dk0)) *
            static_cast<uint64_t>(static_cast<uint32_t>(d0));
    acc1 += static_cast<uint64_t>(static_cast<uint32_t>(dk0 >> 32)) *
            static_cast<uint64_t>(static_cast<uint32_t>(d0 >> 32));
    acc0 += static_cast<uint64_t>(static_cast<uint32_t>(dk1)) *
            static_cast<uint64_t>(static_cast<uint32_t>(d1));
    acc1 += static_cast<uint64_t>(static_cast<uint32_t>(dk1 >> 32)) *
            static_cast<uint64_t>(static_cast<uint32_t>(d1 >> 32));
}

// Position-dependent stripe keys: two different key streams so the two words
// of a stripe can never mix with the same multiplier.
inline void stripeKeys(uint64_t stripeIndex, uint64_t byteOffset, uint64_t& k0,
                       uint64_t& k1) {
    k0 = rotl64(kPrime1 * (stripeIndex + 1), 29) ^ (kPrime2 + byteOffset);
    k1 = rotl64(kPrime2 * (stripeIndex + 2), 31) ^ (kPrime3 + byteOffset);
}

uint64_t finalizeLong(uint64_t acc0, uint64_t acc1, uint32_t length,
                      const char* bytes) {
    uint64_t h = acc0 ^ rotl64(acc1, 29) ^ (static_cast<uint64_t>(length) * kPrime5);
    const uint64_t stripes = length / 16;
    const char* p = bytes + stripes * 16;
    uint32_t rem = length - static_cast<uint32_t>(stripes * 16);
    mixTail(p, rem, h);
    return avalanche64(h);
}

}  // namespace

uint64_t hashCandidateIdReference(const char* bytes, uint32_t length,
                                  uint64_t seed) {
    if (bytes == nullptr || length == 0) {
        return avalanche64(seed ^ kPrime5);
    }
    if (length < 16) {
        uint64_t h = seed ^ (static_cast<uint64_t>(length) * kPrime5);
        const char* p = bytes;
        uint32_t rem = length;
        mixTail(p, rem, h);
        return avalanche64(h);
    }
    const uint64_t stripes = length / 16;
    uint64_t acc0 = seed ^ kPrime1 ^ static_cast<uint64_t>(length);
    uint64_t acc1 = seed ^ kPrime2;
    for (uint64_t i = 0; i < stripes; ++i) {
        const uint64_t off = i * 16;
        uint64_t k0;
        uint64_t k1;
        stripeKeys(i, off, k0, k1);
        mixStripe(acc0, acc1, read64(bytes + off), read64(bytes + off + 8), k0,
                  k1);
    }
    return finalizeLong(acc0, acc1, length, bytes);
}

#if STREAMIFY_HAVE_NEON
// NEON stripe kernel — the vector twin of the scalar loop above. v7-safe
// instruction set only (see header). acc[0] == scalar acc0, acc[1] == acc1.
static uint64_t hashLongNeon(const char* bytes, uint32_t length, uint64_t seed) {
    const uint64_t stripes = length / 16;
    const uint64x1_t a0 = vcreate_u64(seed ^ kPrime1 ^ static_cast<uint64_t>(length));
    const uint64x1_t a1 = vcreate_u64(seed ^ kPrime2);
    uint64x2_t acc = vcombine_u64(a0, a1);
    for (uint64_t i = 0; i < stripes; ++i) {
        const uint64_t off = i * 16;
        uint64_t k0;
        uint64_t k1;
        stripeKeys(i, off, k0, k1);
        // vld1q_u64 accepts arbitrary alignment (ACLE); no typed deref.
        const uint64x2_t data =
            vld1q_u64(reinterpret_cast<const uint64_t*>(bytes + off));
        const uint64x2_t key = vcombine_u64(vcreate_u64(k0), vcreate_u64(k1));
        const uint64x2_t dk = veorq_u64(data, key);
        const uint32x4_t d32 = vreinterpretq_u32_u64(data);
        const uint32x4_t k32 = vreinterpretq_u32_u64(dk);
        const uint64x2_t p0 =
            vmull_u32(vget_low_u32(d32), vget_low_u32(k32));
        const uint64x2_t p1 =
            vmull_u32(vget_high_u32(d32), vget_high_u32(k32));
        acc = vaddq_u64(vaddq_u64(acc, p0), p1);
    }
    return finalizeLong(vgetq_lane_u64(acc, 0), vgetq_lane_u64(acc, 1),
                        length, bytes);
}
#endif  // STREAMIFY_HAVE_NEON

uint64_t hashCandidateId(const char* bytes, uint32_t length, uint64_t seed) {
#if STREAMIFY_HAVE_NEON
    if (bytes != nullptr && length >= 16) {
        return hashLongNeon(bytes, length, seed);
    }
#endif
    return hashCandidateIdReference(bytes, length, seed);
}

void hashCandidateIdsBulk(const CandidateIdSpan* ids, std::size_t count,
                          uint64_t* outHashes, uint64_t seed) {
    if (ids == nullptr || outHashes == nullptr) {
        return;
    }
    for (std::size_t i = 0; i < count; ++i) {
        if (i + 1 < count && ids[i + 1].bytes != nullptr) {
            __builtin_prefetch(ids[i + 1].bytes, 0, 3);
        }
        outHashes[i] = hashCandidateId(ids[i].bytes, ids[i].length, seed);
    }
}

}  // namespace math
}  // namespace streamify

// ---------------------------------------------------------------------------
// Frozen C-ABI (Rust FFI) — see CandidateHasher.h.
// ---------------------------------------------------------------------------
extern "C" {

STREAMIFY_MATH_API uint64_t streamify_hash_candidate_id(const char* id,
                                                        int32_t length) {
    if (id == nullptr) {
        return streamify::math::hashCandidateId(nullptr, 0);
    }
    uint32_t len;
    if (length < 0) {
        len = static_cast<uint32_t>(std::strlen(id));
    } else {
        len = static_cast<uint32_t>(length);
    }
    return streamify::math::hashCandidateId(id, len);
}

STREAMIFY_MATH_API void streamify_hash_candidate_ids_bulk(
    const char* const* idBytes, const int32_t* idLengths, int32_t count,
    uint64_t* outHashes) {
    if (idBytes == nullptr || idLengths == nullptr || outHashes == nullptr ||
        count <= 0) {
        return;
    }
    for (int32_t i = 0; i < count; ++i) {
        const char* bytes = idBytes[i];
        if (bytes == nullptr) {
            outHashes[i] =
                streamify::math::hashCandidateId(nullptr, 0);
            continue;
        }
        const int32_t len = idLengths[i];
        outHashes[i] = streamify::math::hashCandidateId(
            bytes, len > 0 ? static_cast<uint32_t>(len) : 0u);
    }
}

}  // extern "C"
