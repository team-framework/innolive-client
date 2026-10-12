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

    @Test fun acceptsPlatformHostsAndPorts() {
        assertTrue(IngestUrl.parse("rtmps://a.rtmp.youtube.com:443/live2/$secret").isSecure)
        assertFalse(IngestUrl.parse("rtmp://global-rtmp.lip2.navercorp.com:8080/relay/$secret").isSecure)
        assertFalse(IngestUrl.parse("rtmp://127.0.0.1:1935/live/$secret").isSecure)
        assertFalse(IngestUrl.parse("rtmp://[::1]:1935/live/$secret").isSecure)
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
            "rtmp://:8080/relay/$secret", "rtmp://host:not-a-port/relay/$secret", "rtmp://host:/relay/$secret",
            "rtmp://host:0/relay/$secret", "rtmp://host:65536/relay/$secret", "rtmp://user@host/relay/$secret",
            "rtmp://ho_st/relay/$secret", "rtmp://host?x=1/relay/$secret",
        )
        invalid.forEachIndexed { index, value ->
            val error = runCatching { IngestUrl.parse(value) }.exceptionOrNull()
            assertTrue("accepted invalid[$index]", error is IllegalArgumentException)
            assertFalse(error?.message.orEmpty().contains(secret))
            assertEquals(null, error?.cause)
        }
    }
}
