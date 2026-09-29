package com.framework.innolive.feature.live

import android.Manifest
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.BuildConfig
import com.framework.innolive.feature.login.oauth.google.AuthenticationSessionViewModel
import com.framework.innolive.ui.text.resolve
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.webrtc.PeerConnection
import org.webrtc.Logging
import org.webrtc.RTCStatsReport
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Opt-in real-time camera/return-stream measurement against the configured backend.
 * Requires productionProbe=true and a pre-existing login. Does not start RTMP broadcasting.
 * Optional switchTo=server|on_device checks that a connected preview reoffers the other codec.
 * Connection/codec assertions are not a performance acceptance gate; analyze recorded stats.
 */
class ProductionVideoReturnDeviceTest {
    private lateinit var activity: ComponentActivity

    @Test fun deployedVideoReturnPath() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit productionProbe=true and an existing login are required",
            arguments.getString("productionProbe") == "true")
        val expectedCodec = arguments.getString("expectedCodec") ?: "VP8"
        require(expectedCodec in setOf("VP8", "H264"))
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity = it }
        try {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val onDevice = arguments.getString("onDevice") != "false"
            val requestedResolution = arguments.getString("resolution")
            val mode = (if (onDevice) "on_device" else "server") + if (requestedResolution == "fhd") "_fhd" else ""
            val seconds = (arguments.getString("durationSeconds")?.toIntOrNull() ?: 90).coerceIn(30, 180)
            for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
                instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, permission)
            }
            lateinit var session: WebRtcSessionViewModel
            lateinit var auth: AuthenticationSessionViewModel
            val context = instrumentation.targetContext
            val originalAI = AIProcessingPreference(context).onDevice
            val originalPrivacy = AnonymizationPreference(context).enabled
            onMain {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                session = ViewModelProvider(activity)[WebRtcSessionViewModel::class.java]
                auth = ViewModelProvider(activity)[AuthenticationSessionViewModel::class.java]
                assertNotNull("Existing login required", auth.session.value)
            }
            val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
            try {
                onMain {
                    assertTrue(session.selectInitialAIProcessing(context, onDevice))
                    assertTrue(session.selectInitialAnonymization(context, true))
                }
                val initialToken = runBlocking { auth.refreshAccessToken() }
                var usedInitialToken = false
                onMain {
                    activity.setContent {
                        LiveVideoPanels(
                            cameraLensFacing = CameraLensFacing.FRONT,
                            cameraResolution = CameraResolution(1920, 1080),
                            frameAnalyzer = session.frameAnalyzer,
                            lockedRotation = null,
                            remoteVideoTrack = session.remoteVideoTrack,
                            localVideoTrack = session.localVideoTrack,
                            eglContext = session.eglContext,
                            isConnected = session.connectionState == WebRtcConnectionState.CONNECTED,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                onMain {
                    session.start(activity) {
                        try {
                            (if (!usedInitialToken) {
                                usedInitialToken = true
                                initialToken
                            } else auth.refreshAccessToken()).also { Log.i("ProductionVideoProbe", "auth_token_ready=success") }
                        } catch (error: Exception) {
                            val http = Regex("HTTP[^0-9]*([0-9]{3})").find(error.message.orEmpty())?.groupValues?.get(1)
                            Log.i("ProductionVideoProbe", "auth_refresh=failure type=${error.javaClass.simpleName} http=$http")
                            throw error
                        }
                    }
                }
                waitUntil(90_000) {
                    session.connectionState == WebRtcConnectionState.CONNECTED ||
                        session.connectionState == WebRtcConnectionState.FAILED
                }
                assertEquals("connection: ${session.connectionStatus?.resolve(activity)}", WebRtcConnectionState.CONNECTED, session.connectionState)
                waitUntil(30_000) { session.remoteVideoTrack != null }
                val connection = field(session, "connection") as WebRtcConnection
                val peer = field(connection, "peerConnection") as PeerConnection
                Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO)
                val created = field(connection, "session") as CreatedSession
                if (requestedResolution == "fhd") {
                    val token = requireNotNull(auth.session.value).accessToken
                    val request = Request.Builder()
                        .url(BuildConfig.INNOLIVE_SERVER_URL.trimEnd('/') + "/sessions/" + created.sessionId + "/broadcast-resolution")
                        .header("Authorization", "Bearer $token")
                        .header("X-Session-Owner-Token", created.ownerToken)
                        .put(JSONObject().put("resolution", "fhd").toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    client.newCall(request).execute().use { response ->
                        assertEquals("FHD preview resolution", 200, response.code)
                        assertEquals("fhd", JSONObject(response.body.string()).getString("broadcast_resolution"))
                    }
                    Thread.sleep(5_000)
                }
                var lastDiagnosticsNs = 0L
                session.frameAnalyzer?.onFrameDiagnostics = { diagnostic ->
                    val now = System.nanoTime()
                    if (now - lastDiagnosticsNs >= 5_000_000_000L) {
                        lastDiagnosticsNs = now
                        Log.i("ProductionVideoProbe", "ai " + JSONObject().put("mode", mode)
                            .put("faces", diagnostic.analysis.faces).put("plates", diagnostic.analysis.plates)
                            .put("mask_pixels", diagnostic.analysis.maskPixels)
                            .put("detector_gpu", diagnostic.analysis.detectorGpu)
                            .put("blur_gpu", diagnostic.analysis.blurGpu)
                            .put("direct_gpu_input", diagnostic.analysis.directGpuInput)
                            .put("camera_copied_planes", diagnostic.cameraCopiedPlanes)
                            .put("inference_ms", diagnostic.timings.model.inferenceMs)
                            .put("pipeline_ms", diagnostic.timings.totalMs)
                            .put("frame_age_ms", diagnostic.frameAgeMs))
                    }
                }
                fun verifyServer() {
                    val token = requireNotNull(auth.session.value).accessToken
                    val request = Request.Builder()
                        .url(BuildConfig.INNOLIVE_SERVER_URL.trimEnd('/') + "/sessions/" + created.sessionId)
                        .header("Authorization", "Bearer $token")
                        .header("X-Session-Owner-Token", created.ownerToken)
                        .build()
                    client.newCall(request).execute().use { response ->
                        assertEquals("session API", 200, response.code)
                        val payload = JSONObject(response.body.string())
                        val media = payload.getJSONObject("media")
                        val summary = JSONObject().put("mode", mode).put("http", response.code)
                            .put("session_id", created.sessionId)
                            .put("ai_processing", payload.optString("ai_processing"))
                            .put("broadcast_resolution", payload.optString("broadcast_resolution"))
                            .put("anonymization_enabled", media.getBoolean("anonymization_enabled"))
                            .put("ai_fallback_active", media.optBoolean("ai_fallback_active"))
                            .put("video_sender_active", media.optBoolean("video_sender_active"))
                            .put("raw_video_track", media.optJSONObject("raw_video_track"))
                            .put("processed_video_track", media.optJSONObject("processed_video_track"))
                        Log.i("ProductionVideoProbe", "server_status $summary")
                        assertEquals("Server anonymization mode", !onDevice, media.getBoolean("anonymization_enabled"))
                    }
                }
                verifyServer()
                val track = requireNotNull(session.remoteVideoTrack)
                val frames = AtomicInteger()
                val previousFrameNs = AtomicLong()
                val maximumGapNs = AtomicLong()
                val intervalMaximumGapNs = AtomicLong()
                val longGaps = AtomicInteger()
                val sink = VideoSink { _: VideoFrame ->
                    val now = SystemClock.elapsedRealtimeNanos()
                    val previous = previousFrameNs.getAndSet(now)
                    if (previous != 0L) {
                        val gap = now - previous
                        maximumGapNs.accumulateAndGet(gap) { a, b -> maxOf(a, b) }
                        intervalMaximumGapNs.accumulateAndGet(gap) { a, b -> maxOf(a, b) }
                        if (gap > 250_000_000L) longGaps.incrementAndGet()
                    }
                    frames.incrementAndGet()
                }
                track.addSink(sink)
                val start = SystemClock.elapsedRealtime()
                var lastCount = 0
                var lastTime = start
                try {
                    fun sample() {
                        val latch = CountDownLatch(1)
                        var stats: RTCStatsReport? = null
                        peer.getStats { stats = it; latch.countDown() }
                        assertTrue("getStats timeout", latch.await(5, TimeUnit.SECONDS))
                        val report = requireNotNull(stats)
                        val outbound = report.statsMap.values.firstOrNull {
                            it.type == "outbound-rtp" && (it.members["kind"] == "video" || it.members["mediaType"] == "video")
                        }
                        val codec = outbound?.members?.get("codecId") as? String
                        val mime = codec?.let { report.statsMap[it]?.members?.get("mimeType")?.toString() }
                        assertEquals("Expected uplink codec", "video/$expectedCodec", mime)
                        report.statsMap.values.filter {
                            (it.type == "outbound-rtp" || it.type == "inbound-rtp") &&
                                (it.members["kind"] == "video" || it.members["mediaType"] == "video") ||
                                it.type == "candidate-pair" && it.members["nominated"] == true &&
                                (it.members["bytesSent"] as? Number)?.toLong()?.let { count -> count > 0 } == true
                        }.forEach { stat ->
                            val selected = listOf("framesEncoded", "framesSent", "packetsSent", "bytesSent", "totalEncodeTime",
                                "qualityLimitationReason", "encoderImplementation", "frameWidth", "frameHeight", "framesPerSecond",
                                "packetsReceived", "packetsLost", "bytesReceived", "framesReceived", "framesDecoded", "framesDropped",
                                "keyFramesDecoded", "freezeCount", "totalFreezesDuration", "nackCount", "pliCount", "jitter",
                                "decoderImplementation", "totalDecodeTime", "totalProcessingDelay", "totalAssemblyTime",
                                "jitterBufferDelay", "jitterBufferEmittedCount", "currentRoundTripTime", "availableOutgoingBitrate")
                            val statMime = (stat.members["codecId"] as? String)?.let {
                                report.statsMap[it]?.members?.get("mimeType")?.toString()
                            }
                            if (stat.type == "inbound-rtp") {
                                assertEquals("Expected returned codec", "video/$expectedCodec", statMime)
                            }
                            val result = JSONObject().put("mode", mode).put("elapsed_ms", SystemClock.elapsedRealtime() - start)
                                .put("type", stat.type).put("mime", statMime)
                            selected.forEach { key -> stat.members[key]?.let { result.put(key, it) } }
                            Log.i("ProductionVideoProbe", "stats $result")
                        }
                    }
                    sample()
                    repeat(seconds / 5) {
                        Thread.sleep(5_000)
                        val now = SystemClock.elapsedRealtime()
                        val count = frames.get()
                        Log.i("ProductionVideoProbe", "render " + JSONObject().put("mode", mode)
                            .put("elapsed_ms", now - start).put("frames", count)
                            .put("measurement", "remote_track_sink")
                            .put("max_frame_gap_ms", intervalMaximumGapNs.getAndSet(0L) / 1_000_000.0)
                            .put("interval_fps", (count - lastCount) * 1000.0 / (now - lastTime))
                            .put("state", session.connectionState))
                        lastCount = count; lastTime = now
                        sample()
                    }
                    val elapsed = SystemClock.elapsedRealtime() - start
                    Log.i("ProductionVideoProbe", "summary " + JSONObject().put("mode", mode)
                        .put("frames", frames.get()).put("elapsed_ms", elapsed)
                        .put("average_fps", frames.get() * 1000.0 / elapsed)
                        .put("max_frame_gap_ms", maximumGapNs.get() / 1_000_000.0)
                        .put("frame_gaps_over_250ms", longGaps.get()))
                    verifyServer()
                    assertTrue("No returned video frames", frames.get() > 0)
                    assertEquals(WebRtcConnectionState.CONNECTED, session.connectionState)
                } finally { track.removeSink(sink) }
                arguments.getString("switchTo")?.let { nextMode ->
                    require(nextMode in setOf("on_device", "server"))
                    val nextOnDevice = nextMode == "on_device"
                    require(nextOnDevice != onDevice)
                    onMain { assertTrue("Preview mode switch", session.selectAIProcessing(context, nextOnDevice)) }
                    waitUntil(90_000) {
                        session.connectionState == WebRtcConnectionState.CONNECTED ||
                            session.connectionState == WebRtcConnectionState.FAILED
                    }
                    assertEquals("Mode switch connection", WebRtcConnectionState.CONNECTED, session.connectionState)
                    waitUntil(30_000) { session.remoteVideoTrack != null }
                    val switchedConnection = field(session, "connection") as WebRtcConnection
                    assertNotSame("Mode switch must recreate PeerConnection", connection, switchedConnection)
                    val switchedSession = field(switchedConnection, "session") as CreatedSession
                    assertNotEquals("Mode switch must recreate server session", created.sessionId, switchedSession.sessionId)
                    assertEquals(nextOnDevice, session.selectedOnDeviceProcessing)
                    val switchedPeer = field(switchedConnection, "peerConnection") as PeerConnection
                    val targetCodec = if (nextOnDevice) "video/VP8" else "video/H264"
                    waitUntil(30_000) {
                        val latch = CountDownLatch(1)
                        var report: RTCStatsReport? = null
                        switchedPeer.getStats { report = it; latch.countDown() }
                        if (!latch.await(5, TimeUnit.SECONDS)) return@waitUntil false
                        report?.statsMap?.values?.any { stat ->
                            stat.type == "outbound-rtp" &&
                                (stat.members["kind"] == "video" || stat.members["mediaType"] == "video") &&
                                (stat.members["codecId"] as? String)?.let { codecId ->
                                    report?.statsMap?.get(codecId)?.members?.get("mimeType") == targetCodec
                                } == true
                        } == true
                    }
                    val token = requireNotNull(auth.session.value).accessToken
                    val request = Request.Builder()
                        .url(BuildConfig.INNOLIVE_SERVER_URL.trimEnd('/') + "/sessions/" + switchedSession.sessionId)
                        .header("Authorization", "Bearer $token")
                        .header("X-Session-Owner-Token", switchedSession.ownerToken)
                        .build()
                    client.newCall(request).execute().use { response ->
                        assertEquals("Switched session API", 200, response.code)
                        val enabled = JSONObject(response.body.string())
                            .getJSONObject("media").getBoolean("anonymization_enabled")
                        assertEquals("Switched server protection", !nextOnDevice, enabled)
                    }
                    Log.i("ProductionVideoProbe", "mode_switch " + JSONObject()
                        .put("from", mode).put("to", nextMode).put("codec", targetCodec)
                        .put("session_recreated", true))
                }
            } finally {
                session.frameAnalyzer?.onFrameDiagnostics = null
                try {
                    onMain { session.close() }
                } finally {
                    AIProcessingPreference(context).onDevice = originalAI
                    AnonymizationPreference(context).enabled = originalPrivacy
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                }
            }
        } finally { scenario.close() }
    }
    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Real-time wait timeout" }
            Thread.sleep(50)
        }
    }
    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
}
