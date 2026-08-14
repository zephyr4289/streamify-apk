package com.streamify.app.service

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object EqualizerManager {
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    data class EqBand(val centerFreqHz: Int, val level: Short, val lowerHz: Int, val upperHz: Int)

    private val _isEqEnabled = MutableStateFlow(false)
    val isEqEnabled: StateFlow<Boolean> = _isEqEnabled.asStateFlow()

    private val _isLoudnessNormalizationEnabled = MutableStateFlow(true)
    val isLoudnessNormalizationEnabled: StateFlow<Boolean> = _isLoudnessNormalizationEnabled.asStateFlow()

    private val _bands = MutableStateFlow<List<EqBand>>(emptyList())
    val bands: StateFlow<List<EqBand>> = _bands.asStateFlow()

    private val _bassStrength = MutableStateFlow<Short>(0)
    val bassStrength: StateFlow<Short> = _bassStrength.asStateFlow()

    private val _virtualizerStrength = MutableStateFlow<Short>(0)
    val virtualizerStrength: StateFlow<Short> = _virtualizerStrength.asStateFlow()

    // Limits
    var minEqLevel: Short = -1500
    var maxEqLevel: Short = 1500

    fun init(audioSessionId: Int) {
        release()
        try {
            equalizer = Equalizer(0, audioSessionId)
            bassBoost = BassBoost(0, audioSessionId)
            virtualizer = Virtualizer(0, audioSessionId)
            loudnessEnhancer = LoudnessEnhancer(audioSessionId)

            equalizer?.enabled = _isEqEnabled.value
            bassBoost?.enabled = _isEqEnabled.value
            virtualizer?.enabled = _isEqEnabled.value
            
            loudnessEnhancer?.enabled = _isLoudnessNormalizationEnabled.value
            if (_isLoudnessNormalizationEnabled.value) {
                loudnessEnhancer?.setTargetGain(150)
            }

            val eq = equalizer ?: return
            val bandRange = eq.bandLevelRange
            minEqLevel = bandRange[0]
            maxEqLevel = bandRange[1]

            val numBands = eq.numberOfBands
            val newBands = mutableListOf<EqBand>()
            for (i in 0 until numBands) {
                val freq = eq.getCenterFreq(i.toShort()) / 1000 // mHz to Hz
                val range = eq.getBandFreqRange(i.toShort())
                val level = eq.getBandLevel(i.toShort())
                newBands.add(EqBand(freq, level, range[0] / 1000, range[1] / 1000))
            }
            if (_bands.value.isEmpty()) {
                _bands.value = newBands
            } else {
                // Apply existing saved bands
                _bands.value.forEachIndexed { index, band ->
                    eq.setBandLevel(index.toShort(), band.level)
                }
            }

            bassBoost?.setStrength(_bassStrength.value)
            virtualizer?.setStrength(_virtualizerStrength.value)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setLoudnessNormalization(enabled: Boolean) {
        _isLoudnessNormalizationEnabled.value = enabled
        try {
            loudnessEnhancer?.enabled = enabled
            if (enabled) {
                loudnessEnhancer?.setTargetGain(150)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setEqEnabled(enabled: Boolean) {
        _isEqEnabled.value = enabled
        equalizer?.enabled = enabled
        bassBoost?.enabled = enabled
        virtualizer?.enabled = enabled
    }

    fun setBandLevel(bandIndex: Short, level: Short) {
        try {
            equalizer?.setBandLevel(bandIndex, level)
            val current = _bands.value.toMutableList()
            if (bandIndex < current.size) {
                current[bandIndex.toInt()] = current[bandIndex.toInt()].copy(level = level)
                _bands.value = current
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun setBassStrength(strength: Short) {
        _bassStrength.value = strength
        try { bassBoost?.setStrength(strength) } catch (e: Exception) {}
    }

    fun setVirtualizerStrength(strength: Short) {
        _virtualizerStrength.value = strength
        try { virtualizer?.setStrength(strength) } catch (e: Exception) {}
    }

    fun release() {
        equalizer?.release()
        bassBoost?.release()
        virtualizer?.release()
        loudnessEnhancer?.release()
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
    }
}
