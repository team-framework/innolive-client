package com.framework.innolive.feature.face

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegistrationOrientationLockTest {
    @Test
    fun locksWhenNothingElseFixedOrientation() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LOCKED,
            RegistrationOrientationLock.lockFrom(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED),
        )
    }

    @Test
    fun keepsBroadcastOrientationLock() {
        assertNull(RegistrationOrientationLock.lockFrom(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE))
        assertNull(RegistrationOrientationLock.lockFrom(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT))
    }

    @Test
    fun unlocksOnlyOwnLock() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            RegistrationOrientationLock.unlockFrom(ActivityInfo.SCREEN_ORIENTATION_LOCKED),
        )
        // 고정한 사이 방송이 방향을 정했거나 풀었으면 그 값을 덮어쓰지 않습니다.
        assertNull(RegistrationOrientationLock.unlockFrom(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE))
        assertNull(RegistrationOrientationLock.unlockFrom(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED))
    }
}
