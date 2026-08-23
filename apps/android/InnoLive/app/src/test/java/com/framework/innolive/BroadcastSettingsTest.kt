package com.framework.innolive

import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.buildBroadcastSettingsPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
