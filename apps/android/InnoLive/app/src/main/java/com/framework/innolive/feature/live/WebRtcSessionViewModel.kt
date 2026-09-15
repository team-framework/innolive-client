package com.framework.innolive.feature.live

import android.content.Context
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.framework.innolive.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import kotlin.coroutines.resume

class WebRtcSessionViewModel : ViewModel() {
    private var startJob: Job? = null
    private var selectedAudioInput: AudioDeviceInfo? = null
    private var sessionState by mutableStateOf(WebRtcSessionState())
    private val mainHandler = Handler(Looper.getMainLooper())

    val connectionState: WebRtcConnectionState
        get() = sessionState.connection
    val anonymizationState: AnonymizationState
        get() = sessionState.anonymization
    val anonymizationChange: AnonymizationChange
        get() = sessionState.anonymizationChange
    var connectionStatus by mutableStateOf("")
        private set
    var remoteVideoTrack by mutableStateOf<VideoTrack?>(null)
        private set
    var broadcastState by mutableStateOf(BroadcastState.IDLE)
        private set
    var broadcastStatus by mutableStateOf("방송 대기")
        private set

    var frameAnalyzer: CameraFrameAnalyzer? by mutableStateOf(null)
        private set
    var eglContext: EglBase.Context? by mutableStateOf(null)
        private set

    private var connection: WebRtcConnection? = null
    private var closingConnection: WebRtcConnection? = null
    private var anonymizationPreference: AnonymizationPreference? = null
    var selectedAnonymizationEnabled by mutableStateOf(true)
        private set

    var isAnonymizationSelectionLoaded by mutableStateOf(false)
        private set

    fun restoreAnonymizationSelection(context: Context) {
        if (isAnonymizationSelectionLoaded) return
        val preference = AnonymizationPreference(context)
        anonymizationPreference = preference
        selectedAnonymizationEnabled = preference.enabled
        isAnonymizationSelectionLoaded = true
    }

    // 클릭 시점의 실제 연결 상태로 분기하여 오래된 화면 상태로 요청하지 않습니다.
    fun selectAnonymization(context: Context, enabled: Boolean): Boolean =
        if (connectionState == WebRtcConnectionState.CONNECTED) setAnonymizationEnabled(enabled)
        else selectInitialAnonymization(context, enabled)

    // 연결 중에는 초기 선택을 바꾸지 않고, 연결된 세션은 변경 API로만 갱신합니다.
    fun selectInitialAnonymization(context: Context, enabled: Boolean): Boolean {
        if (connectionState == WebRtcConnectionState.CONNECTING ||
            connectionState == WebRtcConnectionState.CONNECTED) return false
        val preference = AnonymizationPreference(context)
        preference.enabled = enabled
        anonymizationPreference = preference
        selectedAnonymizationEnabled = enabled
        isAnonymizationSelectionLoaded = true
        return true
    }

    fun selectAudioInput(audioInput: AudioDeviceInfo?) {
        selectedAudioInput = audioInput
        connection?.setPreferredAudioInput(audioInput)
    }

    fun start(
        context: Context,
        refreshAccessToken: suspend () -> String,
    ) {
        if (
            connectionState == WebRtcConnectionState.CONNECTING ||
            connectionState == WebRtcConnectionState.CONNECTED
        ) {
            return
        }

        val preference = AnonymizationPreference(context)
        anonymizationPreference = preference
        selectedAnonymizationEnabled = preference.enabled
        isAnonymizationSelectionLoaded = true
        val initialEnabled = selectedAnonymizationEnabled
        sessionState = sessionState.beginConnection()
        val generation = sessionState.generation
        startJob?.cancel()
        startJob = null
        val previousConnection = connection ?: closingConnection
        closingConnection = previousConnection
        connection = null
        remoteVideoTrack = null
        frameAnalyzer = null
        eglContext = null
        if (previousConnection != null) {
            broadcastState = BroadcastState.IDLE
            broadcastStatus = "방송 대기"
        }

        connectionStatus = "연결 준비 중…"
        startJob = viewModelScope.launch {
            try {
                previousConnection?.let { oldConnection -> awaitClose(oldConnection) }
                if (closingConnection === previousConnection) closingConnection = null
                if (!isCurrentGeneration(generation)) return@launch

                val accessToken = refreshAccessToken()
                if (!isCurrentGeneration(generation)) return@launch

                val newConnection = WebRtcConnection(
                    context = context,
                    serverUrl = BuildConfig.INNOLIVE_SERVER_URL,
                    accessToken = accessToken,
                    initialAnonymizationEnabled = initialEnabled,
                    preferredAudioInput = selectedAudioInput,
                    onStateChanged = { state, message ->
                        if (sessionState.acceptsCallback(generation)) {
                            sessionState = sessionState.connectionChanged(generation, state)
                            connectionStatus = connectionUserMessage(state, message)
                        }
                    },
                    onAnonymizationStateConfirmed = { state ->
                        sessionState = sessionState.anonymizationConfirmed(generation, state)
                    },
                    onRemoteTrackChanged = { track ->
                        if (isCurrentGeneration(generation)) remoteVideoTrack = track
                    },
                    onLocalMediaReady = { analyzer, context ->
                        mainHandler.post {
                            if (isCurrentGeneration(generation)) {
                                frameAnalyzer = analyzer
                                eglContext = context
                            }
                        }
                    },
                    onLocalMediaCleared = {
                        mainHandler.post {
                            if (isCurrentGeneration(generation)) {
                                frameAnalyzer = null
                                eglContext = null
                            }
                        }
                    },
                    onBroadcastStateChanged = { state, message ->
                        if (isCurrentGeneration(generation)) {
                            broadcastState = state
                            broadcastStatus = if (state == BroadcastState.FAILED) broadcastUserMessage(message) else message
                        }
                    },
                )
                if (!isCurrentGeneration(generation)) {
                    newConnection.close()
                    return@launch
                }
                connection = newConnection
                newConnection.start()
            } catch (exception: CancellationException) {
                if (isCurrentGeneration(generation)) {
                    sessionState = sessionState.connectionChanged(generation, WebRtcConnectionState.IDLE)
                    connectionStatus = ""
                }
                throw exception
            } catch (exception: Exception) {
                if (isCurrentGeneration(generation)) {
                    sessionState = sessionState.connectionChanged(generation, WebRtcConnectionState.FAILED)
                    connectionStatus = connectionUserMessage(WebRtcConnectionState.FAILED, exception.message.orEmpty())
                }
            }
        }
    }

    // 서버가 변경을 확인한 경우에만 다음 연결의 기본 선택을 갱신합니다.
    fun setAnonymizationEnabled(enabled: Boolean): Boolean {
        val currentConnection = connection ?: return false
        val next = sessionState.beginAnonymizationChange(enabled)
        if (next == sessionState) return false
        sessionState = next
        val generation = next.generation
        val requestId = next.anonymizationChange.requestId
        currentConnection.setAnonymizationEnabled(enabled) { confirmed, error ->
            val nextState = sessionState.finishAnonymizationChange(generation, requestId, confirmed, error)
            if (nextState != sessionState && error == null &&
                confirmed == if (enabled) AnonymizationState.ENABLED else AnonymizationState.DISABLED) {
                anonymizationPreference?.enabled = enabled
                selectedAnonymizationEnabled = enabled
            }
            sessionState = nextState
        }
        return true
    }

    fun saveBroadcastSettings(settings: BroadcastSettings) {
        connection?.saveBroadcastSettings(settings)
            ?: run {
                broadcastState = BroadcastState.FAILED
                broadcastStatus = "미리보기를 먼저 연결해 주세요."
            }
    }

    fun prepareBroadcast(settings: BroadcastSettings) {
        connection?.prepareBroadcast(settings)
            ?: run {
                broadcastState = BroadcastState.FAILED
                broadcastStatus = "미리보기를 먼저 연결해 주세요."
            }
    }

    fun goLive() {
        connection?.goLive()
    }

    fun stopBroadcast() {
        connection?.stopBroadcast()
    }

    fun close() {
        sessionState = sessionState.endConnection()
        startJob?.cancel()
        startJob = null
        val currentConnection = connection
        closingConnection = currentConnection ?: closingConnection
        connection = null
        remoteVideoTrack = null
        frameAnalyzer = null
        eglContext = null
        currentConnection?.close()
        connectionStatus = ""
        broadcastState = BroadcastState.IDLE
        broadcastStatus = "방송 대기"
    }

    override fun onCleared() {
        close()
    }

    private fun isCurrentGeneration(generation: Long): Boolean =
        sessionState.generation == generation

    private suspend fun awaitClose(webRtcConnection: WebRtcConnection) {
        suspendCancellableCoroutine { continuation ->
            webRtcConnection.close {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }
}
