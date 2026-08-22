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

    private var statePtr: Long = 0L
    private var normalizerPtr: Long = 0L

    private var currentFormat: AudioFormat = AudioFormat.NOT_SET

    // Direct native buffers for zero-copy FFI (reused across callbacks).
    private var nativeInputBuffer: ByteBuffer = ByteBuffer.allocateDirect(16384).order(ByteOrder.nativeOrder())
    private var nativeOutputBuffer: ByteBuffer = ByteBuffer.allocateDirect(32768).order(ByteOrder.nativeOrder())

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

        nativeInputBuffer.clear()
        if (is16Bit) {
            nativeInputBuffer.put(inputBuffer)
        } else {
            repeat(sampleCount) {
                val f = inputBuffer.float
                nativeInputBuffer.putShort((f.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort())
            }
        }
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
