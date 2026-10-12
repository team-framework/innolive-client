package com.framework.innolive.feature.live.direct.publish

import android.Manifest
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.CameraFrameAnalyzer
import com.framework.innolive.feature.live.direct.DirectPublishResolution
import com.framework.innolive.feature.live.direct.IngestUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.CapturerObserver
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.X509TrustManager
import kotlin.math.PI
import kotlin.math.sin

/**
 * 직접 송출 엔진 기기 테스트입니다.
 *
 * 실제 송출 테스트는 `-e directPublishUrl rtmp://127.0.0.1:1935/live/<key>`로 켭니다. 호스트에서
 * `adb reverse tcp:1935 tcp:1935`와 RTMP 수신 서버(예: `ffmpeg -listen 1 -i rtmp://0.0.0.0:1935/live/<key> -c copy out.flv`)를
 * 먼저 띄웁니다. 영상은 시간에 따라 밝기가 올라가고 음성은 440Hz 사인파라 수신 파일로 확인할 수 있습니다.
 */
@RunWith(AndroidJUnit4::class)
class DirectPublisherDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    private fun initializeWebRtc() = PeerConnectionFactory.initialize(
        PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())

    @Test fun onlyAnonymizedOrUserUnprotectedFramesReachDirectPublish() {
        initializeWebRtc()
        val root = EglBase.create()
        try {
            for ((onDevice, anonymization, expected) in listOf(
                Triple(false, true, 0), Triple(true, false, 3), Triple(true, true, 3),
            )) {
                val published = AtomicInteger()
                val captured = AtomicInteger()
                val analyzer = CameraFrameAnalyzer(countingObserver(captured), context,
                    initialOnDevice = onDevice, initialAnonymizationEnabled = anonymization,
                    sharedEglContext = root.eglBaseContext)
                analyzer.setDirectPublishSink(VideoSink { published.incrementAndGet() })
                analyzer.start()
                try {
                    if (onDevice && anonymization) assertTrue(analyzer.awaitProtectedPreparation())
                    repeat(3) { index ->
                        val input = image(System.nanoTime(), yValue = 60 + index)
                        analyzer.analyze(input.first)
                        assertTrue(input.second.await(5, TimeUnit.SECONDS))
                        waitUntil(5_000) { captured.get() > index }
                    }
                    assertEquals("captured onDevice=$onDevice anonymization=$anonymization", 3, captured.get())
                    assertEquals("published onDevice=$onDevice anonymization=$anonymization", expected, published.get())
                    assertEquals(0L, analyzer.anonymizationFailedFrames)
                } finally {
                    analyzer.close()
                }
            }
        } finally {
            root.release()
        }
    }

    @Test fun publishesAnonymizedFramesAndAudioToRtmpServer() {
        val url = arguments.getString("directPublishUrl")
        assumeTrue("directPublishUrl 인자가 없어 실제 송출을 건너뜁니다.", url != null)
        publishAnonymizedFrames(checkNotNull(url), trustManager = null)
    }

    /**
     * RTMPS(TLS) 송출입니다. `-e directPublishRtmpsUrl rtmps://127.0.0.1:1936/live/<key>`로 켭니다.
     * 호스트의 TLS 종단 프록시가 자체 서명 인증서를 쓰므로 이 테스트에서만 인증서 검증을 끕니다.
     */
    @Test fun publishesOverRtmpsToTlsServer() {
        val url = arguments.getString("directPublishRtmpsUrl")
        assumeTrue("directPublishRtmpsUrl 인자가 없어 RTMPS 송출을 건너뜁니다.", url != null)
        assertTrue(IngestUrl.parse(url).isSecure)
        publishAnonymizedFrames(checkNotNull(url), trustManager = TrustAllForLocalTest)
    }

    private fun publishAnonymizedFrames(url: String, trustManager: X509TrustManager?) {
        val seconds = arguments.getString("directPublishSeconds")?.toLongOrNull() ?: 10L
        initializeWebRtc()
        val root = EglBase.create()
        val failures = CopyOnWriteArrayList<Exception>()
        // `-e directPublishSecondUrl`이 있으면 같은 인코딩을 치지직 대상으로도 보내 동시 송출을 확인합니다.
        val secondUrl = arguments.getString("directPublishSecondUrl")
        val publishing = CountDownLatch(if (secondUrl == null) 1 else 2)
        val profile = DirectPublishProfile.of(DirectPublishResolution.HD_720, PublishOrientation.LANDSCAPE)
        val publisher = DirectPublisher(profile, root.eglBaseContext, SinePcmSource(profile),
            object : DirectPublisher.Listener {
                override fun onTargetStateChanged(provider: BroadcastProvider, state: RtmpTargetState, failure: RtmpFailure?) {
                    Log.i(TAG, "target=$provider state=$state failure=$failure")
                    if (state == RtmpTargetState.PUBLISHING) publishing.countDown()
                }

                override fun onPublisherFailed(error: Exception) {
                    failures += error
                }
            },
            targetFactory = { provider, ingestUrl, onChanged ->
                RtmpPublishTarget(provider, ingestUrl, profile, onChanged, trustManager)
            })
        val analyzer = CameraFrameAnalyzer(null, context, initialOnDevice = true,
            sharedEglContext = root.eglBaseContext)
        try {
            publisher.start()
            analyzer.start()
            assertTrue(analyzer.awaitProtectedPreparation())
            analyzer.setDirectPublishSink(publisher.frameSink)
            publisher.connect(BroadcastProvider.YOUTUBE, IngestUrl.parse(url))
            secondUrl?.let { publisher.connect(BroadcastProvider.CHZZK, IngestUrl.parse(it)) }
            assertTrue("RTMP 서버에 연결하지 못했습니다.", publishing.await(15, TimeUnit.SECONDS))
            val frameCount = (seconds * profile.fps).toInt()
            val intervalNs = 1_000_000_000L / profile.fps
            val startedNs = System.nanoTime()
            repeat(frameCount) { index ->
                val dueNs = startedNs + index * intervalNs
                while (System.nanoTime() < dueNs) Thread.sleep(1)
                // 밝기가 시간에 따라 올라가 수신 영상에서 프레임 진행을 확인할 수 있습니다.
                val input = image(System.nanoTime(), width = 1280, height = 720,
                    yValue = 40 + 160 * index / frameCount)
                analyzer.analyze(input.first)
                // 처음 2초의 인코더 준비 구간은 fps 평균에서 뺍니다.
                if (index == profile.fps * 2) publisher.stats()
            }
            val stats = publisher.stats()
            Log.i(TAG, "stats fps=${stats.fps} dropped=${stats.droppedFrames} targets=${stats.targets}")
            assertTrue("failures=$failures", failures.isEmpty())
            assertTrue("fps=${stats.fps}", stats.fps >= profile.fps * 0.6)
            assertEquals(if (secondUrl == null) 1 else 2, stats.targets.size)
            assertTrue(stats.targets.all { it.connected })
            assertEquals(0L, analyzer.anonymizationFailedFrames)
        } finally {
            analyzer.close()
            publisher.close()
            root.release()
        }
    }

    @Test fun microphoneDeliversIncreasingTimestamps() {
        assumeTrue("directPublishMicrophone 인자가 없어 마이크 테스트를 건너뜁니다.",
            arguments.getString("directPublishMicrophone") == "true")
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val profile = DirectPublishProfile.of(DirectPublishResolution.HD_720, PublishOrientation.LANDSCAPE)
        val timestamps = CopyOnWriteArrayList<Long>()
        val bytes = AtomicInteger()
        val source = MicrophonePcmSource(profile)
        try {
            source.start { _, size, presentationTimeUs ->
                timestamps += presentationTimeUs
                bytes.addAndGet(size)
            }
            Thread.sleep(1_000)
        } finally {
            source.close()
        }
        assertTrue("chunks=${timestamps.size}", timestamps.size >= 50)
        assertTrue(timestamps.zipWithNext().all { (a, b) -> b > a })
        // 1초 동안 48kHz·16비트 모노는 약 96KB입니다.
        assertTrue("bytes=${bytes.get()}", bytes.get() in 70_000..130_000)
    }

    /** 440Hz 사인파를 실제 시간 속도로 보내는 합성 음원입니다. */
    private class SinePcmSource(private val profile: DirectPublishProfile) : PcmSource {
        @Volatile private var running = false
        private var thread: Thread? = null

        override fun start(sink: PcmSink) {
            running = true
            thread = Thread {
                val samplesPerChunk = profile.audioSampleRate / 100
                val chunk = ByteArray(samplesPerChunk * 2)
                var sample = 0L
                val startedNs = System.nanoTime()
                while (running) {
                    val ptsUs = startedNs / 1000 + sample * 1_000_000L / profile.audioSampleRate
                    for (i in 0 until samplesPerChunk) {
                        val value = (sin(2 * PI * 440 * (sample + i) / profile.audioSampleRate) * 12_000).toInt()
                        chunk[i * 2] = value.toByte()
                        chunk[i * 2 + 1] = (value shr 8).toByte()
                    }
                    sink.onPcm(chunk, chunk.size, ptsUs)
                    sample += samplesPerChunk
                    val nextNs = startedNs + sample * 1_000_000_000L / profile.audioSampleRate
                    while (running && System.nanoTime() < nextNs) Thread.sleep(1)
                }
            }.apply { start() }
        }

        override fun close() {
            running = false
            thread?.join(500)
        }
    }

    private object TrustAllForLocalTest : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun countingObserver(counter: AtomicInteger) = object : CapturerObserver {
        override fun onCapturerStarted(success: Boolean) = Unit
        override fun onCapturerStopped() = Unit
        override fun onFrameCaptured(frame: VideoFrame) {
            counter.incrementAndGet()
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
    }

    private fun image(timestampNs: Long, width: Int = 640, height: Int = 480, yValue: Int = 114):
        Pair<ImageProxy, CountDownLatch> {
        val closed = CountDownLatch(1)
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
        val proxy = Proxy.newProxyInstance(ImageProxy::class.java.classLoader, arrayOf(ImageProxy::class.java)) { _, method, _ ->
            when (method.name) {
                "getWidth" -> width
                "getHeight" -> height
                "getCropRect" -> Rect(0, 0, width, height)
                "getPlanes" -> planes
                "getImageInfo" -> info
                "close" -> { closed.countDown(); Unit }
                else -> error("Unexpected image method: ${method.name}")
            }
        } as ImageProxy
        return proxy to closed
    }

    private companion object {
        const val TAG = "DirectPublishTest"
    }
}
