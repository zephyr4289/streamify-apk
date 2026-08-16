package com.streamify.app.service

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import kotlinx.coroutines.*
import kotlin.math.abs

class PhaseLockedLoopController(
    private val player: Player,
    private val syncAudioProcessor: SyncAudioProcessor
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pllJob: Job? = null
    private var isRunning = false

    @Volatile
    private var hostTargetPositionMs: Long = 0L

    @Volatile
    private var hostIsPlaying: Boolean = false

    @Volatile
    private var hostClockTimestamp: Long = 0L

    fun startPll(precisionProtocol: PrecisionTimeProtocol) {
        if (isRunning) return
        isRunning = true

        pllJob?.cancel()
        pllJob = scope.launch {
            while (isActive && isRunning) {
                try {
                    if (player.isPlaying && hostIsPlaying && hostTargetPositionMs > 0) {
                        val synchronizedNow = precisionProtocol.getSynchronizedClockMs()
                        val hostElapsed = (synchronizedNow - hostClockTimestamp).coerceAtLeast(0L)
                        val expectedHostAcousticPos = hostTargetPositionMs + hostElapsed

                        val rawPlayerPos = player.currentPosition
                        val clientAcousticPos = syncAudioProcessor.getAcousticPositionMs(rawPlayerPos)

                        val driftMs = clientAcousticPos - expectedHostAcousticPos
                        val absDrift = abs(driftMs)

                        when {
                            absDrift <= 12L -> {
                                // Zone 1: Sub-15ms Target achieved! Hard lock at standard 1.0x pitch
                                if (player.playbackParameters.speed != 1.0f) {
                                    player.playbackParameters = PlaybackParameters(1.0f, 1.0f)
                                }
                            }
                            absDrift in 13L..120L -> {
                                // Zone 2: Continuous Phase Lock Loop micro-pitch scaling (Inaudible to human ear)
                                val targetSpeed = if (driftMs > 0) 0.996f else 1.004f
                                if (player.playbackParameters.speed != targetSpeed) {
                                    player.playbackParameters = PlaybackParameters(targetSpeed, 1.0f)
                                }
                            }
                            absDrift > 120L -> {
                                // Zone 3: Major desync (e.g. user scrubbed seekbar) -> Fast seek
                                player.playbackParameters = PlaybackParameters(1.0f, 1.0f)
                                player.seekTo(expectedHostAcousticPos)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore transient PLL errors
                }
                delay(400) // 2.5Hz PLL correction loop
            }
        }
    }

    fun updateHostReference(positionMs: Long, isPlaying: Boolean, clockTimestamp: Long) {
        this.hostTargetPositionMs = positionMs
        this.hostIsPlaying = isPlaying
        this.hostClockTimestamp = clockTimestamp
    }

    fun stop() {
        isRunning = false
        pllJob?.cancel()
        if (player.playbackParameters.speed != 1.0f) {
            player.playbackParameters = PlaybackParameters(1.0f, 1.0f)
        }
    }
}
