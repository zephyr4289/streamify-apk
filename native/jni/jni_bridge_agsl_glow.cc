// ============================================================================
//  jni_bridge_agsl_glow.cc — Phase-3 AGSL ambient-glow bridge (BEHIND.md
//  #45) for com.streamify.app.ui.visual.NativeAmbientGlow.
// ============================================================================
//
//  JNI SURFACE (all static):
//
//    nativeGetShaderSource(): String
//    nativeGetUniformNames(): Array<String>
//    nativeGetUniformArities(): IntArray
//    nativeGetUniformCount(): Int
//    nativeGetUniformFloatCount(): Int
//    nativeGetConfigFloatCount(): Int
//    nativeDefaultConfig(outConfigFloats: FloatArray): Int
//    nativeComputeFrameParams(monotonicMs: Long, width: Int, height: Int,
//                             configFloats: FloatArray,
//                             outParams: FloatArray): Int
//
//  CONTRACT
//  --------
//  * nativeGetShaderSource returns the full AGSL (SKSL) program for
//    RuntimeShader(sources). ASCII only.
//  * nativeGetUniformNames/Arities describe the flat GlowShaderParams
//    packing (14 uniforms, 21 floats): bind uniform i with arity a_i by
//    consuming a_i consecutive floats from the params array.
//  * nativeComputeFrameParams runs the zero-allocation per-frame
//    generator: monotonicMs is loop-relative time (pass
//    SystemClock.elapsedRealtime() - loopOriginMs); config floats come
//    from nativeDefaultConfig or a Kotlin-modified copy (16 floats).
//    Non-finite config entries are sanitized, never propagated.
//    Returns the written float count (21), or 0 on bad arguments.
//  * THREADING: pure functions; safe from any thread.
// ============================================================================

#include <jni.h>

#include "../agsl/AmbientGlowShader.h"

using streamify::agsl::ambientGlowShaderSource;
using streamify::agsl::ambientGlowShaderSourceLength;
using streamify::agsl::AmbientGlowConfig;
using streamify::agsl::AmbientGlowRuntime;
using streamify::agsl::GlowShaderParams;
using streamify::agsl::kConfigFloatCount;
using streamify::agsl::kUniformArities;
using streamify::agsl::kUniformCount;
using streamify::agsl::kUniformNames;

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_streamify_app_ui_visual_NativeAmbientGlow_nativeGetShaderSource(
    JNIEnv* env, jclass /* clazz */) {
    if (env == nullptr) {
        return nullptr;
    }
    return env->NewStringUTF(ambientGlowShaderSource());
}

JNIEXPORT jobjectArray JNICALL
Java_com_streamify_app_ui_visual_NativeAmbientGlow_nativeGetUniformNames(
    JNIEnv* env, jclass /* clazz */) {
    if (env == nullptr) {
        return nullptr;
    }
    const jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) {
        return nullptr;
    }
    jobjectArray arr =
        env->NewObjectArray(kUniformCount, stringClass, nullptr);
    if (arr == nullptr) {
        return nullptr;
    }
    for (int i = 0; i < kUniformCount; ++i) {
        const jstring s = env->NewStringUTF(kUniformNames[i]);
        if (s != nullptr) {
            env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
            // Local references are frame-scoped; no explicit delete needed
            // for 14 short-lived strings, but stay tidy on ART anyway.
            env->DeleteLocalRef(reinterpret_cast<jobject>(s));
        }
    }
    return arr;
}

JNIEXPORT jintArray JNICALL
Java_com_streamify_app_ui_visual_NativeAmbientGlow_nativeGetUniformArities(
    JNIEnv* env, jclass /* clazz */) {
    if (env == nullptr) {
        return nullptr;
    }
    jintArray arr = env->NewIntArray(kUniformCount);
    if (arr == nullptr) {
        return nullptr;
    }
    jint vals[kUniformCount];
    for (int i = 0; i < kUniformCount; ++i) {
        vals[i] = kUniformArities[i];
    }
    env->SetIntArrayRegion(arr, 0, kUniformCount, vals);
    return arr;
}

JNIEXPORT jint JNICALL
Java_com_streamify_app_ui_visual_NativeAmbientGlow_nativeGetUniformCount(
    JNIEnv* /* env */, jclass /* clazz */) {
    return kUniformCount;
}

JNIEXPORT jint JNICALL
Java_com_streamify_app_ui_visual_NativeAmbientGlow_nativeGetUniformFloatCount(
    JNIEnv* /* env */, jclass /* clazz */) {
    return GlowShaderParams::kFloatCount;
}

JNIEXPORT jint JNICALL
Java_com_streamify_app_ui_visual_NativeAmbientGlow_nativeGetConfigFloatCount(
    JNIEnv* /* env */, jclass /* clazz */) {
    return kConfigFloatCount;
}

JNIEXPORT jint JNICALL
Java_com_streamify_app_ui_visual_NativeAmbientGlow_nativeDefaultConfig(
    JNIEnv* env, jclass /* clazz */, jfloatArray outConfigFloats) {
    if (env == nullptr || outConfigFloats == nullptr ||
        env->GetArrayLength(outConfigFloats) < kConfigFloatCount) {
        return 0;
    }
    AmbientGlowConfig cfg;  // header defaults
    float packed[kConfigFloatCount];
    AmbientGlowRuntime::configToFloats(cfg, packed);
    jfloat* out = static_cast<jfloat*>(
        env->GetPrimitiveArrayCritical(outConfigFloats, nullptr));
    if (out == nullptr) {
        return 0;
    }
    for (int i = 0; i < kConfigFloatCount; ++i) {
        out[i] = packed[i];
    }
    env->ReleasePrimitiveArrayCritical(outConfigFloats, out, 0);
    return kConfigFloatCount;
}

JNIEXPORT jint JNICALL
Java_com_streamify_app_ui_visual_NativeAmbientGlow_nativeComputeFrameParams(
    JNIEnv* env, jclass /* clazz */, jlong monotonicMs, jint width, jint height,
    jfloatArray configFloats, jfloatArray outParams) {
    if (env == nullptr || configFloats == nullptr || outParams == nullptr) {
        return 0;
    }
    if (env->GetArrayLength(configFloats) < kConfigFloatCount ||
        env->GetArrayLength(outParams) < GlowShaderParams::kFloatCount) {
        return 0;
    }
    // Resolve + copy the config BEFORE pinning the output (no JNI calls
    // inside critical sections).
    float cfgIn[kConfigFloatCount];
    {
        jboolean isCopy = JNI_FALSE;
        jfloat* in = static_cast<jfloat*>(
            env->GetPrimitiveArrayCritical(configFloats, &isCopy));
        if (in == nullptr) {
            return 0;
        }
        for (int i = 0; i < kConfigFloatCount; ++i) {
            cfgIn[i] = in[i];
        }
        env->ReleasePrimitiveArrayCritical(configFloats, in, JNI_ABORT);
    }
    AmbientGlowConfig cfg;
    AmbientGlowRuntime::floatsToConfig(cfgIn, &cfg);

    GlowShaderParams params;
    AmbientGlowRuntime::computeFrameParams(
        static_cast<int64_t>(monotonicMs), width, height, cfg, &params);

    jboolean isCopy = JNI_FALSE;
    jfloat* out = static_cast<jfloat*>(
        env->GetPrimitiveArrayCritical(outParams, &isCopy));
    if (out == nullptr) {
        return 0;
    }
    const float* src = reinterpret_cast<const float*>(&params);
    for (int i = 0; i < GlowShaderParams::kFloatCount; ++i) {
        out[i] = src[i];
    }
    env->ReleasePrimitiveArrayCritical(outParams, out, 0);
    return GlowShaderParams::kFloatCount;
}

}  // extern "C"
