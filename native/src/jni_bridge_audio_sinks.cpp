// ============================================================================
//  jni_bridge_audio_sinks.cpp — Phase-4 audio sink bridge
//  (native/src/jni_bridge_audio_sinks.cpp)
// ============================================================================
//
//  JNI SURFACE — frozen by the Phase-4 directive (deliverable 3), bound to
//  com.streamify.core.native NativeAudioSink via app-side RegisterNatives:
//
//    jlong nativeCreateSink(sampleRate: Int, channels: Int,
//                           capacityFrames: Int): Long          // 0 == failure
//    jint  nativeWriteFloat(handle: Long, data: FloatArray,
//                           offset: Int, length: Int): Int      // samples consumed, -1 invalid
//    jint  nativeReadDirect(handle: Long, buf: ByteBuffer,
//                           capacityBytes: Int): Int            // PCM16 bytes, -1 invalid
//    void  nativeGetStats(handle: Long, statsOut: ByteBuffer)   // 96-byte layout
//    void  nativeFlush(handle: Long)
//    void  nativeDestroy(handle: Long)
//
//  CONTRACT
//  --------
//  * Handles are opaque registry ids (streamify_audio_sinks.h), never raw
//    pointers: a stale, garbage or destroyed handle resolves to nullptr and
//    returns the safe sentinel (-1 / silent no-op) — the JVM can never crash
//    on an invalid handle. destroy() racing an in-flight call is safe: the
//    registry hands out shared_ptr copies, so the sink outlives its last
//    user and nativeDestroy is idempotent.
//  * nativeWriteFloat pins `data` with GetPrimitiveArrayCritical; the array
//    length and offset/length bounds are resolved BEFORE pinning and the
//    release runs on every path (no JNI calls inside the critical section).
//    The sink performs zero heap allocation on this path.
//  * nativeReadDirect performs the zero-copy handoff for Android AudioTrack
//    and network pipelines: PCM16 bytes are memcpy'ed straight into the
//    DirectByteBuffer's native address, frame-aligned, bounded by both the
//    caller's capacityBytes and the buffer's real capacity.
//  * nativeGetStats writes the documented 96-byte little-endian telemetry
//    layout (see streamify_audio_sinks.h) when statsOut is a direct buffer
//    with capacity >= 96; anything else is a silent no-op.
//  * Defensive posture: every entrypoint null-checks env/args, validates
//    ranges, and wraps the body in try/catch(...) so no C++ exception can
//    ever escape into the JVM (which would call std::terminate).
//  * THREADING: WriteFloat is the producer thread, ReadDirect the consumer
//    thread (SPSC contract from audio_sink_ringbuffer.h); Create/Stats/
//    Flush/Destroy are control-plane (Flush requires quiesced audio threads,
//    AudioTrack#flush parity).
// ============================================================================

#include <jni.h>

#include <cstdint>

#include "../include/audio_sink_ringbuffer.h"
#include "../include/streamify_audio_sinks.h"

using streamify::sink::AudioSink;
using streamify::sink::SinkRegistry;
using streamify::sink::WriteSinkStatsBuffer;

namespace {

// Registry resolution + shared_ptr keep-alive in one call; nullptr for any
// invalid handle (0, negative, stale, already destroyed, garbage).
std::shared_ptr<AudioSink> sinkFromHandle(jlong handle) {
    if (handle <= 0) return nullptr;
    return SinkRegistry::instance().resolve(static_cast<int64_t>(handle));
}

// DirectBuffer address + capacity validation. Returns false unless the
// object is a direct ByteBuffer with a positive capacity.
bool directBufferAccess(JNIEnv* env, jobject buffer, uint8_t** addr,
                        size_t* capacity) {
    if (env == nullptr || buffer == nullptr) return false;
    void* p = env->GetDirectBufferAddress(buffer);
    const jlong cap = (p != nullptr) ? env->GetDirectBufferCapacity(buffer) : 0;
    if (p == nullptr || cap <= 0) return false;
    *addr = static_cast<uint8_t*>(p);
    *capacity = static_cast<size_t>(cap);
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_streamify_core_native_NativeAudioSink_nativeCreateSink(
    JNIEnv* /* env */, jobject /* thiz */, jint sampleRate, jint channels,
    jint capacityFrames) {
    try {
        if (sampleRate <= 0 || channels <= 0 || capacityFrames <= 0) {
            return 0;
        }
        AudioSink::Config cfg;
        cfg.sample_rate = static_cast<uint32_t>(sampleRate);
        cfg.channels = static_cast<uint32_t>(channels);
        cfg.capacity_frames = static_cast<size_t>(capacityFrames);
        // Passthrough sink (Float32 -> PCM16) — downmix/decimation stay
        // native-internal configurations; the frozen JNI surface creates
        // the plain sink. ValidateConfig enforces all bounds.
        return static_cast<jlong>(
            SinkRegistry::instance().create(cfg));
    } catch (...) {
        return 0;  // OOM or worse: report failure, never crash the JVM
    }
}

JNIEXPORT jint JNICALL
Java_com_streamify_core_native_NativeAudioSink_nativeWriteFloat(
    JNIEnv* env, jobject /* thiz */, jlong handle, jfloatArray data,
    jint offset, jint length) {
    try {
        auto sink = sinkFromHandle(handle);
        if (env == nullptr || sink == nullptr || data == nullptr) {
            return -1;
        }
        if (offset < 0 || length < 0) return -1;
        // Resolve array bounds BEFORE pinning (no JNI calls inside the
        // critical section).
        const jsize arr_len = env->GetArrayLength(data);
        if (offset > arr_len || length > arr_len - offset) return -1;
        if (length == 0) return 0;

        jfloat* raw =
            static_cast<jfloat*>(env->GetPrimitiveArrayCritical(data, nullptr));
        if (raw == nullptr) {
            return -1;  // pinning failed (OOM pending) — propagate failure
        }
        const size_t consumed =
            sink->WriteFloat(raw + offset, static_cast<size_t>(length));
        env->ReleasePrimitiveArrayCritical(data, raw, JNI_ABORT);
        return static_cast<jint>(consumed);
    } catch (...) {
        return -1;
    }
}

JNIEXPORT jint JNICALL
Java_com_streamify_core_native_NativeAudioSink_nativeReadDirect(
    JNIEnv* env, jobject /* thiz */, jlong handle, jobject directBuffer,
    jint capacityBytes) {
    try {
        auto sink = sinkFromHandle(handle);
        if (env == nullptr || sink == nullptr || capacityBytes <= 0) {
            return -1;
        }
        uint8_t* addr = nullptr;
        size_t buf_cap = 0;
        if (!directBufferAccess(env, directBuffer, &addr, &buf_cap)) {
            return -1;
        }
        const size_t want = static_cast<size_t>(capacityBytes);
        const size_t cap = want < buf_cap ? want : buf_cap;
        return static_cast<jint>(sink->ReadBytes(addr, cap));
    } catch (...) {
        return -1;
    }
}

JNIEXPORT void JNICALL
Java_com_streamify_core_native_NativeAudioSink_nativeGetStats(
    JNIEnv* env, jobject /* thiz */, jlong handle, jobject statsOut) {
    try {
        auto sink = sinkFromHandle(handle);
        if (env == nullptr || sink == nullptr) return;
        uint8_t* addr = nullptr;
        size_t buf_cap = 0;
        if (!directBufferAccess(env, statsOut, &addr, &buf_cap)) return;
        // Silent no-op when the buffer is too small — a telemetry probe must
        // never take the playback path down.
        WriteSinkStatsBuffer(*sink, addr, buf_cap);
    } catch (...) {
        return;
    }
}

JNIEXPORT void JNICALL
Java_com_streamify_core_native_NativeAudioSink_nativeFlush(
    JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    try {
        auto sink = sinkFromHandle(handle);
        if (sink == nullptr) return;
        sink->Flush();  // requires quiesced producer/consumer (contract above)
    } catch (...) {
        return;
    }
}

JNIEXPORT void JNICALL
Java_com_streamify_core_native_NativeAudioSink_nativeDestroy(
    JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    try {
        if (handle <= 0) return;
        SinkRegistry::instance().destroy(static_cast<int64_t>(handle));
        // Idempotent: destroying an unknown/stale handle is a silent no-op.
    } catch (...) {
        return;
    }
}

}  // extern "C"
