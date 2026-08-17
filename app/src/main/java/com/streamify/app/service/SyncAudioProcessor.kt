package com.streamify.app.service

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

class SyncAudioProcessor(private val context: Context? = null) : AudioProcessor {

    private var inputFormat = AudioFormat.NOT_SET
    private var outputFormat = AudioFormat.NOT_SET
    private var buffer: ByteBuffer = EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var isInputEnded = false

    private val totalFramesProcessed = AtomicLong(0L)
    private var bytesPerFrame = 4 // 16-bit stereo = 4 bytes per frame
    private var sampleRate = 44100

    private val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioFormat.NOT_SET
        }
        this.inputFormat = inputAudioFormat
        this.outputFormat = inputAudioFormat
        this.sampleRate = inputAudioFormat.sampleRate
        this.bytesPerFrame = inputAudioFormat.bytesPerFrame
        totalFramesProcessed.set(0L)
        return outputFormat
    }

    override fun isActive(): Boolean = inputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val frames = remaining / bytesPerFrame
        totalFramesProcessed.addAndGet(frames.toLong())

        // Pass buffer through directly (Zero-copy latency, persistent buffer allocation)
        if (buffer.capacity() < remaining) {
            buffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }
        buffer.put(inputBuffer)
        buffer.flip()
        outputBuffer = buffer
    }

    override fun queueEndOfStream() {
        isInputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean = isInputEnded && outputBuffer === EMPTY_BUFFER

    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        isInputEnded = false
    }

    override fun reset() {
        flush()
        buffer = EMPTY_BUFFER
        inputFormat = AudioFormat.NOT_SET
        outputFormat = AudioFormat.NOT_SET
        totalFramesProcessed.set(0L)
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
                        AudioDeviceInfo.TYPE_BLE_SPEAKER -> return 140L // Average Bluetooth SBC/AAC pipeline latency

                        AudioDeviceInfo.TYPE_USB_DEVICE,
                        AudioDeviceInfo.TYPE_USB_HEADSET -> return 25L // Low latency USB DAC

                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET -> return 12L // Direct analog jack
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback
        }
        return 18L // Default phone internal speaker DAC latency
    }

    /**
     * Returns true hardware acoustic playhead position in milliseconds.
     */
    fun getAcousticPositionMs(basePositionMs: Long): Long {
        val hardwareLatency = getHardwareOutputLatencyMs()
        return (basePositionMs - hardwareLatency).coerceAtLeast(0L)
    }

    fun resetFrameCounter() {
        totalFramesProcessed.set(0L)
    }
}
