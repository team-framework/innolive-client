package com.example.innolive.feature.live

import android.media.AudioDeviceInfo

data class AudioInputDevice(
    val id: Int,
    val name: String,
    val isDefault: Boolean,
) {
    val displayName: String
        get() = if (isDefault) "내장 마이크" else name
}

internal fun isSelectableAudioInputType(type: Int): Boolean = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    -> true

    else -> false
}
