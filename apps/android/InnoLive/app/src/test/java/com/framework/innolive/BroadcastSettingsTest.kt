package com.framework.innolive

import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.BroadcastState
import com.framework.innolive.feature.live.buildBroadcastSettingsPayload
import com.framework.innolive.feature.live.canGoLive
import com.framework.innolive.feature.live.canPrepare
import com.framework.innolive.feature.live.canStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastSettingsTest {
    @Test
    fun requestPayloadKeepsExplicitNonKidsSelection() {
        val payload = buildBroadcastSettingsPayload(
            BroadcastSettings(
                title = "  방송 제목  ",
                description = "방송 설명",
                privacy = "unlisted",
                madeForKids = false,
                categoryId = " 22 ",
            ),
        )

        assertEquals("방송 제목", payload.getString("title"))
        assertEquals("방송 설명", payload.getString("description"))
        assertEquals("unlisted", payload.getString("privacy"))
        assertFalse(payload.getBoolean("made_for_kids"))
        assertEquals("22", payload.getString("category_id"))
    }

    @Test
    fun preparedBroadcastWaitsForExplicitGoLiveAction() {
        assertTrue(BroadcastState.IDLE.canPrepare)
        assertFalse(BroadcastState.IDLE.canGoLive)

        assertFalse(BroadcastState.PREPARED.canPrepare)
        assertTrue(BroadcastState.PREPARED.canGoLive)
        assertTrue(BroadcastState.PREPARED.canStop)

        assertFalse(BroadcastState.LIVE.canGoLive)
        assertTrue(BroadcastState.LIVE.canStop)
    }
}
