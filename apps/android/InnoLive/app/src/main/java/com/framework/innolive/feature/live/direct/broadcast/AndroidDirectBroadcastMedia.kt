package com.framework.innolive.feature.live.direct.broadcast

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.framework.innolive.feature.live.CameraFrameAnalyzer
import com.framework.innolive.feature.live.CreatedSession
import com.framework.innolive.feature.live.LiveBroadcastTransport
import com.framework.innolive.feature.live.LiveBroadcastTransportRequest
import com.framework.innolive.feature.live.SessionRecoveryScope
import com.framework.innolive.feature.live.SessionRecoveryStore
import com.framework.innolive.feature.live.EncryptedSessionRecoveryStore
import com.framework.innolive.feature.live.findBluetoothCommunicationDevice
import com.framework.innolive.feature.live.isBluetoothAudioInputType
import com.framework.innolive.feature.live.direct.DirectPublishApi
import com.framework.innolive.feature.live.direct.publish.AssetSlateSource
import com.framework.innolive.feature.live.direct.publish.DirectAudioInput
import com.framework.innolive.feature.live.direct.publish.DirectPublishEngine
import com.framework.innolive.feature.live.direct.publish.DirectPublishProfile
import com.framework.innolive.feature.live.direct.publish.DirectPublisher
import com.framework.innolive.feature.live.direct.publish.MicrophoneFactory
import com.framework.innolive.feature.live.direct.publish.MicrophonePcmSource
import com.framework.innolive.feature.live.direct.publish.MicrophoneState
import com.framework.innolive.feature.live.direct.publish.PcmSink
import com.framework.innolive.feature.live.direct.publish.PcmSource
import com.framework.innolive.feature.live.direct.publish.PublishOrientation
import com.framework.innolive.feature.live.privacy.PrivacyFaceCoordinator
import com.framework.innolive.feature.live.sessionRecoveryScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.webrtc.EglBase
import org.webrtc.VideoSink
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 실제 서버·저장소·카메라·송출 엔진으로 상태 머신 의존성을 만듭니다. */
internal fun androidDirectBroadcastEnvironment(
    request: LiveBroadcastTransportRequest,
    dependencies: DirectBroadcastDependencies,
    /** 서버 API를 만듭니다. 기기 통합 테스트만 가짜 응답을 주는 클라이언트로 바꿉니다. */
    apiFactory: (LiveBroadcastTransportRequest) -> DirectPublishApi =
        { DirectPublishApi(it.serverUrl, it.accessToken, it.refreshAccessToken) },
): DirectBroadcastEnvironment {
    val api = apiFactory(request)
    // 서버 중계와 같은 범위를 써야 어느 경로로 남긴 세션이든 다음 시작 때 지웁니다.
    val scope = sessionRecoveryScope(request.serverUrl.trim().trimEnd('/').toHttpUrl().toString(), request.accessToken)
    val mainHandler = Handler(Looper.getMainLooper())
    return DirectBroadcastEnvironment(
        server = api,
        recovery = StoreSessionRecovery(EncryptedSessionRecoveryStore(request.context), scope),
        media = AndroidDirectMediaPipeline(request, scope),
        heartbeat = { inputs, listener -> dependencies.heartbeat(api, inputs, listener) },
        reconnect = dependencies.reconnect,
        keepAlive = dependencies.keepAlive(request.context),
        owner = ExecutorSerialScheduler(Executors.newSingleThreadScheduledExecutor { Thread(it, "direct-broadcast") }),
        io = ExecutorBackgroundExecutor(
            Executors.newSingleThreadExecutor { Thread(it, "direct-broadcast-io").apply { isDaemon = true } },
        ),
        callbacks = { mainHandler.post(it) },
        elapsedRealtimeMillis = SystemClock::elapsedRealtime,
        log = { Log.i(LOG_TAG, it) },
    )
}

/**
 * 플랫폼 없이 온디바이스 가림 처리 미리보기만 여는 송출 경로를 만듭니다. 서버 API·세션 기록을 만들지 않으며,
 * 등록 얼굴은 직접 송출과 같은 계정 범위에서 읽습니다.
 */
internal fun createOnDevicePreviewTransport(request: LiveBroadcastTransportRequest): LiveBroadcastTransport {
    val scope = sessionRecoveryScope(request.serverUrl.trim().trimEnd('/').toHttpUrl().toString(), request.accessToken)
    val mainHandler = Handler(Looper.getMainLooper())
    return OnDevicePreviewTransport(
        callbacks = request.callbacks,
        media = AndroidDirectMediaPipeline(request, scope),
        initialAnonymizationEnabled = request.initialAnonymizationEnabled,
        owner = ExecutorSerialScheduler(Executors.newSingleThreadScheduledExecutor { Thread(it, "on-device-preview") }),
        callbackExecutor = { mainHandler.post(it) },
        log = { Log.i(PREVIEW_LOG_TAG, it) },
    )
}

private class StoreSessionRecovery(
    private val store: SessionRecoveryStore,
    private val scope: SessionRecoveryScope,
) : DirectSessionRecovery {
    override fun load(): CreatedSession? = store.load(scope)
    override fun loadLegacy(): CreatedSession? = store.loadLegacy()
    override fun save(session: CreatedSession) = store.save(session, scope)
    override fun clear() = store.clear(scope)
    override fun clearLegacy() = store.clearLegacy()
}

/**
 * 직접 송출의 카메라·가림 처리·송출 엔진입니다. WebRTC 오디오 모듈 없이 마이크를 따로 읽습니다.
 * 가림 처리 출력만 엔진으로 넘기며, 서버가 가림 처리할 원본은 분석기가 넘기지 않습니다.
 */
private class AndroidDirectMediaPipeline(
    request: LiveBroadcastTransportRequest,
    private val scope: SessionRecoveryScope,
) : DirectMediaPipeline {
    private val context: Context = request.context
    private val callbacks = request.callbacks
    private val initialAnonymizationEnabled = request.initialAnonymizationEnabled
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val slateSource = AssetSlateSource(context)
    private val firstFrame = AtomicBoolean(false)
    @Volatile private var preferredAudioInput: AudioDeviceInfo? = request.preferredAudioInput
    @Volatile private var latestOrientation: PublishOrientation? = null
    @Volatile private var engineSink: VideoSink? = null
    @Volatile private var audio: DirectAudioInput? = null
    private var eglBase: EglBase? = null
    private var analyzer: CameraFrameAnalyzer? = null
    private var events: DirectMediaEvents? = null

    override val frameOrientation: PublishOrientation
        get() = latestOrientation ?: if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
            PublishOrientation.PORTRAIT else PublishOrientation.LANDSCAPE

    override val anonymizationFailedFrames: Long get() = analyzer?.anonymizationFailedFrames ?: 0

    override fun open(events: DirectMediaEvents) {
        check(analyzer == null) { "이미 카메라 입력을 열었습니다." }
        this.events = events
        val egl = EglBase.create().also { eglBase = it }
        val created = CameraFrameAnalyzer(
            capturerObserver = null,
            applicationContext = context,
            initialOnDevice = true,
            initialAnonymizationEnabled = initialAnonymizationEnabled,
            onProcessingFailure = events::onProcessingFailure,
            sharedEglContext = egl.eglBaseContext,
        ).also { it.faceCoordinatorFactory = { PrivacyFaceCoordinator(context, scope.storageKey) } }
        analyzer = created
        created.setDirectPublishSink { frame ->
            latestOrientation = if (frame.rotatedWidth >= frame.rotatedHeight) PublishOrientation.LANDSCAPE
                else PublishOrientation.PORTRAIT
            if (firstFrame.compareAndSet(false, true)) events.onFirstFrame()
            engineSink?.onFrame(frame)
        }
        updateBluetoothRoute(preferredAudioInput)
        callbacks.onLocalMediaReady(created, egl.eglBaseContext)
        created.start()
    }

    override fun setAnonymizationEnabled(enabled: Boolean) {
        checkNotNull(analyzer).setProcessingMode(onDevice = true, anonymizationEnabled = enabled)
    }

    override fun setPreferredAudioInput(device: AudioDeviceInfo?) {
        preferredAudioInput = device
        updateBluetoothRoute(device)
        audio?.setPreferredDevice(device)
    }

    override fun createEngine(profile: DirectPublishProfile, listener: DirectPublisher.Listener): DirectPublishEngine {
        val egl = checkNotNull(eglBase) { "카메라 입력을 열지 않았습니다." }
        audio?.close()
        val microphone = MicrophoneFactory { device, onError, onRouteChanged ->
            openMicrophone(profile, device, onError, onRouteChanged)
        }
        val input = DirectAudioInput(profile, microphone, preferredAudioInput, onState = { state ->
            events?.onMicrophoneStatus(when (state) {
                MicrophoneState.OK -> MicrophoneStatus.OK
                MicrophoneState.UNAVAILABLE -> MicrophoneStatus.UNAVAILABLE
                MicrophoneState.SILENCED -> MicrophoneStatus.SILENCED
            })
        })
        val publisher = DirectPublisher(profile, egl.eglBaseContext, input, listener, slateSource)
        publisher.start()
        audio = input
        engineSink = publisher.frameSink
        return publisher
    }

    /** 권한이 없거나 열 수 없으면 예외를 던지고, 음성 입력이 무음으로 채웁니다. */
    private fun openMicrophone(
        profile: DirectPublishProfile,
        device: AudioDeviceInfo?,
        onError: (Exception) -> Unit,
        onRouteChanged: (Int?, Boolean) -> Unit,
    ): PcmSource {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("마이크 권한이 없습니다.")
        }
        return PermittedMicrophone(MicrophonePcmSource(profile, device, onError = onError, onRouteChanged = onRouteChanged))
    }

    /** 블루투스 마이크는 통화 장치로 지정해야 SCO 경로가 열립니다. 실패하면 기본 마이크로 받습니다. */
    private fun updateBluetoothRoute(device: AudioDeviceInfo?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            if (device == null || !isBluetoothAudioInputType(device.type)) {
                audioManager.clearCommunicationDevice()
                return
            }
            val communication = findBluetoothCommunicationDevice(device, audioManager.availableCommunicationDevices)
            check(communication != null && audioManager.setCommunicationDevice(communication)) {
                "Bluetooth 통화 장치를 지정하지 못했습니다."
            }
        }.onFailure { Log.w(LOG_TAG, "bluetooth_route_failed type=${it.javaClass.simpleName}") }
    }

    override fun close() {
        engineSink = null
        audio = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { audioManager.clearCommunicationDevice() }
        val current = analyzer ?: return
        analyzer = null
        runCatching { callbacks.onLocalMediaCleared() }
        runCatching { current.close() }
        runCatching { slateSource.close() }
        runCatching { eglBase?.release() }
        eglBase = null
    }
}

/** 권한을 확인한 뒤에만 만듭니다. */
private class PermittedMicrophone(private val source: MicrophonePcmSource) : PcmSource {
    @SuppressLint("MissingPermission")
    override fun start(sink: PcmSink) = source.start(sink)

    override fun close() = source.close()
}

private const val LOG_TAG = "DirectBroadcast"
private const val PREVIEW_LOG_TAG = "OnDevicePreview"
