package com.framework.innolive.feature.live

import android.content.Context
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.framework.innolive.BuildConfig
import com.framework.innolive.feature.live.components.validateYouTubeLiveSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import kotlin.coroutines.resume

class WebRtcSessionViewModel : ViewModel() {
    private var startJob: Job? = null
    private var prepareJob: Job? = null
    var isPreparingBroadcast by mutableStateOf(false)
        private set
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
    var broadcastStartedAtElapsedRealtimeMillis by mutableStateOf<Long?>(null)
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
            broadcastStartedAtElapsedRealtimeMillis = null
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
                            if (state == WebRtcConnectionState.FAILED && broadcastState != BroadcastState.IDLE) {
                                broadcastState = BroadcastState.FAILED
                                broadcastStatus = "연결이 끊겨 방송 준비를 계속할 수 없습니다. 다시 시도해 주세요."
                                broadcastStartedAtElapsedRealtimeMillis = null
                            }
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
                        if (sessionState.acceptsCallback(generation)) {
                            broadcastStartedAtElapsedRealtimeMillis = nextBroadcastStartedAt(
                                currentStartedAtMillis = broadcastStartedAtElapsedRealtimeMillis,
                                state = state,
                                nowMillis = SystemClock.elapsedRealtime(),
                            )
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

    // 사용자의 확인 한 번으로 연결부터 준비까지 진행하되, 연결 성공 전에 방송 API를 호출하지 않습니다.
    fun prepareBroadcast(
        context: Context,
        settings: BroadcastSettings,
        refreshAccessToken: suspend () -> String,
    ): Boolean {
        if (isPreparingBroadcast || !broadcastState.canPrepare) return false
        if (!validateYouTubeLiveSettings(settings).isValid) {
            broadcastState = BroadcastState.FAILED
            broadcastStatus = "방송 제목, 설명과 아동용 콘텐츠 여부를 확인해 주세요."
            return false
        }
        if (readMediaPermissionState(context).missingPermissions.isNotEmpty()) {
            broadcastState = BroadcastState.FAILED
            broadcastStatus = "카메라와 마이크 권한을 허용한 뒤 다시 시도해 주세요."
            return false
        }
        isPreparingBroadcast = true
        broadcastState = BroadcastState.IDLE
        broadcastStatus = "방송 준비 중"
        start(context.applicationContext, refreshAccessToken)
        val generation = sessionState.generation
        prepareJob = viewModelScope.launch {
            try {
                withTimeout(45_000) {
                    snapshotFlow { sessionState }.first {
                        it.generation != generation || it.connection != WebRtcConnectionState.CONNECTING
                    }
                }
                if (!isCurrentGeneration(generation)) return@launch
                val activeConnection = connection
                if (connectionState != WebRtcConnectionState.CONNECTED || activeConnection == null) {
                    broadcastState = BroadcastState.FAILED
                    broadcastStatus = "연결하지 못해 방송을 준비하지 못했습니다. 다시 시도해 주세요."
                    return@launch
                }
                requestBroadcastPreparation(activeConnection, settings)
            } catch (_: TimeoutCancellationException) {
                if (isCurrentGeneration(generation)) {
                    close()
                    broadcastState = BroadcastState.FAILED
                    broadcastStatus = "연결 시간이 초과되었습니다. 방송 준비를 다시 시도해 주세요."
                }
            } finally {
                if (isCurrentGeneration(generation)) isPreparingBroadcast = false
            }
        }
        return true
    }

    // 네이티브 작업의 상태 콜백이 도착하기 전에도 중복 준비를 막습니다.
    internal fun requestBroadcastPreparation(
        activeConnection: WebRtcConnection,
        settings: BroadcastSettings,
    ): Boolean {
        broadcastState = BroadcastState.SAVING_SETTINGS
        broadcastStatus = "방송 설정 저장 중"
        if (activeConnection.prepareBroadcast(settings)) return true

        broadcastState = BroadcastState.FAILED
        broadcastStatus = "방송 준비 요청을 시작하지 못했습니다. 다시 시도해 주세요."
        return false
    }

    fun goLive() {
        connection?.goLive()
    }

    fun pauseBroadcast() {
        connection?.pauseBroadcast()
    }

    fun resumeBroadcast() {
        connection?.resumeBroadcast()
    }

    fun stopBroadcast() {
        connection?.stopBroadcast()
    }

    fun close() {
        sessionState = sessionState.endConnection()
        prepareJob?.cancel()
        prepareJob = null
        isPreparingBroadcast = false
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
        broadcastStartedAtElapsedRealtimeMillis = null
    }

    /** Removes only the deleted account's credentials for its current server. */
    fun clearSessionRecovery(context: Context, accessToken: String) {
        val scope = sessionRecoveryScope(BuildConfig.INNOLIVE_SERVER_URL, accessToken)
        EncryptedSessionRecoveryStore(context.applicationContext).clear(scope)
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
