package com.streamify.app.service

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class CrossfadeAudioProcessor : BaseAudioProcessor() {

    companion object {
        private const val LUT_SIZE = 256
        // Pre-computed Sine/Cosine Equal-Power Tables: cos^2(theta) + sin^2(theta) = 1.0
        private val GAIN_OUT_LUT = FloatArray(LUT_SIZE) { i ->
            cos((PI / 2.0) * (i.toDouble() / (LUT_SIZE - 1))).toFloat()
        }
        private val GAIN_IN_LUT = FloatArray(LUT_SIZE) { i ->
            sin((PI / 2.0) * (i.toDouble() / (LUT_SIZE - 1))).toFloat()
        }
        var crossfadeDurationMs: Long = 4000
    }

    private var crossfadeSamplesTotal: Long = 0
    private var currentCrossfadeSample: Long = 0
    private var isCrossfading: Boolean = false

    fun startCrossfade(durationMs: Long = 4000) {
        CrossfadeAudioProcessor.crossfadeDurationMs = durationMs
        val sampleRate = if (inputAudioFormat.sampleRate > 0) inputAudioFormat.sampleRate else 48000
        this.crossfadeSamplesTotal = (durationMs * sampleRate) / 1000
        this.currentCrossfadeSample = 0
        this.isCrossfading = true
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_16BIT
        ) {
            return AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val outputBuffer = replaceOutputBuffer(remaining)

        if (!isCrossfading || crossfadeSamplesTotal == 0L || inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            // Passthrough Mode: 0 bytes alloc, fast bitwise memory copy
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        // Apply Equal-Power Trigonometric Curve per float sample
        while (inputBuffer.hasRemaining()) {
            val sample = inputBuffer.float
            val progressFraction = (currentCrossfadeSample.toFloat() / crossfadeSamplesTotal).coerceIn(0.0f, 1.0f)
            val lutIndex = (progressFraction * (LUT_SIZE - 1)).toInt().coerceIn(0, LUT_SIZE - 1)

            val gain = GAIN_IN_LUT[lutIndex]
            outputBuffer.putFloat(sample * gain)

            currentCrossfadeSample++
            if (currentCrossfadeSample >= crossfadeSamplesTotal) {
                isCrossfading = false
            }
        }
        outputBuffer.flip()
    }

    override fun onReset() {
        isCrossfading = false
        currentCrossfadeSample = 0
    }
}
