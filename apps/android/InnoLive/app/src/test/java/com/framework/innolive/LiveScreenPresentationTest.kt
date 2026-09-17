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
            broadcastStatus = "방송 중",
        )

        assertEquals("방송 종료", presentation.broadcastButtonText)
        assertTrue(presentation.isBroadcastButtonEnabled)
        assertEquals(LiveBroadcastAction.STOP_BROADCAST, presentation.broadcastAction)
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
            broadcastStatus = "방송 준비 중",
        )

        assertEquals("라이브 시작", prepared.broadcastButtonText)
        assertEquals(LiveBroadcastAction.GO_LIVE, prepared.broadcastAction)
        assertTrue(prepared.isBroadcastPrepared)
        assertTrue(prepared.isBroadcastButtonEnabled)

        assertEquals("방송 준비 중", preparing.broadcastButtonText)
        assertEquals(LiveBroadcastAction.PREPARE_BROADCAST, preparing.broadcastAction)
        assertTrue(preparing.isBroadcastBusy)
        assertFalse(preparing.isBroadcastButtonEnabled)
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
        assertEquals("미리보기를 연결하지 못했습니다.", failed.broadcastStatusText)
        assertTrue(failed.isBroadcastStatusError)
        assertTrue(idle.isBroadcastButtonEnabled)
        assertTrue(failed.isBroadcastButtonEnabled)
    }

    @Test
    fun preparingConnectionLocksButtonUntilPreparationFinishes() {
        for (connection in WebRtcConnectionState.entries) {
            val preparing = buildLiveScreenPresentation(
                connection, BroadcastState.IDLE, "YouTube", "방송 준비 중",
                isPreparingBroadcast = true,
            )
            assertFalse(preparing.isBroadcastButtonEnabled)
            assertEquals("방송 준비 중", preparing.broadcastButtonText)
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
        )
        val stopping = buildLiveScreenPresentation(
            WebRtcConnectionState.CONNECTED,
            stoppedState,
            "YouTube",
            "YouTube 방송 종료 중",
        )
        val idle = buildLiveScreenPresentation(
            WebRtcConnectionState.CONNECTED,
            BroadcastState.IDLE,
            "YouTube",
            "YouTube 방송 준비 취소됨",
        )

        assertEquals(BroadcastState.CANCELLING_PREPARATION, cancelledState)
        assertEquals("방송 준비 취소 중…", cancelling.broadcastButtonText)
        assertFalse(cancelling.isBroadcastButtonEnabled)
        assertEquals("YouTube 방송 준비 취소 중", cancelling.broadcastStatusText)
        assertEquals(BroadcastState.STOPPING, stoppedState)
        assertEquals("방송 종료 중…", stopping.broadcastButtonText)
        assertFalse(stopping.isBroadcastButtonEnabled)
        assertEquals("방송 준비", idle.broadcastButtonText)
        assertTrue(idle.isBroadcastButtonEnabled)
    }
}
