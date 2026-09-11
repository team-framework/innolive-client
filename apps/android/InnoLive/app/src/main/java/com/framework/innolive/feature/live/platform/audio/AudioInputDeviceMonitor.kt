package com.framework.innolive.feature.live

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Keeps the selectable input list in sync with Android audio route changes.
 * Audio callbacks are registered only while the screen is composed.
 */
internal class AudioInputDeviceMonitor(context: Context) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val audioManager = applicationContext.getSystemService(AudioManager::class.java)
    private val callbackHandler = Handler(Looper.getMainLooper())
    private var isRegistered = false

    var devices by mutableStateOf(emptyList<AudioDeviceInfo>())
        private set

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (isRegistered) refresh()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (isRegistered) refresh()
        }
    }

    fun start() {
        if (isRegistered) return

        audioManager.registerAudioDeviceCallback(deviceCallback, callbackHandler)
        isRegistered = true
        refresh()
    }

    override fun close() {
        if (!isRegistered) return

        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        isRegistered = false
    }

    private fun refresh() {
        devices = audioManager
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { device -> isSelectableAudioInputType(device.type) }
            .distinctBy { device ->
                if (device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) null else device.id
            }
    }
}

@Composable
internal fun rememberAudioInputDevices(context: Context): List<AudioDeviceInfo> {
    val monitor = remember(context) { AudioInputDeviceMonitor(context) }
    DisposableEffect(monitor) {
        monitor.start()
        onDispose { monitor.close() }
    }
    return monitor.devices
}
