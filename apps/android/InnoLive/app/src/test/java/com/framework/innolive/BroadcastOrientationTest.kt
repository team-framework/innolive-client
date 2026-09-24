package com.framework.innolive

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Surface
import com.framework.innolive.feature.live.BroadcastState
import com.framework.innolive.feature.live.nextBroadcastRotation
import com.framework.innolive.feature.live.screenOrientationFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BroadcastOrientationTest {
    @Test
    fun portraitNaturalDeviceKeepsEachLandscapeDirection() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            screenOrientationFor(Surface.ROTATION_90, Configuration.ORIENTATION_LANDSCAPE),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            screenOrientationFor(Surface.ROTATION_270, Configuration.ORIENTATION_LANDSCAPE),
        )
    }

    @Test
    fun landscapeNaturalDeviceLocksCurrentDirection() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            screenOrientationFor(Surface.ROTATION_0, Configuration.ORIENTATION_LANDSCAPE),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            screenOrientationFor(Surface.ROTATION_180, Configuration.ORIENTATION_LANDSCAPE),
        )
    }

    @Test
    fun liveOperationsKeepTheOriginalRotation() {
        val originalRotation = 3 // The opposite landscape direction must not replace it.
        for (state in listOf(
            BroadcastState.GOING_LIVE,
            BroadcastState.LIVE,
            BroadcastState.PAUSING,
            BroadcastState.PAUSED,
            BroadcastState.RESUMING,
            BroadcastState.STOPPING,
        )) {
            assertEquals(state.name, originalRotation, nextBroadcastRotation(originalRotation, state))
        }
    }

    @Test
    fun completedOrFailedBroadcastReleasesTheRotation() {
        for (state in listOf(
            BroadcastState.PREPARED,
            BroadcastState.IDLE,
            BroadcastState.FAILED,
        )) {
            assertNull(state.name, nextBroadcastRotation(1, state))
        }
    }
}
