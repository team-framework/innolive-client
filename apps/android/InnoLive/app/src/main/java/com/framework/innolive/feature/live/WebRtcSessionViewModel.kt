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
import com.framework.innolive.ui.text.ServerErrorGuidance
import com.framework.innolive.ui.text.ServerErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import kotlin.coroutines.resume

class WebRtcSessionViewModel : ViewModel() {
    private var startJob: Job? = null
    private var prepareJob: Job? = null
    var serverError by mutableStateOf<ServerErrorGuidance?>(null)
        private set
    private var preparationSettings: BroadcastSettings? = null
    private var retryBroadcastRequest: (() -> Unit)? = null

    fun dismissServerError() { serverError = null }

    fun confirmConcurrentBroadcast(): Boolean {
        if (serverError?.action != ServerErrorAction.CONFIRM_CONCURRENT ||
            connectionState != WebRtcConnectionState.CONNECTED) return false
        val settings = preparationSettings ?: return false
        val activeConnection = connection ?: return false
        serverError = null
        return requestBroadcastPreparation(activeConnection, settings, allowConcurrent = true)
    }

    fun retryBroadcast() {
        if (serverError?.action != ServerErrorAction.RETRY) return
        val request = retryBroadcastRequest ?: return
        serverError = null
        request()
    }
    var isPreparingBroadcast by mutableStateOf(false)
        private set
    private var selectedAudioInput: AudioDeviceInfo? = null
    private var sessionState by mutableStateOf(WebRtcSessionState())
    private var signalingStartedGeneration by mutableStateOf<Long?>(null)
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
    var localVideoTrack by mutableStateOf<VideoTrack?>(null)
        private set
    var sessionSnapshot by mutableStateOf<SessionSnapshot?>(null)
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
    var selectedOnDeviceProcessing by mutableStateOf(false)
        private set
    var isAIProcessingSelectionLoaded by mutableStateOf(false)
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
        selectedOnDeviceProcessing = AIProcessingPreference(context).onDevice
        isAIProcessingSelectionLoaded = true
    }

    fun selectInitialAIProcessing(context: Context, onDevice: Boolean): Boolean {
        if (connectionState == WebRtcConnectionState.CONNECTING ||
            connectionState == WebRtcConnectionState.RECONNECTING ||
            connectionState == WebRtcConnectionState.CONNECTED) return false
        val preference = AIProcessingPreference(context)
        preference.onDevice = onDevice
        selectedOnDeviceProcessing = onDevice
        isAIProcessingSelectionLoaded = true
        return true
    }

    val canChangeAIProcessing: Boolean
        get() = !isPreparingBroadcast &&
            anonymizationChange.status != AnonymizationChangeStatus.CHANGING &&
            broadcastState.canPrepare && connectionState in setOf(
                WebRtcConnectionState.IDLE,
                WebRtcConnectionState.FAILED,
                WebRtcConnectionState.CONNECTED,
            )

    fun selectAIProcessing(context: Context, onDevice: Boolean): Boolean {
        if (!canChangeAIProcessing) return false
        if (connectionState != WebRtcConnectionState.CONNECTED) {
            return selectInitialAIProcessing(context, onDevice)
        }
        if (selectedOnDeviceProcessing == onDevice) return true
        // The settings screen has no CameraPreview. Reconnect only when the next broadcast
        // preparation binds a camera and can produce the protected frames needed for CONNECTED.
        close()
        return selectInitialAIProcessing(context, onDevice)
    }

    // 클릭 시점의 실제 연결 상태로 분기하여 오래된 화면 상태로 요청하지 않습니다.
    fun selectAnonymization(context: Context, enabled: Boolean): Boolean =
        if (connectionState == WebRtcConnectionState.CONNECTED) setAnonymizationEnabled(enabled)
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

    fun localFacesChanged() { frameAnalyzer?.resetFaceExceptions() }

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
        serverError = null
        preparationSettings = null
        retryBroadcastRequest = null
        sessionState = sessionState.beginConnection()
        sessionSnapshot = null
        signalingStartedGeneration = null
        lockedBroadcastRotation = null
        lockedScreenOrientation = null
        val generation = sessionState.generation
        startJob?.cancel()
        startJob = null
        val previousConnection = connection ?: closingConnection
        closingConnection = previousConnection
        connection = null
        remoteVideoTrack = null
        localVideoTrack = null
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
                            connectionStatus = if (state == WebRtcConnectionState.FAILED && serverError != null) {
                                serverError?.message
                            } else connectionUserMessage(state, failure)
                            if (state == WebRtcConnectionState.FAILED) {
                                sessionSnapshot = null
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
                    onLocalVideoTrackChanged = { track ->
                        mainHandler.post {
                            if (isCurrentGeneration(generation)) localVideoTrack = track
                        }
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
                    onInitialSignalingStarted = {
                        if (isCurrentGeneration(generation)) signalingStartedGeneration = generation
                    },
                    onServerError = { guidance ->
                        if (isCurrentGeneration(generation)) serverError = guidance
                    },
                    onSessionSnapshotChanged = { snapshot ->
                        if (sessionState.acceptsCallback(generation)) sessionSnapshot = snapshot
                    },
                    onBroadcastStateChanged = { state, event ->
                        if (sessionState.acceptsCallback(generation) && (state != broadcastState || event != null)) {
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
                            if (event is BroadcastEvent.ApiFailure) serverError = event.guidance
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
        serverError = null
        retryBroadcastRequest = { saveBroadcastSettings(settings) }
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
        if (isPreparingBroadcast || !broadcastState.canPrepare) return false
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
                awaitInitialConnection(
                    states = snapshotFlow {
                        InitialConnectionWaitState(
                            sessionState.generation,
                            sessionState.connection,
                            signalingStartedGeneration == sessionState.generation,
                        )
                    },
                    generation = generation,
                    setupTimeoutMillis = INITIAL_CONNECTION_SETUP_TIMEOUT_MILLIS,
                    signalingTimeoutMillis = INITIAL_SIGNALING_TIMEOUT_MILLIS + SIGNALING_CALLBACK_GRACE_MILLIS,
                )
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
        allowConcurrent: Boolean = false,
    ): Boolean {
        serverError = null
        preparationSettings = settings
        retryBroadcastRequest = {
            connection?.let { requestBroadcastPreparation(it, settings, allowConcurrent) }
        }
        broadcastState = BroadcastState.SAVING_SETTINGS
        broadcastStatus = broadcastStateMessage(BroadcastState.SAVING_SETTINGS).text
        isBroadcastStatusDefault = true
        if (activeConnection.prepareBroadcast(settings, allowConcurrent)) return true

        broadcastState = BroadcastState.FAILED
        broadcastStatus = UiText.Resource(com.framework.innolive.R.string.error_broadcast_request)
        isBroadcastStatusDefault = false
        return false
    }

    fun goLive(rotation: Int, screenOrientation: Int, onAccepted: () -> Unit): Boolean {
        if (connectionState != WebRtcConnectionState.CONNECTED) return false
        val activeConnection = connection ?: return false
        serverError = null
        retryBroadcastRequest = { goLive(rotation, screenOrientation, onAccepted) }
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
        serverError = null
        retryBroadcastRequest = { pauseBroadcast() }
        connection?.pauseBroadcast()
    }

    fun resumeBroadcast() {
        if (connectionState != WebRtcConnectionState.CONNECTED) return
        serverError = null
        retryBroadcastRequest = { resumeBroadcast() }
        connection?.resumeBroadcast()
    }

    fun stopBroadcast() {
        serverError = null
        retryBroadcastRequest = { stopBroadcast() }
        connection?.stopBroadcast()
    }

    fun close() {
        serverError = null
        preparationSettings = null
        retryBroadcastRequest = null
        lockedBroadcastRotation = null
        lockedScreenOrientation = null
        sessionState = sessionState.endConnection()
        sessionSnapshot = null
        signalingStartedGeneration = null
        prepareJob?.cancel()
        prepareJob = null
        isPreparingBroadcast = false
        startJob?.cancel()
        startJob = null
        val currentConnection = connection
        closingConnection = currentConnection ?: closingConnection
        connection = null
        remoteVideoTrack = null
        localVideoTrack = null
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

    private companion object {
        // Recovery cleanup and initial setup may issue several individually bounded HTTP calls.
        const val INITIAL_CONNECTION_SETUP_TIMEOUT_MILLIS = 120_000L
        const val SIGNALING_CALLBACK_GRACE_MILLIS = 5_000L
    }
}
