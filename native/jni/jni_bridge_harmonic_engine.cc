// ============================================================================
//  jni_bridge_harmonic_engine.cc — Phase-2 harmonic math bridge (BEHIND.md
//  gaps #15/#20/#25/#26/#39) for com.streamify.app.audio.NativeHarmonicEngine
//  (Engineer 3 binds Kotlin against these EXACT symbols).
// ============================================================================
//
//  FROZEN JNI ABI (Phase-2 directive §5 — names, order, and types contract):
//
//    deduplicateCandidateHashes(
//        hashesA: LongArray, hashesB: LongArray,
//        outUnion: LongArray, outIsOverlap: BooleanArray): Int
//    calculateTransitionScore(
//        keyA: Int, isMinorA: Boolean, bpmA: Float,
//        keyB: Int, isMinorB: Boolean, bpmB: Float): Float
//    getCircadianWeights(hourOfDay: Float, outWeights3: FloatArray)
//
//  deduplicateCandidateHashes CONTRACT
//  -----------------------------------
//  * Input arrays hold the 64-bit candidate-ID hashes of Member A's and
//    Member B's candidate streams (see CandidateHasher for the hashing side;
//    Rust peers can produce identical hashes via streamify_hash_candidate_*).
//  * Writes the unique union (A-first, first-seen order) into outUnion and
//    per-element overlap flags (1 iff the hash occurs in BOTH streams) into
//    outIsOverlap. Writes are clamped to min(outUnion.size,
//    outIsOverlap.size); the RETURN value is the REQUIRED union size, so a
//    Kotlin caller detects truncation via `result > outUnion.size` and
//    retries with larger arrays.
//  * NULL hashesA/hashesB are treated as empty streams; NULL outputs act as
//    capacity 0 (pure size probe). Returns -1 only if the JVM cannot pin
//    the arrays (out of memory).
//  * Zero-copy via GetPrimitiveArrayCritical; zero allocation and zero
//    locks on the call path; the kernels run in microseconds.
//
//  calculateTransitionScore CONTRACT
//  ---------------------------------
//  * Camelot key numbers 1..12 with isMinor = Camelot A; values outside
//    1..12 (missing metadata) score NEUTRAL 0.5 on the key axis. BPM
//    non-finite or <= 0 scores neutral 0.5 on the tempo axis.
//  * Returns the combined coefficient clamp(0.6*key + 0.4*bpm) in [0, 1].
//
//  getCircadianWeights CONTRACT
//  ----------------------------
//  * hourOfDay is the LOCAL float hour in [0, 24) (wraps otherwise; any
//    non-finite value is a no-op — the Kotlin side owns clock reading).
//  * Writes [energy, acousticness, valence] (each in [0,1]) into the first
//    3 slots of outWeights3; arrays shorter than 3 are a no-op.
//
//  ADDITIVE EXTENSIONS (stable, NOT part of the freeze — same policy as the
//  Phase-1 NativeDspEngine bridge):
//    hashCandidateId(id: String): Long          // XXH3-style 64-bit id hash
//    keyTransitionScore(keyA, isMinorA, keyB, isMinorB): Float
//    bpmTransitionScore(bpmA, bpmB): Float
//
//  THREADING: all entry points are pure functions over their arguments
//  (plus caller-owned buffers) — safe from any thread, no locks, no state.
// ============================================================================

#include <jni.h>

#include "../math/CandidateHasher.h"
#include "../math/CircadianCurves.h"
#include "../math/HarmonicTransitionEngine.h"

using streamify::math::bpmTransitionScore;
using streamify::math::camelotKeyValid;
using streamify::math::circadianWeights;
using streamify::math::computeUniqueUnion;
using streamify::math::hashCandidateId;
using streamify::math::keyTransitionScore;
using streamify::math::transitionCompatibility;

extern "C" {

// ---- FROZEN ABI -------------------------------------------------------------

JNIEXPORT jint JNICALL
Java_com_streamify_app_audio_NativeHarmonicEngine_deduplicateCandidateHashes(
    JNIEnv* env, jclass /* clazz */, jlongArray hashesA, jlongArray hashesB,
    jlongArray outUnion, jbooleanArray outIsOverlap) {
    if (env == nullptr) {
        return -1;
    }

    // Capacity = the tighter of the two output arrays (writes clamped).
    const jsize unionLen =
        outUnion != nullptr ? env->GetArrayLength(outUnion) : 0;
    const jsize flagLen =
        outIsOverlap != nullptr ? env->GetArrayLength(outIsOverlap) : 0;
    jint capacity = unionLen < flagLen ? unionLen : flagLen;
    if (capacity < 0) {
        capacity = 0;
    }
    // Stream lengths MUST be resolved before pinning: no JNI calls are
    // allowed between Get*Critical and Release*Critical.
    const int32_t nA = hashesA != nullptr ? env->GetArrayLength(hashesA) : 0;
    const int32_t nB = hashesB != nullptr ? env->GetArrayLength(hashesB) : 0;

    // Pin all four arrays at once (kernel holds the critical section for
    // microseconds and makes no JNI calls inside it).
    jlong* a = nullptr;
    jlong* b = nullptr;
    jlong* u = nullptr;
    jboolean* f = nullptr;
    bool pinned = true;

    if (hashesA != nullptr) {
        a = static_cast<jlong*>(
            env->GetPrimitiveArrayCritical(hashesA, nullptr));
        if (a == nullptr) {
            pinned = false;
        }
    }
    if (pinned && hashesB != nullptr) {
        b = static_cast<jlong*>(
            env->GetPrimitiveArrayCritical(hashesB, nullptr));
        if (b == nullptr) {
            pinned = false;
        }
    }
    if (pinned && outUnion != nullptr && capacity > 0) {
        u = static_cast<jlong*>(
            env->GetPrimitiveArrayCritical(outUnion, nullptr));
        if (u == nullptr) {
            pinned = false;
        }
    }
    if (pinned && outIsOverlap != nullptr && capacity > 0) {
        f = static_cast<jboolean*>(
            env->GetPrimitiveArrayCritical(outIsOverlap, nullptr));
        if (f == nullptr) {
            pinned = false;
        }
    }

    jint result = -1;
    if (pinned) {
        result = computeUniqueUnion(
            reinterpret_cast<const uint64_t*>(a), nA,
            reinterpret_cast<const uint64_t*>(b), nB,
            reinterpret_cast<uint64_t*>(u),
            reinterpret_cast<uint8_t*>(f), capacity);
    }

    // Release in reverse pin order; JNI_ABORT for inputs (no copy back),
    // mode 0 for outputs (copy the union + flags back to the JVM).
    if (f != nullptr) {
        env->ReleasePrimitiveArrayCritical(outIsOverlap, f, 0);
    }
    if (u != nullptr) {
        env->ReleasePrimitiveArrayCritical(outUnion, u, 0);
    }
    if (b != nullptr) {
        env->ReleasePrimitiveArrayCritical(hashesB, b, JNI_ABORT);
    }
    if (a != nullptr) {
        env->ReleasePrimitiveArrayCritical(hashesA, a, JNI_ABORT);
    }
    return result;
}

JNIEXPORT jfloat JNICALL
Java_com_streamify_app_audio_NativeHarmonicEngine_calculateTransitionScore(
    JNIEnv* /* env */, jclass /* clazz */, jint keyA, jboolean isMinorA,
    jfloat bpmA, jint keyB, jboolean isMinorB, jfloat bpmB) {
    // Key numbers outside 1..12 and non-positive/non-finite BPMs are handled
    // INSIDE the engine (neutral 0.5 axes) — the bridge never crashes on
    // incomplete metadata.
    return transitionCompatibility(keyA, isMinorA != JNI_FALSE, bpmA, keyB,
                                   isMinorB != JNI_FALSE, bpmB);
}

JNIEXPORT void JNICALL
Java_com_streamify_app_audio_NativeHarmonicEngine_getCircadianWeights(
    JNIEnv* env, jclass /* clazz */, jfloat hourOfDay,
    jfloatArray outWeights3) {
    if (env == nullptr || outWeights3 == nullptr) {
        return;
    }
    if (env->GetArrayLength(outWeights3) < 3) {
        return;
    }
    jboolean isCopy = JNI_FALSE;
    jfloat* out = static_cast<jfloat*>(
        env->GetPrimitiveArrayCritical(outWeights3, &isCopy));
    if (out == nullptr) {
        return;
    }
    const streamify::math::CircadianWeights w = circadianWeights(hourOfDay);
    out[0] = w.energy;
    out[1] = w.acousticness;
    out[2] = w.valence;
    env->ReleasePrimitiveArrayCritical(outWeights3, out, 0);
}

// ---- Additive extensions (stable, not part of the freeze) -------------------

// XXH3-style 64-bit hash of one candidate id (UTF-8 bytes), bit-identical
// to the C-ABI streamify_hash_candidate_id and to Rust-side hashes.
JNIEXPORT jlong JNICALL
Java_com_streamify_app_audio_NativeHarmonicEngine_hashCandidateId(
    JNIEnv* env, jclass /* clazz */, jstring id) {
    if (env == nullptr || id == nullptr) {
        return static_cast<jlong>(hashCandidateId(nullptr, 0));
    }
    const char* chars = env->GetStringUTFChars(id, nullptr);
    if (chars == nullptr) {
        return static_cast<jlong>(hashCandidateId(nullptr, 0));
    }
    const uint64_t h =
        hashCandidateId(chars, static_cast<uint32_t>(env->GetStringUTFLength(id)));
    env->ReleaseStringUTFChars(id, chars);
    return static_cast<jlong>(h);
}

// Camelot key compatibility alone (see HarmonicTransitionEngine ladder).
JNIEXPORT jfloat JNICALL
Java_com_streamify_app_audio_NativeHarmonicEngine_keyTransitionScore(
    JNIEnv* /* env */, jclass /* clazz */, jint keyA, jboolean isMinorA,
    jint keyB, jboolean isMinorB) {
    return keyTransitionScore(keyA, isMinorA != JNI_FALSE, keyB,
                              isMinorB != JNI_FALSE);
}

// Continuous BPM compatibility alone (octave folding + 8% window).
JNIEXPORT jfloat JNICALL
Java_com_streamify_app_audio_NativeHarmonicEngine_bpmTransitionScore(
    JNIEnv* /* env */, jclass /* clazz */, jfloat bpmA, jfloat bpmB) {
    return bpmTransitionScore(bpmA, bpmB);
}

}  // extern "C"
