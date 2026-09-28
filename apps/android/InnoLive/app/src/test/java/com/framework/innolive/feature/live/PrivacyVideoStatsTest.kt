package com.framework.innolive.feature.live

import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyVideoStatsTest {
    @Test fun computesPerIntervalVideoRatesWithoutUsingLifetimeAverages() {
        val first = PrivacyVideoStats.from(mapOf(
            "framesEncoded" to 100L, "framesSent" to 90L,
            "packetsSent" to 200L, "bytesSent" to 1_000_000L,
            "totalEncodeTime" to 1.0,
        ), 1_000_000_000L)
        val second = PrivacyVideoStats.from(mapOf(
            "framesEncoded" to 200L, "framesSent" to 190L,
            "packetsSent" to 400L, "bytesSent" to 2_000_000L,
            "totalEncodeTime" to 3.0,
        ), 6_000_000_000L)
        val report = second.interval(first)
        assertTrue(report, report.contains("encoded_fps=20.0"))
        assertTrue(report, report.contains("sent_fps=20.0"))
        assertTrue(report, report.contains("mean_encode_ms=20.0"))
        assertTrue(report, report.contains("bitrate_kbps=1600.0"))
    }

    @Test fun counterResetDoesNotReportNegativeFps() {
        val previous = PrivacyVideoStats.from(mapOf("framesEncoded" to 100L), 1_000_000_000L)
        val restarted = PrivacyVideoStats.from(mapOf("framesEncoded" to 3L), 6_000_000_000L)
        assertTrue(restarted.interval(previous).contains("encoded_fps=null"))
    }
}
