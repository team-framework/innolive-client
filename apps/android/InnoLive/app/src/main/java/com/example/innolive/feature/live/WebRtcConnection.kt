package com.example.innolive.feature.live

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.Executors
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
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val serverBaseUrl = serverUrl.trim().trimEnd('/').toHttpUrl().also { url ->
        require(url.isHttps) { "INNOLIVE_SERVER_URL must use HTTPS." }
    }
    private val audioManager = applicationContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
    private val eglBase = EglBase.create()
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val terminal = AtomicBoolean(false)
    private val audioRouteMonitor = AudioInputRouteMonitor(
        applicationContext,
        ::onAudioRouteChanged,
    )
    private val audioDeviceModule = createAudioDeviceModule()
    private val peerConnectionFactory = createPeerConnectionFactory()
    private val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
    private val localAudioTrack = peerConnectionFactory.createAudioTrack("microphone-audio", audioSource)
    private val videoSource = peerConnectionFactory.createVideoSource(false)
    private val localVideoTrack = peerConnectionFactory.createVideoTrack("camera-video", videoSource)
    private val signalLock = Any()
    private val pendingSignals = mutableListOf<String>()
    private val connectionTimeout = Runnable {
        fail("WebRTC 연결 시간이 초과되었습니다.")
    }
    private val audioRouteVerification = Runnable {
        verifyAudioRoute()
    }

    val frameAnalyzer = CameraFrameAnalyzer(videoSource.capturerObserver)
    val eglContext: EglBase.Context = eglBase.eglBaseContext

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

    init {
        require(accessToken.isNotBlank()) { "Access token must not be blank." }
    }

    fun start() {
        if (!started.compareAndSet(false, true) || closed.get()) return

        try {
            updateBluetoothCommunicationRoute(preferredAudioInput)
        } catch (exception: Exception) {
            fail(exception.message ?: "Bluetooth 오디오 기기를 준비하지 못했습니다.")
            return
        }
        audioRouteMonitor.start()
        updateState(WebRtcConnectionState.CONNECTING, "WebRTC 연결 준비 중")
        mainHandler.postDelayed(connectionTimeout, CONNECTION_TIMEOUT_MILLIS)
        ioExecutor.execute {
            try {
                val iceServers = loadIceServers()
                if (closed.get()) return@execute

                val createdSession = createSession()
                session = createdSession
                if (closed.get()) {
                    deleteSession(createdSession)
                    return@execute
                }

                val connection = createPeerConnection(iceServers)
                if (closed.get()) {
                    connection.close()
                    connection.dispose()
                    return@execute
                }
                peerConnection = connection
                addAudioTransceiver(connection)
                addVideoTransceiver(connection)
                frameAnalyzer.start()
                openSignalingSocket(createdSession)
            } catch (exception: Exception) {
                fail(exception.message ?: "WebRTC 연결을 시작하지 못했습니다.")
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        terminal.set(true)
        mainHandler.removeCallbacks(connectionTimeout)
        releasePeerConnection()
        val createdSession = takeSession()
        ioExecutor.execute {
            createdSession?.let(::deleteSession)
            localAudioTrack.dispose()
            audioSource.dispose()
            localVideoTrack.dispose()
            videoSource.dispose()
            peerConnectionFactory.dispose()
            audioDeviceModule.release()
            eglBase.release()
            httpClient.connectionPool.evictAll()
            httpClient.dispatcher.executorService.shutdown()
        }
        ioExecutor.shutdown()
    }

    private fun createPeerConnectionFactory(): PeerConnectionFactory {
        if (factoryInitialized.compareAndSet(false, true)) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(applicationContext)
                    .createInitializationOptions(),
            )
        }
        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true),
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun setPreferredAudioInput(audioInput: AudioDeviceInfo?) {
        mainHandler.post {
            if (closed.get() || terminal.get()) return@post

            try {
                updateBluetoothCommunicationRoute(audioInput)
            } catch (exception: Exception) {
                fail(exception.message ?: "Bluetooth 오디오 기기를 준비하지 못했습니다.")
                return@post
            }
            preferredAudioInput = audioInput
            audioInput?.let(audioDeviceModule::setPreferredInputDevice)
            resetAudioRouteVerification()
        }
    }

    private fun updateBluetoothCommunicationRoute(audioInput: AudioDeviceInfo?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        if (!isBluetoothAudioInput(audioInput)) {
            audioManager.clearCommunicationDevice()
            return
        }

        val bluetoothInput = checkNotNull(audioInput)
        val communicationDevices = audioManager.availableCommunicationDevices
            .filter { device -> device.type == bluetoothInput.type }
        val communicationDevice = communicationDevices
            .firstOrNull { device -> device.address == bluetoothInput.address }
            ?: communicationDevices.singleOrNull()
            ?: throw IllegalStateException("선택한 Bluetooth 오디오 기기의 통신용 출력을 찾지 못했습니다.")
        check(audioManager.setCommunicationDevice(communicationDevice)) {
            "선택한 Bluetooth 오디오 기기를 통신 장치로 설정하지 못했습니다."
        }
    }

    private fun isBluetoothAudioInput(audioInput: AudioDeviceInfo?): Boolean = when (audioInput?.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        -> true

        else -> false
    }

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
                    mainHandler.post {
                        if (closed.get() || terminal.get()) return@post

                        audioRecordingStarted = true
                        resetAudioRouteVerification()
                    }
                }

                override fun onWebRtcAudioRecordStop() {
                    mainHandler.post {
                        audioRecordingStarted = false
                        mainHandler.removeCallbacks(audioRouteVerification)
                        if (!closed.get() && !terminal.get()) {
                            fail("오디오 입력이 중지되었습니다.")
                        }
                    }
                }
            },
        )
        .createAudioDeviceModule()
        .also { audioDeviceModule ->
            preferredAudioInput?.let(audioDeviceModule::setPreferredInputDevice)
        }

    private fun addAudioTransceiver(connection: PeerConnection) {
        val audioTransceiver = checkNotNull(
            connection.addTransceiver(
                localAudioTrack,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                ),
            ),
        ) { "Unable to add the microphone audio transceiver." }
        val opusCodecs = peerConnectionFactory
            .getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO)
            .codecs
            .filter { codec -> codec.name.equals("opus", ignoreCase = true) }
        require(opusCodecs.isNotEmpty()) { "Opus audio codec is unavailable." }
        check(audioTransceiver.setCodecPreferences(opusCodecs).isSuccess()) {
            "Unable to set the Opus audio codec."
        }
    }

    private fun addVideoTransceiver(connection: PeerConnection) {
        check(
            connection.addTransceiver(
                localVideoTrack,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                ),
            ) != null,
        ) { "Unable to add the camera video transceiver." }
    }

    private fun loadIceServers(): List<PeerConnection.IceServer> {
        val request = authenticatedRequest("/webrtc/config").get().build()
        return httpClient.newCall(request).execute().use { response ->
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

        return httpClient.newCall(request).execute().use { response ->
            requireSuccessful(response, "WebRTC 세션 생성")
            parseCreatedSession(response.body.string())
        }
    }

    private fun createPeerConnection(
        iceServers: List<PeerConnection.IceServer>,
    ): PeerConnection = checkNotNull(
        peerConnectionFactory.createPeerConnection(
            PeerConnection.RTCConfiguration(iceServers),
            peerConnectionObserver,
        ),
    ) { "Unable to create the WebRTC peer connection." }

    private fun openSignalingSocket(createdSession: CreatedSession) {
        val httpUrl = checkNotNull(serverBaseUrl.resolve("/signaling"))
        val signalingUrl = httpUrl.toString().replaceFirst("https://", "wss://")
        val request = Request.Builder().url(signalingUrl).build()

        webSocket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (closed.get()) {
                        webSocket.close(1000, null)
                        return
                    }
                    createOffer(createdSession)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleServerMessage(text)
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    if (!terminal.get()) {
                        fail(t.message ?: "WebRTC signaling 연결에 실패했습니다.")
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!terminal.get()) {
                        fail("WebRTC signaling 연결이 종료되었습니다.")
                    }
                }
            },
        )
    }

    private fun createOffer(createdSession: CreatedSession) {
        val connection = peerConnection ?: return
        connection.createOffer(
            object : SdpObserverAdapter() {
                override fun onCreateSuccess(description: SessionDescription) {
                    connection.setLocalDescription(
                        object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                sendOffer(createdSession, description.description)
                            }

                            override fun onSetFailure(error: String) {
                                fail(error)
                            }
                        },
                        description,
                    )
                }

                override fun onCreateFailure(error: String) {
                    fail(error)
                }
            },
            MediaConstraints(),
        )
    }

    private fun sendOffer(createdSession: CreatedSession, sdp: String) {
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
        try {
            when (val message = parseServerMessage(payload)) {
                is ServerMessage.Answer -> applyAnswer(message.sdp)
                is ServerMessage.Error -> fail(message.message)
                ServerMessage.IceCandidateAdded -> Unit
            }
        } catch (exception: Exception) {
            fail(exception.message ?: "서버 signaling 응답이 올바르지 않습니다.")
        }
    }

    private fun applyAnswer(sdp: String) {
        val connection = peerConnection ?: return
        connection.setRemoteDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    updateState(WebRtcConnectionState.CONNECTING, "서버 영상 수신 대기 중")
                }

                override fun onSetFailure(error: String) {
                    fail(error)
                }
            },
            SessionDescription(SessionDescription.Type.ANSWER, sdp),
        )
    }

    private fun attachRemoteTrack(receiver: RtpReceiver) {
        val track = receiver.track() as? VideoTrack ?: return
        mainHandler.post {
            if (!closed.get()) onRemoteTrackChanged(track)
        }
    }

    private fun onAudioRouteChanged(deviceId: Int?, isSilenced: Boolean) {
        mainHandler.post {
            if (closed.get() || terminal.get()) return@post

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
        mainHandler.removeCallbacks(audioRouteVerification)
        mainHandler.postDelayed(audioRouteVerification, AUDIO_ROUTE_VERIFICATION_DELAY_MILLIS)
    }

    private fun verifyAudioRoute() {
        if (closed.get() || terminal.get() || audioInputVerified) return

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
            preferredAudioInput?.let(audioDeviceModule::setPreferredInputDevice)
            audioRouteMonitor.refresh()
            scheduleAudioRouteVerification()
            return
        }

        fail("선택한 오디오 기기를 실제 입력으로 적용하지 못했습니다.")
    }

    private fun updateConnectedState() {
        if (!peerConnectionConnected || !audioInputVerified) return

        mainHandler.removeCallbacks(connectionTimeout)
        updateState(WebRtcConnectionState.CONNECTED, "WebRTC 연결됨")
    }

    private fun fail(message: String) {
        if (closed.get() || !terminal.compareAndSet(false, true)) return

        mainHandler.removeCallbacks(connectionTimeout)
        releasePeerConnection()
        val createdSession = takeSession()
        if (createdSession != null) {
            runCatching {
                ioExecutor.execute { deleteSession(createdSession) }
            }
        }
        updateState(WebRtcConnectionState.FAILED, message)
    }

    private fun releasePeerConnection() {
        runCatching { clearBluetoothCommunicationRoute() }
        frameAnalyzer.stop()
        audioRouteMonitor.close()
        mainHandler.removeCallbacks(audioRouteVerification)
        audioRecordingStarted = false
        audioInputVerified = false
        peerConnectionConnected = false
        synchronized(signalLock) {
            pendingSignals.clear()
            offerSent = false
        }
        webSocket?.close(1000, null)
        webSocket = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        mainHandler.post { onRemoteTrackChanged(null) }
    }

    @Synchronized
    private fun takeSession(): CreatedSession? = session.also { session = null }

    private fun deleteSession(createdSession: CreatedSession) {
        val request = authenticatedRequest("/sessions/${createdSession.sessionId}")
            .header("X-Session-Owner-Token", createdSession.ownerToken)
            .delete()
            .build()
        runCatching {
            httpClient.newCall(request).execute().close()
        }
    }

    private fun authenticatedRequest(path: String): Request.Builder {
        val endpoint = checkNotNull(serverBaseUrl.resolve(path))
        return Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $accessToken")
    }

    private fun updateState(state: WebRtcConnectionState, message: String) {
        mainHandler.post {
            if (!closed.get()) onStateChanged(state, message)
        }
    }

    private val peerConnectionObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                sendIceCandidate(null)
            }
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            sendIceCandidate(candidate)
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

        override fun onAddStream(stream: MediaStream) = Unit

        override fun onRemoveStream(stream: MediaStream) = Unit

        override fun onDataChannel(channel: DataChannel) = Unit

        override fun onRenegotiationNeeded() = Unit

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    mainHandler.post {
                        if (closed.get() || terminal.get()) return@post

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
            attachRemoteTrack(receiver)
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            attachRemoteTrack(transceiver.receiver)
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
    }
}

private data class CreatedSession(
    val sessionId: String,
    val ownerToken: String,
)

internal sealed interface ServerMessage {
    data class Answer(val sdp: String) : ServerMessage

    data class Error(val message: String) : ServerMessage

    data object IceCandidateAdded : ServerMessage
}

internal fun parseServerMessage(payload: String): ServerMessage {
    val response = JSONObject(payload)
    return when (val type = response.optString("type")) {
        "answer" -> ServerMessage.Answer(
            response.optString("sdp").takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("서버 answer에 SDP가 없습니다."),
        )

        "error" -> ServerMessage.Error(
            response.optJSONObject("error")
                ?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: "서버가 WebRTC 연결을 거부했습니다.",
        )

        "ice_candidate_added" -> ServerMessage.IceCandidateAdded
        else -> throw IllegalArgumentException("지원하지 않는 signaling 응답입니다: $type")
    }
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
