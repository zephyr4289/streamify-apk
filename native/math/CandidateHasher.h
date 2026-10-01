#ifndef STREAMIFY_CANDIDATE_HASHER_H
#define STREAMIFY_CANDIDATE_HASHER_H
// ============================================================================
//  CandidateHasher.h — NEON bulk candidate-ID hashing (Phase 2, BEHIND.md
//  gaps #15 & #25: Group-Taste Blend / multi-user candidate deduplication)
// ============================================================================
//
//  DESIGN — XXH3-inspired 64-bit hashing (NOT spec-compatible with xxHash;
//  the constants and finalizer family are XXH3/XXH64's, the stripe mixing is
//  our own deterministic construction):
//
//  *  len < 16 bytes : Murmur3-style 8/4/1-byte tail mixing (candidate IDs
//                      are typically 8-30 chars; this path dominates).
//  *  len >= 16     : 16-byte stripes mixed with position-dependent keys via
//                      XXH3's 32x32 lane-product pattern
//                          acc += lo32(data ^ key) * lo32(data)
//                          acc += hi32(data ^ key) * hi32(data)
//                      expressed with v7-safe NEON (vmull_u32 + vaddq_u64).
//  *  Determinism   : integer-only math; the NEON kernel and the scalar
//                      reference execute the IDENTICAL operation sequence,
//                      so hashes are bit-exact across arm64-v8a, armeabi-v7a,
//                      x86, x86_64 and host test runners. Hashes stored by an
//                      arm64 phone always match an x86_64 emulator.
//  *  v7 constraint : no 64-bit NEON multiplies / 64-bit compares —
//                      vmull_u32, vaddq_u64, veorq_u64, vld1q_u64,
//                      vgetq_lane_u64 only (all ARMv7-A NEON).
//  *  Zero heap allocations on every path; caller-owned output buffers.
// ============================================================================

#include <cstddef>
#include <cstdint>

#include "MathExport.h"

namespace streamify {
namespace math {

// Default seed ("STREAMFY" big-endian); exposed so tests / Rust FFI can
// reproduce Kotlin-side hashes exactly.
inline constexpr uint64_t kCandidateHashSeed = 0x53545245414D4659ull;

// One candidate ID = one contiguous byte span (track id, artist id, ...).
struct CandidateIdSpan {
    const char* bytes;
    uint32_t length;
};

// Portable reference implementation. Bit-identical to the NEON kernel on
// every ABI (see design note above); also the golden-vector oracle for tests.
uint64_t hashCandidateIdReference(const char* bytes, uint32_t length,
                                  uint64_t seed = kCandidateHashSeed);

// Production entry point: dispatches to the NEON stripe kernel when built
// with NEON and length >= 16, otherwise the reference path.
// NULL/empty inputs hash to avalanche(seed ^ P5) — deterministic, no crash.
uint64_t hashCandidateId(const char* bytes, uint32_t length,
                         uint64_t seed = kCandidateHashSeed);

// Bulk hashing of a candidate stream: one pass over the span array, next
// span's head prefetched, zero heap allocations. `outHashes` must have room
// for `count` entries; NULL ids/out is a no-op.
void hashCandidateIdsBulk(const CandidateIdSpan* ids, std::size_t count,
                          uint64_t* outHashes,
                          uint64_t seed = kCandidateHashSeed);

}  // namespace math
}  // namespace streamify

// ---------------------------------------------------------------------------
// Frozen C-ABI for Rust consumers (extern "C" blocks in Engineer 2's crates).
// ---------------------------------------------------------------------------
extern "C" {

// Hash one NUL-terminated or length-delimited id (length < 0 => strlen).
STREAMIFY_MATH_API uint64_t streamify_hash_candidate_id(const char* id,
                                                        int32_t length);

// Bulk form: idBytes[i]/idLengths[i] describe candidate i; writes
// outHashes[0..count). All pointers non-null or the call is a no-op.
STREAMIFY_MATH_API void streamify_hash_candidate_ids_bulk(
    const char* const* idBytes, const int32_t* idLengths, int32_t count,
    uint64_t* outHashes);

}  // extern "C"

#endif  // STREAMIFY_CANDIDATE_HASHER_H
