package com.streamify.app.service

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

class SyncAudioProcessor(private val context: Context? = null) : BaseAudioProcessor() {

    private var targetDriftCorrectionMs: Float = 0.0f // Signed clock offset: + = speed up, - = slow down
    private var sampleStepAccumulator: Double = 0.0

    private val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun setClockDriftAdjustment(offsetMs: Float) {
        // Clamp adjustment rate between -50ms and +50ms
        this.targetDriftCorrectionMs = offsetMs.coerceIn(-50.0f, 50.0f)
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

        if (kotlin.math.abs(targetDriftCorrectionMs) < 0.5f || inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            // Clock synchronized or 16-bit: Passthrough
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
            return
        }

        // Proportional phase-locked adjustment rate (0.995x to 1.005x speed modifier)
        val rateModifier = 1.0 + (targetDriftCorrectionMs / 5000.0)
        val outputCapacityEstimate = (remaining * 1.05).toInt()
        val outputBuffer = replaceOutputBuffer(outputCapacityEstimate)

        val channelCount = inputAudioFormat.channelCount
        val floatsPerFrame = channelCount

        while (inputBuffer.remaining() >= floatsPerFrame * 4) {
            for (ch in 0 until channelCount) {
                outputBuffer.putFloat(inputBuffer.getFloat(inputBuffer.position() + ch * 4))
            }
            sampleStepAccumulator += rateModifier
            if (sampleStepAccumulator >= 1.0) {
                val advanceFrames = sampleStepAccumulator.toInt()
                val nextPos = inputBuffer.position() + advanceFrames * floatsPerFrame * 4
                if (nextPos <= inputBuffer.limit()) {
                    inputBuffer.position(nextPos)
                } else {
                    inputBuffer.position(inputBuffer.limit())
                }
                sampleStepAccumulator -= advanceFrames
            }
        }
        outputBuffer.flip()
    }

    /**
     * Calculates the estimated hardware latency in milliseconds (e.g. Bluetooth A2DP vs Built-in DAC).
     */
    fun getHardwareOutputLatencyMs(): Long {
        if (audioManager == null) return 15L

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                for (d in devices) {
                    when (d.type) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_BLE_SPEAKER -> return 140L

                        AudioDeviceInfo.TYPE_USB_DEVICE,
                        AudioDeviceInfo.TYPE_USB_HEADSET -> return 25L

                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET -> return 12L
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback
        }
        return 18L
    }

    fun getAcousticPositionMs(basePositionMs: Long): Long {
        val hardwareLatency = getHardwareOutputLatencyMs()
        return (basePositionMs - hardwareLatency).coerceAtLeast(0L)
    }
}
