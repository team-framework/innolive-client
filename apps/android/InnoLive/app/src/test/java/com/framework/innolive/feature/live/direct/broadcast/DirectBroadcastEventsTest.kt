package com.framework.innolive.feature.live.direct.broadcast

import com.framework.innolive.R
import com.framework.innolive.feature.live.BroadcastEvent
import com.framework.innolive.feature.live.BroadcastFailure
import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.ServerApiException
import com.framework.innolive.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectBroadcastEventsTest {
    private fun apiFailureCode(event: BroadcastEvent): String? = (event as? BroadcastEvent.ApiFailure)?.guidance?.code

    @Test fun directOnlyServerCodesUseDirectGuidance() {
        for (code in listOf("on_device_processing", "heartbeat_not_applicable", "broadcast_stopped",
            "channel_in_use_by_other_account", "youtube_channel_required")) {
            for (provider in BroadcastProvider.entries) {
                assertEquals("$code/$provider", code, apiFailureCode(directServerEvent(ServerApiException(code), provider)))
            }
        }
    }

    @Test fun chzzkFieldErrorKeepsFieldSpecificMessage() {
        val event = directServerEvent(ServerApiException("bad_request", field = "tags[0]"), BroadcastProvider.CHZZK)
        assertTrue("$event", event is BroadcastEvent.Message)
    }

    @Test fun unknownCodeFallsBackToRequestFailure() {
        assertEquals(
            BroadcastEvent.Failure(BroadcastFailure.REQUEST),
            directServerEvent(ServerApiException("future_code"), BroadcastProvider.YOUTUBE),
        )
    }

    @Test fun goLiveTargetFailureUsesDirectGuidance() {
        assertEquals("broadcast_stopped", apiFailureCode(directTargetFailureEvent("broadcast_stopped")))
        assertEquals(BroadcastEvent.Failure(BroadcastFailure.REQUEST), directTargetFailureEvent(null))
    }

    @Test fun appSituationsUseDirectNotices() {
        assertEquals(
            BroadcastEvent.Message(UiText.Resource(R.string.direct_notice_prepared_expired)),
            DirectEvent.PREPARED_EXPIRED.toBroadcastEvent(),
        )
        assertEquals(
            DirectBroadcastNotice.ConnectionLost(BroadcastProvider.CHZZK).toBroadcastEvent(),
            directConnectionLostEvent(BroadcastProvider.CHZZK),
        )
        assertEquals(
            DirectBroadcastNotice.TargetStoppedByServer(BroadcastProvider.YOUTUBE).toBroadcastEvent(),
            directTargetsStoppedEvent(listOf(BroadcastProvider.YOUTUBE)),
        )
        assertEquals(
            DirectEvent.SERVER_STOPPED.toBroadcastEvent(),
            directTargetsStoppedEvent(listOf(BroadcastProvider.YOUTUBE, BroadcastProvider.CHZZK)),
        )
    }
}
