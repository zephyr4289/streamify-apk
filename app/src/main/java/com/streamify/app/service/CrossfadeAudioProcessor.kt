package com.streamify.app.service

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER
import java.nio.ByteBuffer

class CrossfadeAudioProcessor : AudioProcessor {
    private var isActive = false
    private var inputAudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat = AudioFormat.NOT_SET
    
    // In a real implementation, this would buffer the last 5 seconds (5 * 44100 * 2 * 2 bytes)
    // and mix it when the stream format is reconfigured for the next track.
    
    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.sampleRate == androidx.media3.common.C.RATE_UNSET) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat
        isActive = true
        return outputAudioFormat
    }

    override fun isActive(): Boolean = isActive

    private var outputBuffer = EMPTY_BUFFER
    
    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        
        // Ensure output buffer is large enough
        if (outputBuffer.capacity() < size) {
            outputBuffer = ByteBuffer.allocateDirect(size).order(java.nio.ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        
        // Pass-through with mock volume dip logic for "crossfade"
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    override fun queueEndOfStream() {
    }

    override fun getOutput(): ByteBuffer {
        val buffer = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return buffer
    }

    override fun isEnded(): Boolean = outputBuffer === EMPTY_BUFFER

    override fun flush() {
        outputBuffer = EMPTY_BUFFER
    }

    override fun reset() {
        flush()
        inputAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
        isActive = false
    }
}
