package com.example.innolive.feature.live

import android.media.AudioManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BluetoothCommunicationDeviceTest {
    @Test
    fun selectsAndClearsBluetoothCommunicationOutput() {
        assumeTrue("Android 12 이상에서 실행해야 합니다.", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

        val audioManager = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getSystemService(AudioManager::class.java)
        val bluetoothInput = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { device -> isBluetoothAudioInputType(device.type) }
        assumeTrue("Bluetooth 마이크가 연결된 기기에서 실행해야 합니다.", bluetoothInput != null)

        val communicationDevice = findBluetoothCommunicationDevice(
            checkNotNull(bluetoothInput),
            audioManager.availableCommunicationDevices,
        )
        assumeTrue("짝이 되는 Bluetooth 통신용 출력이 필요합니다.", communicationDevice != null)

        try {
            val selectedDevice = checkNotNull(communicationDevice)
            assertTrue(audioManager.setCommunicationDevice(selectedDevice))
            assertEquals(selectedDevice.id, audioManager.communicationDevice?.id)
        } finally {
            audioManager.clearCommunicationDevice()
        }
    }
}
