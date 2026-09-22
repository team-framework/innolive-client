package com.framework.innolive.feature.live

import com.framework.innolive.R
import com.framework.innolive.ui.text.UiText
import org.junit.Assert.*
import org.junit.Test

class LiveUserMessageTest {
    @Test fun idleIsHiddenAndProgressDoesNotExposeTechnicalDetails() {
        assertNull(connectionUserMessage(WebRtcConnectionState.IDLE))
        assertEquals(
            UiText.Resource(R.string.preview_connecting),
            connectionUserMessage(WebRtcConnectionState.CONNECTING),
        )
        assertEquals(
            UiText.Resource(R.string.preview_connected),
            connectionUserMessage(WebRtcConnectionState.CONNECTED),
        )
    }

    @Test fun genericTypedFailuresNeverProduceDynamicText() {
        assertEquals(
            UiText.Resource(R.string.error_preview_connect),
            connectionUserMessage(WebRtcConnectionState.FAILED, ConnectionFailure.GENERIC),
        )
        val broadcastMessage = broadcastUserMessage(BroadcastState.FAILED, BroadcastFailure.REQUEST)
        assertEquals(UiText.Resource(R.string.error_request_failed), broadcastMessage.text)
        assertFalse(broadcastMessage.text is UiText.Dynamic)
    }

    @Test fun actionableFailuresKeepUserGuidance() {
        assertEquals(
            UiText.Resource(R.string.error_preview_timeout),
            connectionUserMessage(WebRtcConnectionState.FAILED, ConnectionFailure.TIMEOUT),
        )
        assertEquals(
            UiText.Resource(R.string.error_microphone_unavailable),
            connectionUserMessage(
                WebRtcConnectionState.FAILED,
                ConnectionFailure.MICROPHONE_UNAVAILABLE,
            ),
        )
        assertEquals(
            UiText.Resource(R.string.error_youtube_reconnect),
            broadcastUserMessage(BroadcastState.FAILED, BroadcastFailure.YOUTUBE_RECONNECT).text,
        )
        assertEquals(
            UiText.Resource(R.string.error_existing_broadcast),
            connectionUserMessage(WebRtcConnectionState.FAILED, ConnectionFailure.EXISTING_BROADCAST),
        )
    }

    @Test fun normalBroadcastStateIsIdentifiedWithoutComparingTheRenderedLocale() {
        val feedback = broadcastUserMessage(BroadcastState.PREPARING)

        assertEquals(UiText.Resource(R.string.broadcast_state_preparing), feedback.text)
        assertTrue(feedback.isStateDescription)
    }
}
