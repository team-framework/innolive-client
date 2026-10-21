package com.framework.innolive.feature.live

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPermissionSettingsTest {
    private val both = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    @Test fun noMissingPermissionDoesNotNeedSettings() {
        assertFalse(needsMediaPermissionSettings(emptyList()) { false })
    }

    @Test fun anyPermissionTheSystemCanStillAskDoesNotNeedSettings() {
        assertFalse(needsMediaPermissionSettings(both) { it == Manifest.permission.RECORD_AUDIO })
    }

    @Test fun permissionsTheSystemNoLongerAsksNeedSettings() {
        assertTrue(needsMediaPermissionSettings(both) { false })
    }
}
