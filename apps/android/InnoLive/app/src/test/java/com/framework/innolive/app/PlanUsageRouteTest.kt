package com.framework.innolive.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanUsageRouteTest {
    @Test fun planUsageScreenLoadsSoItsRefreshButtonSendsARequest() {
        assertTrue(loadsPlanUsage(PlanUsageRoute))
        assertTrue(loadsPlanUsage(LiveRoute))
        assertTrue(loadsPlanUsage(SettingsRoute))
        assertFalse(loadsPlanUsage(AccountSettingsRoute))
        assertFalse(loadsPlanUsage(null))
    }

    @Test fun planUsageScreenKeepsRemainingTimeCurrentDuringBroadcast() {
        assertTrue(pollsPlanUsage(PlanUsageRoute))
        assertTrue(pollsPlanUsage(SettingsRoute))
        assertFalse(pollsPlanUsage(LiveRoute))
    }
}
