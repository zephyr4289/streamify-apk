package com.streamify.app.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioDeviceType {
    BLUETOOTH_CAR,
    WIRED_DAC,
    SPEAKER,
    BLUETOOTH_HEADPHONES
}

data class AudioOutputDevice(
    val name: String,
    val isBluetooth: Boolean = false,
    val isHeadphones: Boolean = false,
    val isSpeaker: Boolean = false
)

object AudioDeviceManager {

    private val _currentDevice = MutableStateFlow(AudioOutputDevice("Phone Speaker", isSpeaker = true))
    val currentDevice: StateFlow<AudioOutputDevice> = _currentDevice.asStateFlow()

    fun getCurrentDeviceType(): AudioDeviceType {
        val dev = _currentDevice.value
        val nameLower = dev.name.lowercase()
        return when {
            nameLower.contains("car") || nameLower.contains("auto") -> AudioDeviceType.BLUETOOTH_CAR
            dev.isBluetooth -> AudioDeviceType.BLUETOOTH_HEADPHONES
            dev.isHeadphones || nameLower.contains("dac") || nameLower.contains("usb") -> AudioDeviceType.WIRED_DAC
            else -> AudioDeviceType.SPEAKER
        }
    }

    private var isReceiverRegistered = false

    private val audioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            context?.let { 
                updateCurrentDevice(it)
                autoRoutePreset(it)
            }
        }
    }

    fun init(context: Context) {
        updateCurrentDevice(context)
        if (!isReceiverRegistered) {
            try {
                val filter = IntentFilter().apply {
                    addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                    addAction(AudioManager.ACTION_HEADSET_PLUG)
                    addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
                    addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
                }
                context.registerReceiver(audioReceiver, filter)
                isReceiverRegistered = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun autoRoutePreset(context: Context) {
        val prefs = context.getSharedPreferences("streamify_eq_prefs", Context.MODE_PRIVATE)
        val autoRoutingEnabled = prefs.getBoolean("auto_routing_preset", true)
        if (!autoRoutingEnabled) return

        val device = _currentDevice.value
        val targetPreset = when {
            device.isBluetooth -> prefs.getString("preset_bluetooth", "Bass Booster") ?: "Bass Booster"
            device.isHeadphones -> prefs.getString("preset_headphones", "Acoustic") ?: "Acoustic"
            else -> prefs.getString("preset_speaker", "Vocal Booster") ?: "Vocal Booster"
        }

        EqualizerManager.applyPresetByName(targetPreset)
    }

    fun updateCurrentDevice(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (device in devices) {
                when (device.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER -> {
                        val deviceName = device.productName?.toString() ?: "Bluetooth Audio"
                        _currentDevice.value = AudioOutputDevice(
                            name = deviceName,
                            isBluetooth = true
                        )
                        return
                    }
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE -> {
                        _currentDevice.value = AudioOutputDevice(
                            name = "Headphones / DAC",
                            isHeadphones = true
                        )
                        return
                    }
                }
            }
        }

        // Fallback or default
        _currentDevice.value = AudioOutputDevice(
            name = "This Phone",
            isSpeaker = true
        )
    }

    fun openSystemAudioSettings(context: Context) {
        try {
            val intent = Intent(android.provider.Settings.ACTION_SOUND_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }
}
