#ifndef STREAMIFY_NEON_COMPAT_H
#define STREAMIFY_NEON_COMPAT_H
// ============================================================================
//  NeonCompat.h — shared ARM NEON + memory portability shims (Phase 1)
// ============================================================================
//
//  streamify_fma_f32 (engineering directive, Phase 1 §4):
//      arm64-v8a    -> vfmaq_f32   (true fused multiply-add, single rounding)
//      armeabi-v7a  -> vmlaq_f32   (v7 NEON MAC baseline)
//      non-NEON     -> macro undefined; scalar paths carry the load.
//
//  streamify_aligned_alloc / _free:
//      Bionic libc (minSdk 26) has no aligned_alloc until API 28 and C11
//      aligned_alloc semantics clash with free() on some CRTs. The directive
//      mandate is posix_memalign for every aligned buffer in native/.
//
//  All other Phase-1 units include this header instead of redefining the
//  macro locally (AcousticPhaseResampler kept a private copy until now).
// ============================================================================

#include <cstddef>
#include <cstdlib>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#define STREAMIFY_HAVE_NEON 1
#include <arm_neon.h>
#else
#define STREAMIFY_HAVE_NEON 0
#endif

#if STREAMIFY_HAVE_NEON
#if defined(__aarch64__) || defined(_M_ARM64)
#define streamify_fma_f32(acc, a, b) vfmaq_f32((acc), (a), (b))
#else
#define streamify_fma_f32(acc, a, b) vmlaq_f32((acc), (a), (b))
#endif
#endif

namespace streamify {

// Bionic-safe aligned allocation (see header comment). Returns nullptr on
// failure. `alignment` must be a power of two >= sizeof(void*).
inline void* alignedAlloc(std::size_t alignment, std::size_t size) {
    if (alignment < sizeof(void*)) alignment = sizeof(void*);
    void* p = nullptr;
    if (posix_memalign(&p, alignment, size != 0 ? size : 1) != 0) {
        return nullptr;
    }
    return p;
}

inline void alignedFree(void* p) {
    ::free(p);
}

// Round `size` up so the buffer end is also `alignment`-aligned (keeps
// vectorized tail loops from touching the next page's padding).
inline std::size_t alignUp(std::size_t size, std::size_t alignment) {
    return ((size + alignment - 1) / alignment) * alignment;
}

}  // namespace streamify

#endif  // STREAMIFY_NEON_COMPAT_H
