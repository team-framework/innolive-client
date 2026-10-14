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
import com.framework.innolive.feature.live.consent.BroadcastConsentStep
import com.framework.innolive.feature.live.consent.BroadcastConsentStore
import com.framework.innolive.feature.live.consent.nextBroadcastConsentStep
import com.framework.innolive.feature.live.direct.broadcast.DirectBroadcastPlan
import com.framework.innolive.feature.live.direct.broadcast.DirectBroadcastUiState
import com.framework.innolive.feature.live.status.BroadcastUplinkQuality
import com.framework.innolive.feature.live.status.BroadcastUplinkQualityTracker
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

class WebRtcSessionViewModel internal constructor(
    /** 송출 경로를 만듭니다. 테스트는 가짜 송출 경로를 넣습니다. */
    private val transportFactory: (LiveBroadcastTransportRequest) -> LiveBroadcastTransport,
) : ViewModel() {
    constructor() : this(::createLiveBroadcastTransport)

    private var startJob: Job? = null
    private var prepareJob: Job? = null
    var serverError by mutableStateOf<ServerErrorGuidance?>(null)
        private set
    private var preparationSettings: BroadcastSettings? = null
    private var preparationChzzkSettings: ChzzkBroadcastSettings? = null
    private var retryBroadcastRequest: (() -> Unit)? = null

    fun dismissServerError() { serverError = null }

    fun confirmConcurrentBroadcast(): Boolean {
        if (serverError?.action != ServerErrorAction.CONFIRM_CONCURRENT ||
            connectionState != WebRtcConnectionState.CONNECTED) return false
        val settings = preparationSettings ?: return false
        val activeConnection = connection ?: return false
        serverError = null
        preparationChzzkSettings?.let { chzzk ->
            return requestSimulcastPreparation(activeConnection, settings, chzzk, allowConcurrent = true)
        }
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
    /** 방송 상태 패널이 쓰는 업로드 품질. 값이 바뀔 때만 갱신해 화면 갱신을 줄인다. */
    var uplinkQuality by mutableStateOf(BroadcastUplinkQuality.Measuring)
        private set
    private val uplinkQualityTracker = BroadcastUplinkQualityTracker()

    var frameAnalyzer: CameraFrameAnalyzer? by mutableStateOf(null)
        private set
    var eglContext: EglBase.Context? by mutableStateOf(null)
        private set
    var lockedBroadcastRotation: Int? by mutableStateOf(null)
        private set
    var lockedScreenOrientation: Int? by mutableStateOf(null)
        private set

    private var connection: LiveBroadcastTransport? = null

    /** 직접 송출의 해상도와 대상입니다. 다음 세션을 만들 때 적용합니다. */
    internal var directBroadcastPlan by mutableStateOf(DirectBroadcastPlan())
        private set

    /** 직접 송출 중 서버 응답에 없는 로컬 상태입니다. 서버 중계에서는 기본값입니다. */
    internal var directBroadcastUiState by mutableStateOf(DirectBroadcastUiState())
        private set

    /** 방송 준비 전에만 바꿀 수 있습니다. 이미 연결된 세션은 다음 준비에서 새로 만듭니다. */
    internal fun selectDirectBroadcastPlan(plan: DirectBroadcastPlan): Boolean {
        if (!broadcastState.canPrepare || isPreparingBroadcast) return false
        if (plan != directBroadcastPlan && connectionState != WebRtcConnectionState.IDLE) close()
        directBroadcastPlan = plan
        return true
    }
    /** 송출할 플랫폼입니다. 둘이면 동시 송출이며 세션은 [selectedProvider]로 만듭니다. */
    var selectedProviders by mutableStateOf(setOf(BroadcastProvider.YOUTUBE))
        private set

    /** 세션의 기본 대상입니다. 동시 송출이면 YouTube입니다. */
    val selectedProvider: BroadcastProvider
        get() = BroadcastProvider.entries.first { it in selectedProviders }

    val isSimulcastSelected: Boolean
        get() = selectedProviders.size > 1

    /** 동시 송출에서 한 플랫폼만 준비됐을 때 남은 플랫폼입니다. 비어 있지 않으면 화면이 다시 준비를 연다. */
    val simulcastRetryProviders: List<BroadcastProvider>
        get() = if (isSimulcastSelected && broadcastState == BroadcastState.PREPARED && !isPreparingBroadcast) {
            sessionSnapshot.unpreparedProviders(selectedProviders)
        } else emptyList()

    fun selectProvider(provider: BroadcastProvider): Boolean = selectProviders(setOf(provider))

    fun selectProviders(providers: Set<BroadcastProvider>): Boolean {
        if (providers.isEmpty() || !broadcastState.canPrepare || isPreparingBroadcast) return false
        if (selectedProviders != providers) {
            // 기본 대상이 같으면 세션을 그대로 쓰고 다음 준비에서 대상만 더하거나 뺍니다.
            val primary = BroadcastProvider.entries.first { it in providers }
            if (primary != selectedProvider && connectionState != WebRtcConnectionState.IDLE) close()
            selectedProviders = providers
        }
        return true
    }
    private var closingConnection: LiveBroadcastTransport? = null
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

    fun start(context: Context, refreshAccessToken: suspend () -> String) =
        start(context, BuildConfig.INNOLIVE_SERVER_URL, refreshAccessToken)

    internal fun start(
        context: Context,
        serverUrl: String,
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
        preparationChzzkSettings = null
        retryBroadcastRequest = null
        sessionState = sessionState.beginConnection()
        sessionSnapshot = null
        directBroadcastUiState = DirectBroadcastUiState()
        signalingStartedGeneration = null
        resetUplinkQuality()
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

                val callbacks = LiveBroadcastCallbacks(
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
                    onUplinkVideoStats = { sample ->
                        if (sessionState.acceptsCallback(generation)) {
                            val quality = uplinkQualityTracker.record(sample)
                            if (quality != uplinkQuality) uplinkQuality = quality
                        }
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
                    onDirectUiStateChanged = { state ->
                        if (sessionState.acceptsCallback(generation)) directBroadcastUiState = state
                    },
                )
                val newConnection = transportFactory(
                    LiveBroadcastTransportRequest(
                        context = context,
                        serverUrl = serverUrl,
                        accessToken = accessToken,
                        refreshAccessToken = refreshAccessToken,
                        initialAnonymizationEnabled = initialEnabled,
                        initialOnDeviceProcessing = initialOnDevice,
                        provider = selectedProvider,
                        preferredAudioInput = selectedAudioInput,
                        directPlan = directBroadcastPlan,
                        callbacks = callbacks,
                    ),
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
        if (selectedProvider != BroadcastProvider.YOUTUBE) {
            broadcastStatus = UiText.Resource(com.framework.innolive.R.string.broadcast_settings_youtube_only)
            isBroadcastStatusDefault = false
            return
        }
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
    ): Boolean = prepareBroadcastInternal(context, settings, null, refreshAccessToken)

    fun prepareChzzkBroadcast(
        context: Context,
        settings: ChzzkBroadcastSettings,
        refreshAccessToken: suspend () -> String,
    ): Boolean = prepareBroadcastInternal(context, null, settings, refreshAccessToken)

    /** YouTube와 치지직을 한 세션에서 함께 준비합니다. 한 플랫폼이 실패해도 다른 플랫폼의 준비는 유지합니다. */
    fun prepareSimulcastBroadcast(
        context: Context,
        settings: BroadcastSettings,
        chzzkSettings: ChzzkBroadcastSettings,
        refreshAccessToken: suspend () -> String,
    ): Boolean = prepareBroadcastInternal(context, settings, chzzkSettings, refreshAccessToken)

    private fun prepareBroadcastInternal(
        context: Context,
        settings: BroadcastSettings?,
        chzzkSettings: ChzzkBroadcastSettings?,
        refreshAccessToken: suspend () -> String,
    ): Boolean {
        val youtubeInvalid = settings == null || !validateYouTubeLiveSettings(settings).isValid
        val chzzkInvalid = chzzkSettings == null || chzzkSettings.validationField() != null
        val invalid = selectedProviders.any { provider ->
            if (provider == BroadcastProvider.YOUTUBE) youtubeInvalid else chzzkInvalid
        }
        // 동시 송출의 한 플랫폼만 준비됐으면 연결과 준비된 플랫폼을 유지하고 남은 플랫폼만 다시 준비합니다.
        if (isSimulcastSelected && broadcastState == BroadcastState.PREPARED && !isPreparingBroadcast) {
            val activeConnection = connection
            if (invalid || activeConnection == null || connectionState != WebRtcConnectionState.CONNECTED) return false
            return requestSimulcastPreparation(activeConnection, checkNotNull(settings), checkNotNull(chzzkSettings))
        }
        if (isPreparingBroadcast || !broadcastState.canPrepare) return false
        if (invalid) {
            broadcastState = BroadcastState.FAILED
            broadcastStatus = UiText.Resource(com.framework.innolive.R.string.error_broadcast_validation)
            isBroadcastStatusDefault = false
            return false
        }
        // 화면을 거치지 않은 요청도 동의·안내 없이 카메라와 마이크를 서버에 연결하지 않습니다.
        nextBroadcastConsentStep(selectedProviders, BroadcastConsentStore(context)::hasAccepted)?.let { step ->
            broadcastState = BroadcastState.FAILED
            broadcastStatus = UiText.Resource(
                if (step == BroadcastConsentStep.MEDIA_TRANSMISSION) {
                    com.framework.innolive.R.string.error_broadcast_media_consent_required
                } else {
                    com.framework.innolive.R.string.error_broadcast_transmission_notice_required
                },
            )
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
        start(context.applicationContext, refreshAccessToken = refreshAccessToken)
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
                when {
                    isSimulcastSelected -> requestSimulcastPreparation(
                        activeConnection, checkNotNull(settings), checkNotNull(chzzkSettings),
                    )
                    selectedProvider == BroadcastProvider.CHZZK ->
                        requestChzzkBroadcastPreparation(activeConnection, checkNotNull(chzzkSettings))
                    else -> requestBroadcastPreparation(activeConnection, checkNotNull(settings))
                }
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
        activeConnection: LiveBroadcastTransport,
        settings: BroadcastSettings,
        allowConcurrent: Boolean = false,
    ): Boolean {
        serverError = null
        preparationSettings = settings
        preparationChzzkSettings = null
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

    internal fun requestSimulcastPreparation(
        activeConnection: LiveBroadcastTransport,
        settings: BroadcastSettings,
        chzzkSettings: ChzzkBroadcastSettings,
        allowConcurrent: Boolean = false,
    ): Boolean {
        serverError = null
        preparationSettings = settings
        preparationChzzkSettings = chzzkSettings
        retryBroadcastRequest = {
            connection?.let { requestSimulcastPreparation(it, settings, chzzkSettings, allowConcurrent) }
        }
        val previousState = broadcastState
        broadcastState = BroadcastState.SAVING_SETTINGS
        broadcastStatus = broadcastStateMessage(BroadcastState.SAVING_SETTINGS).text
        isBroadcastStatusDefault = true
        if (activeConnection.prepareTargets(settings, chzzkSettings, allowConcurrent)) return true
        // 일부 대상이 준비된 상태의 재요청이 거절되면 준비된 대상을 그대로 보여 줍니다.
        if (previousState == BroadcastState.PREPARED) {
            broadcastState = previousState
            broadcastStatus = broadcastStateMessage(previousState).text
            return false
        }
        broadcastState = BroadcastState.FAILED
        broadcastStatus = UiText.Resource(com.framework.innolive.R.string.error_broadcast_request)
        isBroadcastStatusDefault = false
        return false
    }

    internal fun requestChzzkBroadcastPreparation(
        activeConnection: LiveBroadcastTransport,
        settings: ChzzkBroadcastSettings,
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
        preparationChzzkSettings = null
        retryBroadcastRequest = null
        lockedBroadcastRotation = null
        lockedScreenOrientation = null
        sessionState = sessionState.endConnection()
        sessionSnapshot = null
        directBroadcastUiState = DirectBroadcastUiState()
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
        resetUplinkQuality()
    }

    private fun resetUplinkQuality() {
        uplinkQualityTracker.reset()
        uplinkQuality = BroadcastUplinkQuality.Measuring
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

    private suspend fun awaitClose(webRtcConnection: LiveBroadcastTransport) {
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
