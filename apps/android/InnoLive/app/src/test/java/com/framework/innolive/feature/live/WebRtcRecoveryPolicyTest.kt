package com.framework.innolive.feature.live

import android.net.NetworkCapabilities
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport

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

    @Test fun existingTokenIsUsedUntilUnauthorizedAndRefreshChangesLaterSignals() {
        val token = RecoveryAccessToken("current-token")
        assertFalse(token.refreshedForCurrentRecovery)
        assertEquals("current-token", token.value)
        token.updateForRecovery(" renewed-token ")
        assertEquals("renewed-token", token.value)
        assertTrue(token.refreshedForCurrentRecovery)
        assertThrows(IllegalArgumentException::class.java) { token.updateForRecovery(" ") }
        assertEquals("renewed-token", token.value)
        token.resetRecovery()
        assertFalse(token.refreshedForCurrentRecovery)
        assertEquals("renewed-token", token.value)
    }

    @Test fun failedCandidateSendRetriesWhileRecoveryWindowIsOpenEvenAfterAnswer() {
        assertEquals(
            SignalingSendFailureAction.FAIL_CONNECTION,
            signalingSendFailureAction(recoveryWindowOpen = false, hasConnected = false),
        )
        assertEquals(
            SignalingSendFailureAction.RETRY_NEGOTIATION,
            signalingSendFailureAction(recoveryWindowOpen = true, hasConnected = true),
        )
        assertEquals(
            SignalingSendFailureAction.START_RECOVERY,
            signalingSendFailureAction(recoveryWindowOpen = false, hasConnected = true),
        )
    }

    @Test fun unauthorizedRefreshesOnlyDuringRecoveryAndOnlyOncePerWindow() {
        assertEquals(
            RecoveryUnauthorizedAction.FAIL_CONNECTION,
            recoveryUnauthorizedAction(recoveryWindowOpen = false, tokenAlreadyRefreshed = false),
        )
        assertEquals(
            RecoveryUnauthorizedAction.REFRESH_AND_RETRY,
            recoveryUnauthorizedAction(recoveryWindowOpen = true, tokenAlreadyRefreshed = false),
        )
        assertEquals(
            RecoveryUnauthorizedAction.FAIL_CONNECTION,
            recoveryUnauthorizedAction(recoveryWindowOpen = true, tokenAlreadyRefreshed = true),
        )
    }

    @Test fun networkMustBeValidatedBeforeAnAttemptIsRecorded() {
        val captivePortal = setOf(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = captivePortal + NetworkCapabilities.NET_CAPABILITY_VALIDATED
        val window = WebRtcRecoveryWindow(WebRtcRecoveryPolicy(maxAttempts = 1))
        window.begin(1_000)

        assertFalse(hasValidatedInternet { it in captivePortal })
        assertFalse(window.recordAttempt(2_000, hasValidatedInternet { it in captivePortal }))
        assertEquals(0, window.attempts)
        assertTrue(hasValidatedInternet { it in validated })
        assertTrue(window.recordAttempt(3_000, hasValidatedInternet { it in validated }))
        assertEquals(1, window.attempts)
    }

    @Test fun retiredNegotiationCannotCompleteRecoveryEvenIfPeerConnects() {
        assertTrue(hasCurrentRecoveryAnswer("current", "current"))
        assertFalse(hasCurrentRecoveryAnswer(null, "retired"))
        assertFalse(hasCurrentRecoveryAnswer("new", "retired"))
    }

    @Test fun videoVerificationWaitsForPeerConnectionBeforeStartingItsOwnDeadline() {
        val gate = RecoveryVideoVerificationGate()
        assertFalse(gate.startIfConnected(peerConnected = false))
        assertFalse(gate.started)
        assertTrue(gate.startIfConnected(peerConnected = true))
        assertTrue(gate.started)
        assertFalse(gate.startIfConnected(peerConnected = true))
        gate.reset()
        assertFalse(gate.started)
        assertTrue(gate.startIfConnected(peerConnected = true))
    }

    @Test fun stoppingBroadcastCannotReusePeerConnectionUntilVideoRecoveryIsVerified() {
        fun canReuse(packets: Boolean, serverReady: Boolean) = canReuseRecoveredPreviewAfterStop(
            peerConnected = true,
            audioVerified = true,
            recoveryAttemptActive = false,
            recoveryOfferPending = false,
            hasCurrentAnswer = true,
            videoPacketsProgressed = packets,
            serverVideoReady = serverReady,
        )

        assertFalse(canReuse(packets = false, serverReady = false))
        assertFalse(canReuse(packets = true, serverReady = false))
        assertFalse(canReuse(packets = false, serverReady = true))
        assertTrue(canReuse(packets = true, serverReady = true))
        assertFalse(canReuseRecoveredPreviewAfterStop(true, true, false, false, false, true, true))
        assertFalse(canReuseRecoveredPreviewAfterStop(true, false, false, false, true, true, true))
        assertFalse(canReuseRecoveredPreviewAfterStop(true, true, true, false, true, true, true))
        assertFalse(canReuseRecoveredPreviewAfterStop(true, true, false, true, true, true, true))
    }

    @Test fun reusedVideoSenderRequiresNewOutboundPackets() {
        val progress = OutboundVideoProgress()
        progress.observe(null)
        progress.observe(900)
        progress.observe(900)
        assertFalse(progress.hasProgress)
        progress.observe(901)
        assertTrue(progress.hasProgress)

        val afterCounterReset = OutboundVideoProgress()
        afterCounterReset.observe(900)
        afterCounterReset.observe(0)
        assertFalse(afterCounterReset.hasProgress)
        afterCounterReset.observe(1)
        assertTrue(afterCounterReset.hasProgress)
    }

    @Test fun videoStatsAndServerSnapshotIdentifyLiveVideo() {
        val report = RTCStatsReport(
            0L,
            mapOf(
                "video" to RTCStats(0L, "outbound-rtp", "video", mapOf("packetsSent" to 42L)),
                "audio" to RTCStats(0L, "inbound-rtp", "audio", mapOf("packetsReceived" to 100L)),
            ),
        )
        assertEquals(42L, outboundVideoPackets(report))
        assertFalse(hasLiveServerVideoTrack("""{"media":{"raw_video_track":null}}"""))
        assertFalse(hasLiveServerVideoTrack("""{"media":{"raw_video_track":{"ready_state":"ended"}}}"""))
        assertTrue(hasLiveServerVideoTrack("""{"media":{"raw_video_track":{"ready_state":"live"}}}"""))
        assertEquals(
            RecoveryServerVideoStatus.READY,
            recoveryServerVideoStatus(200, """{"media":{"raw_video_track":{"ready_state":"live"}}}"""),
        )
        assertEquals(RecoveryServerVideoStatus.PENDING, recoveryServerVideoStatus(200, "{}"))
        assertEquals(RecoveryServerVideoStatus.UNAUTHORIZED, recoveryServerVideoStatus(401))
        assertEquals(RecoveryServerVideoStatus.TERMINAL, recoveryServerVideoStatus(404))
    }
}
