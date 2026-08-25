package com.streamify.app.service

import android.os.SystemClock
import com.streamify.app.util.SLog as Log
import com.streamify.app.data.NativeBridge
import com.streamify.app.data.network.MeshDiscoveryEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class PrecisionTimeProtocol private constructor(
    private val meshEngine: MeshDiscoveryEngine
) {
    companion object {
        private const val TAG = "PTP_Engine"

        @Volatile
        private var INSTANCE: PrecisionTimeProtocol? = null

        fun getInstance(meshEngine: MeshDiscoveryEngine): PrecisionTimeProtocol {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PrecisionTimeProtocol(meshEngine).also { INSTANCE = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pingJob: Job? = null
    private var isRunning = false

    private val _isSynchronized = MutableStateFlow(false)
    val isSynchronized: StateFlow<Boolean> = _isSynchronized.asStateFlow()

    private val _rttMs = MutableStateFlow(0f)
    val rttMs: StateFlow<Float> = _rttMs.asStateFlow()

    private val _offsetMs = MutableStateFlow(0f)
    val offsetMs: StateFlow<Float> = _offsetMs.asStateFlow()

    init {
        // Continuously listen to raw UDP packets received by MeshDiscoveryEngine
        scope.launch {
            meshEngine.rawUdpPackets.collect { packet ->
                handleIncomingPtpPacket(packet)
            }
        }
    }

    fun startClockAlignment(targetIp: InetAddress, isHost: Boolean) {
        isRunning = true
        pingJob?.cancel()

        if (!isHost) {
            // Client initiates PTP 10Hz sync with Host
            pingJob = scope.launch {
                val sendBuffer = ByteArray(16)
                while (isActive && isRunning) {
                    try {
                        val t0 = System.nanoTime()
                        ByteBuffer.wrap(sendBuffer, 0, 8).putLong(t0)
                        ByteBuffer.wrap(sendBuffer, 8, 8).putLong(0L) // reserved

                        meshEngine.sendUdpPacket(sendBuffer, targetIp)
                    } catch (e: Exception) {
                        Log.w(TAG, "PTP send failure", e)
                    }
                    delay(100) // 10Hz Ping-Pong loop (100ms)
                }
            }
        }
    }

    private fun handleIncomingPtpPacket(packet: DatagramPacket) {
        val len = packet.length
        val data = packet.data

        if (len == 16) {
            // 1. Host received request from client (contains t0). Host replies with (t0, t1, t2)
            val t0 = ByteBuffer.wrap(data, 0, 8).long
            val t1 = System.nanoTime()

            val replyBuffer = ByteArray(24)
            ByteBuffer.wrap(replyBuffer, 0, 8).putLong(t0)
            ByteBuffer.wrap(replyBuffer, 8, 8).putLong(t1)
            val t2 = System.nanoTime()
            ByteBuffer.wrap(replyBuffer, 16, 8).putLong(t2)

            meshEngine.sendUdpPacket(replyBuffer, packet.address, packet.port)
        } else if (len == 24) {
            // 2. Client received response from host (contains t0, t1, t2). Client marks t3.
            val t3 = System.nanoTime()
            val t0 = ByteBuffer.wrap(data, 0, 8).long
            val t1 = ByteBuffer.wrap(data, 8, 8).long
            val t2 = ByteBuffer.wrap(data, 16, 8).long

            // Process inside C++20 Kalman / EMA engine
            val offsetNanos = NativeBridge.processPtpTimestamps(t0, t1, t2, t3)
            val rttNanos = NativeBridge.getPtpRttNanos()

            _offsetMs.value = offsetNanos / 1_000_000f
            _rttMs.value = rttNanos / 1_000_000f
            _isSynchronized.value = true
        }
    }

    /**
     * Returns the atomic synchronized monotonic time in milliseconds.
     */
    fun getSynchronizedClockMs(): Long {
        return NativeBridge.getSynchronizedClockMs()
    }

    fun stop() {
        isRunning = false
        pingJob?.cancel()
        NativeBridge.resetPtpState()
        _isSynchronized.value = false
    }
}
