package com.framework.innolive.feature.live.direct.broadcast

import android.Manifest
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.feature.live.BroadcastEvent
import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.BroadcastState
import com.framework.innolive.feature.live.CameraFrameAnalyzer
import com.framework.innolive.feature.live.LiveBroadcastCallbacks
import com.framework.innolive.feature.live.LiveBroadcastTransportRequest
import com.framework.innolive.feature.live.WebRtcConnectionState
import com.framework.innolive.feature.live.direct.DirectPublishApi
import com.framework.innolive.feature.live.direct.DirectPublishResolution
import com.framework.innolive.feature.live.direct.lifecycle.ServiceBroadcastKeepAlive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnectionFactory
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 직접 송출 상태 머신을 실제 가림 처리 분석기·인코더·RTMP·마이크·하트비트·재연결·foreground service로
 * 끝까지 돌립니다. 서버만 가짜 응답이며, ingest 주소는 호스트의 로컬 RTMP 서버입니다.
 *
 * `-e directBroadcastIngestUrl rtmp://127.0.0.1:1935/live/<key>`로 켭니다(`adb reverse tcp:1935 tcp:1935`).
 */
@RunWith(AndroidJUnit4::class)
class DirectBroadcastIntegrationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    private class FakeServer(private val ingestUrl: String) {
        val calls = CopyOnWriteArrayList<String>()
        val heartbeats = AtomicInteger()
        @Volatile var phase = "idle"

        private fun session(phase: String) = """{"session_id":"s-it","owner_token":"o-it","provider":"youtube",
            "ai_processing":"on_device","broadcast_resolution":"720p","media":{"anonymization_enabled":false},
            "stream":{"status":"idle","broadcast_phase":"$phase"},
            "targets":[{"provider":"youtube","stream":{"status":"idle","broadcast_phase":"$phase"}}],
            "notices":[],"broadcast_remaining_seconds":null}"""

        fun respond(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val path = request.url.encodedPath
            calls += "${request.method} $path"
            val (code, body) = when {
                request.method == "POST" && path == "/sessions" -> 201 to session("idle")
                request.method == "PUT" && path.endsWith("/broadcast") -> 200 to session(phase)
                path.endsWith("/stream/prepare") -> {
                    phase = "prepared"
                    200 to session("prepared").dropLast(1) + ""","ingest_url":"$ingestUrl"}"""
                }
                path.endsWith("/stream/golive") -> {
                    phase = "live"
                    200 to """{"status":"idle","broadcast_phase":"live",
                        "targets":[{"provider":"youtube","stream":{"status":"idle","broadcast_phase":"live"}}]}"""
                }
                path.endsWith("/heartbeat") -> {
                    heartbeats.incrementAndGet()
                    200 to """{"stop_targets":[],"notices":[],"broadcast_remaining_seconds":null}"""
                }
                path.endsWith("/stream/stop") -> {
                    phase = "idle"
                    200 to """{"status":"idle","broadcast_phase":"idle"}"""
                }
                request.method == "DELETE" -> 204 to ""
                request.method == "GET" -> 200 to session(phase)
                else -> 404 to """{"error":{"code":"not_found","message":"m"}}"""
            }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
    }

    @Test fun youtubeDirectBroadcastRunsEndToEndWithRealMedia() {
        val ingest = arguments.getString("directBroadcastIngestUrl")
        assumeTrue("directBroadcastIngestUrl 인자가 없어 통합 송출을 건너뜁니다.", ingest != null)
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS)) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())

        val server = FakeServer(checkNotNull(ingest))
        val states = CopyOnWriteArrayList<Pair<BroadcastState, BroadcastEvent?>>()
        val connected = CountDownLatch(1)
        val analyzerRef = AtomicReference<CameraFrameAnalyzer>()
        val uiStates = CopyOnWriteArrayList<DirectBroadcastUiState>()
        val callbacks = LiveBroadcastCallbacks(
            onStateChanged = { state, _ -> if (state == WebRtcConnectionState.CONNECTED) connected.countDown() },
            onRemoteTrackChanged = {},
            onLocalMediaReady = { analyzer, _ -> analyzerRef.set(analyzer) },
            onLocalMediaCleared = {},
            onBroadcastStateChanged = { state, event -> states += state to event; Log.i(TAG, "state=$state event=$event") },
            onAnonymizationStateConfirmed = {},
            onLocalVideoTrackChanged = {},
            onInitialSignalingStarted = {},
            onSessionSnapshotChanged = {},
            onServerError = { Log.i(TAG, "serverError=${it.code}") },
            onUplinkVideoStats = {},
            onDirectUiStateChanged = { uiStates += it },
        )
        val request = LiveBroadcastTransportRequest(
            context = context,
            serverUrl = "https://direct-it.example.test",
            accessToken = TEST_ACCESS_TOKEN,
            refreshAccessToken = { TEST_ACCESS_TOKEN },
            initialAnonymizationEnabled = true,
            initialOnDeviceProcessing = true,
            provider = BroadcastProvider.YOUTUBE,
            preferredAudioInput = null,
            directPlan = DirectBroadcastPlan(DirectPublishResolution.HD_720, setOf(BroadcastProvider.YOUTUBE)),
            callbacks = callbacks,
        )
        val client = OkHttpClient.Builder().addInterceptor(Interceptor(server::respond)).build()
        var controller: DirectBroadcastController? = null
        val dependencies = DirectBroadcastDependencies(
            heartbeat = { api, input, listener -> createDirectHeartbeatLoop(context, api, input, listener) },
            reconnect = { actions -> RtmpReconnectSupervisor(actions, AndroidNetworkAvailability(context)) },
            keepAlive = { ctx ->
                ServiceBroadcastKeepAlive(ctx, { reason, active -> controller?.setSlate(reason, active) },
                    { controller?.stopBroadcast() })
            },
        )
        val env = androidDirectBroadcastEnvironment(request, dependencies) {
            DirectPublishApi(it.serverUrl, it.accessToken, it.refreshAccessToken, client = client, log = {})
        }
        val created = DirectBroadcastController(callbacks,
            DirectBroadcastConfig(request.directPlan, request.provider, request.initialAnonymizationEnabled), env)
        controller = created

        val pumping = AtomicBoolean(true)
        val pump = Thread {
            while (analyzerRef.get() == null && pumping.get()) Thread.sleep(10)
            val analyzer = analyzerRef.get() ?: return@Thread
            val intervalNs = 1_000_000_000L / 30
            var next = System.nanoTime()
            var index = 0
            while (pumping.get()) {
                // 밝기가 시간에 따라 바뀌어 수신 영상에서 진행과 대기 화면 구간을 구분할 수 있습니다.
                analyzer.analyze(image(System.nanoTime(), yValue = 60 + (index++ / 30) % 120))
                next += intervalNs
                val sleepMs = (next - System.nanoTime()) / 1_000_000
                if (sleepMs > 0) Thread.sleep(sleepMs)
            }
        }.apply { start() }

        // 화면에 보이는 Activity가 있어야 앱이 포그라운드로 보고 카메라 프레임을 송출합니다.
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            created.start()
            assertTrue("첫 가림 처리 프레임이 오지 않았습니다.", connected.await(60, TimeUnit.SECONDS))
            assertTrue(created.prepareBroadcast(BroadcastSettings("통합 확인", "", "private", false, "22"), false))
            waitFor(30_000, "PREPARED") { states.lastOrNull()?.first == BroadcastState.PREPARED }
            assertTrue(created.goLive {})
            waitFor(30_000, "LIVE") { states.lastOrNull()?.first == BroadcastState.LIVE }
            waitFor(10_000, "foreground service") { serviceRunning() }

            Thread.sleep(16_000)
            assertTrue("heartbeats=${server.heartbeats.get()}", server.heartbeats.get() >= 2)

            Log.i(TAG, "mark live")
            created.pauseBroadcast()
            waitFor(10_000, "slate") { uiStates.lastOrNull()?.slate?.contains(SlateReason.USER_PAUSE) == true }
            Log.i(TAG, "mark paused")
            Thread.sleep(4_000)
            created.resumeBroadcast()
            waitFor(10_000, "slate off") { uiStates.lastOrNull()?.slate?.isEmpty() == true }
            Log.i(TAG, "mark resumed")
            Thread.sleep(4_000)

            // 백그라운드로 가면 대기 화면, 돌아오면 카메라 프레임입니다.
            scenario.moveToState(Lifecycle.State.CREATED)
            waitFor(10_000, "background slate") { uiStates.lastOrNull()?.slate == setOf(SlateReason.BACKGROUND) }
            Log.i(TAG, "mark background")
            Thread.sleep(4_000)
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor(10_000, "foreground") { uiStates.lastOrNull()?.slate?.isEmpty() == true }
            Log.i(TAG, "mark foreground")
            Thread.sleep(4_000)

            created.stopBroadcast()
            waitFor(30_000, "IDLE") { states.lastOrNull()?.first == BroadcastState.IDLE }
            waitFor(10_000, "service stopped") { !serviceRunning() }
            assertTrue("calls=${server.calls}", server.calls.any { it.endsWith("/stream/stop") })
            assertTrue("calls=${server.calls}", server.calls.any { it.startsWith("DELETE ") })
            Log.i(TAG, "calls=${server.calls} heartbeats=${server.heartbeats.get()} states=${states.map { it.first }}")
            assertEquals(BroadcastState.IDLE, states.last().first)
        } finally {
            pumping.set(false)
            pump.join(2_000)
            created.close(null)
            scenario.close()
        }
    }

    private fun serviceRunning(): Boolean {
        val pfd = instrumentation.uiAutomation.executeShellCommand("dumpsys activity services ${context.packageName}")
        val output = ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        return output.contains("DirectBroadcastService")
    }

    private fun waitFor(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            assertTrue("$what 대기 시간 초과", System.nanoTime() < deadline)
            Thread.sleep(50)
        }
    }

    private fun image(timestampNs: Long, width: Int = 1280, height: Int = 720, yValue: Int): ImageProxy {
        fun plane(data: ByteBuffer, rowStride: Int): ImageProxy.PlaneProxy =
            Proxy.newProxyInstance(ImageProxy.PlaneProxy::class.java.classLoader,
                arrayOf(ImageProxy.PlaneProxy::class.java)) { _, method, _ ->
                when (method.name) {
                    "getBuffer" -> data
                    "getRowStride" -> rowStride
                    "getPixelStride" -> 1
                    else -> error("Unexpected plane method: ${method.name}")
                }
            } as ImageProxy.PlaneProxy
        fun filled(size: Int, value: Int) = ByteBuffer.allocateDirect(size).apply {
            repeat(size) { put(value.toByte()) }
            flip()
        }
        val planes = arrayOf(
            plane(filled(width * height, yValue), width),
            plane(filled(width * height / 4, 128), width / 2),
            plane(filled(width * height / 4, 128), width / 2),
        )
        val info = Proxy.newProxyInstance(ImageInfo::class.java.classLoader, arrayOf(ImageInfo::class.java)) { _, method, _ ->
            when (method.name) {
                "getRotationDegrees" -> 0
                "getTimestamp" -> timestampNs
                else -> error("Unexpected image info method: ${method.name}")
            }
        } as ImageInfo
        return Proxy.newProxyInstance(ImageProxy::class.java.classLoader, arrayOf(ImageProxy::class.java)) { _, method, _ ->
            when (method.name) {
                "getWidth" -> width
                "getHeight" -> height
                "getCropRect" -> Rect(0, 0, width, height)
                "getPlanes" -> planes
                "getImageInfo" -> info
                "close" -> Unit
                else -> error("Unexpected image method: ${method.name}")
            }
        } as ImageProxy
    }

    private companion object {
        const val TAG = "DirectBroadcastIT"

        /** 복구 저장소 범위가 JWT `sub`를 읽으므로 형식만 맞춘 서명 없는 테스트 값입니다. 실제 인증 정보가 아닙니다. */
        const val TEST_ACCESS_TOKEN = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJkaXJlY3QtaXQtdXNlciJ9.test"
    }
}
