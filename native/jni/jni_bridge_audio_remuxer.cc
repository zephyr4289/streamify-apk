// ============================================================================
//  jni_bridge_audio_remuxer.cc — Phase-3 remuxer bridge (BEHIND.md #38/#44)
//  for com.streamify.app.audio.NativeAudioRemuxer.
// ============================================================================
//
//  JNI SURFACE — session API (one handle per downloaded track):
//
//    nativeBeginSession(codec: Int, sampleRate: Int, channels: Int,
//                       strictRates: Boolean): Long      // 0 == failure
//    nativeRemuxPacket(handle: Long, packet: ByteArray, length: Int,
//                      sequence: Long): Int
//    nativeRemuxPacketDirect(handle: Long, buf: ByteBuffer, length: Int,
//                            sequence: Long): Int
//    nativeFinishSession(handle: Long): Int
//    nativeDrainOutput(handle: Long, out: ByteArray, offset: Int): Int
//    nativeGetStats(handle: Long, outStats8: LongArray): Int
//    nativeDestroySession(handle: Long)
//
//  JNI SURFACE — standalone validators (probe a download before committing
//  to a session; no state, no allocation):
//
//    nativeValidateOpusPacket(packet: ByteArray, length: Int,
//                             outInfo8: IntArray): Int
//    nativeValidateAdtsFrame(frame: ByteArray, length: Int,
//                            strictRates: Boolean, outInfo8: IntArray): Int
//    nativeResyncAdts(stream: ByteArray, length: Int, fromOffset: Int): Int
//    nativeResyncOpus(stream: ByteArray, length: Int, fromOffset: Int): Int
//
//  CONTRACT
//  --------
//  * codec: 0 = Opus (itag 251, -> Ogg Opus), 1 = AAC/ADTS (itag 140, ->
//    normalized ADTS). Session handles are raw pointers like the DSP
//    bridge's nativeInitDsp/nativeFreeDsp pair; destroy exactly once.
//  * Remux output accumulates in the session; nativeDrainOutput copies
//    into `out` from `offset` and returns the copied byte count (0 when
//    drained; -1 on a bad handle/args). Repeated drains are cheap.
//  * outStats8 = [packetsIn, packetsAccepted, droppedCorrupt,
//    droppedTruncated, droppedOutOfOrder, bytesIn, bytesOut,
//    pagesEmitted+framesEmitted].
//  * Status codes mirror ParseStatus (0 ok .. 10 io-error).
//  * Zero-copy reads via GetPrimitiveArrayCritical / direct buffers;
//    lengths are resolved BEFORE pinning, no JNI calls inside critical
//    sections. Per-packet path performs zero heap allocation (the session
//    output vector grows amortized at page granularity).
//  * THREADING: one session per stream, single-threaded ownership; the
//    standalone validators are pure and thread-safe.
// ============================================================================

#include <jni.h>

#include <cstring>
#include <new>
#include <vector>

#include "../audio/AdtsFrameParser.h"
#include "../audio/AudioFrameRemuxer.h"
#include "../audio/OpusPacketParser.h"

using streamify::audio::AdtsFrameParser;
using streamify::audio::AdtsFrameInfo;
using streamify::audio::AudioFrameRemuxer;
using streamify::audio::Codec;
using streamify::audio::OpusFrameInfo;
using streamify::audio::OpusPacketParser;
using streamify::audio::ParseStatus;
using streamify::audio::RemuxerConfig;
using streamify::audio::RemuxSink;
using PS = streamify::audio::ParseStatus;

namespace {

struct RemuxSession {
    AudioFrameRemuxer remuxer;
    std::vector<uint8_t> output;
    size_t drained = 0;
};

bool sinkWrite(void* ctx, const uint8_t* data, size_t len) {
    auto* out = static_cast<std::vector<uint8_t>*>(ctx);
    out->insert(out->end(), data, data + len);
    return true;
}

RemuxSession* sessionFromHandle(jlong handle) {
    if (handle == 0) return nullptr;
    return reinterpret_cast<RemuxSession*>(static_cast<uintptr_t>(handle));
}

jint asJint(ParseStatus s) { return static_cast<jint>(s); }

}  // namespace

extern "C" {

// ---- session API -----------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_streamify_app_audio_NativeAudioRemuxer_nativeBeginSession(
    JNIEnv* /* env */, jclass /* clazz */, jint codec, jint sampleRate,
    jint channels, jboolean strictRates) {
    RemuxerConfig cfg;
    cfg.codec = codec == 1 ? Codec::kAacAdts : Codec::kOpus;
    cfg.nominalSampleRate = sampleRate > 0 ? sampleRate : 48000;
    cfg.channels = (channels == 1 || channels == 2) ? channels : 0;
    cfg.strictRates = strictRates != JNI_FALSE;

    auto* session = new (std::nothrow) RemuxSession();
    if (session == nullptr) {
        return 0;
    }
    session->output.reserve(1 << 20);  // 1 MiB initial (amortized growth)
    const RemuxSink sink{&session->output, sinkWrite};
    session->remuxer.begin(cfg, sink);
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(session));
}

JNIEXPORT jint JNICALL
Java_com_streamify_app_audio_NativeAudioRemuxer_nativeRemuxPacket(
    JNIEnv* env, jclass /* clazz */, jlong handle, jbyteArray packet,
    jint length, jlong sequence) {
    RemuxSession* s = sessionFromHandle(handle);
    if (env == nullptr || s == nullptr || packet == nullptr) {
        return asJint(PS::kInvalidArgument);
    }
    // Clamp to the array length, resolved BEFORE pinning.
    const jsize arrLen = env->GetArrayLength(packet);
    jint len = length < 0 ? 0 : length;
    if (len > arrLen) len = arrLen;
    jboolean isCopy = JNI_FALSE;
    jbyte* in =
        static_cast<jbyte*>(env->GetPrimitiveArrayCritical(packet, &isCopy));
    if (in == nullptr) {
        return asJint(PS::kInvalidArgument);
    }
    const ParseStatus status = s->remuxer.remuxPacket(
        reinterpret_cast<const uint8_t*>(in),
        static_cast<size_t>(len), static_cast<uint64_t>(sequence));
    env->ReleasePrimitiveArrayCritical(packet, in, JNI_ABORT);
    return asJint(status);
}

JNIEXPORT jint JNICALL
Java_com_streamify_app_audio_NativeAudioRemuxer_nativeRemuxPacketDirect(
    JNIEnv* env, jclass /* clazz */, jlong handle, jobject buf, jint length,
    jlong sequence) {
    RemuxSession* s = sessionFromHandle(handle);
    if (env == nullptr || s == nullptr || buf == nullptr) {
        return asJint(PS::kInvalidArgument);
    }
    auto* in = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buf));
    const jlong cap = env->GetDirectBufferCapacity(buf);
    if (in == nullptr || cap <= 0) {
        return asJint(PS::kInvalidArgument);
    }
    jint len = length < 0 ? 0 : length;
    if (len > cap) len = static_cast<jint>(cap);
    return asJint(s->remuxer.remuxPacket(in, static_cast<size_t>(len),
                                         static_cast<uint64_t>(sequence)));
}

JNIEXPORT jint JNICALL
Java_com_streamify_app_audio_NativeAudioRemuxer_nativeFinishSession(
    JNIEnv* /* env */, jclass /* clazz */, jlong handle) {
    RemuxSession* s = sessionFromHandle(handle);
    if (s == nullptr) {
        return asJint(PS::kInvalidArgument);
    }
    return asJint(s->remuxer.finish());
}

JNIEXPORT jint JNICALL
Java_com_streamify_app_audio_NativeAudioRemuxer_nativeDrainOutput(
    JNIEnv* env, jclass /* clazz */, jlong handle, jbyteArray out,
    jint offset) {
    RemuxSession* s = sessionFromHandle(handle);
    if (env == nullptr || s == nullptr || out == nullptr) {
        return -1;
    }
    const jsize arrLen = env->GetArrayLength(out);
    if (offset < 0 || offset >= arrLen) {
        return -1;
    }
    const size_t available = s->output.size() - s->drained;
    if (available == 0) {
        return 0;
    }
    const size_t room = static_cast<size_t>(arrLen - offset);
    const size_t n = available < room ? available : room;
    jboolean isCopy = JNI_FALSE;
    jbyte* dst = static_cast<jbyte*>(
        env->GetPrimitiveArrayCritical(out, &isCopy));
    if (dst == nullptr) {
        return -1;
    }
    std::memcpy(dst + offset, s->output.data() + s->drained, n);
    env->ReleasePrimitiveArrayCritical(out, dst, 0);
    s->drained += n;
    if (s->drained == s->output.size()) {
        // Fully consumed: release the buffer back to the 1 MiB reserve.
        s->output.clear();
        s->drained = 0;
    }
    return static_cast<jint>(n);
}

JNIEXPORT jint JNICALL
Java_com_streamify_app_audio_NativeAudioRemuxer_nativeGetStats(
    JNIEnv* env, jclass /* clazz */, jlong handle, jlongArray outStats8) {
    RemuxSession* s = sessionFromHandle(handle);
    if (env == nullptr || s == nullptr || outStats8 == nullptr ||
        env->GetArrayLength(outStats8) < 8) {
        return asJint(PS::kInvalidArgument);
    }
    const auto& st = s->remuxer.stats();
    jlong vals[8] = {
        static_cast<jlong>(st.packetsIn),     static_cast<jlong>(st.packetsAccepted),
        static_cast<jlong>(st.droppedCorrupt), static_cast<jlong>(st.droppedTruncated),
        static_cast<jlong>(st.droppedOutOfOrder), static_cast<jlong>(st.bytesIn),
        static_cast<jlong>(st.bytesOut),
        static_cast<jlong>(st.pagesEmitted + st.framesEmitted),
    };
    jlong* out = static_cast<jlong*>(
        env->GetPrimitiveArrayCritical(outStats8, nullptr));
    if (out == nullptr) {
        return asJint(PS::kInvalidArgument);
    }
    for (int i = 0; i < 8; ++i) out[i] = vals[i];
    env->ReleasePrimitiveArrayCritical(outStats8, out, 0);
    return asJint(PS::kOk);
}

JNIEXPORT void JNICALL
Java_com_streamify_app_audio_NativeAudioRemuxer_nativeDestroySession(
    JNIEnv* /* env */, jclass /* clazz */, jlong handle) {
    RemuxSession* s = sessionFromHandle(handle);
    delete s;  // nullptr-safe by contract
}

// ---- standalone validators (stateless) -------------------------------------

JNIEXPORT jint JNICALL
Java_com_streamify_app_audio_NativeAudioRemuxer_nativeValidateOpusPacket(
    JNIEnv* env, jclass /* clazz */, jbyteArray packet, jint length,
    jintArray outInfo8) {
    if (env == nullptr || packet == nullptr) {
        return asJint(PS::kInvalidArgument);
    }
    const jsize arrLen = env->GetArrayLength(packet);
    jint len = length < 0 ? 0 : length;
    if (len > arrLen) len = arrLen;
    const bool wantInfo = outInfo8 != nullptr && env->GetArrayLength(outInfo8) >= 8;
    OpusFrameInfo info;
    jboolean isCopy = JNI_FALSE;
    jbyte* in = static_cast<jbyte*>(
        env->GetPrimitiveArrayCritical(packet, &isCopy));
    if (in == nullptr) {
        return asJint(PS::kInvalidArgument);
    }
    const ParseStatus status = OpusPacketParser::parse(
        reinterpret_cast<const uint8_t*>(in), static_cast<size_t>(len), &info);
    // Fill the info array AFTER releasing the input (no nested criticals).
    env->ReleasePrimitiveArrayCritical(packet, in, JNI_ABORT);
    if (status == ParseStatus::kOk && wantInfo) {
        jint vals[8] = {info.config,
                        info.frameCount,
                        info.samplesAt48k,
                        info.channels,
                        info.sampleRateHz,
                        info.pcmBitDepth,
                        info.largestFrameBytes,
                        static_cast<jint>(info.frameSizeMs * 10.0f + 0.5f)};
        jint* out = static_cast<jint*>(
            env->GetPrimitiveArrayCritical(outInfo8, nullptr));
        if (out != nullptr) {
            for (int i = 0; i < 8; ++i) out[i] = vals[i];
            env->ReleasePrimitiveArrayCritical(outInfo8, out, 0);
        }
    }
    return asJint(status);
}

JNIEXPORT jint JNICALL
Java_com_streamify_app_audio_NativeAudioRemuxer_nativeValidateAdtsFrame(
    JNIEnv* env, jclass /* clazz */, jbyteArray frame, jint length,
    jboolean strictRates, jintArray outInfo8) {
    if (env == nullptr || frame == nullptr) {
        return asJint(PS::kInvalidArgument);
    }
    const jsize arrLen = env->GetArrayLength(frame);
    jint len = length < 0 ? 0 : length;
    if (len > arrLen) len = arrLen;
    const bool wantInfo = outInfo8 != nullptr && env->GetArrayLength(outInfo8) >= 8;
    AdtsFrameInfo info;
    jboolean isCopy = JNI_FALSE;
    jbyte* in = static_cast<jbyte*>(
        env->GetPrimitiveArrayCritical(frame, &isCopy));
    if (in == nullptr) {
        return asJint(PS::kInvalidArgument);
    }
    const ParseStatus status = AdtsFrameParser::parse(
        reinterpret_cast<const uint8_t*>(in), static_cast<size_t>(len),
        strictRates != JNI_FALSE, &info);
    env->ReleasePrimitiveArrayCritical(frame, in, JNI_ABORT);
    if (status == ParseStatus::kOk && wantInfo) {
        jint vals[8] = {info.profile,
                        info.sampleRateHz,
                        info.channels,
                        info.frameLength,
                        info.headerBytes,
                        info.payloadBytes,
                        info.rawBlocksInFrame,
                        info.pcmBitDepth};
        jint* out = static_cast<jint*>(
            env->GetPrimitiveArrayCritical(outInfo8, nullptr));
        if (out != nullptr) {
            for (int i = 0; i < 8; ++i) out[i] = vals[i];
            env->ReleasePrimitiveArrayCritical(outInfo8, out, 0);
        }
    }
    return asJint(status);
}

namespace {
// Shared resync body for both byte-array entry points.
jint resyncJni(JNIEnv* env, jbyteArray stream, jint length, jint fromOffset,
               bool adts) {
    if (env == nullptr || stream == nullptr) {
        return -1;
    }
    const jsize arrLen = env->GetArrayLength(stream);
    jint len = length < 0 ? 0 : length;
    if (len > arrLen) len = arrLen;
    jint from = fromOffset < 0 ? 0 : fromOffset;
    if (from > len) {
        return -1;
    }
    jboolean isCopy = JNI_FALSE;
    jbyte* in = static_cast<jbyte*>(
        env->GetPrimitiveArrayCritical(stream, &isCopy));
    if (in == nullptr) {
        return -1;
    }
    const uint8_t* bytes = reinterpret_cast<const uint8_t*>(in);
    const int64_t off =
        adts ? AdtsFrameParser::resync(bytes, static_cast<size_t>(len),
                                       static_cast<size_t>(from))
             : OpusPacketParser::resyncToc(bytes, static_cast<size_t>(len),
                                           static_cast<size_t>(from));
    env->ReleasePrimitiveArrayCritical(stream, in, JNI_ABORT);
    return off < 0 ? -1 : static_cast<jint>(off);
}
}  // namespace

JNIEXPORT jint JNICALL
Java_com_streamify_app_audio_NativeAudioRemuxer_nativeResyncAdts(
    JNIEnv* env, jclass /* clazz */, jbyteArray stream, jint length,
    jint fromOffset) {
    return resyncJni(env, stream, length, fromOffset, /*adts=*/true);
}

JNIEXPORT jint JNICALL
Java_com_streamify_app_audio_NativeAudioRemuxer_nativeResyncOpus(
    JNIEnv* env, jclass /* clazz */, jbyteArray stream, jint length,
    jint fromOffset) {
    return resyncJni(env, stream, length, fromOffset, /*adts=*/false);
}

}  // extern "C"
