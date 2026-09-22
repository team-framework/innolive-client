package com.framework.innolive

import com.framework.innolive.feature.live.BroadcastState
import com.framework.innolive.feature.live.LiveBroadcastAction
import com.framework.innolive.feature.live.WebRtcConnectionState
import com.framework.innolive.feature.live.buildLiveScreenPresentation
import com.framework.innolive.feature.live.stoppingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveScreenPresentationTest {
    @Test
    fun liveBroadcastStateControlsButtonAndAction() {
        val presentation = buildLiveScreenPresentation(
            connectionState = WebRtcConnectionState.CONNECTED,
            broadcastState = BroadcastState.LIVE,
            selectedPlatform = "YouTube",
            broadcastStatus = "YouTube 방송 중",
            isBroadcastStatusDefault = true,
        )

        assertEquals(R.string.broadcast_state_live, presentation.broadcastButtonTextRes)
        assertTrue(presentation.isBroadcastButtonEnabled)
        assertEquals(LiveBroadcastAction.SHOW_BROADCAST_ACTIONS, presentation.broadcastAction)
        assertEquals("", presentation.broadcastStatusText)
    }

    @Test
    fun preparedAndBusyStatesKeepTheirExistingPresentation() {
        val prepared = buildLiveScreenPresentation(
            connectionState = WebRtcConnectionState.CONNECTED,
            broadcastState = BroadcastState.PREPARED,
            selectedPlatform = null,
            broadcastStatus = "방송 준비 완료",
        )
        val preparing = buildLiveScreenPresentation(
            connectionState = WebRtcConnectionState.CONNECTED,
            broadcastState = BroadcastState.PREPARING,
            selectedPlatform = "YouTube",
            broadcastStatus = "YouTube 방송 준비 중",
            isBroadcastStatusDefault = true,
        )

        assertEquals(R.string.broadcast_state_prepared, prepared.broadcastButtonTextRes)
        assertEquals(LiveBroadcastAction.SHOW_BROADCAST_ACTIONS, prepared.broadcastAction)
        assertTrue(prepared.isBroadcastPrepared)
        assertTrue(prepared.isBroadcastButtonEnabled)
        assertEquals("방송 준비 완료", prepared.broadcastStatusText)

        assertEquals(R.string.broadcast_state_preparing, preparing.broadcastButtonTextRes)
        assertEquals(LiveBroadcastAction.PREPARE_BROADCAST, preparing.broadcastAction)
        assertTrue(preparing.isBroadcastBusy)
        assertFalse(preparing.isBroadcastButtonEnabled)
        assertEquals("", preparing.broadcastStatusText)
    }

    @Test
    fun pausedBroadcastShowsInformationButtonAndCanBeStopped() {
        val paused = buildLiveScreenPresentation(
            WebRtcConnectionState.CONNECTED,
            BroadcastState.PAUSED,
            "YouTube",
            "YouTube 송출 일시 중지됨",
        )

        assertTrue(paused.isBroadcastLive)
        assertTrue(paused.isBroadcastPaused)
        assertEquals(R.string.broadcast_state_paused, paused.broadcastButtonTextRes)
        assertEquals(LiveBroadcastAction.SHOW_BROADCAST_ACTIONS, paused.broadcastAction)
        assertEquals("YouTube 송출 일시 중지됨", paused.broadcastStatusText)
        assertEquals(BroadcastState.STOPPING, BroadcastState.PAUSED.stoppingState())
    }

    @Test
    fun disconnectedStateOpensPlatformOrShowsConnectionFailure() {
        val idle = buildLiveScreenPresentation(
            connectionState = WebRtcConnectionState.IDLE,
            broadcastState = BroadcastState.IDLE,
            selectedPlatform = null,
            broadcastStatus = "방송 대기",
        )
        val failed = buildLiveScreenPresentation(
            connectionState = WebRtcConnectionState.FAILED,
            broadcastState = BroadcastState.FAILED,
            selectedPlatform = null,
            broadcastStatus = "방송 준비에 실패했습니다.",
        )

        assertEquals(LiveBroadcastAction.SELECT_PLATFORM, idle.broadcastAction)
        assertEquals("", idle.broadcastStatusText)
        assertEquals("", failed.broadcastStatusText)
        assertTrue(failed.isBroadcastStatusError)
        assertTrue(idle.isBroadcastButtonEnabled)
        assertTrue(failed.isBroadcastButtonEnabled)

        val connectedBroadcastFailure = buildLiveScreenPresentation(
            connectionState = WebRtcConnectionState.CONNECTED,
            broadcastState = BroadcastState.FAILED,
            selectedPlatform = "YouTube",
            broadcastStatus = "방송 준비에 실패했습니다.",
        )
        assertEquals("방송 준비에 실패했습니다.", connectedBroadcastFailure.broadcastStatusText)
    }

    @Test
    fun failedConnectionNeverAddsASecondBroadcastMessage() {
        for (broadcast in BroadcastState.entries) {
            val presentation = buildLiveScreenPresentation(
                connectionState = WebRtcConnectionState.FAILED,
                broadcastState = broadcast,
                selectedPlatform = "YouTube",
                broadcastStatus = "연결하지 못해 방송을 준비하지 못했습니다. 다시 시도해 주세요.",
            )
            assertEquals("Duplicate failure for $broadcast", "", presentation.broadcastStatusText)
        }
    }

    @Test
    fun preparingConnectionLocksButtonUntilPreparationFinishes() {
        for (connection in WebRtcConnectionState.entries) {
            val preparing = buildLiveScreenPresentation(
                connection, BroadcastState.IDLE, "YouTube", "방송 준비 중",
                isPreparingBroadcast = true,
            )
            assertFalse(preparing.isBroadcastButtonEnabled)
            assertEquals(R.string.broadcast_state_preparing, preparing.broadcastButtonTextRes)
        }
        val connecting = buildLiveScreenPresentation(
            WebRtcConnectionState.CONNECTING, BroadcastState.IDLE, "YouTube", "",
        )
        assertFalse(connecting.isBroadcastButtonEnabled)
    }

    @Test
    fun cancellingPreparationAndStoppingLiveShowDistinctProgressAndReturnToPrepare() {
        val cancelledState = BroadcastState.PREPARED.stoppingState()
        val stoppedState = BroadcastState.LIVE.stoppingState()
        val cancelling = buildLiveScreenPresentation(
            WebRtcConnectionState.CONNECTED,
            cancelledState,
            "YouTube",
            "YouTube 방송 준비 취소 중",
            isBroadcastStatusDefault = true,
        )
        val stopping = buildLiveScreenPresentation(
            WebRtcConnectionState.CONNECTED,
            stoppedState,
            "YouTube",
            "YouTube 방송 종료 중",
            isBroadcastStatusDefault = true,
        )
        val idle = buildLiveScreenPresentation(
            WebRtcConnectionState.CONNECTED,
            BroadcastState.IDLE,
            "YouTube",
            "YouTube 방송 준비 취소됨",
        )

        assertEquals(BroadcastState.CANCELLING_PREPARATION, cancelledState)
        assertEquals(R.string.broadcast_state_cancelling, cancelling.broadcastButtonTextRes)
        assertFalse(cancelling.isBroadcastButtonEnabled)
        assertEquals("", cancelling.broadcastStatusText)
        assertEquals(BroadcastState.STOPPING, stoppedState)
        assertEquals(R.string.broadcast_state_stopping, stopping.broadcastButtonTextRes)
        assertFalse(stopping.isBroadcastButtonEnabled)
        assertEquals(R.string.action_prepare_broadcast, idle.broadcastButtonTextRes)
        assertTrue(idle.isBroadcastButtonEnabled)
    }

    @Test
    fun normalStateErrorsAndRecoveryGuidanceRemainVisibleWhenTheyDoNotRepeatTheButton() {
        val pauseFailed = buildLiveScreenPresentation(
            WebRtcConnectionState.CONNECTED,
            BroadcastState.LIVE,
            "YouTube",
            "YouTube 송출을 일시 중지하지 못했습니다.",
        )
        val resumeFailed = buildLiveScreenPresentation(
            WebRtcConnectionState.CONNECTED,
            BroadcastState.PAUSED,
            "YouTube",
            "YouTube 송출을 재개하지 못했습니다.",
        )
        val notReady = buildLiveScreenPresentation(
            WebRtcConnectionState.CONNECTED,
            BroadcastState.PREPARED,
            "YouTube",
            "YouTube가 아직 영상을 받을 준비가 되지 않았습니다. 잠시 후 다시 시도해 주세요.",
        )
        val goingLive = buildLiveScreenPresentation(
            WebRtcConnectionState.CONNECTED,
            BroadcastState.GOING_LIVE,
            "YouTube",
            "YouTube 라이브 전환 중",
        )

        assertEquals("YouTube 송출을 일시 중지하지 못했습니다.", pauseFailed.broadcastStatusText)
        assertEquals("YouTube 송출을 재개하지 못했습니다.", resumeFailed.broadcastStatusText)
        assertEquals("YouTube가 아직 영상을 받을 준비가 되지 않았습니다. 잠시 후 다시 시도해 주세요.", notReady.broadcastStatusText)
        assertEquals("YouTube 라이브 전환 중", goingLive.broadcastStatusText)
    }

    @Test
    fun onlyKnownButtonDuplicatesAreHidden() {
        val duplicateStatuses = listOf(
            BroadcastState.PAUSING to "YouTube 송출 일시 중지 중",
            BroadcastState.RESUMING to "YouTube 송출 재개 중",
        )

        duplicateStatuses.forEach { (state, status) ->
            val presentation = buildLiveScreenPresentation(
                WebRtcConnectionState.CONNECTED,
                state,
                "YouTube",
                status,
                isBroadcastStatusDefault = true,
            )
            assertEquals("Duplicate status for $state", "", presentation.broadcastStatusText)
        }
    }
}
