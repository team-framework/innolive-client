package com.framework.innolive.feature.live

import com.framework.innolive.R
import com.framework.innolive.feature.live.components.sessionNoticeMessageResource
import com.framework.innolive.ui.text.UiText
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

    @Test fun youtubeVideoIdIsParsedFromTargetsAndKeptAcrossPartialResponses() {
        val root = generateSequence(File(checkNotNull(System.getProperty("innolive.android.resDir")))) { it.parentFile }
            .first { File(it, "contracts/api/fixtures").isDirectory }
        val payload = File(root, "contracts/api/fixtures/youtube-viewer-chat.json").readText()
        val live = parseSessionSnapshot(payload, "s")
        assertEquals("dQw4w9WgXcQ", live.targets!!.first { it.provider == "youtube" }.videoId)
        assertNull(live.targets!!.first { it.provider == "chzzk" }.videoId)
        // 한 대상의 stream만 오는 부분 응답과 video_id가 없는 이전 서버 응답은 ID를 지우지 않는다.
        val paused = parseSessionSnapshot("""{"stream":{"status":"paused","broadcast_phase":"live"}}""", "s", live)
        assertEquals("dQw4w9WgXcQ", paused.targets!!.first { it.provider == "youtube" }.videoId)
        val legacy = parseSessionSnapshot(
            """{"targets":[{"provider":"youtube","stream":{"status":"streaming","broadcast_phase":"live"}}]}""", "s", paused,
        )
        assertEquals("dQw4w9WgXcQ", legacy.targets!!.single().videoId)
        assertNull(parseSessionSnapshot(payload, "s2").targets!!.first { it.provider == "chzzk" }.videoId)
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

    @Test fun lastRemainingSecondsSurvivesNullWithinTheSameSession() {
        val live = parseSessionSnapshot("""{"broadcast_remaining_seconds":42900}""", "s")
        val stopped = parseSessionSnapshot("""{"broadcast_remaining_seconds":null}""", "s", live)

        assertEquals(BroadcastRemainingTime.UnlimitedOrInactive, stopped.remainingTime)
        assertEquals(42_900L, stopped.lastRemainingSeconds)
        // 새 세션은 이전 세션의 값을 이어받지 않는다.
        assertEquals(null, parseSessionSnapshot("""{"broadcast_remaining_seconds":null}""", "s2").lastRemainingSeconds)
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
        // 치지직이 아직 송출 중이므로 합산 상태는 라이브입니다.
        assertEquals(BroadcastState.LIVE, paused.broadcastState())
        assertEquals(2, paused.visibleTargets.size)
        assertEquals(initial.notices, paused.notices)
        assertEquals(initial.remainingTime, paused.remainingTime)
        assertEquals(initial.targets!!.last(), paused.targets!!.first())
        assertEquals(paused, parseSessionSnapshot("{}", "s", paused))
    }

    @Test fun simulcastStateCombinesTargetsAndTargetResponsesUpdateOnlyThatTarget() {
        val initial = parseSessionSnapshot("""{"session_id":"s","provider":"youtube",
            "targets":[{"provider":"youtube","stream":{"status":"streaming","broadcast_phase":"live"}},
            {"provider":"chzzk","stream":{"status":"streaming","broadcast_phase":"live"}}]}""", "s")
        val chzzkPaused = parseSessionSnapshot(
            """{"status":"paused","broadcast_phase":"live"}""", "s", initial, targetProvider = "chzzk",
        )
        assertEquals("youtube", chzzkPaused.provider)
        assertEquals("streaming", chzzkPaused.targets!!.first { it.provider == "youtube" }.status)
        assertEquals("paused", chzzkPaused.targets!!.first { it.provider == "chzzk" }.status)
        assertEquals(BroadcastState.LIVE, chzzkPaused.broadcastState())

        val bothPaused = parseSessionSnapshot("""{"status":"paused","broadcast_phase":"live"}""", "s", chzzkPaused)
        assertEquals(BroadcastState.PAUSED, bothPaused.broadcastState())

        val oneStopped = parseSessionSnapshot(
            """{"status":"stopped","broadcast_phase":"idle"}""", "s", bothPaused, targetProvider = "chzzk",
        )
        assertEquals(BroadcastState.PAUSED, oneStopped.broadcastState())
        assertEquals(listOf("youtube"), oneStopped.visibleTargets.map { it.provider })

        val partlyPrepared = parseSessionSnapshot("""{"session_id":"s","provider":"youtube",
            "targets":[{"provider":"youtube","stream":{"broadcast_phase":"idle"}},
            {"provider":"chzzk","stream":{"broadcast_phase":"prepared"}}]}""", "s")
        assertEquals(BroadcastState.PREPARED, partlyPrepared.broadcastState())
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
    @Test fun simulcastRetryTargetsOnlyUnpreparedSelectedPlatforms() {
        val both = setOf(BroadcastProvider.YOUTUBE, BroadcastProvider.CHZZK)
        fun snapshot(youtube: String, chzzk: String?) = SessionSnapshot("s", targets = listOfNotNull(
            SessionTarget("youtube", "idle", youtube, null, null),
            chzzk?.let { SessionTarget("chzzk", "idle", it, null, null) },
        ))
        assertEquals(listOf(BroadcastProvider.CHZZK), snapshot("prepared", "idle").unpreparedProviders(both))
        assertEquals(listOf(BroadcastProvider.CHZZK), snapshot("prepared", null).unpreparedProviders(both))
        assertEquals(emptyList<BroadcastProvider>(), snapshot("prepared", "prepared").unpreparedProviders(both))
        assertEquals(both.toList(), null.unpreparedProviders(both))
    }

    private fun fixtureText(name: String): String {
        val root = generateSequence(File(checkNotNull(System.getProperty("innolive.android.resDir")))) { it.parentFile }
            .first { File(it, "contracts/fixtures").isDirectory }
        return File(root, "contracts/fixtures/broadcast-session-state-$name.v1.json").readText()
    }

    /** broadcast-session-state-v1.md: golive는 StreamState·targets·failed_targets만 돌려주며 일부 실패도 성공 응답이다. */
    @Test fun goLivePartialFixtureUpdatesTargetsAndKeepsTheLastFullSnapshotFields() {
        val live = fixture("dual")
        val payload = fixtureText("golive-partial")
        val next = parseSessionSnapshot(payload, live.sessionId, live)

        assertEquals(BroadcastState.LIVE, next.broadcastState())
        assertEquals(listOf("chzzk"), next.visibleTargets.map { it.provider })
        assertEquals("idle", next.targets!!.first { it.provider == "youtube" }.broadcastPhase)
        assertEquals(live.notices, next.notices)
        assertEquals(BroadcastRemainingTime.Seconds(120), next.remainingTime)
        assertEquals(120L, next.lastRemainingSeconds)
        assertEquals("fhd", next.broadcastResolution)
        assertEquals("youtube", next.provider)

        assertEquals(listOf(BroadcastProvider.YOUTUBE), parseFailedTargets(payload))
        assertEquals(BroadcastState.IDLE, parseBroadcastState(payload, BroadcastProvider.YOUTUBE))
        assertEquals(BroadcastState.LIVE, parseBroadcastState(payload, BroadcastProvider.CHZZK))
        assertEquals(UiText.Resource(R.string.simulcast_golive_failed_youtube),
            simulcastGoLiveFailedMessage(parseFailedTargets(payload)))
    }

    @Test fun failedTargetsIgnoreUnknownProvidersAndDuplicates() {
        assertEquals(emptyList<BroadcastProvider>(), parseFailedTargets("""{"targets":[]}"""))
        assertEquals(listOf(BroadcastProvider.CHZZK), parseFailedTargets(
            """{"failed_targets":[{"provider":"future_provider","code":"x"},{"provider":"chzzk","code":"a"},
                {"provider":"chzzk","code":"b"},{"code":"no_provider"}]}"""))
        assertEquals(UiText.Resource(R.string.simulcast_golive_failed_all),
            simulcastGoLiveFailedMessage(listOf(BroadcastProvider.YOUTUBE, BroadcastProvider.CHZZK)))
        assertEquals(UiText.Resource(R.string.simulcast_golive_failed_chzzk),
            simulcastGoLiveFailedMessage(listOf(BroadcastProvider.CHZZK)))
        assertEquals(UiText.Resource(R.string.simulcast_prepare_failed_all),
            simulcastPrepareFailedMessage(listOf(BroadcastProvider.YOUTUBE, BroadcastProvider.CHZZK)))
        assertEquals(UiText.Resource(R.string.simulcast_prepare_failed_youtube),
            simulcastPrepareFailedMessage(listOf(BroadcastProvider.YOUTUBE)))
    }

    @Test fun failedTargetCodesKeepTheFirstReasonPerProvider() {
        assertEquals(emptyMap<BroadcastProvider, String>(), parseFailedTargetCodes("""{"targets":[]}"""))
        assertEquals(
            mapOf(BroadcastProvider.YOUTUBE to "streaming_rate_limited", BroadcastProvider.CHZZK to ""),
            parseFailedTargetCodes(
                """{"failed_targets":[{"provider":"future_provider","code":"x"},
                    {"provider":"youtube","code":"streaming_rate_limited"},{"provider":"youtube","code":"later"},
                    {"provider":"chzzk"}]}"""),
        )
    }

    /** innolive-server#505: YouTube 요청 한도는 채널 단위라 재시도·채널 변경 안내가 붙은 문구를 씁니다. */
    @Test fun youtubeRateLimitAtGoLiveUsesTheChannelLimitMessage() {
        val payload = """{"failed_targets":[{"provider":"youtube","code":"streaming_rate_limited","message":"m"}]}"""
        val codes = parseFailedTargetCodes(payload)
        assertEquals(UiText.Resource(R.string.simulcast_golive_rate_limited_youtube),
            simulcastGoLiveFailedMessage(codes.keys, codes))
        // 다른 사유와 두 플랫폼 모두 실패는 기존 문구를 유지합니다.
        assertEquals(UiText.Resource(R.string.simulcast_golive_failed_youtube),
            simulcastGoLiveFailedMessage(listOf(BroadcastProvider.YOUTUBE),
                mapOf(BroadcastProvider.YOUTUBE to "streaming_golive_failed")))
        assertEquals(UiText.Resource(R.string.simulcast_golive_failed_all),
            simulcastGoLiveFailedMessage(listOf(BroadcastProvider.YOUTUBE, BroadcastProvider.CHZZK),
                mapOf(BroadcastProvider.YOUTUBE to "streaming_rate_limited")))
    }

    /**
     * android-session-snapshot.md: 세션 스냅샷은 알 수 없는 status를 확정 상태로 보지 않는다.
     * direct-publish-v1.md: 직접 송출 중 stream.status는 항상 idle이고 방송 상태는 broadcast_phase로 판단하므로,
     * 제어 응답 파서는 status와 무관하게 단계를 따른다. 두 해석이 다른 것은 의도한 차이다.
     */
    @Test fun snapshotHoldsUnknownLiveStatusWhileControlResponsesFollowThePhase() {
        val unknown = """{"stream":{"broadcast_phase":"live","status":"future_status"}}"""
        assertNull(parseSessionSnapshot(unknown, "s").broadcastState())
        assertEquals(BroadcastState.LIVE, parseBroadcastState(unknown, BroadcastProvider.YOUTUBE))
        val directLive = """{"stream":{"broadcast_phase":"live","status":"idle"}}"""
        assertNull(parseSessionSnapshot(directLive, "s").broadcastState())
        assertEquals(BroadcastState.LIVE, parseBroadcastState(directLive, BroadcastProvider.YOUTUBE))
        assertEquals(BroadcastState.PAUSED, parseBroadcastState(
            """{"stream":{"broadcast_phase":"live","status":"paused_reconnecting"}}""", BroadcastProvider.YOUTUBE))
    }

    @Test fun unknownPhaseTargetsDoNotHideConfirmedStatesOfOtherTargets() {
        val mixed = parseSessionSnapshot("""{"targets":[{"provider":"youtube","stream":{"broadcast_phase":"future_phase"}},
            {"provider":"chzzk","stream":{"broadcast_phase":"live","status":"streaming"}}]}""", "s")
        assertEquals(BroadcastState.LIVE, mixed.broadcastState())
        assertEquals(2, mixed.visibleTargets.size)
        val allUnknown = parseSessionSnapshot(
            """{"targets":[{"provider":"youtube","stream":{"broadcast_phase":"future_phase"}}]}""", "s")
        assertNull(allUnknown.broadcastState())
    }

    @Test fun targetPartialResponsePreservesWarningsResolutionAndStaleFlag() {
        val prepared = parseSessionSnapshot("""{"session_id":"s","provider":"youtube","broadcast_resolution":"fhd",
            "warnings":[{"code":"youtube_quota_low","message":"m"}],"broadcast_remaining_seconds":300,
            "targets":[{"provider":"youtube","stream":{"status":"streaming","broadcast_phase":"live"}},
            {"provider":"chzzk","stream":{"status":"streaming","broadcast_phase":"live"}}]}""", "s")
        val stale = prepared.copy(isRemainingTimeStale = true)
        val chzzkStopped = parseSessionSnapshot("""{"status":"stopped","broadcast_phase":"idle"}""", "s", stale,
            targetProvider = "chzzk")

        assertEquals(prepared.warnings, chzzkStopped.warnings)
        assertEquals("fhd", chzzkStopped.broadcastResolution)
        assertEquals(BroadcastRemainingTime.Seconds(300), chzzkStopped.remainingTime)
        assertEquals(300L, chzzkStopped.lastRemainingSeconds)
        assertTrue(chzzkStopped.isRemainingTimeStale)
        assertEquals(prepared.targets!!.first(), chzzkStopped.targets!!.first { it.provider == "youtube" })
        assertEquals(listOf("youtube"), chzzkStopped.visibleTargets.map { it.provider })
        assertEquals(BroadcastState.LIVE, chzzkStopped.broadcastState())
    }
}
