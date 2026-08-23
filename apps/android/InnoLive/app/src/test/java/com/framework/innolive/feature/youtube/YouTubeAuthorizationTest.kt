package com.framework.innolive.feature.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeAuthorizationTest {
    @Test
    fun accountLookupSelectsOnlyYouTubeProvider() {
        val youtube = StreamingAccount(
            provider = "YouTube",
            channelId = "channel-id",
            channelTitle = "InnoLive",
            reconnectRequired = false,
        )

        assertEquals(
            youtube,
            findYouTubeAccount(
                listOf(
                    StreamingAccount("chzzk", "other", "Other", false),
                    youtube,
                ),
            ),
        )
        assertNull(findYouTubeAccount(listOf(StreamingAccount("soop", "other", "Other", false))))
    }
}
