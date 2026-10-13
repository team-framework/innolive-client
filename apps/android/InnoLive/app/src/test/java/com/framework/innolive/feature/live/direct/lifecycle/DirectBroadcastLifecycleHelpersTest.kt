package com.framework.innolive.feature.live.direct.lifecycle

import android.os.PowerManager
import androidx.camera.core.CameraState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectBroadcastLifecycleHelpersTest {
    @Test fun notificationReadyBelowAndroid13WhenEnabled() {
        assertEquals(NotificationPermissionAction.READY, action(sdk = 32, enabled = true, granted = false))
    }

    @Test fun notificationGuidanceBelowAndroid13WhenDisabled() {
        assertEquals(NotificationPermissionAction.SHOW_GUIDANCE, action(sdk = 32, enabled = false, granted = false))
    }

    @Test fun notificationRequestedOnceOnAndroid13() {
        assertEquals(NotificationPermissionAction.REQUEST, action(sdk = 33, enabled = false, granted = false))
        assertEquals(
            NotificationPermissionAction.SHOW_GUIDANCE,
            action(sdk = 33, enabled = false, granted = false, requested = true),
        )
    }

    @Test fun notificationRequestedAgainWhenSystemAllowsRationale() {
        assertEquals(
            NotificationPermissionAction.REQUEST,
            action(sdk = 34, enabled = false, granted = false, requested = true, rationale = true),
        )
    }

    @Test fun grantedButDisabledNotificationsShowGuidance() {
        assertEquals(NotificationPermissionAction.SHOW_GUIDANCE, action(sdk = 34, enabled = false, granted = true))
        assertEquals(NotificationPermissionAction.READY, action(sdk = 34, enabled = true, granted = true))
    }

    @Test fun cameraOpenIsAvailable() {
        assertEquals(false, cameraUnavailable(CameraState.Type.OPEN, hasError = false))
    }

    @Test fun cameraErrorIsUnavailable() {
        assertEquals(true, cameraUnavailable(CameraState.Type.PENDING_OPEN, hasError = true))
        assertEquals(true, cameraUnavailable(CameraState.Type.CLOSED, hasError = true))
    }

    @Test fun cameraClosedWithoutErrorLeavesStateUnchanged() {
        assertNull(cameraUnavailable(CameraState.Type.CLOSING, hasError = false))
        assertNull(cameraUnavailable(CameraState.Type.CLOSED, hasError = false))
    }

    @Test fun thermalWarningStartsAtSevere() {
        assertFalse(isThermalWarning(PowerManager.THERMAL_STATUS_MODERATE))
        assertTrue(isThermalWarning(PowerManager.THERMAL_STATUS_SEVERE))
        assertTrue(isThermalWarning(PowerManager.THERMAL_STATUS_SHUTDOWN))
    }

    @Test fun staleStopRegistrationDoesNotRemoveNewerHandler() {
        val requests = DirectBroadcastStopRequests()
        var first = 0
        var second = 0
        val old = requests.register { first++ }
        requests.register { second++ }

        old.close()

        assertTrue(requests.request())
        assertEquals(0, first)
        assertEquals(1, second)
    }

    @Test fun stopRequestWithoutHandlerIsIgnored() {
        val requests = DirectBroadcastStopRequests()
        requests.register {}.close()

        assertFalse(requests.request())
    }

    private fun action(
        sdk: Int,
        enabled: Boolean,
        granted: Boolean,
        requested: Boolean = false,
        rationale: Boolean = false,
    ) = notificationPermissionAction(sdk, enabled, granted, requested, rationale)
}
