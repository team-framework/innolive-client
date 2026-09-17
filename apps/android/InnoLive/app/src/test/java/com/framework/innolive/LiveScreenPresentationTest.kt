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

        assertEquals("방송 중", presentation.broadcastButtonText)
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
            broadcastStatus = "방송 준비 중",
        )

        assertEquals("방송 준비 완료", prepared.broadcastButtonText)
        assertEquals(LiveBroadcastAction.SHOW_BROADCAST_ACTIONS, prepared.broadcastAction)
        assertTrue(prepared.isBroadcastPrepared)
        assertTrue(prepared.isBroadcastButtonEnabled)
        assertEquals("", prepared.broadcastStatusText)

        assertEquals("방송 준비 중", preparing.broadcastButtonText)
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
        assertEquals("방송 일시 중지", paused.broadcastButtonText)
        assertEquals(LiveBroadcastAction.SHOW_BROADCAST_ACTIONS, paused.broadcastAction)
        assertEquals("", paused.broadcastStatusText)
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
        assertEquals("방송 준비 취소 중", cancelling.broadcastButtonText)
        assertFalse(cancelling.isBroadcastButtonEnabled)
        assertEquals("", cancelling.broadcastStatusText)
        assertEquals(BroadcastState.STOPPING, stoppedState)
        assertEquals("방송 종료 중", stopping.broadcastButtonText)
        assertFalse(stopping.isBroadcastButtonEnabled)
        assertEquals("방송 준비", idle.broadcastButtonText)
        assertTrue(idle.isBroadcastButtonEnabled)
    }
}
