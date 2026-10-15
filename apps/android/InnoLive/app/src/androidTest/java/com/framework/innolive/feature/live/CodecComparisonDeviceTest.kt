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
import com.framework.innolive.feature.live.direct.broadcast.DirectBroadcastPlan
import com.framework.innolive.feature.live.direct.DirectPublishResolution
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
import org.webrtc.RTCStatsReport
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Opt-in real-time camera/return-stream measurement against the configured backend.
 * Requires productionProbe=true and a pre-existing login, or the opt-in localhost backend.
 * Does not go live. Local-preview runs also reject stalls of 250ms or longer.
 * Full performance comparisons require analysis of the recorded resolution and statistics.
 */
class CodecComparisonDeviceTest {
    private lateinit var activity: ComponentActivity

    @Test fun deployedVideoReturnPath() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit productionProbe=true and an existing login are required",
            arguments.getString("productionProbe") == "true")
        val expectedCodec = arguments.getString("expectedCodec") ?: "H264"
        val localPreview = arguments.getString("localPreview") == "true"
        val localServer = arguments.getString("serverUrl")?.also {
            val target = java.net.URI(it)
            require(BuildConfig.DEBUG && target.scheme == "http" && target.host == "127.0.0.1" &&
                target.port in 1..65535 && target.rawUserInfo == null && target.rawQuery == null &&
                target.rawFragment == null && target.path in listOf("", "/"))
        }
        val serverUrl = localServer ?: BuildConfig.INNOLIVE_SERVER_URL
        // Synthetic unsigned test identity; accepted only by the opt-in localhost backend.
        val localProbeToken = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJsb2NhbC1wcm9iZSJ9.c3ludGhldGlj"
        require(expectedCodec in setOf("VP8", "H264"))
        if (arguments.getString("forceCodec") != "false") System.setProperty("innolive.codec.probe", expectedCodec)
        val runLabel = arguments.getString("runLabel") ?: expectedCodec
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity = it }
        try {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val onDevice = arguments.getString("onDevice") != "false"
            val requestedResolution = arguments.getString("resolution")
            val captureResolution = if (arguments.getString("capture") == "720") CameraResolution(1280,720)
                else CameraResolution(1920,1080)
            val mode = runLabel + "_" + (if (onDevice) "on_device" else "server") + if (requestedResolution == "fhd") "_fhd" else ""
            val seconds = (arguments.getString("durationSeconds")?.toIntOrNull() ?: 90).coerceIn(30, 900)
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
                if (localServer == null) assertNotNull("Existing login required", auth.session.value)
            }
            val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
            try {
                onMain {
                    assertTrue(session.selectInitialAIProcessing(context, onDevice))
                    assertTrue(session.selectInitialAnonymization(context, true))
                    // The server-AI uplink budget follows the plan resolution, so the FHD probe
                    // creates the session as FHD instead of relying on the later PUT alone.
                    if (requestedResolution == "fhd") {
                        assertTrue(session.selectDirectBroadcastPlan(DirectBroadcastPlan(resolution = DirectPublishResolution.FHD)))
                    }
                }
                val initialToken = if (localServer != null) localProbeToken else runBlocking { auth.refreshAccessToken() }
                var usedInitialToken = false
                onMain {
                    activity.setContent {
                        LiveVideoPanels(
                            cameraLensFacing = CameraLensFacing.FRONT,
                            cameraResolution = captureResolution,
                            frameAnalyzer = session.frameAnalyzer,
                            lockedRotation = null,
                            remoteVideoTrack = session.remoteVideoTrack,
                            localVideoTrack = session.localVideoTrack,
                            eglContext = session.eglContext,
                            isConnected = session.connectionState == WebRtcConnectionState.CONNECTED,
                            onDeviceProcessing = onDevice && localPreview,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                onMain {
                    session.start(activity, serverUrl = serverUrl) {
                        try {
                            (if (!usedInitialToken) {
                                usedInitialToken = true
                                initialToken
                            } else if (localServer != null) localProbeToken else auth.refreshAccessToken()).also { Log.i("ProductionVideoProbe", "auth_token_ready=success") }
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
                val connection = field(session, "connection") as WebRtcConnection
                val sendOnly = onDevice && field(connection, "sendOnlyVideoSupported") == true
                if (sendOnly) require(localPreview) { "Send-only uses the protected local preview" }
                else waitUntil(30_000) { session.remoteVideoTrack != null }
                waitUntil(30_000) { session.localVideoTrack != null }
                val peer = field(connection, "peerConnection") as PeerConnection
                // Do not enable verbose SDK logging; probe statistics use an explicit allowlist.
                val created = field(connection, "session") as CreatedSession
                if (requestedResolution == "fhd") {
                    val token = if (localServer != null) localProbeToken else requireNotNull(auth.session.value).accessToken
                    val request = Request.Builder()
                        .url(serverUrl.trimEnd('/') + "/sessions/" + created.sessionId + "/broadcast-resolution")
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
                val pipelineSamples = java.util.Collections.synchronizedList(mutableListOf<Double>())
                val inferenceSamples = java.util.Collections.synchronizedList(mutableListOf<Double>())
                val ageSamples = java.util.Collections.synchronizedList(mutableListOf<Double>())
                val faceFrames = AtomicInteger()
                val plateFrames = AtomicInteger()
                val protectedFrames = AtomicInteger()
                val collecting = java.util.concurrent.atomic.AtomicBoolean(false)
                var lastDiagnosticsNs = 0L
                session.frameAnalyzer?.onFrameDiagnostics = { diagnostic ->
                    val now = System.nanoTime()
                    if (collecting.get()) {
                        pipelineSamples.add(diagnostic.timings.totalMs)
                        inferenceSamples.add(diagnostic.timings.model.inferenceMs)
                        ageSamples.add(diagnostic.frameAgeMs)
                        if(diagnostic.analysis.faces > 0) faceFrames.incrementAndGet()
                        if(diagnostic.analysis.plates > 0) plateFrames.incrementAndGet()
                        if(diagnostic.analysis.maskPixels > 0) protectedFrames.incrementAndGet()
                    }
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
                    val token = if (localServer != null) localProbeToken else requireNotNull(auth.session.value).accessToken
                    val request = Request.Builder()
                        .url(serverUrl.trimEnd('/') + "/sessions/" + created.sessionId)
                        .header("Authorization", "Bearer $token")
                        .header("X-Session-Owner-Token", created.ownerToken)
                        .build()
                    client.newCall(request).execute().use { response ->
                        assertEquals("session API", 200, response.code)
                        val payload = JSONObject(response.body.string())
                        val media = payload.getJSONObject("media")
                        val summary = JSONObject().put("mode", mode).put("http", response.code)

                            .put("ai_processing", payload.optString("ai_processing"))
                            .put("broadcast_resolution", payload.optString("broadcast_resolution"))
                            .put("anonymization_enabled", media.getBoolean("anonymization_enabled"))
                            .put("ai_fallback_active", media.optBoolean("ai_fallback_active"))
                            .put("video_sender_active", media.optBoolean("video_sender_active"))

                        Log.i("ProductionVideoProbe", "server_status $summary")
                        assertEquals("Server anonymization mode", !onDevice, media.getBoolean("anonymization_enabled"))
                    }
                }
                verifyServer()
                val warmup = arguments.getString("warmupSeconds")?.toLongOrNull() ?: 30L
                Log.i("ProductionVideoProbe", "warmup " + JSONObject().put("mode", mode).put("seconds", warmup))
                Thread.sleep(warmup * 1000)
                collecting.set(true)
                val readbacksAtStart = com.framework.innolive.feature.live.privacy.PrivacyTextureReadbackCounter.value()
                val track = requireNotNull(if (localPreview) session.localVideoTrack else session.remoteVideoTrack)
                val frames = AtomicInteger()
                val localFrames = AtomicInteger()
                val localSink = VideoSink { _: VideoFrame -> localFrames.incrementAndGet() }
                session.localVideoTrack?.addSink(localSink)
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
                        val power = context.getSystemService(android.os.PowerManager::class.java)
                        Log.i("ProductionVideoProbe", "cpu " + JSONObject().put("mode", mode)
                            .put("elapsed_ms", SystemClock.elapsedRealtime() - start)
                            .put("process_cpu_ms", android.os.Process.getElapsedCpuTime())
                            .put("thermal_status", power.currentThermalStatus))
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
                                "jitterBufferDelay", "jitterBufferEmittedCount", "currentRoundTripTime", "availableOutgoingBitrate", "availableIncomingBitrate", "state", "nominated", "jitterBufferMinimumDelay", "jitterBufferTargetDelay")
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
                            .put("measurement", if (localPreview) "local_protected_track_sink" else "remote_track_sink")
                            .put("max_frame_gap_ms", intervalMaximumGapNs.getAndSet(0L) / 1_000_000.0)
                            .put("interval_fps", (count - lastCount) * 1000.0 / (now - lastTime))
                            .put("state", session.connectionState))
                        lastCount = count; lastTime = now
                        sample()
                    }
                    val elapsed = SystemClock.elapsedRealtime() - start
                    Log.i("ProductionVideoProbe", "summary " + JSONObject().put("mode", mode)
                        .put("measurement", if (localPreview) "local_protected_track_sink" else "remote_track_sink")
                        .put("send_only", sendOnly)
                        .put("frames", frames.get()).put("elapsed_ms", elapsed)
                        .put("average_fps", frames.get() * 1000.0 / elapsed)
                        .put("max_frame_gap_ms", maximumGapNs.get() / 1_000_000.0)
                        .put("frame_gaps_over_250ms", longGaps.get())
                        .put("local_frames", localFrames.get())
                        .put("local_average_fps", localFrames.get() * 1000.0 / elapsed)
                        .put("texture_readbacks", com.framework.innolive.feature.live.privacy.PrivacyTextureReadbackCounter.value() - readbacksAtStart)
                        .put("face_frames", faceFrames.get()).put("plate_frames", plateFrames.get())
                        .put("protected_frames", protectedFrames.get()))
                    fun percentile(values: List<Double>, fraction: Double): Double {
                        val sorted = synchronized(values) { values.toList().sorted() }
                        // org.json rejects NaN; the server-AI probe has no local pipeline samples.
                        return if(sorted.isEmpty()) -1.0 else sorted[((sorted.size-1)*fraction).toInt()]
                    }
                    collecting.set(false)
                    Log.i("ProductionVideoProbe", "ai_summary " + JSONObject().put("mode", mode)
                        .put("samples", pipelineSamples.size)
                        .put("pipeline_p50_ms", percentile(pipelineSamples, .5))
                        .put("pipeline_p95_ms", percentile(pipelineSamples, .95))
                        .put("inference_p50_ms", percentile(inferenceSamples, .5))
                        .put("inference_p95_ms", percentile(inferenceSamples, .95))
                        .put("frame_age_p50_ms", percentile(ageSamples, .5))
                        .put("frame_age_p95_ms", percentile(ageSamples, .95)))
                    verifyServer()
                    assertTrue("No preview frames", frames.get() > 0)
                    assertTrue("No protected face frames", protectedFrames.get() > 0)
                    if (localPreview) assertTrue("Protected preview stalled", maximumGapNs.get() < 250_000_000L)
                    if (sendOnly) assertNull("Unexpected server return track", session.remoteVideoTrack)
                    if (expectedCodec == "H264") assertEquals("Hardware texture path must stay on GPU", 0L, com.framework.innolive.feature.live.privacy.PrivacyTextureReadbackCounter.value() - readbacksAtStart)
                    assertEquals(WebRtcConnectionState.CONNECTED, session.connectionState)
                } finally { track.removeSink(sink); session.localVideoTrack?.removeSink(localSink) }
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
        } finally { System.clearProperty("innolive.codec.probe"); scenario.close() }
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
