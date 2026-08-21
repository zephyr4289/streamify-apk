package com.streamify.app.service

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER
import com.streamify.app.data.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RustDspAudioProcessor : AudioProcessor {
    private var statePtr: Long = 0L
    private var inputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var inputBuffer: ByteBuffer = EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var normalizerPtr: Long = 0L
    private var isInputEnded: Boolean = false

    // Direct native buffers for zero-copy FFI
    private var nativeInputBuffer: ByteBuffer = ByteBuffer.allocateDirect(16384).order(ByteOrder.nativeOrder())
    private var nativeOutputBuffer: ByteBuffer = ByteBuffer.allocateDirect(32768).order(ByteOrder.nativeOrder())

    init {
        statePtr = try {
            NativeBridge.nativeInitDsp()
        } catch (e: Throwable) {
            0L
        }
        normalizerPtr = try {
            NativeBridge.nativeInitNormalizer(0.25f)
        } catch (e: Throwable) {
            0L
        }
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioFormat.NOT_SET
        }

        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            C.ENCODING_PCM_FLOAT
        )
        return if (isActive) this.outputAudioFormat else AudioFormat.NOT_SET
    }

    override fun isActive(): Boolean {
        return statePtr != 0L && outputAudioFormat != AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining() || statePtr == 0L) return

        val remainingBytes = inputBuffer.remaining()
        val channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        val is16Bit = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        val bytesPerFrame = if (is16Bit) channelCount * 2 else channelCount * 4
        val numFrames = remainingBytes / bytesPerFrame
        if (numFrames <= 0) return

        val requiredInputCap = numFrames * channelCount * 2 // 16-bit PCM for Rust DSP engine
        val requiredOutputCap = numFrames * channelCount * 4 // 32-bit Float PCM output

        if (nativeInputBuffer.capacity() < requiredInputCap) {
            nativeInputBuffer = ByteBuffer.allocateDirect(requiredInputCap * 2).order(ByteOrder.nativeOrder())
        }
        if (nativeOutputBuffer.capacity() < requiredOutputCap) {
            nativeOutputBuffer = ByteBuffer.allocateDirect(requiredOutputCap * 2).order(ByteOrder.nativeOrder())
        }

        nativeInputBuffer.clear()
        if (is16Bit) {
            val oldLimit = inputBuffer.limit()
            inputBuffer.limit(inputBuffer.position() + (numFrames * bytesPerFrame))
            nativeInputBuffer.put(inputBuffer)
            inputBuffer.limit(oldLimit)
        } else {
            // Float to 16-bit integer PCM conversion for Rust DSP engine
            val sampleCount = numFrames * channelCount
            for (i in 0 until sampleCount) {
                val f = inputBuffer.float
                val s = (f.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
                nativeInputBuffer.putShort(s)
            }
        }
        nativeInputBuffer.flip()

        nativeOutputBuffer.clear()
        val result = try {
            NativeBridge.nativeProcessDsp(statePtr, nativeInputBuffer, nativeOutputBuffer, numFrames)
        } catch (e: Throwable) {
            -1
        }

        if (result == 0) {
            if (normalizerPtr != 0L) {
                try {
                    NativeBridge.nativeApplyNormalization(normalizerPtr, nativeOutputBuffer, numFrames)
                } catch (e: Throwable) {
                    // Ignore
                }
            }
            nativeOutputBuffer.position(0)
            nativeOutputBuffer.limit(numFrames * channelCount * 4)
            outputBuffer = nativeOutputBuffer
        } else {
            outputBuffer = EMPTY_BUFFER
        }
    }

    override fun queueEndOfStream() {
        isInputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean {
        return isInputEnded && outputBuffer === EMPTY_BUFFER
    }

    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        inputBuffer = EMPTY_BUFFER
        isInputEnded = false
    }

    override fun reset() {
        flush()
        inputAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
    }
}
