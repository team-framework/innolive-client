package com.framework.innolive

import com.framework.innolive.feature.live.BroadcastState
import com.framework.innolive.feature.live.LiveBroadcastAction
import com.framework.innolive.feature.live.WebRtcConnectionState
import com.framework.innolive.feature.live.buildLiveScreenPresentation
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
            connectionState = WebRtcConnectionState.CONNECTED,
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
        assertEquals("비식별화 연결에 실패하였습니다.", failed.broadcastStatusText)
        assertTrue(failed.isBroadcastStatusError)
        assertFalse(failed.isBroadcastButtonEnabled)
    }
}
