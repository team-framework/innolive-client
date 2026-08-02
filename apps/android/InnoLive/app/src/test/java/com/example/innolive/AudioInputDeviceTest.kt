package com.example.innolive

import com.example.innolive.feature.live.AudioInputDevice
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioInputDeviceTest {
    @Test
    fun displayNameMarksOnlyDefaultDevice() {
        assertEquals(
            "내장 마이크",
            AudioInputDevice(id = 1, name = "내장 마이크", isDefault = true).displayName,
        )
        assertEquals(
            "USB 마이크",
            AudioInputDevice(id = 2, name = "USB 마이크", isDefault = false).displayName,
        )
    }
}
