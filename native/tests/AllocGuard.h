#ifndef STREAMIFY_TEST_ALLOC_GUARD_H
#define STREAMIFY_TEST_ALLOC_GUARD_H
// ============================================================================
//  AllocGuard.h — shared zero-allocation audit guard for the native suites
// ============================================================================
//
//  The dsp_test_suite binary links several test TUs; the global
//  operator new/delete replacements and the thread-local counters live in
//  exactly ONE TU (test_dsp_phase1.cc, which owns them since Phase 1).
//  Other suites (test_harmonic_math.cc, ...) include this header to arm
//  the same guard around their own hot-path calls.
//
//  Usage:   { streamify_test::AllocGuard g; hotPathCall(...);
//            check(g.count() == 0, "zero allocs"); }
// ============================================================================

namespace streamify_test {

// Defined in test_dsp_phase1.cc (single TU).
extern thread_local int g_guardDepth;
extern thread_local unsigned long long g_allocCount;

struct AllocGuard {
    AllocGuard() {
        ++g_guardDepth;
        g_allocCount = 0;
    }
    ~AllocGuard() { --g_guardDepth; }
    unsigned long long count() const { return g_allocCount; }
};

}  // namespace streamify_test

#endif  // STREAMIFY_TEST_ALLOC_GUARD_H
