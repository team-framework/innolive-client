package com.framework.innolive.feature.live

import android.util.Log
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

enum class WebRtcConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED,
}

internal fun hasValidatedInternet(hasCapability: (Int) -> Boolean): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

class WebRtcConnection(
    context: Context,
    serverUrl: String,
    accessToken: String,
    private val refreshAccessToken: suspend () -> String,
    private val initialAnonymizationEnabled: Boolean,
    private var preferredAudioInput: AudioDeviceInfo?,
    private val onStateChanged: (WebRtcConnectionState, ConnectionFailure?) -> Unit,
    private val onRemoteTrackChanged: (VideoTrack?) -> Unit,
    private val onLocalMediaReady: (CameraFrameAnalyzer, EglBase.Context) -> Unit,
    private val onLocalMediaCleared: () -> Unit,
    private val onBroadcastStateChanged: (BroadcastState, BroadcastEvent?) -> Unit,
    private val onAnonymizationStateConfirmed: (AnonymizationState) -> Unit,
    private val broadcastCallbackExecutor: Executor? = null,
    private val onLocalVideoTrackChanged: (VideoTrack?) -> Unit = {},
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val recoveryAccessToken = RecoveryAccessToken(accessToken)
    private var sessionRecoveryStore: SessionRecoveryStore = EncryptedSessionRecoveryStore(applicationContext)
    private val serverBaseUrl = serverUrl.trim().trimEnd('/').toHttpUrl().also { url ->
        if (!url.isHttps) throw ConnectionFailureException(ConnectionFailure.CONFIGURATION)
    }
    private val sessionRecoveryScope = sessionRecoveryScope(serverBaseUrl.toString(), accessToken)
    private val audioManager = applicationContext.getSystemService(AudioManager::class.java)
    private val connectivityManager = applicationContext.getSystemService(ConnectivityManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    /**
     * The owner for all WebRTC, signaling, audio-route, and teardown work.
     * Callback threads only enqueue work here; they never touch native state.
     */
    private val ownerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val timerExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor()
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val terminal = AtomicBoolean(false)
    private val broadcastOperation = AtomicBoolean(false)
    // 완료 콜백은 다음 제어 요청을 받을 수 있도록 작업 잠금을 해제한 뒤 전달합니다.
    private var pendingBroadcastCompletion: Pair<BroadcastState, BroadcastEvent?>? = null
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
    private var recoveryTask: ScheduledFuture<*>? = null
    private var recoveryVideoStatsTask: ScheduledFuture<*>? = null
    private var recoveryPeerConnectionTimeoutTask: ScheduledFuture<*>? = null
    private var recoveryVideoTimeoutTask: ScheduledFuture<*>? = null
    private var recoveryVideoStatusJob: Job? = null
    private var recoveryVideoVerificationId: String? = null
    private val recoveryVideoVerificationGate = RecoveryVideoVerificationGate()
    private var recoveryVideoProgress: OutboundVideoProgress? = null
    private var recoveryServerVideoReady = false
    private var recoveryTokenRefreshJob: Job? = null
    private var recoveryAuthPending = false
    private var recoveryDeadlineTask: ScheduledFuture<*>? = null
    private var answerTimeoutTask: ScheduledFuture<*>? = null
    private var recoveryPolicy = WebRtcRecoveryPolicy()
    private var recoveryWindow = WebRtcRecoveryWindow(recoveryPolicy)
    private var hasConnected = false
    private var recoveryOfferPending = false
    private var recoveryAttemptActive = false
    private var waitingForNetwork = false
    private var networkCallbackRegistered = false
    private var recoverySuppressedAfterStop = false
    private var activeNegotiationId: String? = null
    private var remoteDescriptionNegotiationId: String? = null
    private val pendingRemoteCandidates = mutableListOf<Pair<String, IceCandidate>>()
    private var localIceUfrags = emptySet<String>()
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkChanged()

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = onNetworkChanged()
    }

    private fun onNetworkChanged() {
        executeOnOwner {
            if (isActive() && waitingForNetwork && isNetworkAvailable()) {
                waitingForNetwork = false
                scheduleRecoveryAttempt(0)
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return hasValidatedInternet(capabilities::hasCapability)
    }

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

    private var videoSender: RtpSender? = null

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
                updateState(WebRtcConnectionState.CONNECTING)
                connectionTimeoutTask = timerExecutor.schedule(
                    { fail(ConnectionFailure.TIMEOUT) },
                    CONNECTION_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS,
                )

                val (iceServers, policy) = loadIceServers()
                recoveryPolicy = policy
                recoveryWindow = WebRtcRecoveryWindow(policy)
                if (!isActive()) return@executeOnOwner

                val createdSession = createSession()
                session = createdSession
                if (!isActive()) return@executeOnOwner
                updateState(WebRtcConnectionState.CONNECTING)
                val confirmed = confirmInitialAnonymization(initialAnonymizationEnabled) {
                    val payload = executeSessionRequest(
                        "anonymization", "PATCH", anonymizationPayload(initialAnonymizationEnabled),
                    )
                    parseAnonymizationResponse(payload, createdSession.sessionId)
                }
                if (!isActive()) return@executeOnOwner
                mainHandler.post {
                    if (isActive()) onAnonymizationStateConfirmed(confirmed)
                }

                val connection = createPeerConnection(iceServers)
                peerConnection = connection
                if (!isActive()) return@executeOnOwner

                addAudioTransceiver(connection)
                if (!isActive()) return@executeOnOwner
                addVideoTransceiver(connection)
                if (!isActive()) return@executeOnOwner
                checkNotNull(frameAnalyzer).start()
                val negotiationId = UUID.randomUUID().toString()
                activeNegotiationId = negotiationId
                openSignalingSocket(createdSession, negotiationId, iceRestart = false)
            } catch (exception: Exception) {
                Log.w("LiveConnection", "start_failed type=${exception.javaClass.simpleName} cause=${exception.cause?.javaClass?.simpleName}")
                fail(exception.toConnectionFailure())
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
        onLocalVideoTrackChanged(localVideoTrack)
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
                fail(ConnectionFailure.MICROPHONE_UNAVAILABLE)
                return@executeOnOwner
            }
            audioInput?.let { audioDeviceModule?.setPreferredInputDevice(it) }
            resetAudioRouteVerification()
        }
    }

    internal fun setAnonymizationEnabled(
        enabled: Boolean,
        onComplete: (AnonymizationState?, AnonymizationFailure?) -> Unit,
    ) {
        fun complete(state: AnonymizationState?, error: AnonymizationFailure?) {
            mainHandler.post { if (isActive()) onComplete(state, error) }
        }
        executeOnOwner(
            block = {
                if (!isActive()) return@executeOnOwner
                try {
                    val currentSession = checkNotNull(session) { "WebRTC 세션이 없습니다." }
                    val payload = executeSessionRequest("anonymization", "PATCH", anonymizationPayload(enabled))
                    val confirmed = parseAnonymizationResponse(payload, currentSession.sessionId)
                    val expected = if (enabled) AnonymizationState.ENABLED else AnonymizationState.DISABLED
                    complete(
                        confirmed,
                        if (confirmed == expected) null else AnonymizationFailure.NOT_APPLIED,
                    )
                } catch (_: Exception) {
                    // 응답 유실 시 서버에 적용됐을 수도 있으므로 실패를 Off로 해석하지 않습니다.
                    complete(null, AnonymizationFailure.CONFIRMATION)
                }
            },
            onRejected = { complete(null, AnonymizationFailure.REQUEST) },
        )
    }

    fun saveBroadcastSettings(settings: BroadcastSettings) {
        runBroadcastOperation {
            if (settings.madeForKids == null) {
                updateBroadcastState(
                    BroadcastState.FAILED,
                    BroadcastEvent.Failure(BroadcastFailure.AUDIENCE_REQUIRED),
                )
                return@runBroadcastOperation
            }
            updateBroadcastState(BroadcastState.SAVING_SETTINGS)
            putBroadcastSettings(settings)
            updateBroadcastState(BroadcastState.IDLE, BroadcastEvent.SettingsSaved)
        }
    }

    fun prepareBroadcast(settings: BroadcastSettings): Boolean {
        if (!broadcastState.canPrepare) return false
        return runBroadcastOperation {
            if (settings.madeForKids == null) {
                updateBroadcastState(
                    BroadcastState.FAILED,
                    BroadcastEvent.Failure(BroadcastFailure.AUDIENCE_REQUIRED),
                )
                return@runBroadcastOperation
            }
            updateBroadcastState(BroadcastState.SAVING_SETTINGS)
            putBroadcastSettings(settings)
            updateBroadcastState(BroadcastState.PREPARING)
            postSessionRequest("stream/prepare", JSONObject().put("provider", "youtube"))
            recoverySuppressedAfterStop = false
            updateBroadcastState(BroadcastState.PREPARED)
        }
    }

    fun goLive(onAccepted: () -> Unit = {}): Boolean {
        if (!broadcastState.canGoLive) return false
        return runBroadcastOperation(onAccepted = onAccepted) {
            if (!peerConnectionConnected || !audioInputVerified) {
                updateBroadcastState(
                    BroadcastState.PREPARED,
                    BroadcastEvent.Failure(BroadcastFailure.REQUEST),
                )
                return@runBroadcastOperation
            }
            updateBroadcastState(BroadcastState.GOING_LIVE)
            try {
                goLiveWithRetry()
                updateBroadcastState(BroadcastState.LIVE)
            } catch (exception: ServerApiException) {
                updateBroadcastState(
                    BroadcastState.PREPARED,
                    exception.toBroadcastEvent(BroadcastFailure.REQUEST),
                )
            } catch (_: Exception) {
                updateBroadcastState(
                    BroadcastState.PREPARED,
                    BroadcastEvent.Failure(BroadcastFailure.REQUEST),
                )
            }
        }
    }

    fun pauseBroadcast() {
        if (!broadcastState.canPause) return
        runBroadcastOperation {
            updateBroadcastState(BroadcastState.PAUSING)
            try {
                postSessionRequest("stream/pause")
                updateBroadcastState(BroadcastState.PAUSED)
            } catch (exception: ServerApiException) {
                updateBroadcastState(
                    BroadcastState.LIVE,
                    exception.toBroadcastEvent(BroadcastFailure.YOUTUBE_PAUSE),
                )
            } catch (_: Exception) {
                updateBroadcastState(
                    BroadcastState.LIVE,
                    BroadcastEvent.Failure(BroadcastFailure.YOUTUBE_PAUSE),
                )
            }
        }
    }

    fun resumeBroadcast() {
        if (!broadcastState.canResume) return
        runBroadcastOperation {
            if (!peerConnectionConnected || !audioInputVerified) return@runBroadcastOperation
            updateBroadcastState(BroadcastState.RESUMING)
            try {
                postSessionRequest("stream/resume")
                updateBroadcastState(BroadcastState.LIVE)
            } catch (exception: ServerApiException) {
                updateBroadcastState(
                    BroadcastState.PAUSED,
                    exception.toBroadcastEvent(BroadcastFailure.YOUTUBE_RESUME),
                )
            } catch (_: Exception) {
                updateBroadcastState(
                    BroadcastState.PAUSED,
                    BroadcastEvent.Failure(BroadcastFailure.YOUTUBE_RESUME),
                )
            }
        }
    }

    fun stopBroadcast() {
        if (!broadcastState.canStop) return
        runBroadcastOperation {
            val previousState = broadcastState
            val stoppingState = broadcastState.stoppingState()
            updateBroadcastState(stoppingState)
            try {
                postSessionRequest("stream/stop")
                updateBroadcastState(BroadcastState.IDLE)
                recoverySuppressedAfterStop = true
                if (recoveryWindow.deadlineMillis != null) {
                    if (canReuseRecoveredPreviewAfterStop(
                            peerConnected = peerConnectionConnected &&
                                peerConnection?.connectionState() == PeerConnection.PeerConnectionState.CONNECTED,
                            audioVerified = audioInputVerified,
                            recoveryAttemptActive = recoveryAttemptActive,
                            recoveryOfferPending = recoveryOfferPending,
                            hasCurrentAnswer = hasCurrentRecoveryAnswer(
                                activeNegotiationId,
                                remoteDescriptionNegotiationId,
                            ),
                            videoPacketsProgressed = recoveryVideoProgress?.hasProgress == true,
                            serverVideoReady = recoveryServerVideoReady,
                        )) {
                        cancelRecoveryWork()
                        updateState(WebRtcConnectionState.CONNECTED)
                    } else {
                        // Deliver the successful stop before closing an unverified preview.
                        executeOnOwner { fail(ConnectionFailure.DISCONNECTED) }
                    }
                }
            } catch (exception: ServerApiException) {
                updateBroadcastState(
                    previousState,
                    exception.toBroadcastEvent(BroadcastFailure.REQUEST),
                )
            } catch (_: Exception) {
                updateBroadcastState(
                    previousState,
                    BroadcastEvent.Failure(BroadcastFailure.REQUEST),
                )
            }
        }
    }

    private fun runBroadcastOperation(
        onAccepted: () -> Unit = {},
        operation: () -> Unit,
    ): Boolean {
        if (!isActive() || !broadcastOperation.compareAndSet(false, true)) return false
        try {
            onAccepted()
        } catch (_: Exception) {
            broadcastOperation.set(false)
            return false
        }
        return executeOnOwner(
            block = {
                try {
                    if (!isActive()) return@executeOnOwner
                    check(session != null) { "WebRTC 세션이 없습니다." }
                    operation()
                } catch (exception: Exception) {
                    updateBroadcastState(
                        BroadcastState.FAILED,
                        BroadcastEvent.Failure(exception.toBroadcastFailure()),
                    )
                } finally {
                    val completion = pendingBroadcastCompletion
                    pendingBroadcastCompletion = null
                    broadcastOperation.set(false)
                    completion?.let { (state, event) -> dispatchBroadcastState(state, event) }
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

    private fun executeSessionRequest(path: String, method: String, body: JSONObject): String {
        val createdSession = checkNotNull(session) { "WebRTC 세션이 없습니다." }
        val request = authenticatedRequest("/sessions/${createdSession.sessionId}/$path")
            .header("X-Session-Owner-Token", createdSession.ownerToken)
            .method(method, body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return executeHttp(request).use { response ->
            val payload = response.body.string()
            if (!response.isSuccessful) throw parseServerApiException(payload)
            payload
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
        throw ServerApiException("broadcast_not_ready")
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
                            fail(ConnectionFailure.MICROPHONE_BLOCKED)
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
        val transceiver = checkNotNull(
            connection.addTransceiver(
                videoTrack,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                ),
            ),
        ) { "Unable to add the camera video transceiver." }
        videoSender = transceiver.sender
    }

    private fun loadIceServers(): Pair<List<PeerConnection.IceServer>, WebRtcRecoveryPolicy> {
        val request = authenticatedRequest("/webrtc/config").get().build()
        return executeHttp(request).use { response ->
            requireSuccessful(response, "ICE 서버 설정 조회")
            val payload = response.body.string()
            parseIceServers(payload) to parseWebRtcRecoveryPolicy(JSONObject(payload))
        }
    }

    private fun createSession(): CreatedSession {
        sessionRecoveryStore.load(sessionRecoveryScope)?.let { staleSession ->
            // The owner token is only returned at creation. A previous process may have
            // died before its normal close could remove this account's session.
            deleteSession(staleSession, allowAfterClose = false)
        }
        var created = requestSessionCreation()
        if (created == null) {
            // Legacy records have no account identity. A 409 proves that the current account
            // already owns a server session before its owner token may be used for cleanup.
            val legacySession = sessionRecoveryStore.loadLegacy()
                ?: throw sessionAlreadyExistsException()
            deleteLegacySession(legacySession)
            created = requestSessionCreation() ?: throw sessionAlreadyExistsException()
        }
        return persistCreatedSession(created)
    }

    private fun requestSessionCreation(): CreatedSession? {
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
            if (!response.isSuccessful) {
                val code = runCatching { JSONObject(response.body.string()).optJSONObject("error")?.optString("code") }.getOrNull()
                if (response.code == 409 && code == "session_already_exists") {
                    return@use null
                }
                throw IOException("WebRTC 세션 생성 실패: HTTP ${response.code}")
            }
            parseCreatedSession(response.body.string())
        }
    }

    private fun persistCreatedSession(created: CreatedSession): CreatedSession = created.also {
        try {
            sessionRecoveryStore.save(created, sessionRecoveryScope)
        } catch (exception: Exception) {
            runCatching { deleteSession(created, allowAfterClose = false) }
            throw IOException("세션 복구 정보를 저장하지 못했습니다.", exception)
        }
    }

    private fun sessionAlreadyExistsException() =
        ConnectionFailureException(ConnectionFailure.EXISTING_BROADCAST)

    private fun createPeerConnection(
        iceServers: List<PeerConnection.IceServer>,
    ): PeerConnection = checkNotNull(
        checkNotNull(peerConnectionFactory).createPeerConnection(
            PeerConnection.RTCConfiguration(iceServers),
            peerConnectionObserver,
        ),
    ) { "Unable to create the WebRTC peer connection." }

    private fun openSignalingSocket(
        createdSession: CreatedSession,
        negotiationId: String,
        iceRestart: Boolean,
    ) {
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
                            val connection = peerConnection
                            if (connection != null && activeNegotiationId == negotiationId) {
                                if (iceRestart) connection.restartIce()
                                createOffer(createdSession, negotiationId, iceRestart)
                            }
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
                    executeOnOwner {
                        if (isActive() && this@WebRtcConnection.webSocket === webSocket) {
                            onSignalingFailure()
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    executeOnOwner {
                        if (isActive() && this@WebRtcConnection.webSocket === webSocket) {
                            onSignalingFailure()
                        }
                    }
                }
            },
        )
    }

    private fun createOffer(
        createdSession: CreatedSession,
        negotiationId: String,
        iceRestart: Boolean,
    ) {
        if (!isActive()) return
        val connection = peerConnection ?: return
        connection.createOffer(
            object : SdpObserverAdapter() {
                override fun onCreateSuccess(description: SessionDescription) {
                    executeOnOwner {
                        if (!isActive() || peerConnection !== connection ||
                            activeNegotiationId != negotiationId) return@executeOnOwner
                        localIceUfrags = extractIceUfrags(description.description)
                        connection.setLocalDescription(
                            object : SdpObserverAdapter() {
                                override fun onSetSuccess() {
                                    executeOnOwner {
                                        if (isActive() && peerConnection === connection &&
                                            activeNegotiationId == negotiationId) {
                                            sendOffer(createdSession, description.description, negotiationId, iceRestart)
                                        }
                                    }
                                }

                                override fun onSetFailure(error: String) {
                                    executeOnOwner { onNegotiationFailure(negotiationId) }
                                }
                            },
                            description,
                        )
                    }
                }

                override fun onCreateFailure(error: String) {
                    executeOnOwner { onNegotiationFailure(negotiationId) }
                }
            },
            MediaConstraints(),
        )
    }

    private fun sendOffer(
        createdSession: CreatedSession,
        sdp: String,
        negotiationId: String,
        iceRestart: Boolean,
    ) {
        if (!isActive() || session !== createdSession || activeNegotiationId != negotiationId) return
        val payload = JSONObject()
            .put("type", "offer")
            .put("session_id", createdSession.sessionId)
            .put("owner_token", createdSession.ownerToken)
            .put("access_token", recoveryAccessToken.value)
            .put("sdp", sdp)
            .put("negotiation_id", negotiationId)
            .put("ice_restart", iceRestart)
            .toString()

        synchronized(signalLock) {
            val socket = webSocket ?: return
            if (!socket.send(payload)) {
                onNegotiationFailure(negotiationId)
                return
            }
            offerSent = true
            pendingSignals.forEach { signal ->
                if (!socket.send(signal)) {
                    onNegotiationFailure(negotiationId)
                    return
                }
            }
            pendingSignals.clear()
        }
        if (iceRestart) {
            answerTimeoutTask?.cancel(false)
            val timeout = recoveryWindow.remainingMillis(SystemClock.elapsedRealtime())
                .minus(5_000).coerceIn(1_000, 30_000)
            answerTimeoutTask = timerExecutor.schedule(
                { executeOnOwner { onNegotiationFailure(negotiationId) } },
                timeout,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        if (!isActive()) return
        val createdSession = session ?: return
        val negotiationId = activeNegotiationId ?: return
        val candidateUfrag = extractCandidateUfrag(candidate.sdp)
        if (candidateUfrag != null && candidateUfrag !in localIceUfrags) return
        val payload = JSONObject()
            .put("type", "ice_candidate")
            .put("session_id", createdSession.sessionId)
            .put("owner_token", createdSession.ownerToken)
            .put("access_token", recoveryAccessToken.value)
            .put("negotiation_id", negotiationId)
            .put("candidate", candidate.sdp)
            .put("sdpMid", candidate.sdpMid)
            .put("sdpMLineIndex", candidate.sdpMLineIndex)
            .toString()

        synchronized(signalLock) {
            val socket = webSocket
            if (!offerSent || socket == null) {
                pendingSignals += payload
            } else if (!socket.send(payload)) {
                when (signalingSendFailureAction(
                    recoveryWindowOpen = recoveryWindow.deadlineMillis != null,
                    hasConnected = hasConnected,
                )) {
                    SignalingSendFailureAction.RETRY_NEGOTIATION -> onNegotiationFailure(negotiationId)
                    SignalingSendFailureAction.START_RECOVERY -> onSignalingFailure()
                    SignalingSendFailureAction.FAIL_CONNECTION -> fail(ConnectionFailure.GENERIC)
                }
            }
        }
    }

    private fun handleServerMessage(payload: String) {
        if (!isActive()) return
        try {
            val expectedSessionId = session?.sessionId ?: return
            when (val message = parseServerMessage(payload, expectedSessionId)) {
                is ServerMessage.Answer -> {
                    val negotiationId = message.negotiationId
                    if (negotiationId != null && negotiationId == activeNegotiationId) {
                        applyAnswer(message.sdp, negotiationId)
                    }
                }
                is ServerMessage.Error -> {
                    if (message.code == "unauthorized") {
                        when (recoveryUnauthorizedAction(
                            recoveryWindowOpen = recoveryWindow.deadlineMillis != null,
                            tokenAlreadyRefreshed = recoveryAccessToken.refreshedForCurrentRecovery,
                        )) {
                            RecoveryUnauthorizedAction.REFRESH_AND_RETRY -> onRecoveryUnauthorized()
                            RecoveryUnauthorizedAction.FAIL_CONNECTION -> fail(ConnectionFailure.DISCONNECTED)
                        }
                    } else if (message.code in setOf("forbidden", "not_found", "peer_recovery_attempts_exhausted")) {
                        fail(ConnectionFailure.DISCONNECTED)
                    } else if (recoveryWindow.deadlineMillis != null) {
                        activeNegotiationId?.let(::onNegotiationFailure)
                    } else {
                        fail(connectionFailureForServerCode(message.code))
                    }
                }
                is ServerMessage.IceCandidateAdded -> Unit
                is ServerMessage.RemoteIceCandidate -> {
                    val negotiationId = message.negotiationId
                    if (negotiationId != null && negotiationId == activeNegotiationId) {
                        message.candidate?.let { candidate ->
                            if (remoteDescriptionNegotiationId == negotiationId) {
                                peerConnection?.addIceCandidate(candidate)
                            } else {
                                pendingRemoteCandidates += negotiationId to candidate
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            if (recoveryWindow.deadlineMillis != null) {
                activeNegotiationId?.let(::onNegotiationFailure)
            } else {
                fail(ConnectionFailure.GENERIC)
            }
        }
    }

    private fun applyAnswer(sdp: String, negotiationId: String) {
        if (!isActive()) return
        val connection = peerConnection ?: return
        if (connection.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return
        connection.setRemoteDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    executeOnOwner {
                        if (isActive() && peerConnection === connection && activeNegotiationId == negotiationId) {
                            answerTimeoutTask?.cancel(false)
                            answerTimeoutTask = null
                            remoteDescriptionNegotiationId = negotiationId
                            pendingRemoteCandidates.filter { it.first == negotiationId }
                                .forEach { (_, candidate) -> connection.addIceCandidate(candidate) }
                            pendingRemoteCandidates.clear()
                            if (recoveryWindow.deadlineMillis != null) {
                                recoveryAttemptActive = false
                                awaitRecoveryPeerConnection(negotiationId)
                                updateConnectedState()
                            } else {
                                updateState(WebRtcConnectionState.CONNECTING)
                            }
                        }
                    }
                }

                override fun onSetFailure(error: String) {
                    executeOnOwner { onNegotiationFailure(negotiationId) }
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
            fail(ConnectionFailure.MICROPHONE_BLOCKED)
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

        fail(ConnectionFailure.MICROPHONE_UNAVAILABLE)
    }

    private fun updateConnectedState() {
        if (!isActive()) return
        if (!peerConnectionConnected) return
        if (recoveryWindow.deadlineMillis != null) {
            val negotiationId = activeNegotiationId
            if (!hasCurrentRecoveryAnswer(negotiationId, remoteDescriptionNegotiationId)) return
            if (recoveryVideoVerificationId != negotiationId) {
                awaitRecoveryPeerConnection(checkNotNull(negotiationId))
            }
            startRecoveryVideoVerification(checkNotNull(negotiationId))
            if (recoveryVideoProgress?.hasProgress != true || !recoveryServerVideoReady) return
        }
        if (!audioInputVerified) return

        connectionTimeoutTask?.cancel(false)
        connectionTimeoutTask = null
        hasConnected = true
        if (recoveryWindow.deadlineMillis != null) {
            cancelRecoveryWork()
            Log.i("LiveConnection", "network_recovery_succeeded")
        }
        updateState(WebRtcConnectionState.CONNECTED)
    }

    private fun awaitRecoveryPeerConnection(negotiationId: String) {
        cancelRecoveryVideoVerification()
        recoveryVideoVerificationId = negotiationId
        recoveryVideoProgress = OutboundVideoProgress()
        recoveryTask?.cancel(false)
        recoveryTask = null
        val timeout = recoveryWindow.remainingMillis(SystemClock.elapsedRealtime())
            .coerceAtMost(RECOVERY_PEER_CONNECTION_WAIT_MILLIS)
        if (timeout == 0L) {
            onNegotiationFailure(negotiationId)
            return
        }
        recoveryPeerConnectionTimeoutTask = timerExecutor.schedule(
            {
                executeOnOwner {
                    if (isCurrentRecoveryVideoVerification(negotiationId) &&
                        !recoveryVideoVerificationGate.started) {
                        onNegotiationFailure(negotiationId)
                    }
                }
            },
            timeout,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun startRecoveryVideoVerification(negotiationId: String) {
        if (!isCurrentRecoveryVideoVerification(negotiationId) ||
            !recoveryVideoVerificationGate.startIfConnected(peerConnectionConnected &&
                peerConnection?.connectionState() == PeerConnection.PeerConnectionState.CONNECTED)) return
        recoveryPeerConnectionTimeoutTask?.cancel(false)
        recoveryPeerConnectionTimeoutTask = null
        val timeout = recoveryWindow.remainingMillis(SystemClock.elapsedRealtime())
            .coerceAtMost(RECOVERY_VIDEO_VERIFICATION_MILLIS)
        if (timeout == 0L) {
            onNegotiationFailure(negotiationId)
            return
        }
        recoveryVideoTimeoutTask = timerExecutor.schedule(
            {
                executeOnOwner {
                    if (isCurrentRecoveryVideoVerification(negotiationId)) {
                        onNegotiationFailure(negotiationId)
                    }
                }
            },
            timeout,
            TimeUnit.MILLISECONDS,
        )
        collectRecoveryVideoStats(negotiationId)
        pollRecoveryServerVideoTrack(negotiationId)
    }

    private fun isCurrentRecoveryVideoVerification(negotiationId: String): Boolean =
        isActive() && recoveryWindow.deadlineMillis != null &&
            activeNegotiationId == negotiationId && recoveryVideoVerificationId == negotiationId

    private fun collectRecoveryVideoStats(negotiationId: String) {
        if (!isCurrentRecoveryVideoVerification(negotiationId)) return
        val connection = peerConnection ?: return
        val sender = videoSender ?: return
        runCatching {
            connection.getStats(sender) { report ->
                executeOnOwner {
                    if (!isCurrentRecoveryVideoVerification(negotiationId)) return@executeOnOwner
                    recoveryVideoProgress?.observe(outboundVideoPackets(report))
                    if (recoveryVideoProgress?.hasProgress == true) {
                        updateConnectedState()
                    } else {
                        recoveryVideoStatsTask = timerExecutor.schedule(
                            { executeOnOwner { collectRecoveryVideoStats(negotiationId) } },
                            RECOVERY_VIDEO_STATS_POLL_MILLIS,
                            TimeUnit.MILLISECONDS,
                        )
                    }
                }
            }
        }.onFailure {
            recoveryVideoStatsTask = timerExecutor.schedule(
                { executeOnOwner { collectRecoveryVideoStats(negotiationId) } },
                RECOVERY_VIDEO_STATS_POLL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun pollRecoveryServerVideoTrack(negotiationId: String) {
        if (!isCurrentRecoveryVideoVerification(negotiationId)) return
        val createdSession = session ?: return
        val endpoint = serverBaseUrl.resolve("/sessions/${createdSession.sessionId}") ?: return
        val accessToken = recoveryAccessToken.value
        recoveryVideoStatusJob = recoveryScope.launch {
            while (isActive()) {
                val result = runCatching {
                    val request = Request.Builder()
                        .url(endpoint)
                        .header("Authorization", "Bearer $accessToken")
                        .header("X-Session-Owner-Token", createdSession.ownerToken)
                        .get()
                        .build()
                    executeHttp(request, callTimeoutMillis = RECOVERY_VIDEO_STATUS_HTTP_TIMEOUT_MILLIS).use { response ->
                        recoveryServerVideoStatus(
                            response.code,
                            if (response.code == 200) response.body.string() else null,
                        )
                    }
                }.getOrDefault(RecoveryServerVideoStatus.PENDING)
                if (result != RecoveryServerVideoStatus.PENDING) {
                    executeOnOwner {
                        if (!isCurrentRecoveryVideoVerification(negotiationId)) return@executeOnOwner
                        recoveryVideoStatusJob = null
                        when (result) {
                            RecoveryServerVideoStatus.READY -> {
                                recoveryServerVideoReady = true
                                updateConnectedState()
                            }
                            RecoveryServerVideoStatus.UNAUTHORIZED -> onRecoveryUnauthorized()
                            RecoveryServerVideoStatus.TERMINAL -> fail(ConnectionFailure.DISCONNECTED)
                            RecoveryServerVideoStatus.PENDING -> Unit
                        }
                    }
                    return@launch
                }
                delay(RECOVERY_VIDEO_STATUS_POLL_MILLIS)
            }
        }
    }

    private fun cancelRecoveryVideoVerification() {
        recoveryVideoVerificationId = null
        recoveryVideoVerificationGate.reset()
        recoveryVideoProgress = null
        recoveryServerVideoReady = false
        recoveryPeerConnectionTimeoutTask?.cancel(false)
        recoveryPeerConnectionTimeoutTask = null
        recoveryVideoStatsTask?.cancel(false)
        recoveryVideoStatsTask = null
        recoveryVideoTimeoutTask?.cancel(false)
        recoveryVideoTimeoutTask = null
        recoveryVideoStatusJob?.cancel()
        recoveryVideoStatusJob = null
    }

    private fun cancelRecoveryWork() {
        recoveryWindow.clear()
        cancelRecoveryVideoVerification()
        recoveryTokenRefreshJob?.cancel()
        recoveryTokenRefreshJob = null
        recoveryAuthPending = false
        recoveryAccessToken.resetRecovery()
        recoveryTask?.cancel(false)
        recoveryTask = null
        recoveryDeadlineTask?.cancel(false)
        recoveryDeadlineTask = null
        answerTimeoutTask?.cancel(false)
        answerTimeoutTask = null
        recoveryAttemptActive = false
        recoveryOfferPending = false
        waitingForNetwork = false
        if (networkCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            networkCallbackRegistered = false
        }
    }

    private fun onSignalingFailure() {
        if (!hasConnected) {
            fail(ConnectionFailure.DISCONNECTED)
        } else if (recoveryWindow.deadlineMillis != null && activeNegotiationId != null) {
            activeNegotiationId?.let(::onNegotiationFailure)
        } else {
            scheduleRecovery(immediate = true)
        }
    }

    private fun scheduleRecovery(immediate: Boolean) {
        if (!isActive()) return
        if (recoverySuppressedAfterStop) {
            fail(ConnectionFailure.DISCONNECTED)
            return
        }
        if (!hasConnected || session == null) {
            fail(ConnectionFailure.DISCONNECTED)
            return
        }
        if (recoveryWindow.deadlineMillis == null) {
            val now = SystemClock.elapsedRealtime()
            if (!networkCallbackRegistered) {
                try {
                    connectivityManager.registerDefaultNetworkCallback(networkCallback)
                    networkCallbackRegistered = true
                } catch (_: RuntimeException) {
                    fail(ConnectionFailure.DISCONNECTED)
                    return
                }
            }
            recoveryWindow.begin(now)
            peerConnectionConnected = false
            updateState(WebRtcConnectionState.RECONNECTING)
            recoveryDeadlineTask = timerExecutor.schedule(
                {
                    executeOnOwner {
                        if (recoveryWindow.deadlineMillis != null && isActive()) {
                            if (peerConnection?.connectionState() == PeerConnection.PeerConnectionState.CONNECTED) {
                                peerConnectionConnected = true
                                updateConnectedState()
                                if (recoveryWindow.deadlineMillis != null) fail(ConnectionFailure.DISCONNECTED)
                            } else {
                                fail(ConnectionFailure.DISCONNECTED)
                            }
                        }
                    }
                },
                recoveryWindow.remainingMillis(now),
                TimeUnit.MILLISECONDS,
            )
            Log.w("LiveConnection", "network_recovery_started")
        }
        if (recoveryAttemptActive || recoveryAuthPending || recoveryOfferPending ||
            recoveryVideoVerificationId != null || recoveryTask != null) return
        scheduleRecoveryAttempt(if (immediate) 0 else recoveryPolicy.debounceMillis)
    }

    private fun scheduleRecoveryAttempt(delayMillis: Long) {
        if (recoveryWindow.deadlineMillis == null || !isActive()) return
        recoveryTask?.cancel(false)
        recoveryTask = timerExecutor.schedule(
            { executeOnOwner { recoveryTask = null; runRecoveryAttempt() } },
            delayMillis.coerceAtMost(recoveryWindow.remainingMillis(SystemClock.elapsedRealtime())),
            TimeUnit.MILLISECONDS,
        )
    }

    private fun runRecoveryAttempt() {
        if (!isActive() || recoveryWindow.deadlineMillis == null) return
        val connection = peerConnection ?: return fail(ConnectionFailure.DISCONNECTED)
        if (recoveryAttemptActive || recoveryAuthPending) return
        if (connection.connectionState() == PeerConnection.PeerConnectionState.CONNECTED &&
            hasCurrentRecoveryAnswer(activeNegotiationId, remoteDescriptionNegotiationId)) {
            peerConnectionConnected = true
            updateConnectedState()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!recoveryWindow.mayAttempt(now)) {
            fail(ConnectionFailure.DISCONNECTED)
            return
        }
        if (connection.signalingState() != PeerConnection.SignalingState.STABLE) {
            recoveryOfferPending = true
            return
        }
        recoveryOfferPending = false
        if (!recoveryWindow.recordAttempt(now, isNetworkAvailable())) {
            waitingForNetwork = true
            return
        }
        waitingForNetwork = false
        recoveryAttemptActive = true
        val negotiationId = UUID.randomUUID().toString()
        activeNegotiationId = negotiationId
        remoteDescriptionNegotiationId = null
        localIceUfrags = emptySet()
        pendingRemoteCandidates.clear()
        synchronized(signalLock) {
            offerSent = false
            pendingSignals.clear()
        }
        webSocket?.close(1000, null)
        webSocket = null
        openRecoverySignalingSocket(negotiationId)
    }

    private fun openRecoverySignalingSocket(negotiationId: String) {
        if (!isActive() || activeNegotiationId != negotiationId ||
            recoveryWindow.remainingMillis(SystemClock.elapsedRealtime()) <= 0) {
            onNegotiationFailure(negotiationId)
            return
        }
        try {
            openSignalingSocket(checkNotNull(session), negotiationId, iceRestart = true)
            Log.i("LiveConnection", "network_recovery_attempt count=${recoveryWindow.attempts}")
        } catch (_: Exception) {
            onNegotiationFailure(negotiationId)
        }
    }

    private fun onRecoveryUnauthorized() {
        if (recoveryAccessToken.refreshedForCurrentRecovery) {
            fail(ConnectionFailure.DISCONNECTED)
            return
        }
        val negotiationId = activeNegotiationId ?: return fail(ConnectionFailure.DISCONNECTED)
        if (recoveryAuthPending) return
        recoveryAuthPending = true
        onNegotiationFailure(negotiationId, onRetired = ::refreshRecoveryAccessToken)
    }

    private fun refreshRecoveryAccessToken() {
        if (!isActive() || recoveryWindow.deadlineMillis == null || !recoveryAuthPending) return
        recoveryTokenRefreshJob = recoveryScope.launch {
            val refreshedToken = runCatching {
                refreshAccessToken().trim().takeIf { it.isNotEmpty() }
                    ?: throw IllegalStateException("Refreshed access token is blank.")
            }.getOrNull()
            executeOnOwner {
                if (!isActive() || recoveryWindow.deadlineMillis == null ||
                    !recoveryAuthPending || activeNegotiationId != null) return@executeOnOwner
                recoveryTokenRefreshJob = null
                recoveryAuthPending = false
                if (refreshedToken != null) recoveryAccessToken.updateForRecovery(refreshedToken)
                scheduleRecoveryAttempt(if (refreshedToken != null) 0 else recoveryPolicy.debounceMillis)
            }
        }
    }

    private fun onNegotiationFailure(negotiationId: String, onRetired: (() -> Unit)? = null) {
        if (!isActive() || activeNegotiationId != negotiationId) return
        if (recoveryWindow.deadlineMillis == null) {
            if (!hasConnected) fail(ConnectionFailure.GENERIC)
            return
        }
        recoveryAttemptActive = false
        cancelRecoveryVideoVerification()
        recoveryTokenRefreshJob?.cancel()
        recoveryTokenRefreshJob = null
        activeNegotiationId = null
        recoveryTask?.cancel(false)
        recoveryTask = null
        answerTimeoutTask?.cancel(false)
        answerTimeoutTask = null
        webSocket?.close(1000, null)
        webSocket = null
        pendingRemoteCandidates.clear()
        val connection = peerConnection ?: return fail(ConnectionFailure.DISCONNECTED)
        val retry = {
            if (isActive() && recoveryWindow.deadlineMillis != null && activeNegotiationId == null) {
                if (onRetired != null) onRetired() else scheduleRecoveryAttempt(recoveryPolicy.debounceMillis)
            }
        }
        if (connection.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            connection.setLocalDescription(
                object : SdpObserverAdapter() {
                    override fun onSetSuccess() { executeOnOwner { if (isActive()) retry() } }
                    override fun onSetFailure(error: String) { fail(ConnectionFailure.DISCONNECTED) }
                },
                SessionDescription(SessionDescription.Type.ROLLBACK, ""),
            )
        } else {
            retry()
        }
    }

    private fun fail(failure: ConnectionFailure) {
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

        val category = when (failure) {
            ConnectionFailure.TIMEOUT -> "timeout"
            ConnectionFailure.MICROPHONE_UNAVAILABLE,
            ConnectionFailure.MICROPHONE_BLOCKED,
            -> "audio"
            else -> "connection"
        }
        Log.w("LiveConnection", "connection_failed category=$category")
        updateState(WebRtcConnectionState.FAILED, failure)
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

        cancelRecoveryVideoVerification()
        recoveryScope.cancel()
        timerExecutor.shutdownNow()
        if (networkCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            networkCallbackRegistered = false
        }
        connectionTimeoutTask?.cancel(false)
        connectionTimeoutTask = null
        audioRouteVerificationTask?.cancel(false)
        audioRouteVerificationTask = null
        recoveryTask?.cancel(false)
        recoveryTask = null
        recoveryDeadlineTask?.cancel(false)
        recoveryDeadlineTask = null
        answerTimeoutTask?.cancel(false)
        answerTimeoutTask = null
        recoveryWindow.clear()
        pendingRemoteCandidates.clear()

        runCatching { clearBluetoothCommunicationRoute() }
        runCatching { onLocalVideoTrackChanged(null) }
        runCatching { onLocalMediaCleared() }
        runCatching { frameAnalyzer?.close() }
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
        videoSender = null

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
        runCatching { createdSession?.let { deleteSession(it, allowAfterClose = true) } }
            .onFailure { Log.w("LiveConnection", "session_cleanup_failed type=${it.javaClass.simpleName}") }
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
    ): Boolean =
        try {
            ownerExecutor.execute(block)
            true
        } catch (_: RejectedExecutionException) {
            onRejected?.invoke()
            false
        }

    private fun deleteSession(createdSession: CreatedSession, allowAfterClose: Boolean) {
        val request = authenticatedRequest("/sessions/${createdSession.sessionId}")
            .header("X-Session-Owner-Token", createdSession.ownerToken)
            .delete()
            .build()
        executeHttp(request, allowAfterClose = allowAfterClose).use { response ->
            when (response.code) {
                204, 404, 403 -> sessionRecoveryStore.clear(sessionRecoveryScope)
                else -> throw IOException("WebRTC 세션 삭제 실패: HTTP ${response.code}")
            }
        }
    }

    private fun deleteLegacySession(createdSession: CreatedSession) {
        val request = authenticatedRequest("/sessions/${createdSession.sessionId}")
            .header("X-Session-Owner-Token", createdSession.ownerToken)
            .delete()
            .build()
        executeHttp(request).use { response ->
            when (response.code) {
                204, 404 -> sessionRecoveryStore.clearLegacy()
                403 -> throw IOException("기존 방송 세션이 현재 계정과 일치하지 않습니다.")
                else -> throw IOException("기존 WebRTC 세션 삭제 실패: HTTP ${response.code}")
            }
        }
    }

    private fun executeHttp(
        request: Request,
        allowAfterClose: Boolean = false,
        callTimeoutMillis: Long? = null,
    ): Response {
        val call = httpClient.newCall(request)
        if (callTimeoutMillis != null) call.timeout().timeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        activeHttpCalls += call
        if (closeSignal.isClosed && !allowAfterClose) {
            activeHttpCalls -= call
            call.cancel()
            throw IOException("WebRTC 연결이 종료되었습니다.")
        }

        return try {
            val operation = when {
                request.url.encodedPath.endsWith("/anonymization") -> "anonymization"
                request.url.encodedPath.endsWith("/broadcast") -> "broadcast_settings"
                request.url.encodedPath.endsWith("/stream/prepare") -> "stream_prepare"
                request.url.encodedPath.endsWith("/stream/stop") -> "stream_stop"
                request.url.encodedPath.endsWith("/stream/golive") -> "stream_golive"
                request.method == "GET" && request.url.encodedPath.startsWith("/sessions/") -> "session_status"
                request.url.encodedPath.endsWith("/sessions") -> "create_session"
                request.method == "DELETE" -> "delete_session"
                else -> "connection_config"
            }
            call.execute().also { response ->
                Log.i("LiveConnection", "operation=$operation status=${response.code}")
            }
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
            .header("Authorization", "Bearer ${recoveryAccessToken.value}")
    }

    private fun updateState(
        state: WebRtcConnectionState,
        failure: ConnectionFailure? = null,
    ) {
        mainHandler.post {
            if (closed.get()) return@post
            if (terminal.get() && state != WebRtcConnectionState.FAILED) return@post
            onStateChanged(state, failure)
        }
    }

    private fun updateBroadcastState(
        state: BroadcastState,
        event: BroadcastEvent? = null,
    ) {
        if (!isActive()) return
        broadcastState = state
        when (state) {
            BroadcastState.IDLE,
            BroadcastState.PREPARED,
            BroadcastState.LIVE,
            BroadcastState.PAUSED,
            BroadcastState.FAILED -> pendingBroadcastCompletion = state to event
            else -> dispatchBroadcastState(state, event)
        }
    }

    private fun dispatchBroadcastState(state: BroadcastState, event: BroadcastEvent?) {
        val callback = Runnable {
            if (!closed.get()) onBroadcastStateChanged(state, event)
        }
        if (broadcastCallbackExecutor != null) {
            broadcastCallbackExecutor.execute(callback)
        } else {
            mainHandler.post(callback)
        }
    }

    private val peerConnectionObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) {
            if (state == PeerConnection.SignalingState.STABLE) {
                executeOnOwner {
                    if (isActive() && recoveryOfferPending) {
                        recoveryOfferPending = false
                        scheduleRecoveryAttempt(0)
                    }
                }
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit

        override fun onIceCandidate(candidate: IceCandidate) {
            executeOnOwner { sendIceCandidate(candidate) }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

        override fun onAddStream(stream: MediaStream) = Unit

        override fun onRemoveStream(stream: MediaStream) = Unit

        override fun onDataChannel(channel: DataChannel) = Unit

        override fun onRenegotiationNeeded() = Unit

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            executeOnOwner {
                if (!isActive()) return@executeOnOwner
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        peerConnectionConnected = true
                        updateConnectedState()
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        peerConnectionConnected = false
                        if (recoveryVideoVerificationId != null) {
                            activeNegotiationId?.let(::onNegotiationFailure)
                        } else if (hasConnected) {
                            scheduleRecovery(immediate = false)
                        }
                    }
                    PeerConnection.PeerConnectionState.FAILED -> {
                        peerConnectionConnected = false
                        if (recoveryVideoVerificationId != null) {
                            activeNegotiationId?.let(::onNegotiationFailure)
                        } else {
                            scheduleRecovery(immediate = true)
                        }
                    }
                    PeerConnection.PeerConnectionState.CLOSED -> fail(ConnectionFailure.DISCONNECTED)
                    else -> Unit
                }
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
        private const val RECOVERY_PEER_CONNECTION_WAIT_MILLIS = 20_000L
        private const val RECOVERY_VIDEO_VERIFICATION_MILLIS = 10_000L
        private const val RECOVERY_VIDEO_STATS_POLL_MILLIS = 250L
        private const val RECOVERY_VIDEO_STATUS_POLL_MILLIS = 1_000L
        private const val RECOVERY_VIDEO_STATUS_HTTP_TIMEOUT_MILLIS = 3_000L
        private const val GO_LIVE_RETRY_COUNT = 15
        private const val GO_LIVE_RETRY_DELAY_MILLIS = 1_000L
    }
}

private class ServerApiException(
    val code: String?,
    val serverMessage: String? = null,
) : IOException("Server API request failed")

internal class ConnectionFailureException(
    val failure: ConnectionFailure,
    cause: Throwable? = null,
) : IOException(cause)

private fun ServerApiException.toKnownBroadcastFailure(): BroadcastFailure? = when (code) {
    "streaming_not_connected" -> BroadcastFailure.YOUTUBE_NOT_CONNECTED
    "live_streaming_blocked" -> BroadcastFailure.YOUTUBE_LIVE_BLOCKED
    "streaming_reconnect_required" -> BroadcastFailure.YOUTUBE_RECONNECT
    "streaming_prepare_failed" -> BroadcastFailure.YOUTUBE_PREPARE
    "broadcast_stopped" -> BroadcastFailure.YOUTUBE_STOPPED
    "broadcast_not_ready" -> BroadcastFailure.YOUTUBE_NOT_READY
    else -> null
}

private fun ServerApiException.toBroadcastEvent(
    fallback: BroadcastFailure,
): BroadcastEvent = toKnownBroadcastFailure()?.let(BroadcastEvent::Failure)
    ?: serverMessage?.takeIf(String::isNotBlank)?.let(BroadcastEvent::ServerMessage)
    ?: BroadcastEvent.Failure(fallback)

private fun Throwable.toBroadcastFailure(): BroadcastFailure =
    (this as? ServerApiException)?.toKnownBroadcastFailure() ?: BroadcastFailure.REQUEST

private fun connectionFailureForServerCode(code: String): ConnectionFailure = when (code) {
    "session_already_exists" -> ConnectionFailure.EXISTING_BROADCAST
    else -> ConnectionFailure.GENERIC
}

private fun Throwable.toConnectionFailure(): ConnectionFailure =
    (this as? ConnectionFailureException)?.failure ?: ConnectionFailure.GENERIC

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

internal fun extractIceUfrags(sdp: String): Set<String> =
    Regex("(?m)^a=ice-ufrag:([^\\r\\n]+)")
        .findAll(sdp)
        .map { it.groupValues[1].trim() }
        .filter(String::isNotEmpty)
        .toSet()

internal fun extractCandidateUfrag(candidate: String): String? =
    Regex("(?:^|\\s)ufrag\\s+(\\S+)")
        .find(candidate)
        ?.groupValues
        ?.get(1)

private fun requireSuccessful(response: Response, operation: String) {
    if (!response.isSuccessful) {
        throw IOException("$operation 실패: HTTP ${response.code}")
    }
}

private fun parseServerApiException(payload: String): ServerApiException {
    val error = runCatching { JSONObject(payload).optJSONObject("error") }.getOrNull()
    val code = error?.optString("code")?.takeIf { it.isNotBlank() }
    val serverMessage = error?.optString("message")?.takeIf { it.isNotBlank() }
    return ServerApiException(code, serverMessage)
}

internal fun buildBroadcastSettingsPayload(settings: BroadcastSettings): JSONObject = JSONObject()
    .put("title", settings.title.trim())
    .put("description", settings.description)
    .put("privacy", settings.privacy)
    .put("made_for_kids", settings.madeForKids)
    .put("category_id", settings.categoryId.trim())
