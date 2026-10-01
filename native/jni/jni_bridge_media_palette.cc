// ============================================================================
//  jni_bridge_media_palette.cc — Phase-3 palette bridge (BEHIND.md #57)
//  for com.streamify.app.media.NativeMediaPalette (Engineer 3 binds Kotlin
//  against these EXACT symbols).
// ============================================================================
//
//  JNI SURFACE (all static):
//
//    nativeExtractPalette(pixels: ByteArray, width: Int, height: Int,
//                         format: Int, outArgb4: IntArray): Int
//    nativeExtractPaletteDirect(pixels: ByteBuffer, width: Int, height: Int,
//                               format: Int, outArgb4: IntArray): Int
//    nativeExtractPaletteFull(pixels: ByteArray, width: Int, height: Int,
//                             format: Int, outArgb4: IntArray,
//                             outMetrics4: FloatArray): Int
//    nativeBytesPerPixel(format: Int): Int
//
//  CONTRACT
//  --------
//  * format: 0 = RGBA_8888 (byte order R,G,B,A — Android Bitmap
//    ARGB_8888 via copyPixelsToBuffer), 1 = ARGB_8888 (A,R,G,B), 2 =
//    RGB_565. Unknown values return status 1 without touching outputs.
//  * outArgb4 receives [primary, secondary, textForeground, ambientGlow]
//    as 0xAARRGGBB (alpha always 0xFF); arrays shorter than 4 are a no-op
//    returning status 1 (invalid argument).
//  * outMetrics4 (optional, may be null) receives
//    [foregroundContrastRatio, dominantPopulation, swatchCount, 0].
//  * Return codes mirror PaletteStatus: 0 ok, 1 invalid argument,
//    2 bad dimensions, 3 bad buffer size, 4 too many pixels.
//  * Zero-copy via GetPrimitiveArrayCritical (lengths resolved BEFORE
//    pinning; no JNI calls inside the critical section) and direct
//    ByteBuffer addresses for the media-pipeline path.
//  * THREADING: one internal thread_local extractor per calling thread;
//    extract() itself allocates nothing.
// ============================================================================

#include <jni.h>

#include "../palette/BitmapPalette.h"

using streamify::palette::BitmapPaletteExtractor;
using streamify::palette::PaletteResult;
using streamify::palette::PaletteStatus;
using PF = streamify::palette::PixelFormat;

namespace {

PF toFormat(jint f) {
    switch (f) {
    case 0: return PF::kRgba8888;
    case 1: return PF::kArgb8888;
    case 2: return PF::kRgb565;
    default: return static_cast<PF>(-1);
    }
}

jint asJint(PaletteStatus s) { return static_cast<jint>(s); }

// Shared writer: fills the two output arrays from a result.
void writeOutputs(JNIEnv* env, jintArray outArgb4, jfloatArray outMetrics4,
                  const PaletteResult& r) {
    if (outArgb4 != nullptr) {
        jint vals[4] = {static_cast<jint>(r.primaryArgb),
                        static_cast<jint>(r.secondaryArgb),
                        static_cast<jint>(r.textForegroundArgb),
                        static_cast<jint>(r.ambientGlowArgb)};
        jint* out = static_cast<jint*>(
            env->GetPrimitiveArrayCritical(outArgb4, nullptr));
        if (out != nullptr) {
            out[0] = vals[0];
            out[1] = vals[1];
            out[2] = vals[2];
            out[3] = vals[3];
            env->ReleasePrimitiveArrayCritical(outArgb4, out, 0);
        }
    }
    if (outMetrics4 != nullptr) {
        jfloat met[4] = {r.foregroundContrastRatio,
                         static_cast<jfloat>(
                             static_cast<float>(r.dominantPopulation)),
                         static_cast<jfloat>(static_cast<float>(r.swatchCount)),
                         0.0f};
        jfloat* out = static_cast<jfloat*>(
            env->GetPrimitiveArrayCritical(outMetrics4, nullptr));
        if (out != nullptr) {
            out[0] = met[0];
            out[1] = met[1];
            out[2] = met[2];
            out[3] = met[3];
            env->ReleasePrimitiveArrayCritical(outMetrics4, out, 0);
        }
    }
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_streamify_app_media_NativeMediaPalette_nativeExtractPalette(
    JNIEnv* env, jclass /* clazz */, jbyteArray pixels, jint width, jint height,
    jint format, jintArray outArgb4) {
    if (env == nullptr) return asJint(PaletteStatus::kInvalidArgument);
    if (outArgb4 == nullptr || env->GetArrayLength(outArgb4) < 4) {
        return asJint(PaletteStatus::kInvalidArgument);
    }
    const PF fmt = toFormat(format);
    if (static_cast<jint>(fmt) < 0) {
        return asJint(PaletteStatus::kInvalidArgument);
    }
    // Resolve the input length BEFORE pinning (critical-section rules).
    const jsize pixelLen =
        pixels != nullptr ? env->GetArrayLength(pixels) : 0;
    if (pixels == nullptr) {
        return asJint(PaletteStatus::kInvalidArgument);
    }
    jboolean isCopy = JNI_FALSE;
    jbyte* in = static_cast<jbyte*>(
        env->GetPrimitiveArrayCritical(pixels, &isCopy));
    if (in == nullptr) {
        return asJint(PaletteStatus::kInvalidArgument);
    }
    static thread_local BitmapPaletteExtractor extractor;  // ~64 KB scratch
    PaletteResult result;
    const PaletteStatus status = extractor.extract(
        reinterpret_cast<const uint8_t*>(in),
        static_cast<size_t>(pixelLen), width, height, fmt, &result);
    env->ReleasePrimitiveArrayCritical(pixels, in, JNI_ABORT);
    if (status == PaletteStatus::kOk) {
        writeOutputs(env, outArgb4, nullptr, result);
    }
    return asJint(status);
}

JNIEXPORT jint JNICALL
Java_com_streamify_app_media_NativeMediaPalette_nativeExtractPaletteDirect(
    JNIEnv* env, jclass /* clazz */, jobject pixels, jint width, jint height,
    jint format, jintArray outArgb4) {
    if (env == nullptr) return asJint(PaletteStatus::kInvalidArgument);
    if (outArgb4 == nullptr || env->GetArrayLength(outArgb4) < 4) {
        return asJint(PaletteStatus::kInvalidArgument);
    }
    const PF fmt = toFormat(format);
    if (static_cast<jint>(fmt) < 0) {
        return asJint(PaletteStatus::kInvalidArgument);
    }
    if (pixels == nullptr) {
        return asJint(PaletteStatus::kInvalidArgument);
    }
    // Direct buffers: address + capacity, no copying, no pinning.
    auto* in = static_cast<const uint8_t*>(env->GetDirectBufferAddress(pixels));
    const jlong cap = env->GetDirectBufferCapacity(pixels);
    if (in == nullptr || cap <= 0) {
        return asJint(PaletteStatus::kInvalidArgument);
    }
    static thread_local BitmapPaletteExtractor extractor;
    PaletteResult result;
    const PaletteStatus status = extractor.extract(
        in, static_cast<size_t>(cap), width, height, fmt, &result);
    if (status == PaletteStatus::kOk) {
        writeOutputs(env, outArgb4, nullptr, result);
    }
    return asJint(status);
}

JNIEXPORT jint JNICALL
Java_com_streamify_app_media_NativeMediaPalette_nativeExtractPaletteFull(
    JNIEnv* env, jclass /* clazz */, jbyteArray pixels, jint width, jint height,
    jint format, jintArray outArgb4, jfloatArray outMetrics4) {
    if (env == nullptr) return asJint(PaletteStatus::kInvalidArgument);
    if (outArgb4 == nullptr || env->GetArrayLength(outArgb4) < 4 ||
        (outMetrics4 != nullptr && env->GetArrayLength(outMetrics4) < 4)) {
        return asJint(PaletteStatus::kInvalidArgument);
    }
    const PF fmt = toFormat(format);
    if (static_cast<jint>(fmt) < 0) {
        return asJint(PaletteStatus::kInvalidArgument);
    }
    if (pixels == nullptr) {
        return asJint(PaletteStatus::kInvalidArgument);
    }
    const jsize pixelLen = env->GetArrayLength(pixels);
    jboolean isCopy = JNI_FALSE;
    jbyte* in = static_cast<jbyte*>(
        env->GetPrimitiveArrayCritical(pixels, &isCopy));
    if (in == nullptr) {
        return asJint(PaletteStatus::kInvalidArgument);
    }
    static thread_local BitmapPaletteExtractor extractor;
    PaletteResult result;
    const PaletteStatus status = extractor.extract(
        reinterpret_cast<const uint8_t*>(in),
        static_cast<size_t>(pixelLen), width, height, fmt, &result);
    env->ReleasePrimitiveArrayCritical(pixels, in, JNI_ABORT);
    if (status == PaletteStatus::kOk) {
        writeOutputs(env, outArgb4, outMetrics4, result);
    }
    return asJint(status);
}

JNIEXPORT jint JNICALL
Java_com_streamify_app_media_NativeMediaPalette_nativeBytesPerPixel(
    JNIEnv* /* env */, jclass /* clazz */, jint format) {
    const PF fmt = toFormat(format);
    return static_cast<jint>(fmt) >= 0
               ? BitmapPaletteExtractor::bytesPerPixel(fmt)
               : 0;
}

}  // extern "C"
