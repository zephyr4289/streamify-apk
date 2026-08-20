package com.streamify.app.service

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import com.streamify.app.data.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MeshPcmAudioProcessor : BaseAudioProcessor() {

    private var directProcessingBuffer: ByteBuffer? = null
    private var lastComputedGain: Float = 1.0f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        // Enforce 32-bit Float or 16-bit PCM configuration
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_16BIT
        ) {
            return AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remainingBytes = inputBuffer.remaining()
        if (remainingBytes == 0) return

        // 1. Ensure pre-allocated DirectByteBuffer has sufficient capacity (Zero heap GC)
        val curBuf = directProcessingBuffer
        val directBuf = if (curBuf == null || curBuf.capacity() < remainingBytes) {
            ByteBuffer.allocateDirect(remainingBytes).order(ByteOrder.nativeOrder()).also {
                directProcessingBuffer = it
            }
        } else {
            curBuf.clear()
            curBuf
        }

        // 2. Fast copy into direct memory segment
        val inputDuplicate = inputBuffer.duplicate()
        directBuf.put(inputDuplicate)
        directBuf.flip()

        // 3. Dispatch to Native C++20 DSP (Loudness normalizer + True-peak limiter)
        val floatCount = remainingBytes / 4
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            lastComputedGain = NativeBridge.processLivePcmTap(directBuf, floatCount)
        }

        // 4. Pass audio through to downstream sinks without stalling playback pipeline
        val outputBuffer = replaceOutputBuffer(remainingBytes)
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    fun getLastComputedNormalizationGain(): Float = lastComputedGain

    override fun onReset() {
        directProcessingBuffer = null
        lastComputedGain = 1.0f
    }
}
