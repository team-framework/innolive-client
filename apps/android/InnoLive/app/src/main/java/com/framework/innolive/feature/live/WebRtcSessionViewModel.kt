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
import com.framework.innolive.ui.text.UiText
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
    var connectionStatus by mutableStateOf<UiText?>(null)
        private set
    var remoteVideoTrack by mutableStateOf<VideoTrack?>(null)
        private set
    var broadcastState by mutableStateOf(BroadcastState.IDLE)
        private set
    var broadcastStatus by mutableStateOf(broadcastStateMessage(BroadcastState.IDLE).text)
        private set
    var isBroadcastStatusDefault by mutableStateOf(true)
        private set
    var broadcastStartedAtElapsedRealtimeMillis by mutableStateOf<Long?>(null)
        private set

    var frameAnalyzer: CameraFrameAnalyzer? by mutableStateOf(null)
        private set
    var eglContext: EglBase.Context? by mutableStateOf(null)
        private set
    var lockedBroadcastRotation: Int? by mutableStateOf(null)
        private set
    var lockedScreenOrientation: Int? by mutableStateOf(null)
        private set

    private var connection: WebRtcConnection? = null
    private var closingConnection: WebRtcConnection? = null
    private var anonymizationPreference: AnonymizationPreference? = null
    private var aiProcessingPreference: AIProcessingPreference? = null
    var selectedOnDeviceProcessing by mutableStateOf(false)
        private set
    var isAIProcessingSelectionLoaded by mutableStateOf(false)
        private set
    var isAIProcessingChanging by mutableStateOf(false)
        private set
    var aiProcessingChangeFailed by mutableStateOf(false)
        private set
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

    fun restoreAIProcessingSelection(context: Context) {
        if (isAIProcessingSelectionLoaded) return
        aiProcessingPreference = AIProcessingPreference(context).also {
            selectedOnDeviceProcessing = it.onDevice
        }
        isAIProcessingSelectionLoaded = true
    }

    fun selectInitialAIProcessing(context: Context, onDevice: Boolean): Boolean {
        if (connectionState == WebRtcConnectionState.CONNECTING ||
            connectionState == WebRtcConnectionState.RECONNECTING ||
            connectionState == WebRtcConnectionState.CONNECTED) return false
        val preference = AIProcessingPreference(context)
        preference.onDevice = onDevice
        aiProcessingPreference = preference
        selectedOnDeviceProcessing = onDevice
        aiProcessingChangeFailed = false
        isAIProcessingSelectionLoaded = true
        return true
    }

    fun selectAIProcessing(context: Context, onDevice: Boolean): Boolean {
        if (isAIProcessingChanging || isPreparingBroadcast ||
            broadcastState != BroadcastState.IDLE) return false
        if (connectionState != WebRtcConnectionState.CONNECTED) {
            return selectInitialAIProcessing(context, onDevice)
        }
        if (sessionState.anonymizationChange.status == AnonymizationChangeStatus.CHANGING) return false
        val currentConnection = connection ?: return false
        val generation = sessionState.generation
        isAIProcessingChanging = true
        aiProcessingChangeFailed = false
        currentConnection.setAIProcessingMode(onDevice, selectedAnonymizationEnabled) { success ->
            if (!isCurrentGeneration(generation)) return@setAIProcessingMode
            isAIProcessingChanging = false
            aiProcessingChangeFailed = !success
            if (success) {
                AIProcessingPreference(context).also { it.onDevice = onDevice; aiProcessingPreference = it }
                selectedOnDeviceProcessing = onDevice
            }
        }
        return true
    }

    // 클릭 시점의 실제 연결 상태로 분기하여 오래된 화면 상태로 요청하지 않습니다.
    fun selectAnonymization(context: Context, enabled: Boolean): Boolean =
        if (isAIProcessingChanging || aiProcessingChangeFailed) false
        else if (connectionState == WebRtcConnectionState.CONNECTED) setAnonymizationEnabled(enabled)
        else selectInitialAnonymization(context, enabled)

    // 연결 중에는 초기 선택을 바꾸지 않고, 연결된 세션은 변경 API로만 갱신합니다.
    fun selectInitialAnonymization(context: Context, enabled: Boolean): Boolean {
        if (connectionState == WebRtcConnectionState.CONNECTING ||
            connectionState == WebRtcConnectionState.RECONNECTING ||
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
            connectionState == WebRtcConnectionState.RECONNECTING ||
            connectionState == WebRtcConnectionState.CONNECTED
        ) {
            return
        }

        val preference = AnonymizationPreference(context)
        anonymizationPreference = preference
        selectedAnonymizationEnabled = preference.enabled
        isAnonymizationSelectionLoaded = true
        val initialEnabled = selectedAnonymizationEnabled
        restoreAIProcessingSelection(context)
        val initialOnDevice = selectedOnDeviceProcessing
        sessionState = sessionState.beginConnection()
        isAIProcessingChanging = false
        aiProcessingChangeFailed = false
        lockedBroadcastRotation = null
        lockedScreenOrientation = null
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
            broadcastStatus = broadcastStateMessage(BroadcastState.IDLE).text
            isBroadcastStatusDefault = true
            broadcastStartedAtElapsedRealtimeMillis = null
        }

        connectionStatus = UiText.Resource(com.framework.innolive.R.string.preview_connecting)
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
                    refreshAccessToken = refreshAccessToken,
                    initialAnonymizationEnabled = initialEnabled,
                    initialOnDeviceProcessing = initialOnDevice,
                    preferredAudioInput = selectedAudioInput,
                    onStateChanged = { state, failure ->
                        if (sessionState.acceptsCallback(generation)) {
                            sessionState = sessionState.connectionChanged(generation, state)
                            connectionStatus = connectionUserMessage(state, failure)
                            if (state == WebRtcConnectionState.FAILED) {
                                isAIProcessingChanging = false
                                lockedBroadcastRotation = null
                                lockedScreenOrientation = null
                            }
                            if (state == WebRtcConnectionState.FAILED && broadcastState != BroadcastState.IDLE) {
                                broadcastState = BroadcastState.FAILED
                                broadcastStatus = UiText.Resource(
                                    com.framework.innolive.R.string.error_broadcast_connection_lost,
                                )
                                isBroadcastStatusDefault = false
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
                    onBroadcastStateChanged = { state, event ->
                        if (sessionState.acceptsCallback(generation)) {
                            lockedBroadcastRotation = nextBroadcastRotation(
                                lockedBroadcastRotation,
                                state,
                            )
                            if (lockedBroadcastRotation == null) lockedScreenOrientation = null
                            broadcastStartedAtElapsedRealtimeMillis = nextBroadcastStartedAt(
                                currentStartedAtMillis = broadcastStartedAtElapsedRealtimeMillis,
                                state = state,
                                nowMillis = SystemClock.elapsedRealtime(),
                            )
                            broadcastState = state
                            val feedback = broadcastUserMessage(state, event)
                            broadcastStatus = feedback.text
                            isBroadcastStatusDefault = feedback.isStateDescription
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
                    connectionStatus = null
                }
                throw exception
            } catch (exception: Exception) {
                if (isCurrentGeneration(generation)) {
                    sessionState = sessionState.connectionChanged(generation, WebRtcConnectionState.FAILED)
                    connectionStatus = connectionUserMessage(
                        WebRtcConnectionState.FAILED,
                        (exception as? ConnectionFailureException)?.failure
                            ?: ConnectionFailure.GENERIC,
                    )
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
            val nextState = sessionState.finishAnonymizationChange(
                generation,
                requestId,
                confirmed,
                error?.let(::anonymizationUserMessage),
            )
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
                broadcastStatus = UiText.Resource(com.framework.innolive.R.string.error_preview_required)
                isBroadcastStatusDefault = false
            }
    }

    // 사용자의 확인 한 번으로 연결부터 준비까지 진행하되, 연결 성공 전에 방송 API를 호출하지 않습니다.
    fun prepareBroadcast(
        context: Context,
        settings: BroadcastSettings,
        refreshAccessToken: suspend () -> String,
    ): Boolean {
        if (isPreparingBroadcast || isAIProcessingChanging || aiProcessingChangeFailed ||
            !broadcastState.canPrepare) return false
        if (!validateYouTubeLiveSettings(settings).isValid) {
            broadcastState = BroadcastState.FAILED
            broadcastStatus = UiText.Resource(com.framework.innolive.R.string.error_broadcast_validation)
            isBroadcastStatusDefault = false
            return false
        }
        if (readMediaPermissionState(context).missingPermissions.isNotEmpty()) {
            broadcastState = BroadcastState.FAILED
            broadcastStatus = UiText.Resource(com.framework.innolive.R.string.error_media_permission)
            isBroadcastStatusDefault = false
            return false
        }
        isPreparingBroadcast = true
        broadcastState = BroadcastState.IDLE
        broadcastStatus = broadcastStateMessage(BroadcastState.PREPARING).text
        isBroadcastStatusDefault = true
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
                    broadcastStatus = UiText.Resource(com.framework.innolive.R.string.error_broadcast_connect)
                    isBroadcastStatusDefault = false
                    return@launch
                }
                requestBroadcastPreparation(activeConnection, settings)
            } catch (_: TimeoutCancellationException) {
                if (isCurrentGeneration(generation)) {
                    close()
                    broadcastState = BroadcastState.FAILED
                    broadcastStatus = UiText.Resource(com.framework.innolive.R.string.error_broadcast_timeout)
                    isBroadcastStatusDefault = false
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
        broadcastStatus = broadcastStateMessage(BroadcastState.SAVING_SETTINGS).text
        isBroadcastStatusDefault = true
        if (activeConnection.prepareBroadcast(settings)) return true

        broadcastState = BroadcastState.FAILED
        broadcastStatus = UiText.Resource(com.framework.innolive.R.string.error_broadcast_request)
        isBroadcastStatusDefault = false
        return false
    }

    fun goLive(rotation: Int, screenOrientation: Int, onAccepted: () -> Unit): Boolean {
        if (connectionState != WebRtcConnectionState.CONNECTED) return false
        val activeConnection = connection ?: return false
        val previousRotation = lockedBroadcastRotation
        val previousScreenOrientation = lockedScreenOrientation
        val accepted = activeConnection.goLive {
            lockedBroadcastRotation = rotation
            lockedScreenOrientation = screenOrientation
            onAccepted()
        }
        if (!accepted) {
            lockedBroadcastRotation = previousRotation
            lockedScreenOrientation = previousScreenOrientation
        }
        return accepted
    }

    fun pauseBroadcast() {
        connection?.pauseBroadcast()
    }

    fun resumeBroadcast() {
        if (connectionState != WebRtcConnectionState.CONNECTED) return
        connection?.resumeBroadcast()
    }

    fun stopBroadcast() {
        connection?.stopBroadcast()
    }

    fun close() {
        isAIProcessingChanging = false
        aiProcessingChangeFailed = false
        lockedBroadcastRotation = null
        lockedScreenOrientation = null
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
        connectionStatus = null
        broadcastState = BroadcastState.IDLE
        broadcastStatus = broadcastStateMessage(BroadcastState.IDLE).text
        isBroadcastStatusDefault = true
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
