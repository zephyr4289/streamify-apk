package com.streamify.app.service

import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build

object LatencyProbe {
    @Volatile
    var estimatedLatencyMs: Long = 0L
        private set

    private var activeAudioTrack: AudioTrack? = null
    private var isProbing = false
    private var probeThread: Thread? = null

    fun bindAudioTrack(audioTrack: AudioTrack) {
        activeAudioTrack = audioTrack
        startProbingIfNeeded()
    }

    fun setDirectRouteLatency(latencyMs: Long) {
        estimatedLatencyMs = latencyMs.coerceIn(0L, 600L)
    }

    @Synchronized
    private fun startProbingIfNeeded() {
        if (isProbing) return
        isProbing = true

        probeThread = Thread {
            val audioTimestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) AudioTimestamp() else null
            while (isProbing) {
                try {
                    val track = activeAudioTrack
                    if (track != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && audioTimestamp != null) {
                        if (track.playState == AudioTrack.PLAYSTATE_PLAYING && track.getTimestamp(audioTimestamp)) {
                            val nanoTime = System.nanoTime()
                            val dacTimeNs = audioTimestamp.nanoTime
                            val framePosition = audioTimestamp.framePosition
                            val sampleRate = track.sampleRate.coerceAtLeast(44100)

                            val bufferDurationNs = (framePosition.toDouble() / sampleRate * 1_000_000_000L).toLong()
                            val latencyNs = nanoTime - (dacTimeNs - bufferDurationNs)
                            val measuredMs = (latencyNs / 1_000_000L).coerceIn(0L, 600L)

                            // Smooth running average
                            estimatedLatencyMs = (estimatedLatencyMs * 0.8 + measuredMs * 0.2).toLong()
                        }
                    }
                } catch (e: Exception) {
                    // Ignore non-fatal probing exception
                }

                try {
                    Thread.sleep(500)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "StreamifyLatencyProbe"
            start()
        }
    }

    fun release() {
        isProbing = false
        probeThread?.interrupt()
        probeThread = null
        activeAudioTrack = null
        estimatedLatencyMs = 0L
    }
}
