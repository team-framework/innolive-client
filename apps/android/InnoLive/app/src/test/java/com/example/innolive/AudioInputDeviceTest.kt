package com.example.innolive

import android.media.AudioDeviceInfo
import com.example.innolive.feature.live.AudioInputDevice
import com.example.innolive.feature.live.isSelectableAudioInputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun selectableTypesExcludeInternalAudioRoutes() {
        assertTrue(isSelectableAudioInputType(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertTrue(isSelectableAudioInputType(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertFalse(isSelectableAudioInputType(AudioDeviceInfo.TYPE_TELEPHONY))
        assertFalse(isSelectableAudioInputType(AudioDeviceInfo.TYPE_REMOTE_SUBMIX))
    }
}
