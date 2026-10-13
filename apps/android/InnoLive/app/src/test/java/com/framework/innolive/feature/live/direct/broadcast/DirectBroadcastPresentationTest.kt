package com.framework.innolive.feature.live.direct.broadcast

import com.framework.innolive.R
import com.framework.innolive.feature.live.BroadcastEvent
import com.framework.innolive.feature.live.BroadcastProvider.CHZZK
import com.framework.innolive.feature.live.BroadcastProvider.YOUTUBE
import com.framework.innolive.feature.live.BroadcastState
import com.framework.innolive.feature.live.ServerApiException
import com.framework.innolive.feature.live.SessionTarget
import com.framework.innolive.feature.live.broadcastUserMessage
import com.framework.innolive.feature.live.chzzkBroadcastErrorMessage
import com.framework.innolive.feature.live.direct.DirectPublishResolution.FHD
import com.framework.innolive.feature.live.direct.DirectPublishResolution.HD_720
import com.framework.innolive.feature.live.status.BroadcastTargetTone
import com.framework.innolive.feature.settings.ModeAvailability
import com.framework.innolive.feature.settings.PlanMode
import com.framework.innolive.feature.settings.PlanUsage
import com.framework.innolive.ui.text.ServerErrorAction
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.ui.text.directPublishErrorGuidance
import com.framework.innolive.ui.text.serverErrorGuidance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectBroadcastPresentationTest {
    private fun plan(vararg allowed: PlanMode, seconds: Long? = 3_600L) = PlanUsage(
        plan = "fixture",
        allowedModes = allowed.map { it.value },
        monthlySeconds = 0,
        maxBroadcastSeconds = 0,
        usedSeconds = 0,
        remainingSeconds = null,
        modes = PlanMode.entries.map { ModeAvailability(it.value, it in allowed, seconds, 1) },
    )

    private val connected: (com.framework.innolive.feature.live.BroadcastProvider) -> Boolean = { true }

    @Test fun defaultQualityIsHighestAllowedAndFallsBackTo720pWithoutPlan() {
        assertEquals(HD_720, defaultDirectResolution(null, 1))
        assertEquals(FHD, defaultDirectResolution(plan(PlanMode.HD_SINGLE, PlanMode.FHD_SINGLE), 1))
        assertEquals(HD_720, defaultDirectResolution(plan(PlanMode.HD_SINGLE, PlanMode.FHD_SINGLE), 2))
        assertEquals(HD_720, defaultDirectResolution(plan(PlanMode.HD_SINGLE), 1))
        assertEquals(HD_720, defaultDirectResolution(plan(), 1))
    }

    @Test fun reconcileFollowsPlanUntilUserChoosesAndDowngradesDisallowedChoice() {
        val premium = plan(PlanMode.HD_SINGLE, PlanMode.FHD_SINGLE)
        assertEquals(FHD, reconcileDirectBroadcastPlan(DirectBroadcastPlan(), premium, userSelected = false).resolution)
        // 사용자가 720p를 골랐으면 그대로 둡니다.
        assertEquals(HD_720, reconcileDirectBroadcastPlan(DirectBroadcastPlan(), premium, userSelected = true).resolution)
        // 요금제가 바뀌어 FHD를 쓸 수 없으면 낮춥니다.
        val chosenFhd = DirectBroadcastPlan(FHD, setOf(YOUTUBE))
        assertEquals(HD_720, reconcileDirectBroadcastPlan(chosenFhd, plan(PlanMode.HD_SINGLE), true).resolution)
        // 요금제를 모르면 사용자 선택을 막지 않습니다.
        assertEquals(FHD, reconcileDirectBroadcastPlan(chosenFhd, null, true).resolution)
    }

    @Test fun disallowedModesAreBlockedWithPlanGuidance() {
        val state = directBroadcastModeState(DirectBroadcastPlan(), plan(PlanMode.HD_SINGLE), connected, locked = false)
        assertEquals(DirectModeBlock.PLAN, state.resolutions.single { it.resolution == FHD }.block)
        assertEquals(DirectModeBlock.PLAN, state.providers.single { it.provider == CHZZK }.block)
        assertTrue(state.showsPlanGuidance)
        assertNull(state.selectResolution(FHD))
        assertNull(state.toggleProvider(CHZZK))
        assertNull(state.warning)
    }

    @Test fun unknownPlanDoesNotBlockSelection() {
        val state = directBroadcastModeState(DirectBroadcastPlan(), null, connected, locked = false)
        assertTrue(state.resolutions.all { it.enabled })
        assertEquals(DirectBroadcastPlan(HD_720, setOf(YOUTUBE, CHZZK)), state.toggleProvider(CHZZK))
        assertFalse(state.showsPlanGuidance)
    }

    @Test fun lastTargetCannotBeRemovedAndAccountIsRequiredToAdd() {
        val state = directBroadcastModeState(
            DirectBroadcastPlan(), plan(*PlanMode.entries.toTypedArray()), { it == YOUTUBE }, locked = false,
        )
        assertEquals(DirectModeBlock.LAST_TARGET, state.providers.single { it.provider == YOUTUBE }.block)
        assertEquals(DirectModeBlock.ACCOUNT, state.providers.single { it.provider == CHZZK }.block)
        assertNull(state.toggleProvider(YOUTUBE))

        val both = directBroadcastModeState(
            DirectBroadcastPlan(HD_720, setOf(YOUTUBE, CHZZK)), plan(*PlanMode.entries.toTypedArray()), connected, false,
        )
        assertEquals(DirectBroadcastPlan(HD_720, setOf(CHZZK)), both.toggleProvider(YOUTUBE))
    }

    @Test fun preparingAndLiveLockEveryOption() {
        assertFalse(isDirectBroadcastModeLocked(BroadcastState.IDLE, isPreparing = false))
        assertFalse(isDirectBroadcastModeLocked(BroadcastState.FAILED, isPreparing = false))
        assertTrue(isDirectBroadcastModeLocked(BroadcastState.IDLE, isPreparing = true))
        for (state in listOf(BroadcastState.PREPARED, BroadcastState.LIVE, BroadcastState.PAUSED, BroadcastState.STOPPING)) {
            assertTrue(isDirectBroadcastModeLocked(state, isPreparing = false))
        }
        val locked = directBroadcastModeState(DirectBroadcastPlan(), null, connected, locked = true)
        assertTrue(locked.resolutions.all { it.block == DirectModeBlock.LOCKED })
        assertTrue(locked.providers.all { it.block == DirectModeBlock.LOCKED })
        assertNull(locked.selectResolution(FHD))
    }

    @Test fun selectedModeWarnsWhenNotInPlanOrExhausted() {
        val fhd = DirectBroadcastPlan(FHD, setOf(YOUTUBE))
        assertEquals(
            DirectModeWarning.NOT_IN_PLAN,
            directBroadcastModeState(fhd, plan(PlanMode.HD_SINGLE), connected, false).warning,
        )
        assertEquals(
            DirectModeWarning.TIME_EXHAUSTED,
            directBroadcastModeState(fhd, plan(PlanMode.FHD_SINGLE, seconds = 0), connected, false).warning,
        )
        assertNull(directBroadcastModeState(fhd, plan(PlanMode.FHD_SINGLE, seconds = null), connected, false).warning)
    }

    @Test fun selectedPlatformReplacesOnlySingleTarget() {
        assertEquals(setOf(CHZZK), DirectBroadcastPlan().withSelectedPlatform(CHZZK).providers)
        val both = DirectBroadcastPlan(HD_720, setOf(YOUTUBE, CHZZK))
        assertEquals(both, both.withSelectedPlatform(CHZZK))
    }

    private fun target(provider: String, phase: String, status: String? = "idle") =
        SessionTarget(provider, status, phase, null, null)

    @Test fun serverTargetsAreUnchangedWithoutLocalState() {
        val rows = directTargetRows(listOf(target("youtube", "live", "streaming")), DirectBroadcastUiState())
        assertEquals(listOf(BroadcastTargetRow("youtube", R.string.live_status_target_streaming, BroadcastTargetTone.LIVE)), rows)
    }

    @Test fun liveDirectTargetsUseLocalRtmpStatusInsteadOfIdleServerStatus() {
        val server = listOf(target("youtube", "live"), target("chzzk", "live"))
        fun rows(vararg local: Pair<com.framework.innolive.feature.live.BroadcastProvider, LocalTargetStatus>,
                 slate: Set<SlateReason> = emptySet()) =
            directTargetRows(server, DirectBroadcastUiState(targets = mapOf(*local), slate = slate))

        val publishing = rows(YOUTUBE to LocalTargetStatus.PUBLISHING, CHZZK to LocalTargetStatus.RECONNECTING)
        assertEquals(R.string.live_status_target_streaming, publishing[0].labelRes)
        assertEquals(BroadcastTargetTone.LIVE, publishing[0].tone)
        assertEquals(R.string.live_status_target_reconnecting, publishing[1].labelRes)
        assertEquals(BroadcastTargetTone.ATTENTION, publishing[1].tone)

        val paused = rows(YOUTUBE to LocalTargetStatus.PUBLISHING, slate = setOf(SlateReason.USER_PAUSE))
        assertEquals(R.string.live_status_target_slate, paused[0].labelRes)
        // 로컬 연결이 없는 라이브 대상은 확인 중으로 둡니다.
        assertEquals(R.string.live_status_target_checking, paused[1].labelRes)

        val failed = rows(YOUTUBE to LocalTargetStatus.FAILED, CHZZK to LocalTargetStatus.STOPPED)
        assertEquals(R.string.live_status_target_failed, failed[0].labelRes)
        assertEquals(R.string.live_status_target_stopped, failed[1].labelRes)
    }

    @Test fun serverPhaseLeadsWhilePreparingAndGoingLive() {
        val local = DirectBroadcastUiState(targets = mapOf(YOUTUBE to LocalTargetStatus.PUBLISHING))
        assertEquals(R.string.live_status_target_prepared, directTargetRows(listOf(target("youtube", "prepared")), local)[0].labelRes)
        assertEquals(R.string.live_status_target_going_live, directTargetRows(listOf(target("youtube", "going_live")), local)[0].labelRes)
        val reconnecting = DirectBroadcastUiState(targets = mapOf(YOUTUBE to LocalTargetStatus.RECONNECTING))
        assertEquals(
            R.string.live_status_target_reconnecting,
            directTargetRows(listOf(target("youtube", "prepared")), reconnecting)[0].labelRes,
        )
    }

    @Test fun localFailureStaysVisibleAfterServerHidesTarget() {
        val rows = directTargetRows(
            listOf(target("youtube", "live")),
            DirectBroadcastUiState(targets = mapOf(YOUTUBE to LocalTargetStatus.PUBLISHING, CHZZK to LocalTargetStatus.FAILED)),
        )
        assertEquals(listOf("youtube", "chzzk"), rows.map { it.provider })
        assertEquals(R.string.live_status_target_failed, rows[1].labelRes)
    }

    @Test fun warningsCoverSlateMicrophoneThermalAndPreparedDeadline() {
        val warnings = directStatusWarnings(
            DirectBroadcastUiState(
                microphone = MicrophoneStatus.UNAVAILABLE,
                slate = setOf(SlateReason.BACKGROUND, SlateReason.USER_PAUSE),
                thermalWarning = true,
                preparedDeadlineElapsedMillis = 10_000L + 125_000L,
            ),
            nowElapsedMillis = 10_000L,
        )
        assertEquals(
            listOf(
                UiText.Resource(R.string.direct_warning_slate_paused),
                UiText.Resource(R.string.direct_warning_microphone_unavailable),
                UiText.Resource(R.string.direct_warning_thermal),
                UiText.Resource(R.string.direct_warning_prepared_deadline, listOf("2:05")),
            ),
            warnings.map { it.text },
        )
        assertFalse(warnings.last().urgent)
        assertTrue(directStatusWarnings(DirectBroadcastUiState(preparedDeadlineElapsedMillis = 60_000L), 1L).single().urgent)
        assertEquals(
            UiText.Resource(R.string.direct_warning_prepared_expired),
            directStatusWarnings(DirectBroadcastUiState(preparedDeadlineElapsedMillis = 5L), 5L).single().text,
        )
        assertTrue(directStatusWarnings(DirectBroadcastUiState(), 0L).isEmpty())
        assertEquals(10 * 60 * 1_000L, DIRECT_PREPARED_LIMIT_MILLIS)
    }

    @Test fun appNoticesBecomeLocalizedBroadcastMessages() {
        assertEquals(
            UiText.Resource(R.string.direct_notice_target_stopped_by_server, listOf("YouTube")),
            DirectBroadcastNotice.TargetStoppedByServer(YOUTUBE).text(),
        )
        assertEquals(
            UiText.Resource(R.string.direct_notice_connection_lost, listOf("CHZZK")),
            DirectBroadcastNotice.ConnectionLost(CHZZK).text(),
        )
        val event = DirectBroadcastNotice.PreparedExpired.toBroadcastEvent()
        assertEquals(BroadcastEvent.Message(UiText.Resource(R.string.direct_notice_prepared_expired)), event)
        val feedback = broadcastUserMessage(BroadcastState.IDLE, event)
        assertEquals(UiText.Resource(R.string.direct_notice_prepared_expired), feedback.text)
        assertFalse(feedback.isStateDescription)
        assertEquals(
            UiText.Resource(R.string.direct_notice_microphone_unavailable),
            DirectBroadcastNotice.MicrophoneUnavailable.text(),
        )
    }

    @Test fun directOnlyErrorCodesAreGuidedWithoutChangingServerRelayGuidance() {
        val expected = mapOf(
            "on_device_processing" to R.string.error_on_device_processing,
            "heartbeat_not_applicable" to R.string.error_heartbeat_not_applicable,
            "broadcast_stopped" to R.string.error_broadcast_stopped,
            "channel_in_use_by_other_account" to R.string.error_channel_in_use_by_other_account,
            "youtube_channel_required" to R.string.error_youtube_channel_required,
        )
        expected.forEach { (code, message) ->
            assertEquals(UiText.Resource(message), directPublishErrorGuidance(code)!!.message)
            assertNull("$code must keep the server relay fallback", serverErrorGuidance(code))
        }
        assertEquals(ServerErrorAction.RECONNECT, directPublishErrorGuidance("youtube_channel_required")!!.action)
        assertEquals(
            UiText.Resource(R.string.error_broadcast_stopped),
            ServerApiException("broadcast_stopped", statusCode = 409).directGuidance()!!.message,
        )
        // 공통 코드는 서버 중계와 같은 안내를 씁니다.
        for ((code, message) in mapOf(
            "streaming_rate_limited" to R.string.error_streaming_rate_limit,
            "streaming_quota_exceeded" to R.string.error_streaming_quota,
            "capacity_exceeded" to R.string.error_stream_capacity,
        )) {
            assertEquals(serverErrorGuidance(code), directPublishErrorGuidance(code))
            assertEquals(UiText.Resource(message), directPublishErrorGuidance(code)!!.message)
        }
        assertEquals(ServerErrorAction.LOGIN, directPublishErrorGuidance("on_device_processing", statusCode = 401)!!.action)
        assertNull(directPublishErrorGuidance(null))
        assertNull(directPublishErrorGuidance("new_server_error"))
    }

    @Test fun chzzkErrorsUseLocalizedResources() {
        assertEquals(UiText.Resource(R.string.chzzk_error_title), chzzkBroadcastErrorMessage("bad_request", "title"))
        assertEquals(UiText.Resource(R.string.chzzk_error_tags), chzzkBroadcastErrorMessage("bad_request", "tags[2]"))
        assertEquals(UiText.Resource(R.string.chzzk_error_category), chzzkBroadcastErrorMessage("bad_request", "category_id"))
        assertEquals(UiText.Resource(R.string.chzzk_error_settings), chzzkBroadcastErrorMessage("bad_request", null))
        assertEquals(UiText.Resource(R.string.chzzk_error_not_ready), chzzkBroadcastErrorMessage("broadcast_not_prepared", null))
        assertEquals(UiText.Resource(R.string.chzzk_error_request), chzzkBroadcastErrorMessage(null, null))
    }
}
