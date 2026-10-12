package com.framework.innolive.feature.live.direct

import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.BroadcastState
import com.framework.innolive.feature.live.SessionNotice
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class DirectPublishContractTest {
    private fun fixture(name: String): String {
        val root = generateSequence(File(checkNotNull(System.getProperty("innolive.android.resDir")))) { it.parentFile }
            .first { File(it, "contracts/fixtures").isDirectory }
        return File(root, "contracts/fixtures/direct-publish-$name.v1.json").readText()
    }

    private fun JSONObject.keyNames(): Set<String> = keys().asSequence().toSet()

    /** 숫자 표현(Int·Double·BigDecimal) 차이를 없애 JSON 값을 비교합니다. */
    private fun normalized(value: Any?): Any? = when (value) {
        is JSONObject -> value.keyNames().associateWith { normalized(value.get(it)) }
        is org.json.JSONArray -> List(value.length()) { normalized(value.get(it)) }
        is Number -> value.toDouble()
        else -> value
    }

    @Test fun sessionRequestUsesTopLevelOnDeviceAndResolution() {
        val expected = JSONObject(fixture("session-request"))
        val actual = buildDirectSessionRequest(BroadcastProvider.YOUTUBE, DirectPublishResolution.HD_720)
        for (key in expected.keys()) assertEquals(key, expected.get(key), actual.get(key))
        assertEquals("fhd", buildDirectSessionRequest(BroadcastProvider.CHZZK, DirectPublishResolution.FHD)
            .getString("broadcast_resolution"))
    }

    @Test fun youTubePrepareReturnsIngestUrlBeforeGoLive() {
        val result = parseDirectPrepare(fixture("prepare-youtube"), BroadcastProvider.YOUTUBE)
        assertEquals(BroadcastState.PREPARED, result.state)
        assertEquals("rtmps://a.rtmp.youtube.com/live2/fixture-stream-key", result.ingestUrl?.reveal())
        assertTrue(checkNotNull(result.ingestUrl).isSecure)
    }

    @Test fun youTubePrepareWithoutIngestUrlIsNotDirectPublish() {
        val serverMode = JSONObject(fixture("prepare-youtube")).apply { remove("ingest_url") }.toString()
        assertThrows(IllegalArgumentException::class.java) {
            parseDirectPrepare(serverMode, BroadcastProvider.YOUTUBE)
        }
    }

    @Test fun chzzkPrepareMustNotCarryIngestUrl() {
        val prepared = """{"stream":{"broadcast_phase":"prepared","status":"idle"}}"""
        assertNull(parseDirectPrepare(prepared, BroadcastProvider.CHZZK).ingestUrl)
        val leaked = """{"stream":{"broadcast_phase":"prepared"},"ingest_url":"rtmp://h/app/key"}"""
        assertThrows(IllegalStateException::class.java) { parseDirectPrepare(leaked, BroadcastProvider.CHZZK) }
    }

    @Test fun prepareRequiresPreparedPhase() {
        val live = """{"stream":{"broadcast_phase":"live"},"ingest_url":"rtmps://h/app/key"}"""
        assertThrows(IllegalStateException::class.java) { parseDirectPrepare(live, BroadcastProvider.YOUTUBE) }
    }

    @Test fun chzzkGoLiveReturnsDirectIngestOnly() {
        val result = parseDirectGoLive(fixture("golive-chzzk"), BroadcastProvider.YOUTUBE)
        assertEquals(BroadcastState.LIVE, result.state)
        assertEquals(listOf(BroadcastProvider.CHZZK), result.directIngest.map { it.provider })
        assertEquals(
            "rtmp://global-rtmp.lip2.navercorp.com:8080/relay/fixture-stream-key",
            result.directIngest.single().ingestUrl.reveal(),
        )
        assertFalse(result.directIngest.single().ingestUrl.isSecure)
        assertTrue(result.failedTargets.isEmpty())
    }

    @Test fun goLiveKeepsFailuresAndTurnsInvalidIngestIntoFailure() {
        val payload = """
            {"broadcast_phase":"live",
             "failed_targets":[{"provider":"youtube","code":"broadcast_not_ready","message":"YouTube is not ready"}],
             "direct_ingest":[{"provider":"chzzk","ingest_url":"http://bad/key"},{"provider":"unknown","ingest_url":"rtmp://h/a/k"}]}
        """.trimIndent()
        val result = parseDirectGoLive(payload, BroadcastProvider.CHZZK)
        assertTrue(result.directIngest.isEmpty())
        assertEquals(
            listOf(
                DirectTargetFailure("youtube", "broadcast_not_ready", "YouTube is not ready"),
                DirectTargetFailure("chzzk", INVALID_INGEST_URL, null),
            ),
            result.failedTargets,
        )
    }

    @Test fun goLiveDropsDuplicateIngestForSameProvider() {
        val payload = """
            {"broadcast_phase":"live","direct_ingest":[
              {"provider":"chzzk","ingest_url":"rtmp://h/a/k1"},{"provider":"chzzk","ingest_url":"rtmp://h/a/k2"}]}
        """.trimIndent()
        assertEquals(1, parseDirectGoLive(payload, BroadcastProvider.CHZZK).directIngest.size)
    }

    @Test fun heartbeatPayloadMatchesSharedFixture() {
        val heartbeat = DirectHeartbeat(
            sentAt = Instant.parse("2026-10-12T08:30:00.789Z"),
            targets = listOf(
                HeartbeatTarget(BroadcastProvider.YOUTUBE, connected = true, bitrateKbps = 5200, reconnects = 0),
                HeartbeatTarget(BroadcastProvider.CHZZK, connected = true, bitrateKbps = 5100, reconnects = 1),
            ),
            video = HeartbeatVideo(fps = 29.8, droppedFrames = 12, width = 1280, height = 720),
            anonymization = HeartbeatAnonymization(
                failedFrames = 0, enabled = true, inferenceMsP50 = 18.2, inferenceMsP95 = 31.0,
            ),
            model = "Pixel 8",
            device = HeartbeatDevice(DeviceThermal.FAIR, batteryPct = 64, charging = false, cpuPct = 42),
        )
        val expected = JSONObject(fixture("heartbeat-request"))
        val actual = buildHeartbeatPayload(heartbeat)
        assertEquals(normalized(expected), normalized(actual))
    }

    @Test fun heartbeatOmitsOptionalFieldsTheDeviceCannotCollect() {
        val payload = buildHeartbeatPayload(
            DirectHeartbeat(
                sentAt = Instant.parse("2026-10-12T08:30:00Z"),
                targets = listOf(HeartbeatTarget(BroadcastProvider.CHZZK, connected = false)),
                video = HeartbeatVideo(fps = 0.0, droppedFrames = 0),
                anonymization = HeartbeatAnonymization(failedFrames = 3),
                model = "Pixel 8",
                device = HeartbeatDevice(),
            ),
        )
        assertEquals(setOf("sent_at", "targets", "video", "anonymization", "platform", "model"), payload.keyNames())
        assertEquals(setOf("provider", "connected"), payload.getJSONArray("targets").getJSONObject(0).keyNames())
        assertEquals(setOf("failed_frames"), payload.getJSONObject("anonymization").keyNames())
    }

    @Test fun heartbeatRequiresTargetsAndModel() {
        val video = HeartbeatVideo(30.0, 0)
        val anonymization = HeartbeatAnonymization(0)
        assertThrows(IllegalArgumentException::class.java) {
            DirectHeartbeat(Instant.EPOCH, emptyList(), video, anonymization, "Pixel 8")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DirectHeartbeat(Instant.EPOCH, listOf(HeartbeatTarget(BroadcastProvider.YOUTUBE, true)), video, anonymization, " ")
        }
    }

    @Test fun heartbeatResponseCarriesStopTargetsNoticesAndRemainingTime() {
        val result = parseHeartbeatAccepted(fixture("heartbeat-response"))
        assertEquals(listOf(BroadcastProvider.CHZZK), result.stopTargets)
        assertEquals(listOf(SessionNotice("broadcast_limit_10m", "2026-10-12T08:30:00Z")), result.notices)
        assertEquals(540L, result.broadcastRemainingSeconds)
    }

    @Test fun heartbeatResponseToleratesUnknownProvidersAndMissingValues() {
        val result = parseHeartbeatAccepted(
            """{"stop_targets":["future","chzzk","chzzk"],"notices":[{"at":"x"}],"broadcast_remaining_seconds":null}""",
        )
        assertEquals(listOf(BroadcastProvider.CHZZK), result.stopTargets)
        assertTrue(result.notices.isEmpty())
        assertNull(result.broadcastRemainingSeconds)
        assertEquals(HeartbeatResult.Accepted(emptyList(), emptyList(), null), parseHeartbeatAccepted("{}"))
    }
}
