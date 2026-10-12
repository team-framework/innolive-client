package com.framework.innolive.feature.live.direct

import com.framework.innolive.feature.live.BroadcastProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IngestUrlTest {
    private val secret = "live-secret-key-1234"

    @Test fun stringConversionNeverExposesStreamKey() {
        val url = IngestUrl.parse("rtmps://a.rtmp.youtube.com/live2/$secret")
        assertEquals("IngestUrl(<redacted>)", url.toString())
        assertFalse("$url".contains(secret))
        assertFalse(DirectIngest(BroadcastProvider.CHZZK, url).toString().contains(secret))
        assertFalse(
            DirectPrepareResult(com.framework.innolive.feature.live.BroadcastState.PREPARED, url).toString()
                .contains(secret),
        )
        assertEquals("rtmps://a.rtmp.youtube.com/live2/$secret", url.reveal())
    }

    @Test fun acceptsRtmpAndRtmps() {
        assertTrue(IngestUrl.parse(" rtmps://host/app/$secret ").isSecure)
        assertFalse(IngestUrl.parse("rtmp://host:8080/relay/$secret").isSecure)
        assertEquals(IngestUrl.parse("rtmp://h/a/k"), IngestUrl.parse("rtmp://h/a/k"))
    }

    @Test fun rejectsInvalidValuesWithoutEchoingThem() {
        val invalid = listOf(
            null, 42, "", "   ", "https://host/app/$secret", "rtmp://", "rtmp:///app/$secret",
            "rtmp://host", "rtmp://host/", "rtmp://host/app/", "rtmp://host/app/$secret extra",
        )
        for (value in invalid) {
            val error = runCatching { IngestUrl.parse(value) }.exceptionOrNull()
            assertTrue("accepted $value", error is IllegalArgumentException)
            assertFalse(error?.message.orEmpty().contains(secret))
            assertEquals(null, error?.cause)
        }
    }
}
