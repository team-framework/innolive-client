package com.framework.innolive.feature.live

import com.framework.innolive.feature.live.components.sessionNoticeMessageResource
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SessionSnapshotTest {
    private fun fixture(name: String): SessionSnapshot {
        val root = generateSequence(File(checkNotNull(System.getProperty("innolive.android.resDir")))) { it.parentFile }
            .first { File(it, "contracts/fixtures").isDirectory }
        val payload = File(root, "contracts/fixtures/broadcast-session-state-$name.v1.json").readText()
        val id = org.json.JSONObject(payload).getString("session_id")
        return parseSessionSnapshot(payload, id)
    }

    @Test fun sharedServerFixturesPreserveTargetsNoticesAndUnknownValues() {
        val youtube = fixture("youtube")
        assertEquals(BroadcastState.LIVE, youtube.broadcastState())
        assertEquals(BroadcastRemainingTime.Seconds(120), youtube.remainingTime)
        assertEquals("youtube", youtube.visibleTargets.single().provider)
        val dual = fixture("dual")
        assertEquals(2, dual.visibleTargets.size)
        assertEquals("broadcast_limit_30m", dual.notices!!.single().code)
        assertEquals("chzzk", fixture("chzzk").provider)
        val unknown = fixture("unknown")
        assertEquals("future_provider", unknown.targets!!.single().provider)
        assertEquals("future_status", unknown.targets!!.single().status)
        assertEquals("future_notice", unknown.notices!!.single().code)
        assertNull(unknown.broadcastState())
    }

    @Test fun missingNullAndZeroRemainingTimeAreDifferent() {
        assertEquals(BroadcastRemainingTime.Unknown, fixture("missing").remainingTime)
        assertEquals(BroadcastRemainingTime.UnlimitedOrInactive, fixture("null").remainingTime)
        val zero = parseSessionSnapshot("""{"broadcast_remaining_seconds":0}""", "s")
        assertEquals(BroadcastRemainingTime.Seconds(0), zero.remainingTime)
        for (invalid in listOf("-1", "1.5", "\"120\"", "true", "9223372036854775808")) {
            assertEquals(zero, parseSessionSnapshot("""{"broadcast_remaining_seconds":$invalid}""", "s", zero))
        }
    }

    @Test fun sessionAndPrepareWarningsExposeEachCodeOnce() {
        val prepared = parseSessionSnapshot(
            """{"notices":[{"code":"youtube_quota_low","at":"2026-09-30T00:00:00Z"}],
                "warnings":[{"code":"youtube_quota_low","message":"server copy"},{"code":"thumbnail_failed","message":"ignored"}]}""",
            "s",
        )
        assertEquals(listOf("youtube_quota_low", "thumbnail_failed"), prepared.bannerNotices.map { it.code })

        val polled = parseSessionSnapshot("{}", "s", prepared)
        assertEquals(prepared.warnings, polled.warnings)
        assertEquals(
            emptyList<SessionNotice>(),
            parseSessionSnapshot("""{"warnings":[]}""", "s", prepared).warnings,
        )

        val nextPrepare = parseSessionSnapshot("{}", "s", polled, clearWarningsWhenMissing = true)
        assertEquals(emptyList<SessionNotice>(), nextPrepare.warnings)
        assertEquals(listOf("youtube_quota_low"), nextPrepare.bannerNotices.map { it.code })
    }

    @Test fun localizedBannerCopyCoversSpecifiedNoticeCodesOnly() {
        val codes = listOf(
            "broadcast_limit_30m", "broadcast_limit_10m", "broadcast_limit_reached",
            "monthly_usage_80", "monthly_usage_100", "monthly_limit_reached", "no_input_stopped",
            "channel_live_elsewhere", "platform_broadcast_ended", "youtube_quota_low",
        )
        assertTrue(codes.all { sessionNoticeMessageResource(it) != null })
        assertNull(sessionNoticeMessageResource("future_notice"))
    }

    @Test fun partialStreamResponseDoesNotEraseOtherTargetsNoticesOrTime() {
        val initial = parseSessionSnapshot("""{"session_id":"s","provider":"youtube",
            "targets":[{"provider":"youtube","stream":{"status":"streaming","broadcast_phase":"live"}},
            {"provider":"chzzk","stream":{"status":"streaming","broadcast_phase":"live"}}],
            "notices":[{"code":"broadcast_limit_30m","at":"2026-09-30T00:00:00Z"}],
            "broadcast_remaining_seconds":120}""", "s")
        val paused = parseSessionSnapshot("""{"status":"paused","broadcast_phase":"live"}""", "s", initial)
        assertEquals(BroadcastState.PAUSED, paused.broadcastState())
        assertEquals(2, paused.visibleTargets.size)
        assertEquals(initial.notices, paused.notices)
        assertEquals(initial.remainingTime, paused.remainingTime)
        assertEquals(initial.targets!!.last(), paused.targets!!.first())
        assertEquals(paused, parseSessionSnapshot("{}", "s", paused))
    }

    @Test fun emptyTargetsOverrideLegacyStreamAndIdleTargetsRetainStopReason() {
        val initial = fixture("youtube")
        val empty = parseSessionSnapshot("""{"targets":[],"stream":{"status":"streaming","broadcast_phase":"live"}}""",
            initial.sessionId, initial)
        assertEquals(emptyList<SessionTarget>(), empty.targets)
        assertEquals(BroadcastState.IDLE, empty.broadcastState())
        val stopped = parseSessionSnapshot("""{"targets":[{"provider":"youtube","stream":{
            "status":"stopped","broadcast_phase":"idle","stop_reason":"platform_ended","reconnect_attempts":3}}]}""",
            initial.sessionId, initial)
        assertTrue(stopped.visibleTargets.isEmpty())
        assertEquals("platform_ended", stopped.targets!!.single().stopReason)
        assertEquals(3L, stopped.targets.single().reconnectAttempts)
        assertEquals(BroadcastState.IDLE, stopped.broadcastState())
    }

    @Test fun newFullLegacySnapshotReplacesOldTargetsAndNullNoticesClear() {
        val initial = parseSessionSnapshot("""{"targets":[{"provider":"chzzk","stream":{
            "status":"streaming","broadcast_phase":"live"}}],"notices":[{"code":"old"}]}""", "s")
        val next = parseSessionSnapshot("""{"session_id":"s","stream":{"status":"idle","broadcast_phase":"idle"},
            "notices":null}""", "s", initial)
        assertEquals("youtube", next.targets!!.single().provider)
        assertEquals(emptyList<SessionNotice>(), next.notices)
        assertEquals(BroadcastState.IDLE, next.broadcastState())
    }

    @Test fun mismatchedSessionAndMalformedTargetAreRejected() {
        for (payload in listOf("""{"session_id":"old"}""", """{"targets":[{"stream":{}}]}""",
            """{"targets":[null]}""")) {
            assertThrows(Exception::class.java) { parseSessionSnapshot(payload, "current") }
        }
    }

    @Test fun reconfiguringStatesKeepLiveAndPauseIntent() {
        for ((status, expected) in listOf("reconfiguring" to BroadcastState.LIVE,
            "paused_reconfiguring" to BroadcastState.PAUSED, "paused_reconnecting" to BroadcastState.PAUSED)) {
            val next = parseSessionSnapshot("""{"status":"$status","broadcast_phase":"live"}""", "s")
            assertEquals(expected, next.broadcastState())
        }
    }
}
