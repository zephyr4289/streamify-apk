package com.streamify.app.service

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class CrossfadeAudioProcessor : AudioProcessor {
    companion object {
        var crossfadeDurationMs = 5000L // Configurable 0s - 12s
        private var trackABuffer: ShortArray? = null
        private var trackAWritePos = 0
        private var trackAReadPos = 0
        private var trackAFramesStored = 0
    }

    private var isActive = false
    private var inputAudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat = AudioFormat.NOT_SET
    
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var isEnding = false
    private var crossfading = false
    private var fadeFramesTotal = 0
    private var fadeFramesCurrent = 0

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) {
            return AudioFormat.NOT_SET
        }
        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat
        isActive = true
        fadeFramesTotal = (crossfadeDurationMs * inputAudioFormat.sampleRate / 1000L).toInt()
        
        crossfading = trackABuffer != null && trackAFramesStored > 0 && fadeFramesTotal > 0
        fadeFramesCurrent = 0
        if (crossfading && trackABuffer != null) {
            trackAReadPos = (trackAWritePos - (trackAFramesStored * inputAudioFormat.channelCount))
            if (trackAReadPos < 0) trackAReadPos += trackABuffer!!.size
        }
        return outputAudioFormat
    }

    override fun isActive(): Boolean = isActive

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return

        if (outputBuffer.capacity() < size) {
            outputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }

        val shortBuffer = inputBuffer.asShortBuffer()
        val outShortBuffer = outputBuffer.asShortBuffer()
        val sampleCount = shortBuffer.remaining()
        
        val requiredBufferSize = fadeFramesTotal * inputAudioFormat.channelCount
        if (requiredBufferSize > 0 && (trackABuffer == null || trackABuffer!!.size != requiredBufferSize)) {
            trackABuffer = ShortArray(requiredBufferSize)
            trackAWritePos = 0
            trackAFramesStored = 0
        }

        for (i in 0 until sampleCount step inputAudioFormat.channelCount) {
            var frameMixed = false
            
            if (crossfading && fadeFramesCurrent < fadeFramesTotal && fadeFramesCurrent < trackAFramesStored) {
                val progress = fadeFramesCurrent.toFloat() / fadeFramesTotal
                val gainA = sqrt(1.0f - progress)
                val gainB = sqrt(progress)
                
                for (ch in 0 until inputAudioFormat.channelCount) {
                    val sampleB = shortBuffer.get(shortBuffer.position() + i + ch).toFloat()
                    val sampleA = trackABuffer!![trackAReadPos].toFloat()
                    trackAReadPos = (trackAReadPos + 1) % trackABuffer!!.size
                    
                    var mixed = (sampleA * gainA + sampleB * gainB).toInt()
                    if (mixed > Short.MAX_VALUE) mixed = Short.MAX_VALUE.toInt()
                    if (mixed < Short.MIN_VALUE) mixed = Short.MIN_VALUE.toInt()
                    
                    outShortBuffer.put(mixed.toShort())
                }
                fadeFramesCurrent++
                frameMixed = true
            }
            
            if (!frameMixed) {
                for (ch in 0 until inputAudioFormat.channelCount) {
                    outShortBuffer.put(shortBuffer.get(shortBuffer.position() + i + ch))
                }
            }
            
            if (requiredBufferSize > 0) {
                for (ch in 0 until inputAudioFormat.channelCount) {
                    trackABuffer!![trackAWritePos] = shortBuffer.get(shortBuffer.position() + i + ch)
                    trackAWritePos = (trackAWritePos + 1) % trackABuffer!!.size
                }
                if (trackAFramesStored < fadeFramesTotal) trackAFramesStored++
            }
        }
        
        shortBuffer.position(shortBuffer.position() + sampleCount)
        inputBuffer.position(inputBuffer.position() + size)
        
        outputBuffer.limit(size)
    }

    override fun queueEndOfStream() {
        isEnding = true
    }

    override fun getOutput(): ByteBuffer {
        val buffer = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return buffer
    }

    override fun isEnded(): Boolean = isEnding && outputBuffer === EMPTY_BUFFER

    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        isEnding = false
    }

    override fun reset() {
        flush()
        inputAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
        isActive = false
    }
}

