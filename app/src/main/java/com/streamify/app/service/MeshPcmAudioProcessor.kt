package com.streamify.app.service

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER
import com.streamify.app.data.EdgeMeshRepository
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MeshPcmAudioProcessor : AudioProcessor {
    private var isActive = true
    private var inputAudioFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var isEnding = false

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        this.inputAudioFormat = inputAudioFormat
        this.isActive = inputAudioFormat != AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun isActive(): Boolean = isActive

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return

        // 1. Pass read-only slice to Edge Mesh Ingestion Engine (Zero-Allocation)
        try {
            EdgeMeshRepository.feedPcmChunk(
                inputBuffer = inputBuffer.asReadOnlyBuffer(),
                byteCount = size,
                sampleRate = if (inputAudioFormat.sampleRate > 0) inputAudioFormat.sampleRate else 44100,
                channelCount = if (inputAudioFormat.channelCount > 0) inputAudioFormat.channelCount else 2,
                encoding = inputAudioFormat.encoding
            )
        } catch (e: Exception) {
            // Non-fatal tap exception
        }

        // 2. Pass-through buffer to output for downstream AudioTrack playback
        if (outputBuffer.capacity() < size) {
            outputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }

        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    override fun queueEndOfStream() {
        isEnding = true
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean = isEnding && outputBuffer === EMPTY_BUFFER

    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        isEnding = false
    }

    override fun reset() {
        flush()
        inputAudioFormat = AudioFormat.NOT_SET
        isActive = false
    }
}
