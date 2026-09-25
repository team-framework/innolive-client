package com.framework.innolive.feature.live

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcRecoveryPolicyTest {
    @Test fun offlineTimeDoesNotConsumeRecoveryAttempts() {
        val window = WebRtcRecoveryWindow(WebRtcRecoveryPolicy(maxAttempts = 2))
        window.begin(1_000)

        repeat(10) { assertFalse(window.recordAttempt(2_000L + it, networkAvailable = false)) }
        assertEquals(0, window.attempts)
        assertTrue(window.recordAttempt(20_000, networkAvailable = true))
        assertEquals(1, window.attempts)
        assertFalse(window.recordAttempt(51_000, networkAvailable = true))
    }

    @Test fun serverWindowAndAttemptLimitBoundRetries() {
        val policy = parseWebRtcRecoveryPolicy(
            JSONObject("""{"recovery":{"window_ms":50000,"debounce_ms":2000,"max_attempts":2}}"""),
        )
        assertEquals(2_000, policy.debounceMillis)
        val window = WebRtcRecoveryWindow(policy)
        window.begin(100_000)
        assertEquals(50_000, window.remainingMillis(100_000))
        assertTrue(window.recordAttempt(102_000))
        assertTrue(window.recordAttempt(110_000))
        assertFalse(window.recordAttempt(110_001))
        assertFalse(window.mayAttempt(150_000))
        window.clear()
        window.begin(200_000)
        assertEquals(0, window.attempts)
        assertTrue(window.recordAttempt(202_000))
    }

    @Test fun malformedPolicyFallsBackWithoutExtendingRecoveryIndefinitely() {
        val policy = parseWebRtcRecoveryPolicy(
            JSONObject("""{"recovery":{"window_ms":0,"debounce_ms":-1,"max_attempts":0}}"""),
        )
        assertEquals(WebRtcRecoveryPolicy(), policy)
        val window = WebRtcRecoveryWindow(policy)
        window.begin(1_000)
        window.begin(20_000)
        assertEquals(31_000, window.remainingMillis(20_000))
        assertFalse(window.recordAttempt(51_000))
    }

    @Test fun candidateUfragCanRejectStaleIceGeneration() {
        val current = extractIceUfrags("v=0\r\na=ice-ufrag:new123\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n")
        assertEquals(setOf("new123"), current)
        assertTrue(extractCandidateUfrag("candidate:1 1 udp 1 127.0.0.1 9 typ host ufrag new123") in current)
        assertFalse(extractCandidateUfrag("candidate:2 1 udp 1 127.0.0.1 9 typ host ufrag old456") in current)
    }
}
