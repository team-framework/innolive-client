package com.framework.innolive.feature.live

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaStreamTrack
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class WebRtcConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    FAILED,
}

class WebRtcConnection(
    context: Context,
    serverUrl: String,
    private val accessToken: String,
    private var preferredAudioInput: AudioDeviceInfo?,
    private val onStateChanged: (WebRtcConnectionState, String) -> Unit,
    private val onRemoteTrackChanged: (VideoTrack?) -> Unit,
    private val onLocalMediaReady: (CameraFrameAnalyzer, EglBase.Context) -> Unit,
    private val onLocalMediaCleared: () -> Unit,
    private val onBroadcastStateChanged: (BroadcastState, String) -> Unit,
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val serverBaseUrl = serverUrl.trim().trimEnd('/').toHttpUrl().also { url ->
        require(url.isHttps) { "INNOLIVE_SERVER_URL must use HTTPS." }
    }
    private val audioManager = applicationContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    /**
     * The owner for all WebRTC, signaling, audio-route, and teardown work.
     * Callback threads only enqueue work here; they never touch native state.
     */
    private val ownerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val timerExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor()
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val terminal = AtomicBoolean(false)
    private val broadcastOperation = AtomicBoolean(false)
    private val closeSignal = CloseSignal()
    private val shutdownLock = Any()
    private val shutdownInitiated = AtomicBoolean(false)
    private val closeCallbacks = mutableListOf<() -> Unit>()
    private var shutdownFinished = false
    private val activeHttpCalls = ConcurrentHashMap.newKeySet<Call>()
    private val audioRouteMonitor = AudioInputRouteMonitor(
        applicationContext,
        ::onAudioRouteChanged,
    )
    private val signalLock = Any()
    private val pendingSignals = mutableListOf<String>()
    private var connectionTimeoutTask: ScheduledFuture<*>? = null
    private var audioRouteVerificationTask: ScheduledFuture<*>? = null

    @Volatile
    private var eglBase: EglBase? = null

    @Volatile
    private var audioDeviceModule: JavaAudioDeviceModule? = null

    @Volatile
    private var peerConnectionFactory: PeerConnectionFactory? = null

    @Volatile
    private var audioSource: org.webrtc.AudioSource? = null

    @Volatile
    private var localAudioTrack: org.webrtc.AudioTrack? = null

    @Volatile
    private var videoSource: org.webrtc.VideoSource? = null

    @Volatile
    private var localVideoTrack: org.webrtc.VideoTrack? = null

    @Volatile
    private var frameAnalyzer: CameraFrameAnalyzer? = null

    @Volatile
    private var peerConnection: PeerConnection? = null

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var offerSent = false

    @Volatile
    private var session: CreatedSession? = null

    private var audioRecordingStarted = false
    private var audioInputVerified = false
    private var audioRouteRetryAttempted = false
    private var actualAudioInputId: Int? = null
    private var actualAudioInputSilenced = false
    private var peerConnectionConnected = false
    private val resourcesReleased = AtomicBoolean(false)

    @Volatile
    private var broadcastState = BroadcastState.IDLE

    init {
        require(accessToken.isNotBlank()) { "Access token must not be blank." }
    }

    fun start() {
        if (
            !started.compareAndSet(false, true) ||
            closed.get() ||
            terminal.get()
        ) {
            return
        }

        executeOnOwner {
            try {
                if (!isActive()) return@executeOnOwner

                initializeNativeResourcesOnOwner()
                if (!isActive()) return@executeOnOwner
                notifyLocalMediaReadyOnOwner()
                updateBluetoothCommunicationRoute(preferredAudioInput)
                audioRouteMonitor.start()
                updateState(WebRtcConnectionState.CONNECTING, "WebRTC 연결 준비 중")
                connectionTimeoutTask = timerExecutor.schedule(
                    { fail("WebRTC 연결 시간이 초과되었습니다.") },
                    CONNECTION_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS,
                )

                val iceServers = loadIceServers()
                if (!isActive()) return@executeOnOwner

                val createdSession = createSession()
                session = createdSession
                if (!isActive()) return@executeOnOwner

                val connection = createPeerConnection(iceServers)
                peerConnection = connection
                if (!isActive()) return@executeOnOwner

                addAudioTransceiver(connection)
                if (!isActive()) return@executeOnOwner
                addVideoTransceiver(connection)
                if (!isActive()) return@executeOnOwner
                checkNotNull(frameAnalyzer).start()
                openSignalingSocket(createdSession)
            } catch (exception: Exception) {
                fail(exception.message ?: "WebRTC 연결을 시작하지 못했습니다.")
            }
        }
    }

    override fun close() {
        close(null)
    }

    /**
     * Closes this connection and invokes [onComplete] after the owner has
     * released every resource. The callback is useful when a failed session
     * must finish teardown before a retry creates its replacement.
     */
    fun close(onComplete: (() -> Unit)?) {
        var callbackAfterShutdown: (() -> Unit)? = null
        val shouldStartShutdown = synchronized(shutdownLock) {
            onComplete?.let { callback ->
                if (shutdownFinished) {
                    callbackAfterShutdown = callback
                } else {
                    closeCallbacks += callback
                }
            }
            closed.set(true)
            terminal.set(true)
            if (shutdownInitiated.compareAndSet(false, true)) {
                closeSignal.close()
                cancelHttpRequests()
                true
            } else {
                false
            }
        }

        callbackAfterShutdown?.let(::invokeCloseCallback)
        if (shouldStartShutdown) enqueueResourceRelease()
    }

    private fun initializeNativeResourcesOnOwner() {
        if (peerConnectionFactory != null) return

        eglBase = EglBase.create()
        audioDeviceModule = createAudioDeviceModule()
        val factory = createPeerConnectionFactory()
        peerConnectionFactory = factory
        val createdAudioSource = factory.createAudioSource(MediaConstraints())
        audioSource = createdAudioSource
        localAudioTrack = factory.createAudioTrack("microphone-audio", createdAudioSource)
        val createdVideoSource = factory.createVideoSource(false)
        videoSource = createdVideoSource
        localVideoTrack = factory.createVideoTrack("camera-video", createdVideoSource)
        frameAnalyzer = CameraFrameAnalyzer(createdVideoSource.capturerObserver)
    }

    private fun notifyLocalMediaReadyOnOwner() {
        val analyzer = frameAnalyzer ?: return
        val context = eglBase?.eglBaseContext ?: return
        onLocalMediaReady(analyzer, context)
    }

    private fun createPeerConnectionFactory(): PeerConnectionFactory {
        val eglContext = checkNotNull(this@WebRtcConnection.eglBase).eglBaseContext
        val createdAudioDeviceModule =
            checkNotNull(this@WebRtcConnection.audioDeviceModule)
        if (factoryInitialized.compareAndSet(false, true)) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(applicationContext)
                    .createInitializationOptions(),
            )
        }
        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(createdAudioDeviceModule)
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(eglContext, true, true),
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .createPeerConnectionFactory()
    }

    fun setPreferredAudioInput(audioInput: AudioDeviceInfo?) {
        executeOnOwner {
            if (!isActive()) return@executeOnOwner
            preferredAudioInput = audioInput
            if (!started.get()) return@executeOnOwner

            try {
                updateBluetoothCommunicationRoute(audioInput)
            } catch (exception: Exception) {
                fail(exception.message ?: "Bluetooth 오디오 기기를 준비하지 못했습니다.")
                return@executeOnOwner
            }
            audioInput?.let { audioDeviceModule?.setPreferredInputDevice(it) }
            resetAudioRouteVerification()
        }
    }

    fun saveBroadcastSettings(settings: BroadcastSettings) {
        runBroadcastOperation {
            require(settings.madeForKids != null) { "아동용 콘텐츠 여부를 선택해 주세요." }
            updateBroadcastState(BroadcastState.SAVING_SETTINGS, "방송 설정 저장 중")
            putBroadcastSettings(settings)
            updateBroadcastState(BroadcastState.IDLE, "방송 설정 저장됨")
        }
    }

    fun prepareBroadcast(settings: BroadcastSettings) {
        if (!broadcastState.canPrepare) return
        runBroadcastOperation {
            require(settings.madeForKids != null) { "아동용 콘텐츠 여부를 선택해 주세요." }
            updateBroadcastState(BroadcastState.SAVING_SETTINGS, "방송 설정 저장 중")
            putBroadcastSettings(settings)
            updateBroadcastState(BroadcastState.PREPARING, "YouTube 방송 준비 중")
            postSessionRequest("stream/prepare", JSONObject().put("provider", "youtube"))
            updateBroadcastState(BroadcastState.PREPARED, "YouTube 라이브 전환 대기 중")
        }
    }

    fun goLive() {
        if (!broadcastState.canGoLive) return
        runBroadcastOperation {
            updateBroadcastState(BroadcastState.GOING_LIVE, "YouTube 라이브 전환 중")
            try {
                goLiveWithRetry()
                updateBroadcastState(BroadcastState.LIVE, "YouTube 방송 중")
            } catch (exception: ServerApiException) {
                if (exception.code == "broadcast_not_ready") {
                    updateBroadcastState(BroadcastState.PREPARED, exception.message.orEmpty())
                    return@runBroadcastOperation
                }
                throw exception
            }
        }
    }

    fun stopBroadcast() {
        if (!broadcastState.canStop) return
        runBroadcastOperation {
            updateBroadcastState(BroadcastState.STOPPING, "YouTube 방송 종료 중")
            postSessionRequest("stream/stop")
            updateBroadcastState(BroadcastState.IDLE, "YouTube 방송 종료됨")
        }
    }

    private fun runBroadcastOperation(operation: () -> Unit) {
        if (!isActive() || !broadcastOperation.compareAndSet(false, true)) return
        executeOnOwner(
            block = {
                try {
                    if (!isActive()) return@executeOnOwner
                    check(session != null) { "WebRTC 세션이 없습니다." }
                    operation()
                } catch (exception: Exception) {
                    updateBroadcastState(
                        BroadcastState.FAILED,
                        exception.message ?: "방송 요청을 처리하지 못했습니다.",
                    )
                } finally {
                    broadcastOperation.set(false)
                }
            },
            onRejected = {
                broadcastOperation.set(false)
            },
        )
    }

    private fun putBroadcastSettings(settings: BroadcastSettings) {
        executeSessionRequest("broadcast", "PUT", buildBroadcastSettingsPayload(settings))
    }

    private fun postSessionRequest(path: String, body: JSONObject = JSONObject()) {
        executeSessionRequest(path, "POST", body)
    }

    private fun executeSessionRequest(path: String, method: String, body: JSONObject) {
        val createdSession = checkNotNull(session) { "WebRTC 세션이 없습니다." }
        val request = authenticatedRequest("/sessions/${createdSession.sessionId}/$path")
            .header("X-Session-Owner-Token", createdSession.ownerToken)
            .method(method, body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        executeHttp(request).use { response ->
            val payload = response.body.string()
            if (!response.isSuccessful) throw parseServerApiException(response.code, payload)
        }
    }

    private fun goLiveWithRetry() {
        repeat(GO_LIVE_RETRY_COUNT) { attempt ->
            check(isActive()) { "WebRTC 연결이 종료되었습니다." }
            try {
                postSessionRequest("stream/golive")
                return
            } catch (exception: ServerApiException) {
                if (exception.code != "broadcast_not_ready") throw exception
                if (attempt < GO_LIVE_RETRY_COUNT - 1) {
                    if (closeSignal.await(GO_LIVE_RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS)) {
                        throw IOException("WebRTC 연결이 종료되었습니다.")
                    }
                }
            }
        }
        throw ServerApiException(
            "broadcast_not_ready",
            "YouTube가 아직 영상을 받을 준비가 되지 않았습니다. 잠시 후 다시 시도해 주세요.",
        )
    }

    private fun updateBluetoothCommunicationRoute(audioInput: AudioDeviceInfo?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        if (!isBluetoothAudioInput(audioInput)) {
            audioManager.clearCommunicationDevice()
            return
        }

        val bluetoothInput = checkNotNull(audioInput)
        val communicationDevice = findBluetoothCommunicationDevice(
            bluetoothInput,
            audioManager.availableCommunicationDevices,
        )
            ?: throw IllegalStateException("선택한 Bluetooth 오디오 기기의 통신용 출력을 찾지 못했습니다.")
        check(audioManager.setCommunicationDevice(communicationDevice)) {
            "선택한 Bluetooth 오디오 기기를 통신 장치로 설정하지 못했습니다."
        }
    }

    private fun isBluetoothAudioInput(audioInput: AudioDeviceInfo?): Boolean =
        audioInput?.let { input -> isBluetoothAudioInputType(input.type) } == true

    private fun clearBluetoothCommunicationRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
    }

    private fun createAudioDeviceModule(): JavaAudioDeviceModule = JavaAudioDeviceModule
        .builder(applicationContext)
        .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        .setAudioRecordStateCallback(
            object : JavaAudioDeviceModule.AudioRecordStateCallback {
                override fun onWebRtcAudioRecordStart() {
                    executeOnOwner {
                        if (!isActive()) return@executeOnOwner

                        audioRecordingStarted = true
                        resetAudioRouteVerification()
                    }
                }

                override fun onWebRtcAudioRecordStop() {
                    executeOnOwner {
                        audioRecordingStarted = false
                        audioRouteVerificationTask?.cancel(false)
                        audioRouteVerificationTask = null
                        if (isActive()) {
                            fail("오디오 입력이 중지되었습니다.")
                        }
                    }
                }
            },
        )
        .createAudioDeviceModule()
        .also { createdAudioDeviceModule ->
            preferredAudioInput?.let(createdAudioDeviceModule::setPreferredInputDevice)
    }

    private fun addAudioTransceiver(connection: PeerConnection) {
        val audioTrack = checkNotNull(this@WebRtcConnection.localAudioTrack)
        val audioTransceiver = checkNotNull(
            connection.addTransceiver(
                audioTrack,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                ),
            ),
        ) { "Unable to add the microphone audio transceiver." }
        val opusCodecs = checkNotNull(peerConnectionFactory)
            .getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO)
            .codecs
            .filter { codec -> codec.name.equals("opus", ignoreCase = true) }
        require(opusCodecs.isNotEmpty()) { "Opus audio codec is unavailable." }
        check(audioTransceiver.setCodecPreferences(opusCodecs).isSuccess()) {
            "Unable to set the Opus audio codec."
        }
    }

    private fun addVideoTransceiver(connection: PeerConnection) {
        val videoTrack = checkNotNull(this@WebRtcConnection.localVideoTrack)
        check(
            connection.addTransceiver(
                videoTrack,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                ),
            ) != null,
        ) { "Unable to add the camera video transceiver." }
    }

    private fun loadIceServers(): List<PeerConnection.IceServer> {
        val request = authenticatedRequest("/webrtc/config").get().build()
        return executeHttp(request).use { response ->
            requireSuccessful(response, "ICE 서버 설정 조회")
            parseIceServers(response.body.string())
        }
    }

    private fun createSession(): CreatedSession {
        val requestBody = JSONObject()
            .put(
                "metadata",
                JSONObject().put("client", "innolive-android"),
            )
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = authenticatedRequest("/sessions")
            .post(requestBody)
            .build()

        return executeHttp(request).use { response ->
            requireSuccessful(response, "WebRTC 세션 생성")
            parseCreatedSession(response.body.string())
        }
    }

    private fun createPeerConnection(
        iceServers: List<PeerConnection.IceServer>,
    ): PeerConnection = checkNotNull(
        checkNotNull(peerConnectionFactory).createPeerConnection(
            PeerConnection.RTCConfiguration(iceServers),
            peerConnectionObserver,
        ),
    ) { "Unable to create the WebRTC peer connection." }

    private fun openSignalingSocket(createdSession: CreatedSession) {
        if (!isActive()) return

        val httpUrl = checkNotNull(serverBaseUrl.resolve("/signaling"))
        val signalingUrl = httpUrl.toString().replaceFirst("https://", "wss://")
        val request = Request.Builder().url(signalingUrl).build()

        webSocket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!isActive()) {
                        executeOnOwner { webSocket.close(1000, null) }
                        return
                    }
                    executeOnOwner {
                        if (isActive() && this@WebRtcConnection.webSocket === webSocket) {
                            createOffer(createdSession)
                        } else {
                            webSocket.close(1000, null)
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    executeOnOwner {
                        if (isActive() && this@WebRtcConnection.webSocket === webSocket) {
                            handleServerMessage(text)
                        }
                    }
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    if (isActive()) {
                        fail(t.message ?: "WebRTC signaling 연결에 실패했습니다.")
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (isActive()) {
                        fail("WebRTC signaling 연결이 종료되었습니다.")
                    }
                }
            },
        )
    }

    private fun createOffer(createdSession: CreatedSession) {
        if (!isActive()) return
        val connection = peerConnection ?: return
        connection.createOffer(
            object : SdpObserverAdapter() {
                override fun onCreateSuccess(description: SessionDescription) {
                    executeOnOwner {
                        if (!isActive() || peerConnection !== connection) return@executeOnOwner
                        connection.setLocalDescription(
                            object : SdpObserverAdapter() {
                                override fun onSetSuccess() {
                                    executeOnOwner {
                                        if (isActive() && peerConnection === connection) {
                                            sendOffer(createdSession, description.description)
                                        }
                                    }
                                }

                                override fun onSetFailure(error: String) {
                                    fail(error)
                                }
                            },
                            description,
                        )
                    }
                }

                override fun onCreateFailure(error: String) {
                    fail(error)
                }
            },
            MediaConstraints(),
        )
    }

    private fun sendOffer(createdSession: CreatedSession, sdp: String) {
        if (!isActive() || session !== createdSession) return
        val payload = JSONObject()
            .put("type", "offer")
            .put("session_id", createdSession.sessionId)
            .put("owner_token", createdSession.ownerToken)
            .put("access_token", accessToken)
            .put("sdp", sdp)
            .toString()

        synchronized(signalLock) {
            val socket = webSocket ?: return
            if (!socket.send(payload)) {
                fail("WebRTC offer를 전송하지 못했습니다.")
                return
            }
            offerSent = true
            pendingSignals.forEach { signal ->
                if (!socket.send(signal)) {
                    fail("ICE candidate를 전송하지 못했습니다.")
                    return
                }
            }
            pendingSignals.clear()
        }
    }

    private fun sendIceCandidate(candidate: IceCandidate?) {
        if (!isActive()) return
        val createdSession = session ?: return
        val payload = JSONObject()
            .put("type", "ice_candidate")
            .put("session_id", createdSession.sessionId)
            .put("owner_token", createdSession.ownerToken)
            .put("access_token", accessToken)
            .put("candidate", candidate?.sdp ?: JSONObject.NULL)
            .apply {
                if (candidate != null) {
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                }
            }
            .toString()

        synchronized(signalLock) {
            val socket = webSocket
            if (!offerSent || socket == null) {
                pendingSignals += payload
            } else if (!socket.send(payload)) {
                fail("ICE candidate를 전송하지 못했습니다.")
            }
        }
    }

    private fun handleServerMessage(payload: String) {
        if (!isActive()) return
        try {
            val expectedSessionId = session?.sessionId ?: return
            when (val message = parseServerMessage(payload, expectedSessionId)) {
                is ServerMessage.Answer -> applyAnswer(message.sdp)
                is ServerMessage.Error -> fail(message.message)
                is ServerMessage.IceCandidateAdded -> Unit
            }
        } catch (exception: Exception) {
            fail(exception.message ?: "서버 signaling 응답이 올바르지 않습니다.")
        }
    }

    private fun applyAnswer(sdp: String) {
        if (!isActive()) return
        val connection = peerConnection ?: return
        connection.setRemoteDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    executeOnOwner {
                        if (isActive() && peerConnection === connection) {
                            updateState(WebRtcConnectionState.CONNECTING, "서버 영상 수신 대기 중")
                        }
                    }
                }

                override fun onSetFailure(error: String) {
                    fail(error)
                }
            },
            SessionDescription(SessionDescription.Type.ANSWER, sdp),
        )
    }

    private fun attachRemoteTrack(receiver: RtpReceiver) {
        if (!isActive()) return
        val track = receiver.track() as? VideoTrack ?: return
        mainHandler.post {
            if (!closed.get() && !terminal.get()) onRemoteTrackChanged(track)
        }
    }

    private fun onAudioRouteChanged(deviceId: Int?, isSilenced: Boolean) {
        executeOnOwner {
            if (!isActive()) return@executeOnOwner

            actualAudioInputId = deviceId
            actualAudioInputSilenced = isSilenced
            if (audioRecordingStarted) scheduleAudioRouteVerification()
        }
    }

    private fun resetAudioRouteVerification() {
        audioInputVerified = false
        audioRouteRetryAttempted = false
        actualAudioInputId = null
        actualAudioInputSilenced = false
        if (audioRecordingStarted) {
            audioRouteMonitor.refresh()
            scheduleAudioRouteVerification()
        }
    }

    private fun scheduleAudioRouteVerification() {
        audioRouteVerificationTask?.cancel(false)
        audioRouteVerificationTask = timerExecutor.schedule(
            { executeOnOwner { verifyAudioRoute() } },
            AUDIO_ROUTE_VERIFICATION_DELAY_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun verifyAudioRoute() {
        if (!isActive() || audioInputVerified) return

        if (actualAudioInputSilenced) {
            fail("다른 앱 또는 시스템 정책으로 마이크 입력이 차단되었습니다.")
            return
        }

        val expectedInputId = preferredAudioInput?.id
        if (actualAudioInputId != null &&
            (expectedInputId == null || actualAudioInputId == expectedInputId)
        ) {
            audioInputVerified = true
            updateConnectedState()
            return
        }

        if (!audioRouteRetryAttempted) {
            audioRouteRetryAttempted = true
            preferredAudioInput?.let { audioDeviceModule?.setPreferredInputDevice(it) }
            audioRouteMonitor.refresh()
            scheduleAudioRouteVerification()
            return
        }

        fail("선택한 오디오 기기를 실제 입력으로 적용하지 못했습니다.")
    }

    private fun updateConnectedState() {
        if (!peerConnectionConnected || !audioInputVerified) return

        connectionTimeoutTask?.cancel(false)
        connectionTimeoutTask = null
        updateState(WebRtcConnectionState.CONNECTED, "WebRTC 연결됨")
    }

    private fun fail(message: String) {
        val shouldStartShutdown = synchronized(shutdownLock) {
            if (closed.get() || terminal.get()) {
                false
            } else {
                terminal.set(true)
                if (shutdownInitiated.compareAndSet(false, true)) {
                    closeSignal.close()
                    cancelHttpRequests()
                    true
                } else {
                    false
                }
            }
        }
        if (!shouldStartShutdown) return

        updateState(WebRtcConnectionState.FAILED, message)
        enqueueResourceRelease()
    }

    private fun enqueueResourceRelease() {
        executeOnOwner(
            block = {
                try {
                    releaseResourcesOnOwner()
                } finally {
                    completeCloseCallbacks()
                }
            },
            onRejected = ::completeCloseCallbacks,
        )
    }

    private fun completeCloseCallbacks() {
        val callbacks = synchronized(shutdownLock) {
            shutdownFinished = true
            closeCallbacks.toList().also { closeCallbacks.clear() }
        }
        callbacks.forEach(::invokeCloseCallback)
    }

    private fun invokeCloseCallback(callback: () -> Unit) {
        runCatching { callback() }
    }

    private fun releaseResourcesOnOwner() {
        if (!resourcesReleased.compareAndSet(false, true)) return

        timerExecutor.shutdownNow()
        connectionTimeoutTask?.cancel(false)
        connectionTimeoutTask = null
        audioRouteVerificationTask?.cancel(false)
        audioRouteVerificationTask = null

        runCatching { clearBluetoothCommunicationRoute() }
        runCatching { onLocalMediaCleared() }
        runCatching { frameAnalyzer?.stop() }
        frameAnalyzer = null
        runCatching { audioRouteMonitor.close() }
        audioRecordingStarted = false
        audioInputVerified = false
        peerConnectionConnected = false
        synchronized(signalLock) {
            pendingSignals.clear()
            offerSent = false
        }
        runCatching { webSocket?.close(1000, null) }
        webSocket = null
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null

        val createdSession = takeSession()
        runCatching { localAudioTrack?.dispose() }
        localAudioTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        runCatching { localVideoTrack?.dispose() }
        localVideoTrack = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        runCatching { peerConnectionFactory?.dispose() }
        peerConnectionFactory = null
        runCatching { audioDeviceModule?.release() }
        audioDeviceModule = null
        runCatching { eglBase?.release() }
        eglBase = null
        runCatching { createdSession?.let(::deleteSession) }
        runCatching { httpClient.connectionPool.evictAll() }
        runCatching { httpClient.dispatcher.executorService.shutdown() }

        mainHandler.post { onRemoteTrackChanged(null) }
        ownerExecutor.shutdown()
    }

    @Synchronized
    private fun takeSession(): CreatedSession? = session.also { session = null }

    private fun isActive(): Boolean = !closed.get() && !terminal.get()

    private fun executeOnOwner(
        onRejected: (() -> Unit)? = null,
        block: () -> Unit,
    ) {
        try {
            ownerExecutor.execute(block)
        } catch (_: RejectedExecutionException) {
            onRejected?.invoke()
        }
    }

    private fun deleteSession(createdSession: CreatedSession) {
        val request = authenticatedRequest("/sessions/${createdSession.sessionId}")
            .header("X-Session-Owner-Token", createdSession.ownerToken)
            .delete()
            .build()
        runCatching {
            executeHttp(request, allowAfterClose = true).close()
        }
    }

    private fun executeHttp(
        request: Request,
        allowAfterClose: Boolean = false,
    ): Response {
        val call = httpClient.newCall(request)
        activeHttpCalls += call
        if (closeSignal.isClosed && !allowAfterClose) {
            activeHttpCalls -= call
            call.cancel()
            throw IOException("WebRTC 연결이 종료되었습니다.")
        }

        return try {
            call.execute()
        } finally {
            activeHttpCalls -= call
        }
    }

    private fun cancelHttpRequests() {
        httpClient.dispatcher.cancelAll()
        activeHttpCalls.forEach { call -> call.cancel() }
    }

    private fun authenticatedRequest(path: String): Request.Builder {
        val endpoint = checkNotNull(serverBaseUrl.resolve(path))
        return Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $accessToken")
    }

    private fun updateState(state: WebRtcConnectionState, message: String) {
        mainHandler.post {
            if (closed.get()) return@post
            if (terminal.get() && state != WebRtcConnectionState.FAILED) return@post
            onStateChanged(state, message)
        }
    }

    private fun updateBroadcastState(state: BroadcastState, message: String) {
        if (!isActive()) return
        broadcastState = state
        mainHandler.post {
            if (!closed.get()) onBroadcastStateChanged(state, message)
        }
    }

    private val peerConnectionObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                executeOnOwner { sendIceCandidate(null) }
            }
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            executeOnOwner { sendIceCandidate(candidate) }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

        override fun onAddStream(stream: MediaStream) = Unit

        override fun onRemoveStream(stream: MediaStream) = Unit

        override fun onDataChannel(channel: DataChannel) = Unit

        override fun onRenegotiationNeeded() = Unit

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    executeOnOwner {
                        if (!isActive()) return@executeOnOwner
                        peerConnectionConnected = true
                        updateConnectedState()
                    }
                }

                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.DISCONNECTED,
                -> fail("WebRTC 연결이 끊겼습니다.")

                else -> Unit
            }
        }

        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
            executeOnOwner { attachRemoteTrack(receiver) }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            executeOnOwner { attachRemoteTrack(transceiver.receiver) }
        }
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit

        override fun onSetSuccess() = Unit

        override fun onCreateFailure(error: String) = Unit

        override fun onSetFailure(error: String) = Unit
    }

    companion object {
        private val factoryInitialized = AtomicBoolean(false)
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val CONNECTION_TIMEOUT_MILLIS = 30_000L
        private const val AUDIO_ROUTE_VERIFICATION_DELAY_MILLIS = 500L
        private const val GO_LIVE_RETRY_COUNT = 15
        private const val GO_LIVE_RETRY_DELAY_MILLIS = 1_000L
    }
}

private class ServerApiException(
    val code: String?,
    message: String,
) : IOException(message)

private data class CreatedSession(
    val sessionId: String,
    val ownerToken: String,
)

internal fun isBluetoothAudioInputType(type: Int): Boolean = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    -> true

    else -> false
}

internal fun findBluetoothCommunicationDevice(
    bluetoothInput: AudioDeviceInfo,
    communicationDevices: List<AudioDeviceInfo>,
): AudioDeviceInfo? {
    val sameTypeDevices = communicationDevices.filter { device ->
        device.type == bluetoothInput.type
    }
    return sameTypeDevices.firstOrNull { device ->
        device.address == bluetoothInput.address
    } ?: sameTypeDevices.singleOrNull()
}

private fun parseIceServers(payload: String): List<PeerConnection.IceServer> {
    val items = JSONObject(payload).optJSONArray("iceServers")
        ?: throw IllegalArgumentException("ICE 서버 설정이 없습니다.")
    return buildList {
        repeat(items.length()) { index ->
            val item = items.getJSONObject(index)
            val urlsJson = item.getJSONArray("urls")
            val urls = buildList {
                repeat(urlsJson.length()) { urlIndex ->
                    add(urlsJson.getString(urlIndex))
                }
            }
            require(urls.isNotEmpty()) { "ICE 서버 URL이 없습니다." }
            add(
                PeerConnection.IceServer.builder(urls)
                    .setUsername(item.optString("username"))
                    .setPassword(
                        item.opt("credential")
                            ?.takeUnless { it == JSONObject.NULL }
                            ?.toString()
                            .orEmpty(),
                    )
                    .createIceServer(),
            )
        }
    }
}

private fun parseCreatedSession(payload: String): CreatedSession {
    val response = JSONObject(payload)
    return CreatedSession(
        sessionId = response.optString("session_id").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("세션 ID가 없습니다."),
        ownerToken = response.optString("owner_token").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("세션 owner token이 없습니다."),
    )
}

private fun requireSuccessful(response: Response, operation: String) {
    if (!response.isSuccessful) {
        throw IOException("$operation 실패: HTTP ${response.code}")
    }
}

private fun parseServerApiException(statusCode: Int, payload: String): ServerApiException {
    val error = runCatching { JSONObject(payload).optJSONObject("error") }.getOrNull()
    val code = error?.optString("code")?.takeIf { it.isNotBlank() }
    val fallback = error?.optString("message")?.takeIf { it.isNotBlank() }
        ?: "서버 요청 실패: HTTP $statusCode"
    val message = when (code) {
        "streaming_not_connected" -> "YouTube 계정을 먼저 연결해 주세요."
        "live_streaming_blocked" -> "YouTube 라이브 기능을 먼저 활성화해 주세요."
        "streaming_reconnect_required" -> "YouTube 계정을 다시 연결해 주세요."
        "streaming_prepare_failed" -> "YouTube 방송을 준비하지 못했습니다."
        "broadcast_stopped" -> "라이브 전환 중 방송이 종료되었습니다."
        else -> fallback
    }
    return ServerApiException(code, message)
}

internal fun buildBroadcastSettingsPayload(settings: BroadcastSettings): JSONObject = JSONObject()
    .put("title", settings.title.trim())
    .put("description", settings.description)
    .put("privacy", settings.privacy)
    .put("made_for_kids", settings.madeForKids)
    .put("category_id", settings.categoryId.trim())
