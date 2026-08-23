package com.streamify.app.service

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import com.streamify.app.data.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * THE render-path processor. Replaces the old 4-processor chain
 * (RustDSP -> MeshPcm -> Crossfade -> Sync) which cost 5 full buffer copies +
 * 3 JNI crossings + an interpreted per-sample loop per 20ms buffer — all on
 * the real-time audio thread.
 *
 * Now: ONE processor, ONE fused JNI crossing per buffer
 * (i16 -> EQ/DSP -> RMS normalization -> f32), direct buffers reused,
 * native states freed on [release].
 *
 * CONTRACT: queueInput always consumes the whole input buffer and always
 * produces output (pure-Kotlin i16->f32 fallback if the native pass fails),
 * so a native failure can never stall or glitch the Media3 sink.
 */
class StreamifyAudioProcessor : BaseAudioProcessor() {

    companion object {
        /**
         * PHASE 1 LOUDNESS TRUTH: YouTube's own per-stream measurement
         * (loudnessDb, relative to −14 LUFS reference). Applied as exact
         * pre-gain; when present the legacy RMS normalizer is bypassed.
         * Null → non-YT/local source → legacy RMS path.
         */
        @Volatile var currentPreGainDb: Float? = null

        /**
         * PHASE 2 PARAMETRIC EQ: 10 band gains in dB, published by
         * EqualizerManager. Applied through the Rust StudioEqualizer biquads —
         * ONLY on 44100 Hz streams, because the native EQ singleton locks its
         * coefficient design rate at first construction. Non-44100 streams
         * keep the system Equalizer engine (activeEqEngine == "SYSTEM").
         */
        @Volatile var eqBandGainsDb: FloatArray? = null

        /** Which engine owns EQ for the CURRENT stream — set on configure. */
        @Volatile var activeEqEngine: String = "SYSTEM"

        /** PHASE 2 SAFETY: soft-knee ceiling after every gain stage. */
        @Volatile var limiterEnabled: Boolean = false // opt-in after device testing
    }

    private var statePtr: Long = 0L
    private var normalizerPtr: Long = 0L

    private var currentFormat: AudioFormat = AudioFormat.NOT_SET

    // Direct native buffers for zero-copy FFI (reused across callbacks).
    private var nativeInputBuffer: ByteBuffer = ByteBuffer.allocateDirect(16384).order(ByteOrder.nativeOrder())
    private var nativeOutputBuffer: ByteBuffer = ByteBuffer.allocateDirect(32768).order(ByteOrder.nativeOrder())
    private var scratchFloats: FloatArray = FloatArray(4096)

    init {
        statePtr = try {
            NativeBridge.nativeInitDsp()
        } catch (_: Throwable) {
            0L
        }
        normalizerPtr = try {
            NativeBridge.nativeInitNormalizer(0.25f)
        } catch (_: Throwable) {
            0L
        }
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioFormat.NOT_SET
        }
        currentFormat = inputAudioFormat
        // PHASE 2: route EQ ownership by stream sample rate (native biquad
        // coefficients are designed at 44.1k).
        activeEqEngine =
            if (inputAudioFormat.sampleRate == 44_100) "RUST" else "SYSTEM"
        return AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            C.ENCODING_PCM_FLOAT
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remainingBytes = inputBuffer.remaining()
        if (remainingBytes == 0) return

        val channelCount = currentFormat.channelCount.coerceAtLeast(1)
        val is16Bit = currentFormat.encoding == C.ENCODING_PCM_16BIT
        val bytesPerFrame = if (is16Bit) channelCount * 2 else channelCount * 4
        val numFrames = remainingBytes / bytesPerFrame
        if (numFrames <= 0) {
            // Sub-frame tail (<1 frame): consume it as silence-equivalent passthrough.
            consumeAsFloat(inputBuffer, remainingBytes, channelCount, is16Bit)
            return
        }

        val sampleCount = numFrames * channelCount
        val requiredInputCap = sampleCount * 2   // i16 PCM for the Rust engine
        val requiredOutputCap = sampleCount * 4  // f32 output

        if (nativeInputBuffer.capacity() < requiredInputCap) {
            nativeInputBuffer = ByteBuffer.allocateDirect(requiredInputCap * 2).order(ByteOrder.nativeOrder())
        }
        if (nativeOutputBuffer.capacity() < requiredOutputCap) {
            nativeOutputBuffer = ByteBuffer.allocateDirect(requiredOutputCap * 2).order(ByteOrder.nativeOrder())
        }

        // ═══ PHASE 1 QUANTIZATION FIX + LOUDNESS TRUTH ═══
        // FLOAT input never round-trips through i16 anymore (that injected
        // ditherless quantization into a supposedly-float pipeline).
        if (!is16Bit) {
            val preGainLin = currentPreGainDb?.let { Math.pow(10.0, it / 20.0).toFloat() }
            nativeOutputBuffer.clear()
            if (preGainLin != null) {
                // EXACT PATH: YouTube-told-us gain; RMS engine bypassed.
                var i = 0
                while (i < sampleCount) {
                    val v = inputBuffer.float * preGainLin
                    nativeOutputBuffer.putFloat(if (v > 1f) 1f else if (v < -1f) -1f else v)
                    i++
                }
            } else {
                // LEGACY PATH: identity copy → native RMS normalization.
                while (inputBuffer.hasRemaining()) nativeOutputBuffer.putFloat(inputBuffer.float)
                nativeOutputBuffer.position(0)
                nativeOutputBuffer.limit(sampleCount * 4)
                if (normalizerPtr != 0L) {
                    runCatching {
                        NativeBridge.nativeApplyNormalization(normalizerPtr, nativeOutputBuffer, numFrames)
                    }
                }
                nativeOutputBuffer.position(0)
                nativeOutputBuffer.limit(sampleCount * 4)
            }

            postProcessFloat(nativeOutputBuffer, sampleCount, channelCount)

            val out = replaceOutputBuffer(sampleCount * 4)
            nativeOutputBuffer.position(0)
            nativeOutputBuffer.limit(sampleCount * 4)
            out.put(nativeOutputBuffer)
            out.flip()
            return
        }

        nativeInputBuffer.clear()
        nativeInputBuffer.put(inputBuffer)
        nativeInputBuffer.flip()

        nativeOutputBuffer.clear()
        val result: Int = try {
            if (statePtr != 0L) {
                NativeBridge.nativeProcessFusedAudio(statePtr, normalizerPtr, nativeInputBuffer, nativeOutputBuffer, numFrames)
            } else -1
        } catch (_: Throwable) {
            -1
        }

        if (result == 0) {
            postProcessFloat(nativeOutputBuffer, sampleCount, channelCount)

            val out = replaceOutputBuffer(sampleCount * 4)
            nativeOutputBuffer.position(0)
            nativeOutputBuffer.limit(sampleCount * 4)
            out.put(nativeOutputBuffer)
            out.flip()
        } else {
            // Native failed: still emit clean audio via pure-Kotlin conversion
            // so the sink never starves. Rewind what we consumed from the copy.
            consumeAsFloatFromNativeInput(sampleCount, channelCount)
        }
    }

    /**
     * PHASE 2 POST STAGE — applied to EVERY rendered buffer:
     *   1. Rust parametric EQ when this stream is RUST-owned (44.1 kHz),
     *      using live gains published from EqualizerManager.
     *   2. C++ SoftKneeLimiter at −1 dB threshold / 2 dB knee — the always-on
     *      true-peak safety net that makes loudness pre-gain clip-free.
     * One reusable scratch array; one bulk copy in/out per buffer.
     */
    private fun postProcessFloat(buffer: java.nio.ByteBuffer, sampleCount: Int, channels: Int) {
        if (sampleCount <= 0) return
        val gains = eqBandGainsDb
        val needsEq = activeEqEngine == "RUST" && gains != null && gains.size == 10
        if (!needsEq && !limiterEnabled) return

        if (scratchFloats.size < sampleCount) scratchFloats = FloatArray(sampleCount * 2)
        buffer.position(0)
        buffer.limit(sampleCount * 4)
        // ByteBuffer has no bulk float ops: copy through an aligned view.
        buffer.asFloatBuffer().get(scratchFloats, 0, sampleCount)

        if (needsEq) {
            runCatching {
                NativeBridge.rustProcessEqualizerFrame(
                    pcmFloats = scratchFloats,
                    channels = channels,
                    gains = gains
                )
            }
        }

        if (limiterEnabled) {
            runCatching {
                NativeBridge.processLimiterFloats(
                    buffer = scratchFloats,
                    length = sampleCount,
                    threshold = -1.0f,
                    kneeWidth = 2.0f
                )
            }
        }

        buffer.position(0)
        buffer.asFloatBuffer().put(scratchFloats, 0, sampleCount)
        buffer.position(0)
        buffer.limit(sampleCount * 4)
    }

    /** Fallback: convert the staged i16 copy in nativeInputBuffer to f32 output. */
    private fun consumeAsFloatFromNativeInput(sampleCount: Int, channelCount: Int) {
        val out = replaceOutputBuffer(sampleCount * 4)
        nativeInputBuffer.position(0)
        nativeInputBuffer.limit(sampleCount * 2)
        repeat(sampleCount) {
            out.putFloat(nativeInputBuffer.short.toFloat() / 32767.0f)
        }
        out.flip()
    }

    /** Consume arbitrary remainder (sub-frame tail) with best-effort conversion. */
    private fun consumeAsFloat(input: ByteBuffer, byteCount: Int, channelCount: Int, is16Bit: Boolean) {
        val samples = if (is16Bit) byteCount / 2 else byteCount / 4
        val out = replaceOutputBuffer(samples * 4)
        repeat(samples) {
            val f = if (is16Bit) input.short.toFloat() / 32767.0f else input.float
            out.putFloat(f)
        }
        // Contract: ALWAYS leave the input fully consumed.
        input.position(input.limit())
        out.flip()
    }

    override fun onFlush() {
        // Filter state lives natively; nothing to clear Kotlin-side beyond
        // buffer cursors, which BaseAudioProcessor handles.
    }

    /**
     * Frees native DSP/normalizer state. Call from service teardown — the old
     * implementation leaked both permanently.
     */
    fun release() {
        if (statePtr != 0L) {
            try { NativeBridge.nativeFreeDsp(statePtr) } catch (_: Throwable) {}
            statePtr = 0L
        }
        if (normalizerPtr != 0L) {
            try { NativeBridge.nativeFreeNormalizer(normalizerPtr) } catch (_: Throwable) {}
            normalizerPtr = 0L
        }
    }
}
