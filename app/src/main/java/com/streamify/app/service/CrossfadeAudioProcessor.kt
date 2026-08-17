package com.streamify.app.service

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER
import com.streamify.app.data.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class CrossfadeAudioProcessor : AudioProcessor {
    companion object {
        var crossfadeDurationMs = 0L // Default 0s (Disabled for direct playback)
        private var trackABuffer: ShortArray? = null
        private var trackAWritePos = 0
        private var trackAReadPos = 0
        private var trackAFramesStored = 0

        // Precomputed 256-entry equal-power trigonometric LUT
        private const val LUT_SIZE = 256
        private val LUT_COS = FloatArray(LUT_SIZE) { i -> cos((i.toDouble() / (LUT_SIZE - 1)) * (PI / 2.0)).toFloat() }
        private val LUT_SIN = FloatArray(LUT_SIZE) { i -> sin((i.toDouble() / (LUT_SIZE - 1)) * (PI / 2.0)).toFloat() }
    }

    private var isActive = false
    private var inputAudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat = AudioFormat.NOT_SET
    
    private var buffer: ByteBuffer = EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var tempShortBuffer: ShortArray = ShortArray(4096)
    private var isEnding = false
    private var crossfading = false
    private var fadeFramesTotal = 0
    private var fadeFramesCurrent = 0

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (crossfadeDurationMs <= 0 || inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) {
            isActive = false
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

        if (buffer.capacity() < size) {
            buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }

        val shortBuffer = inputBuffer.asShortBuffer()
        val sampleCount = shortBuffer.remaining()
        if (tempShortBuffer.size < sampleCount) {
            tempShortBuffer = ShortArray(sampleCount)
        }
        val tempOutput = tempShortBuffer
        var tempOutIdx = 0
        
        val requiredBufferSize = fadeFramesTotal * inputAudioFormat.channelCount
        if (requiredBufferSize > 0 && (trackABuffer == null || trackABuffer!!.size != requiredBufferSize)) {
            trackABuffer = ShortArray(requiredBufferSize)
            trackAWritePos = 0
            trackAFramesStored = 0
        }

        for (i in 0 until sampleCount step inputAudioFormat.channelCount) {
            var frameMixed = false
            
            if (crossfading && fadeFramesCurrent < fadeFramesTotal && fadeFramesCurrent < trackAFramesStored) {
                val lutIdx = ((fadeFramesCurrent * (LUT_SIZE - 1)) / fadeFramesTotal).coerceIn(0, LUT_SIZE - 1)
                val gainA = LUT_COS[lutIdx]
                val gainB = LUT_SIN[lutIdx]
                
                for (ch in 0 until inputAudioFormat.channelCount) {
                    val sampleB = shortBuffer.get(shortBuffer.position() + i + ch).toFloat()
                    val sampleA = trackABuffer!![trackAReadPos].toFloat()
                    trackAReadPos = (trackAReadPos + 1) % trackABuffer!!.size
                    
                    var mixed = (sampleA * gainA + sampleB * gainB).toInt()
                    if (mixed > Short.MAX_VALUE) mixed = Short.MAX_VALUE.toInt()
                    if (mixed < Short.MIN_VALUE) mixed = Short.MIN_VALUE.toInt()
                    
                    tempOutput[tempOutIdx++] = mixed.toShort()
                }
                fadeFramesCurrent++
                frameMixed = true
            }
            
            if (!frameMixed) {
                for (ch in 0 until inputAudioFormat.channelCount) {
                    tempOutput[tempOutIdx++] = shortBuffer.get(shortBuffer.position() + i + ch)
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
        
        // Native Soft-Knee Limiter to prevent clipping
        try {
            NativeBridge.processLimiterShorts(tempOutput, sampleCount, 0.92f, 0.15f)
        } catch (e: UnsatisfiedLinkError) {
            // Ignore if native lib not loaded in test
        }

        val outShortBuffer = buffer.asShortBuffer()
        outShortBuffer.put(tempOutput, 0, sampleCount)

        shortBuffer.position(shortBuffer.position() + sampleCount)
        inputBuffer.position(inputBuffer.position() + size)
        
        buffer.limit(size)
        outputBuffer = buffer
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
        buffer = EMPTY_BUFFER
        inputAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
        isActive = false
    }
}
