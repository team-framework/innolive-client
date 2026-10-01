package com.framework.innolive.feature.settings

import com.framework.innolive.feature.live.BroadcastRemainingTime
import com.framework.innolive.feature.live.SessionSnapshot
import com.framework.innolive.feature.live.compactRemainingSeconds
import com.framework.innolive.feature.live.displayedRemainingTime
import com.framework.innolive.feature.live.parseSessionSnapshot
import com.framework.innolive.ui.text.ServerErrorAction
import com.framework.innolive.ui.text.serverErrorGuidance
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PlanUsageTest {
    private val plan = """{"plan":"beam","allowed_modes":["720p_single","fhd_single"],"monthly_broadcast_seconds":432000,"max_per_broadcast_seconds":3600}"""
    private fun fixture(name: String): String {
        val root = generateSequence(File(checkNotNull(System.getProperty("innolive.android.resDir")))) { it.parentFile }
            .first { File(it, "contracts/fixtures").isDirectory }
        return File(root, "contracts/fixtures/plan-usage-$name.v1.json").readText()
    }

    @Test fun sharedFixturesDistinguishAllowedLockedExhaustedAndUnlimited() {
        val allowed = parsePlanUsage(plan, fixture("allowed"))
        assertTrue(allowed.isAllowed(PlanMode.HD_SINGLE))
        assertEquals(1L, allowed.availability(PlanMode.HD_SINGLE)!!.multiplier)
        assertEquals(3600L, allowed.previewRemaining(PlanMode.HD_SINGLE))
        assertFalse(parsePlanUsage(plan, fixture("locked")).isAllowed(PlanMode.HD_SINGLE))
        assertEquals(0L, parsePlanUsage(plan, fixture("exhausted")).previewRemaining(PlanMode.HD_SINGLE))
        val unlimited = parsePlanUsage(plan, fixture("unlimited"))
        assertNull(unlimited.remainingSeconds)
        assertEquals(3600L, unlimited.previewRemaining(PlanMode.HD_SINGLE))
        assertNull(unlimited.copy(maxBroadcastSeconds = 0).previewRemaining(PlanMode.HD_SINGLE))
        assertFalse(allowed.isAllowed(PlanMode.FHD_SINGLE))
        assertFalse(allowed.copy(allowedModes = emptyList()).isAllowed(PlanMode.HD_SINGLE))
    }

    @Test fun malformedAndMissingTimesCannotBecomeUnlimited() {
        val usage = fixture("allowed")
        for (invalid in listOf("-1", "1.5", "\"7200\"", "true", "9223372036854775808")) {
            assertThrows(Exception::class.java) { parsePlanUsage(plan, usage.replace("\"remaining_seconds\": 7200", "\"remaining_seconds\": $invalid")) }
        }
        assertThrows(Exception::class.java) { parsePlanUsage(plan, usage.replace("\"remaining_seconds\": 7200,", "")) }
        assertThrows(Exception::class.java) { parsePlanUsage(plan, usage.replace("\"seconds\": 7200,", "")) }
        assertThrows(Exception::class.java) { parsePlanUsage(plan, usage.replace("\"multiplier\": 1", "\"multiplier\": 0")) }
        assertThrows(Exception::class.java) { parsePlanUsage(plan, usage.replace("beam", "spark")) }
    }

    @Test fun activeSessionAlwaysOverridesMonthlyUsageAndNullOnlyMeansUnlimitedWhenKnown() {
        val usage = parsePlanUsage(plan, fixture("allowed"))
        assertEquals(BroadcastRemainingTime.Seconds(3600), displayedRemainingTime(false, null, usage))
        assertEquals(BroadcastRemainingTime.Unknown, displayedRemainingTime(true, null, usage))
        val session = SessionSnapshot("s", remainingTime = BroadcastRemainingTime.Seconds(720))
        assertEquals(BroadcastRemainingTime.Seconds(720), displayedRemainingTime(true, session, usage))
        assertEquals(BroadcastRemainingTime.UnlimitedOrInactive,
            displayedRemainingTime(true, session.copy(remainingTime = BroadcastRemainingTime.UnlimitedOrInactive), usage))
        assertEquals(BroadcastRemainingTime.Unknown, displayedRemainingTime(false, null, usage.copy(allowedModes = emptyList())))
    }

    @Test fun snapshotRetainsResolutionAndStaleValueUntilRemainingTimeIsConfirmed() {
        val stale = SessionSnapshot("s", remainingTime = BroadcastRemainingTime.Seconds(720),
            broadcastResolution = "fhd", isRemainingTimeStale = true)
        assertEquals(stale, parseSessionSnapshot("{}", "s", stale))
        val fresh = parseSessionSnapshot("""{"broadcast_remaining_seconds":600,"broadcast_resolution":"720p"}""", "s", stale)
        assertFalse(fresh.isRemainingTimeStale)
        assertEquals("720p", fresh.broadcastResolution)
        assertEquals(BroadcastRemainingTime.Seconds(600), fresh.remainingTime)
    }

    @Test fun modeAndTimeFormattingReflectTheServerValues() {
        assertEquals(PlanMode.HD_SINGLE, PlanMode.current(null, 0))
        assertEquals(PlanMode.FHD_SINGLE, PlanMode.current("fhd", 1))
        assertEquals(PlanMode.HD_MULTI, PlanMode.current("720p", 2))
        assertEquals(PlanMode.FHD_MULTI, PlanMode.current("fhd", 2))
        assertEquals("12min", compactRemainingSeconds(720))
        assertEquals("59s", compactRemainingSeconds(59))
        assertEquals("0s", compactRemainingSeconds(0))
        assertEquals("01:01:01", formatPlanDuration(3661))
        assertEquals("00:00:00", formatPlanDuration(0))
        assertEquals(ServerErrorAction.PLAN, serverErrorGuidance("monthly_limit_exhausted")!!.action)
    }
}
