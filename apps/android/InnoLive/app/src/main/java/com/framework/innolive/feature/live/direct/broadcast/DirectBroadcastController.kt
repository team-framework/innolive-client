package com.framework.innolive.feature.live.direct.broadcast

import android.content.Context
import android.media.AudioDeviceInfo
import com.framework.innolive.feature.live.AnonymizationFailure
import com.framework.innolive.feature.live.AnonymizationState
import com.framework.innolive.feature.live.BroadcastEvent
import com.framework.innolive.feature.live.BroadcastFailure
import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.BroadcastRemainingTime
import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.BroadcastState
import com.framework.innolive.feature.live.ChzzkBroadcastSettings
import com.framework.innolive.feature.live.ConnectionFailure
import com.framework.innolive.feature.live.status.UplinkVideoStats
import com.framework.innolive.feature.live.ConnectionFailureException
import com.framework.innolive.feature.live.CreatedSession
import com.framework.innolive.feature.live.LiveBroadcastCallbacks
import com.framework.innolive.feature.live.LiveBroadcastTransport
import com.framework.innolive.feature.live.LiveBroadcastTransportRequest
import com.framework.innolive.feature.live.ServerApiException
import com.framework.innolive.feature.live.SessionNotice
import com.framework.innolive.feature.live.SessionSnapshot
import com.framework.innolive.feature.live.WebRtcConnectionState
import com.framework.innolive.feature.live.canPrepare
import com.framework.innolive.feature.live.canStop
import com.framework.innolive.feature.live.direct.DirectGoLiveResult
import com.framework.innolive.feature.live.direct.DirectPublishApi
import com.framework.innolive.feature.live.direct.DirectSessionRejectedException
import com.framework.innolive.feature.live.direct.HeartbeatAnonymization
import com.framework.innolive.feature.live.direct.HeartbeatStopReason
import com.framework.innolive.feature.live.direct.HeartbeatTarget
import com.framework.innolive.feature.live.direct.HeartbeatVideo
import com.framework.innolive.feature.live.direct.IngestUrl
import com.framework.innolive.feature.live.direct.publish.DirectPublishEngine
import com.framework.innolive.feature.live.direct.publish.DirectPublishProfile
import com.framework.innolive.feature.live.direct.publish.DirectPublisher
import com.framework.innolive.feature.live.direct.publish.DirectUplinkLimitation
import com.framework.innolive.feature.live.direct.publish.PublishOrientation
import com.framework.innolive.feature.live.direct.publish.RtmpFailure
import com.framework.innolive.feature.live.direct.publish.RtmpTargetState
import com.framework.innolive.feature.live.parseSessionSnapshot
import com.framework.innolive.feature.live.resolveChzzkSettingsForPrepare
import com.framework.innolive.feature.live.stoppingState
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 하트비트·재연결·생명주기 구현을 바꿔 끼우는 지점입니다. 각 작업이 기본값을 실제 구현으로 바꿉니다. */
internal class DirectBroadcastDependencies(
    val heartbeat: (DirectPublishApi, HeartbeatInputSource, HeartbeatListener) -> HeartbeatScheduler =
        { _, _, _ -> NoopHeartbeatScheduler },
    val reconnect: (ReconnectActions) -> ReconnectSupervisor = { NoopReconnectSupervisor },
    val keepAlive: (Context) -> BroadcastKeepAlive = { NoopBroadcastKeepAlive },
)

/**
 * 앱 직접 송출 상태 머신입니다(contracts/api/direct-publish-v1.md). 세션 생성·준비·라이브 전환·종료를
 * 서버 API와 송출 엔진으로 진행합니다. 동작은 iOS `YouTubeIntegration`의 직접 송출과 맞춥니다.
 *
 * - 모든 상태는 [DirectBroadcastEnvironment.owner] 한 스레드에서만 바꿉니다. 화면 콜백은 메인 스레드로 보냅니다.
 * - YouTube는 prepare 응답 주소로 바로 연결하고 송출이 시작된 뒤 PREPARED를 알립니다.
 * - 치지직은 golive 응답의 `direct_ingest[]`로만 연결합니다. 연결하는 순간 공개 방송입니다.
 * - 일시 중지는 서버를 부르지 않고 대기 화면과 무음으로 처리합니다.
 * - 방송을 끝내면 세션을 삭제합니다. 다음 방송은 새 세션으로 시작합니다(iOS `endBroadcast`).
 * - 스트림 키가 든 [IngestUrl]은 메모리에만 두고 로그에 남기지 않습니다.
 */
internal class DirectBroadcastController internal constructor(
    private val callbacks: LiveBroadcastCallbacks,
    private val config: DirectBroadcastConfig,
    private val env: DirectBroadcastEnvironment,
) : LiveBroadcastTransport, DirectMediaControl {
    constructor(
        request: LiveBroadcastTransportRequest,
        dependencies: DirectBroadcastDependencies = DirectBroadcastDependencies(),
    ) : this(
        request.callbacks,
        DirectBroadcastConfig(request.directPlan, request.provider, request.initialAnonymizationEnabled),
        androidDirectBroadcastEnvironment(request, dependencies),
    )

    private enum class TargetPhase { PREPARED, LIVE }

    private class Target(
        val provider: BroadcastProvider,
        var phase: TargetPhase,
        /** 재연결과 엔진 재생성에 씁니다. 메모리에만 둡니다. */
        var ingestUrl: IngestUrl?,
        var local: LocalTargetStatus? = null,
        var published: Boolean = false,
    )

    /** 비동기로 기다리는 작업입니다. 준비 중 YouTube 송출 시작, 라이브 전환 전 연결 확인입니다. */
    private enum class Wait { PREPARE_PUBLISHING, GO_LIVE_PUBLISHING, GO_LIVE_RETRY }

    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val closeLock = Any()
    private val closeCallbacks = mutableListOf<() -> Unit>()
    private var closeFinished = false
    private val operation = AtomicBoolean(false)

    @Volatile private var broadcastState = BroadcastState.IDLE
    @Volatile private var connected = false
    @Volatile private var terminal = false
    @Volatile private var wait: Wait? = null
    @Volatile private var engine: DirectPublishEngine? = null
    @Volatile private var liveProviders: List<BroadcastProvider> = emptyList()
    private val uplinkLimitation = DirectUplinkLimitation()
    @Volatile private var anonymizationEnabled = config.anonymizationEnabled

    // 아래 값은 owner 스레드에서만 다룹니다.
    private var session: CreatedSession? = null
    private var sessionActivityAt = 0L
    private var firstFrame = false
    private var engineGeneration = 0
    private val targets = linkedMapOf<BroadcastProvider, Target>()
    private val slate = linkedSetOf<SlateReason>()
    private var microphone = MicrophoneStatus.OK
    private val processingFailures = ProcessingFailureWarning()
    private var processingFailureCheck: Cancellable? = null
    private var preparedDeadline: Long? = null
    private var snapshot: SessionSnapshot? = null
    private var publishedUiState: DirectBroadcastUiState? = null
    private var waitTimeout: Cancellable? = null
    private var pollTask: Cancellable? = null
    private var pollGeneration = 0
    /** 제어 요청을 시작할 때마다 올립니다. 그 전에 보낸 상태 조회 결과를 버리는 기준입니다. */
    private val controlEpoch = AtomicInteger()
    private var heartbeatStarted = false
    private var keepAliveStarted = false
    /** 오류 문구를 고를 플랫폼입니다. 동시 송출 준비에서는 지금 요청 중인 플랫폼입니다. */
    private var operationProvider = config.provider

    private val supervisor: ReconnectSupervisor = env.reconnect(object : ReconnectActions {
        override fun reconnect(provider: BroadcastProvider) = env.owner.execute {
            if (targets.containsKey(provider)) engine?.reconnect(provider)
        }

        override fun giveUp(provider: BroadcastProvider, failure: RtmpFailure?) = env.owner.execute {
            if (!targets.containsKey(provider)) return@execute
            env.log("reconnect_given_up provider=${provider.wireValue} failure=$failure")
            stopTarget(provider)
            afterTargetsRemoved(directConnectionLostEvent(provider))
        }
    })

    /** 하트비트를 멈출 때마다 바뀝니다. 멈춘 뒤 늦게 도착한 결과를 버립니다. */
    @Volatile private var heartbeatGeneration = 0

    /** 하트비트 스레드에서 온 결과를 owner 스레드로 넘깁니다. 그 사이 멈췄으면 버립니다. */
    private fun onHeartbeat(block: () -> Unit) {
        val generation = heartbeatGeneration
        env.owner.execute { if (heartbeatStarted && generation == heartbeatGeneration && isActive()) block() }
    }

    private val heartbeat: HeartbeatScheduler = env.heartbeat(HeartbeatInputSource(::heartbeatInputs),
        object : HeartbeatListener {
            override fun onStopTargets(providers: List<BroadcastProvider>) = onHeartbeat { endTargetsByServer(providers) }

            override fun onStopAll(reason: HeartbeatStopReason) = onHeartbeat { stopAllByServer(reason) }

            override fun onNotices(notices: List<SessionNotice>, broadcastRemainingSeconds: Long?) =
                onHeartbeat { applyHeartbeatNotices(notices, broadcastRemainingSeconds) }

            override fun onInvalid(field: String?) = env.log("heartbeat_invalid field=$field")
        })

    // --- 화면 동작 ---

    override fun start() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        postConnection(WebRtcConnectionState.CONNECTING)
        // 준비 대기는 이 콜백 뒤 CONNECTED를 기다립니다. 세션 생성과 첫 가림 처리 프레임을 함께 기다립니다.
        post { callbacks.onInitialSignalingStarted() }
        env.owner.execute {
            if (!isActive()) return@execute
            try {
                env.media.open(mediaEvents)
                ensureSession()
                maybeConnected()
            } catch (exception: Exception) {
                failStart(exception)
            }
        }
    }

    override fun close(onComplete: (() -> Unit)?) {
        var completeNow: (() -> Unit)? = null
        val shouldClean = synchronized(closeLock) {
            onComplete?.let { done -> if (closeFinished) completeNow = done else closeCallbacks += done }
            closed.compareAndSet(false, true)
        }
        completeNow?.let { done -> env.callbacks.execute { done() } }
        if (!shouldClean) return
        env.owner.execute {
            invalidateWait()
            try {
                endBroadcast()
                session?.let(::deleteSession)
            } catch (exception: Exception) {
                env.log("close_cleanup_failed type=${exception.javaClass.simpleName}")
            }
            releaseResources()
            val callbacks = synchronized(closeLock) {
                closeFinished = true
                closeCallbacks.toList().also { closeCallbacks.clear() }
            }
            callbacks.forEach { done -> env.callbacks.execute { done() } }
            env.io.shutdown()
            env.owner.shutdown()
        }
    }

    override fun setPreferredAudioInput(audioInput: AudioDeviceInfo?) = env.owner.execute {
        if (isActive()) env.media.setPreferredAudioInput(audioInput)
    }

    /** 녹화 종료 전에 끊어야 하므로 작업 스레드를 거치지 않고 바로 바꿉니다. */
    override fun setRecordingAudioSink(sink: com.framework.innolive.feature.live.recording.RecordingAudioSink?) {
        if (!closed.get()) env.media.setRecordingAudioSink(sink)
    }

    /** 서버 PATCH는 직접 송출 세션에서 409이므로 앱 가림 처리만 바꿉니다. */
    override fun setAnonymizationEnabled(
        enabled: Boolean,
        onComplete: (AnonymizationState?, AnonymizationFailure?) -> Unit,
    ) = env.owner.execute {
        if (!isActive()) return@execute post { onComplete(null, AnonymizationFailure.REQUEST) }
        try {
            env.media.setAnonymizationEnabled(enabled)
            anonymizationEnabled = enabled
            val confirmed = anonymizationState(enabled)
            post { callbacks.onAnonymizationStateConfirmed(confirmed) }
            post { onComplete(confirmed, null) }
        } catch (_: Exception) {
            post { onComplete(null, AnonymizationFailure.REQUEST) }
        }
    }

    /** 준비·방송 중에는 설정을 잠급니다(youtube-broadcast-v1.md). 일시 중지도 서버 단계는 live입니다. */
    override fun saveBroadcastSettings(settings: BroadcastSettings) {
        if (BroadcastProvider.YOUTUBE !in config.targets || !broadcastState.canPrepare || !beginOperation()) return
        env.owner.execute {
            operationProvider = BroadcastProvider.YOUTUBE
            runOperation {
                if (settings.madeForKids == null) {
                    return@runOperation finish(BroadcastState.FAILED, BroadcastEvent.Failure(BroadcastFailure.AUDIENCE_REQUIRED))
                }
                report(BroadcastState.SAVING_SETTINGS)
                env.server.saveYouTubeSettings(ensureSession(), settings)
                sessionActivityAt = env.elapsedRealtimeMillis()
                finish(BroadcastState.IDLE, BroadcastEvent.SettingsSaved)
            }
        }
    }

    override fun prepareBroadcast(settings: BroadcastSettings, allowConcurrent: Boolean): Boolean =
        prepareDirectBroadcast(settings, null, allowConcurrent)

    override fun prepareBroadcast(chzzkSettings: ChzzkBroadcastSettings): Boolean =
        prepareDirectBroadcast(null, chzzkSettings, false)

    override fun prepareTargets(
        youtube: BroadcastSettings?,
        chzzk: ChzzkBroadcastSettings?,
        allowConcurrent: Boolean,
    ): Boolean = prepareDirectBroadcast(youtube, chzzk, allowConcurrent)

    /**
     * 구성의 플랫폼마다 설정을 저장하고 prepare합니다. 설정을 넘긴 플랫폼만 준비하며, 이미 준비한 플랫폼은
     * 건너뜁니다. [allowConcurrent]는 `channel_already_live`를 사용자가 확인한 YouTube 재요청입니다.
     */
    internal fun prepareDirectBroadcast(
        youtube: BroadcastSettings?,
        chzzk: ChzzkBroadcastSettings?,
        allowConcurrent: Boolean,
    ): Boolean {
        if (!broadcastState.canPrepare || !connected || !isActive()) return false
        // 화면은 고른 플랫폼의 설정만 넘깁니다. 동시 송출의 치지직 설정이 없으면 서버 기본값으로 준비합니다.
        // YouTube는 made_for_kids가 필요해 기본값으로 대신할 수 없으므로 YouTube 설정이 없으면 준비하지 않습니다.
        val chzzkSettings = chzzk ?: ChzzkBroadcastSettings().takeIf { youtube != null }
        val requested = config.targets.filter {
            when (it) {
                BroadcastProvider.YOUTUBE -> youtube != null
                BroadcastProvider.CHZZK -> chzzkSettings != null
            }
        }
        if (requested.isEmpty() || !beginOperation()) return false
        env.owner.execute {
            runOperation(rollback = true) {
                if (requested.size != config.targets.size) {
                    return@runOperation finish(BroadcastState.FAILED, DirectEvent.PREPARE_FAILED.toBroadcastEvent())
                }
                if (youtube != null && youtube.madeForKids == null) {
                    return@runOperation finish(BroadcastState.FAILED, BroadcastEvent.Failure(BroadcastFailure.AUDIENCE_REQUIRED))
                }
                try {
                    prepareTargets(requested, youtube, chzzkSettings, allowConcurrent)
                } catch (exception: ServerApiException) {
                    if (exception.statusCode != 404) throw exception
                    // prepared 10분이 지나거나 종료 직후 회수로 세션이 사라졌습니다. 새 세션으로 한 번 다시 준비합니다.
                    env.log("session_gone_on_prepare")
                    disconnectAll()
                    closeEngine()
                    forgetSession()
                    prepareTargets(requested, youtube, chzzkSettings, allowConcurrent)
                }
            }
        }
        return true
    }

    override fun goLive(onAccepted: () -> Unit): Boolean {
        if (broadcastState != BroadcastState.PREPARED || !connected || !isActive() || !beginOperation()) return false
        try {
            onAccepted()
        } catch (_: Exception) {
            operation.set(false)
            return false
        }
        env.owner.execute {
            operationProvider = config.provider
            runOperation { beginGoLive() }
        }
        return true
    }

    override fun pauseBroadcast() {
        if (broadcastState != BroadcastState.LIVE || operation.get()) return
        env.owner.execute {
            if (broadcastState != BroadcastState.LIVE || !isActive()) return@execute
            updateSlate(SlateReason.USER_PAUSE, true)
            report(BroadcastState.PAUSED)
        }
    }

    override fun resumeBroadcast() {
        if (broadcastState != BroadcastState.PAUSED || operation.get()) return
        env.owner.execute {
            if (broadcastState != BroadcastState.PAUSED || !isActive()) return@execute
            updateSlate(SlateReason.USER_PAUSE, false)
            report(BroadcastState.LIVE)
        }
    }

    /** 준비 취소와 방송 종료입니다. 송출 시작·재시도를 기다리는 중이면 기다림을 끊고 종료합니다. */
    override fun stopBroadcast() {
        if (!isActive()) return
        val waiting = wait != null
        if (!waiting && (!broadcastState.canStop || !beginOperation())) return
        env.owner.execute {
            if (!isActive()) return@execute
            if (waiting) invalidateWait()
            val stopping = if (broadcastState.canStop) broadcastState.stoppingState() else BroadcastState.CANCELLING_PREPARATION
            operation.set(true)
            controlEpoch.incrementAndGet()
            report(stopping)
            endBroadcast()
            session?.let(::deleteSession)
            finish(BroadcastState.IDLE)
        }
    }

    override fun setSlate(reason: SlateReason, active: Boolean) = env.owner.execute {
        if (isActive()) updateSlate(reason, active)
    }

    // --- 세션 ---

    /** 이전 기록을 지우고 새 세션을 만들어 즉시 저장합니다(WebRtcConnection.createSession과 같은 순서). */
    private fun ensureSession(): CreatedSession {
        session?.let { return it }
        env.recovery.load()?.let { stale ->
            try {
                env.server.deleteSession(stale)
                env.recovery.clear()
            } catch (exception: Exception) {
                // 지우지 못한 기록은 남겨 두고 다음에 다시 지웁니다. 서버에 남았다면 생성이 409로 알립니다.
                env.log("stale_session_cleanup_failed type=${exception.javaClass.simpleName}")
            }
        }
        val created = try {
            createSession()
        } catch (exception: ServerApiException) {
            if (exception.statusCode != 409 || exception.code != "session_already_exists") throw exception
            // 계정 정보가 없는 이전 기록은 409로 이 계정의 세션임이 확인된 뒤에만 지웁니다.
            val legacy = env.recovery.loadLegacy() ?: throw exception
            env.server.deleteSession(legacy)
            env.recovery.clearLegacy()
            createSession()
        }
        try {
            env.recovery.save(created)
        } catch (exception: Exception) {
            runCatching { env.server.deleteSession(created) }
            throw IOException("세션 복구 정보를 저장하지 못했습니다.", exception)
        }
        session = created
        sessionActivityAt = env.elapsedRealtimeMillis()
        snapshot = SessionSnapshot(created.sessionId, created.provider.wireValue)
        startPolling(created)
        return created
    }

    private fun createSession(): CreatedSession = try {
        env.server.createSession(config.provider, config.plan.resolution)
    } catch (exception: DirectSessionRejectedException) {
        exception.uncleanedSession?.let { uncleaned ->
            runCatching { env.recovery.save(uncleaned) }
                .onFailure { env.log("rejected_session_save_failed") }
        }
        throw exception
    }

    private fun deleteSession(created: CreatedSession) {
        stopPolling()
        session = null
        clearSnapshot()
        preparedDeadline = null
        try {
            env.server.deleteSession(created)
            env.recovery.clear()
        } catch (exception: Exception) {
            // 기록을 남겨 다음 시작 때 지웁니다.
            env.log("session_delete_failed type=${exception.javaClass.simpleName}")
        }
        pushUi()
    }

    /** 서버에서 이미 사라진 세션입니다. 지울 필요 없이 기록만 비웁니다. */
    private fun forgetSession() {
        stopPolling()
        session = null
        clearSnapshot()
        preparedDeadline = null
        runCatching { env.recovery.clear() }
        pushUi()
    }

    // --- 준비 ---

    private fun prepareTargets(
        requested: List<BroadcastProvider>,
        youtube: BroadcastSettings?,
        chzzk: ChzzkBroadcastSettings?,
        allowConcurrent: Boolean,
    ) {
        for (provider in requested) {
            if (targets.containsKey(provider)) continue
            operationProvider = provider
            report(BroadcastState.SAVING_SETTINGS)
            val created = ensureSession()
            when (provider) {
                BroadcastProvider.YOUTUBE -> env.server.saveYouTubeSettings(created, checkNotNull(youtube))
                BroadcastProvider.CHZZK -> env.server.saveChzzkSettings(created,
                    resolveChzzkSettingsForPrepare(checkNotNull(chzzk)) { env.server.chzzkDefaults(created) })
            }
            sessionActivityAt = env.elapsedRealtimeMillis()
            report(BroadcastState.PREPARING)
            val result = env.server.prepare(created, provider, allowConcurrent && provider == BroadcastProvider.YOUTUBE)
            val target = Target(provider, TargetPhase.PREPARED, result.ingestUrl)
            targets[provider] = target
            // YouTube는 라이브 전환 전에 영상이 도착해야 하므로 준비 응답 주소로 바로 송출합니다.
            result.ingestUrl?.let { connectTarget(target, ensureEngine()) }
        }
        preparedDeadline = sessionActivityAt + PREPARED_LIFETIME_MILLIS
        pushUi()
        if (preconnectReady()) finish(BroadcastState.PREPARED)
        else startWait(Wait.PREPARE_PUBLISHING)
    }

    /** prepare 응답 주소로 미리 연결한 대상(YouTube)이 모두 송출 중입니다. */
    private fun preconnectReady(): Boolean =
        targets.values.filter { it.ingestUrl != null && it.phase == TargetPhase.PREPARED }
            .all { it.local == LocalTargetStatus.PUBLISHING }

    // --- 라이브 전환 ---

    private fun beginGoLive() {
        report(BroadcastState.GOING_LIVE)
        // 화면 방향은 이 시점에 고정됩니다. 미리 연결한 엔진의 방향이 다르면 고정한 방향으로 다시 만듭니다.
        val orientation = env.media.frameOrientation
        val current = engine
        if (current != null && current.profile.orientation != orientation) {
            env.log("engine_rebuild orientation=$orientation")
            closeEngine()
            val rebuilt = ensureEngine(orientation)
            targets.values.filter { it.ingestUrl != null }.forEach { connectTarget(it, rebuilt) }
        } else {
            // 준비 뒤 끊긴 연결은 다시 엽니다. 연결 중이거나 송출 중이면 아무것도 하지 않습니다.
            targets.values.filter { it.ingestUrl != null && it.local != LocalTargetStatus.PUBLISHING }
                .forEach { current?.reconnect(it.provider) }
        }
        if (!keepAliveStarted) {
            keepAliveStarted = true
            env.keepAlive.onBroadcastStarting()
        }
        if (preconnectReady()) requestGoLive(1) else startWait(Wait.GO_LIVE_PUBLISHING)
    }

    private fun requestGoLive(attempt: Int) {
        val created = session ?: return failGoLive(BroadcastState.FAILED, DirectEvent.REQUEST.toBroadcastEvent())
        val result = try {
            env.server.goLive(created, config.provider)
        } catch (exception: ServerApiException) {
            when {
                // 영상이 아직 도착하지 않았습니다. 송출을 유지한 채 1초 뒤 다시 요청합니다.
                exception.code == "broadcast_not_ready" && attempt < GO_LIVE_ATTEMPTS -> {
                    val token = startWait(Wait.GO_LIVE_RETRY, timeout = false)
                    env.owner.schedule(GO_LIVE_RETRY_MILLIS) {
                        if (isCurrentWait(token)) {
                            clearWait()
                            requestGoLive(attempt + 1)
                        }
                    }
                }
                exception.statusCode == 404 -> {
                    // prepared 10분 제한으로 세션이 지워졌습니다. 다음 준비에서 새 세션을 만듭니다.
                    env.log("session_gone_on_golive")
                    disconnectAll()
                    forgetSession()
                    failGoLive(BroadcastState.FAILED, DirectEvent.PREPARED_EXPIRED.toBroadcastEvent())
                }
                // 전환 중 stop이 먼저 처리됐습니다. 방송을 끝냅니다.
                exception.code == "broadcast_stopped" -> {
                    endBroadcast()
                    session?.let(::deleteSession)
                    finish(BroadcastState.IDLE, DirectEvent.STOPPED_DURING_GO_LIVE.toBroadcastEvent())
                }
                // 15회를 다 써도 준비 상태와 송출을 유지해 다시 시도하거나 취소할 수 있게 합니다. `broadcast_busy`와
                // `broadcast_going_live`도 같습니다. 직접 송출은 서버 조회로 방송 상태를 전진시키지 않으므로
                // GOING_LIVE에 남겨 두면 종료와 재시도가 모두 막힙니다.
                else -> failGoLive(BroadcastState.PREPARED, directServerEvent(exception, config.provider))
            }
            return
        } catch (exception: Exception) {
            env.log("golive_failed type=${exception.javaClass.simpleName}")
            return failGoLive(BroadcastState.PREPARED, DirectEvent.REQUEST.toBroadcastEvent())
        }
        applyGoLive(created, result)
    }

    private fun failGoLive(state: BroadcastState, event: BroadcastEvent?) {
        if (keepAliveStarted && targets.values.none { it.phase == TargetPhase.LIVE }) {
            keepAliveStarted = false
            env.keepAlive.onBroadcastEnded()
        }
        finish(state, event)
    }

    private fun applyGoLive(created: CreatedSession, result: DirectGoLiveResult) {
        var failure: BroadcastEvent? = null
        val failed = result.failedTargets.map { it.provider }.toSet()
        val othersContinue = targets.values.any { it.provider.wireValue !in failed }
        result.failedTargets.forEach { item ->
            env.log("golive_target_failed provider=${item.provider} code=${item.code}")
            failure = failure ?: directTargetFailureEvent(item.provider, item.code, othersContinue)
        }
        // 실패한 대상은 정리하고 다른 대상은 계속합니다.
        targets.values.filter { it.provider.wireValue in failed }.map { it.provider }.forEach(::stopTarget)
        targets.values.forEach { it.phase = TargetPhase.LIVE }
        val ingest = result.directIngest.associate { it.provider to it.ingestUrl }
        for (target in targets.values.toList()) {
            if (target.local != null) continue // 이미 연결한 대상은 다시 연결하지 않습니다.
            val url = ingest[target.provider]
            if (url == null) {
                // 라이브로 전환됐는데 주소가 없습니다. 영상 없이 남지 않게 정리합니다.
                env.log("golive_ingest_missing provider=${target.provider.wireValue}")
                stopTarget(target.provider)
                failure = failure ?: DirectEvent.CONNECT_FAILED.toBroadcastEvent()
                continue
            }
            target.ingestUrl = url
            try {
                connectTarget(target, ensureEngine())
            } catch (exception: Exception) {
                env.log("connect_failed provider=${target.provider.wireValue} type=${exception.javaClass.simpleName}")
                stopTarget(target.provider)
                failure = failure ?: DirectEvent.CONNECT_FAILED.toBroadcastEvent()
            }
        }
        if (targets.isEmpty()) {
            endBroadcast()
            deleteSession(created)
            return finish(BroadcastState.FAILED, failure ?: DirectEvent.CONNECT_FAILED.toBroadcastEvent())
        }
        preparedDeadline = null
        updateLiveProviders()
        if (!heartbeatStarted) {
            heartbeatStarted = true
            heartbeat.start(created)
        }
        pushUi()
        finish(if (SlateReason.USER_PAUSE in slate) BroadcastState.PAUSED else BroadcastState.LIVE, failure)
    }

    // --- 송출 엔진 ---

    private fun ensureEngine(orientation: PublishOrientation = env.media.frameOrientation): DirectPublishEngine {
        engine?.let { return it }
        val generation = ++engineGeneration
        val listener = object : DirectPublisher.Listener {
            override fun onTargetStateChanged(provider: BroadcastProvider, state: RtmpTargetState, failure: RtmpFailure?) {
                env.owner.execute { if (generation == engineGeneration) onTargetState(provider, state, failure) }
            }

            override fun onPublisherFailed(error: Exception) {
                env.owner.execute { if (generation == engineGeneration) handlePublisherFailed(error) }
            }
        }
        // 프로필은 세션 해상도만 따릅니다. 카메라가 더 작으면 늘리고, 비율이 다르면 여백을 넣습니다.
        val created = env.media.createEngine(DirectPublishProfile.of(config.plan.resolution, orientation), listener)
        engine = created
        created.setSlate(slate.isNotEmpty())
        return created
    }

    private fun closeEngine() {
        val current = engine ?: return
        engine = null
        engineGeneration++
        targets.values.forEach {
            // 의도한 종료입니다. 재연결 판단보다 먼저 알립니다.
            if (it.published) supervisor.onTargetRemoved(it.provider)
            it.local = null
            it.published = false
        }
        runCatching { current.close() }
    }

    private fun connectTarget(target: Target, publisher: DirectPublishEngine) {
        val url = checkNotNull(target.ingestUrl)
        target.local = LocalTargetStatus.CONNECTING
        target.published = false
        pushUi()
        publisher.connect(target.provider, url)
    }

    private fun onTargetState(provider: BroadcastProvider, state: RtmpTargetState, failure: RtmpFailure?) {
        val target = targets[provider] ?: return
        when (state) {
            RtmpTargetState.IDLE -> return
            RtmpTargetState.CONNECTING -> target.local =
                if (target.published) LocalTargetStatus.RECONNECTING else LocalTargetStatus.CONNECTING
            RtmpTargetState.PUBLISHING -> {
                target.local = LocalTargetStatus.PUBLISHING
                target.published = true
            }
            RtmpTargetState.FAILED, RtmpTargetState.DISCONNECTED -> {
                if (!target.published) return onFirstConnectFailed(target, failure)
                target.local = LocalTargetStatus.RECONNECTING
            }
        }
        pushUi()
        if (target.published) supervisor.onTargetStateChanged(provider, state, failure)
        if (state == RtmpTargetState.PUBLISHING && preconnectReady()) {
            when (wait) {
                Wait.PREPARE_PUBLISHING -> {
                    clearWait()
                    finish(BroadcastState.PREPARED)
                }
                Wait.GO_LIVE_PUBLISHING -> {
                    clearWait()
                    requestGoLive(1)
                }
                else -> Unit
            }
        }
    }

    /** 처음 연결부터 실패했습니다. 키 오류나 다른 도구 방송 등이므로 해당 대상을 끝냅니다. */
    private fun onFirstConnectFailed(target: Target, failure: RtmpFailure?) {
        env.log("connect_failed provider=${target.provider.wireValue} failure=$failure")
        when (wait) {
            Wait.PREPARE_PUBLISHING -> {
                clearWait()
                rollbackPreparation()
                finish(BroadcastState.FAILED, DirectEvent.PREPARE_FAILED.toBroadcastEvent())
            }
            Wait.GO_LIVE_PUBLISHING -> {
                // 준비 상태를 유지합니다. 다시 누르면 연결부터 다시 엽니다.
                clearWait()
                target.local = LocalTargetStatus.FAILED
                pushUi()
                failGoLive(BroadcastState.PREPARED, DirectEvent.NOT_READY.toBroadcastEvent())
            }
            else -> if (target.phase == TargetPhase.LIVE) {
                stopTarget(target.provider)
                afterTargetsRemoved(DirectEvent.CONNECT_FAILED.toBroadcastEvent())
            } else {
                target.local = LocalTargetStatus.FAILED
                pushUi()
            }
        }
    }

    private fun handlePublisherFailed(error: Exception) {
        env.log("publisher_failed type=${error.javaClass.simpleName}")
        invalidateWait()
        val hadSession = session
        endBroadcast()
        hadSession?.let(::deleteSession)
        finish(BroadcastState.FAILED, DirectEvent.REQUEST.toBroadcastEvent())
    }

    // --- 종료 ---

    /** stop을 보낸 뒤 연결을 끊습니다. stop이 실패해도 끊습니다. */
    private fun stopTarget(provider: BroadcastProvider) {
        targets.remove(provider) ?: return
        supervisor.onTargetRemoved(provider)
        session?.let { created ->
            try {
                env.server.stop(created, provider)
            } catch (exception: Exception) {
                env.log("stop_failed provider=${provider.wireValue} type=${exception.javaClass.simpleName}")
            }
        }
        engine?.disconnect(provider)
        updateLiveProviders()
        pushUi()
    }

    /** 서버가 이미 끝낸 대상입니다. stop 없이 연결만 끊습니다. */
    private fun disconnectTarget(provider: BroadcastProvider) {
        targets.remove(provider) ?: return
        supervisor.onTargetRemoved(provider)
        engine?.disconnect(provider)
        updateLiveProviders()
        pushUi()
    }

    private fun disconnectAll() = targets.keys.toList().forEach(::disconnectTarget)

    /** 남은 대상을 모두 끝내고 엔진·하트비트를 정리합니다. 세션 삭제는 호출자가 정합니다. */
    private fun endBroadcast() {
        if (heartbeatStarted) {
            heartbeatStarted = false
            heartbeatGeneration++
            heartbeat.stop()
        }
        targets.keys.toList().forEach(::stopTarget)
        closeEngine()
        slate.remove(SlateReason.USER_PAUSE)
        if (keepAliveStarted) {
            keepAliveStarted = false
            env.keepAlive.onBroadcastEnded()
        }
        preparedDeadline = null
        updateLiveProviders()
        pushUi()
    }

    private fun rollbackPreparation() {
        targets.keys.toList().forEach(::stopTarget)
        closeEngine()
        pushUi()
    }

    /** 대상을 정리한 뒤 남은 대상이 없으면 방송을 끝내고 세션을 지웁니다. */
    private fun afterTargetsRemoved(event: BroadcastEvent?) {
        if (targets.isNotEmpty()) {
            if (event != null) report(broadcastState, event)
            return
        }
        invalidateWait()
        endBroadcast()
        session?.let(::deleteSession)
        if (operation.get()) finish(BroadcastState.IDLE, event) else report(BroadcastState.IDLE, event)
    }

    private fun endTargetsByServer(providers: List<BroadcastProvider>) {
        val ended = providers.filter { targets[it]?.phase == TargetPhase.LIVE }
        if (ended.isEmpty() || !isActive()) return
        env.log("server_stopped_targets count=${ended.size}")
        ended.forEach(::disconnectTarget)
        refreshSnapshot()
        afterTargetsRemoved(directTargetsStoppedEvent(ended))
    }

    private fun stopAllByServer(reason: HeartbeatStopReason) {
        if (!isActive() || targets.isEmpty()) return
        env.log("server_stopped_all reason=$reason")
        disconnectAll()
        when (reason) {
            // 세션이 없거나 소유권을 잃었습니다. 지울 수 없으므로 기록만 비웁니다.
            HeartbeatStopReason.SESSION_GONE, HeartbeatStopReason.OWNERSHIP_LOST -> {
                invalidateWait()
                endBroadcast()
                forgetSession()
                if (operation.get()) finish(BroadcastState.IDLE, DirectEvent.SERVER_STOPPED.toBroadcastEvent())
                else report(BroadcastState.IDLE, DirectEvent.SERVER_STOPPED.toBroadcastEvent())
            }
            HeartbeatStopReason.NOT_APPLICABLE -> afterTargetsRemoved(DirectEvent.SERVER_STOPPED.toBroadcastEvent())
        }
    }

    // --- 대기 화면 ---

    private fun updateSlate(reason: SlateReason, active: Boolean) {
        val changed = if (active) slate.add(reason) else slate.remove(reason)
        if (!changed) return
        engine?.setSlate(slate.isNotEmpty())
        pushUi()
    }

    // --- 세션 상태 ---

    private fun startPolling(created: CreatedSession) {
        stopPolling()
        val generation = ++pollGeneration
        pollTask = env.owner.schedule(SESSION_POLL_MILLIS) { pollSession(created, generation) }
    }

    private fun stopPolling() {
        pollGeneration++
        pollTask?.cancel()
        pollTask = null
    }

    private fun refreshSnapshot() {
        session?.let { created ->
            val generation = pollGeneration
            val epoch = controlEpoch.get()
            env.io.execute { fetchSnapshot(created, generation, epoch) }
        }
    }

    private fun pollSession(created: CreatedSession, generation: Int) {
        if (!isActive() || generation != pollGeneration) return
        val epoch = controlEpoch.get()
        env.io.execute { fetchSnapshot(created, generation, epoch) }
        pollTask = env.owner.schedule(SESSION_POLL_MILLIS) { pollSession(created, generation) }
    }

    private fun fetchSnapshot(created: CreatedSession, generation: Int, epoch: Int) {
        val outcome = runCatching { env.server.fetchSession(created) }
        env.owner.execute {
            // 제어 요청 중이거나 조회를 보낸 뒤 제어 요청이 시작됐으면 늦게 온 조회가 최신 결과를 덮어쓰지 않게 버립니다.
            if (!isActive() || generation != pollGeneration || session !== created || operation.get() ||
                epoch != controlEpoch.get()
            ) return@execute
            outcome.fold(
                onSuccess = { applySnapshot(created, it) },
                onFailure = { error ->
                    if ((error as? ServerApiException)?.statusCode == 404) onSessionGone()
                    else snapshot?.copy(isRemainingTimeStale = true)?.let(::publishSnapshot)
                },
            )
        }
    }

    private fun applySnapshot(created: CreatedSession, payload: String) {
        val next = runCatching {
            parseSessionSnapshot(payload, created.sessionId, snapshot ?: SessionSnapshot(created.sessionId))
        }.getOrElse {
            env.log("session_snapshot_invalid")
            return
        }
        publishSnapshot(next)
        // 서버에서 방송이 끝난 대상(스튜디오 종료, 한도 종료 등)은 앱 연결도 닫습니다.
        val ended = next.targets.orEmpty().filter { it.broadcastPhase == "idle" }
            .mapNotNull { target -> targets.values.firstOrNull { it.provider.wireValue == target.provider } }
            .filter { it.phase == TargetPhase.LIVE }
            .map { it.provider }
        if (ended.isNotEmpty()) endTargetsByServer(ended)
    }

    private fun onSessionGone() {
        env.log("session_gone_on_poll")
        if (targets.values.any { it.phase == TargetPhase.LIVE }) return stopAllByServer(HeartbeatStopReason.SESSION_GONE)
        // 준비 전이거나 준비 상태로 10분이 지났습니다. 다음 준비에서 새 세션을 만듭니다.
        val hadTargets = targets.isNotEmpty()
        disconnectAll()
        closeEngine()
        forgetSession()
        if (hadTargets) report(BroadcastState.FAILED, DirectEvent.PREPARED_EXPIRED.toBroadcastEvent())
    }

    private fun applyHeartbeatNotices(notices: List<SessionNotice>, remaining: Long?) {
        val current = snapshot ?: return
        publishSnapshot(current.copy(
            notices = notices,
            remainingTime = remaining?.let(BroadcastRemainingTime::Seconds)
                ?: BroadcastRemainingTime.UnlimitedOrInactive,
            lastRemainingSeconds = remaining ?: current.lastRemainingSeconds,
            isRemainingTimeStale = false,
        ))
    }

    private fun publishSnapshot(next: SessionSnapshot) {
        snapshot = next
        post { callbacks.onSessionSnapshotChanged(next) }
    }

    /**
     * 세션이 끝나면 화면의 마지막 세션 상태도 비웁니다. 비우지 않으면 종료 뒤에도 라이브 대상 상태·업로드 화질·
     * 시청자 채팅 버튼이 남습니다. iOS `clearSessionResponseState`와 같은 동작입니다.
     */
    private fun clearSnapshot() {
        val hadSnapshot = snapshot != null
        snapshot = null
        if (hadSnapshot) post { callbacks.onSessionSnapshotCleared() }
    }

    // --- 하트비트 입력 ---

    private fun heartbeatInputs(): HeartbeatInputs? {
        val live = liveProviders
        if (live.isEmpty()) return null
        val stats = runCatching { engine?.stats() }.getOrNull()
        // 상태 패널의 송출 화질과 제한 사유도 같은 주기의 인코더 값으로 채웁니다. 직접 송출에는 WebRTC 송신 통계가 없습니다.
        stats?.let {
            val reason = uplinkLimitation.reason(it)
            post { callbacks.onUplinkVideoStats(UplinkVideoStats(reason, it.width, it.height, it.fps)) }
        }
        // 서버에서는 라이브인데 로컬 연결이 없으면 connected:false로 보고합니다. 대기 화면 중에도 연결은 유지됩니다.
        val reported = live.map { provider ->
            stats?.targets?.firstOrNull { it.provider == provider }
                ?.let { HeartbeatTarget(provider, it.connected, it.bitrateKbps, it.reconnects) }
                ?: HeartbeatTarget(provider, connected = false)
        }
        return HeartbeatInputs(
            targets = reported,
            video = stats?.heartbeatVideo() ?: HeartbeatVideo(0.0, 0),
            anonymization = HeartbeatAnonymization(env.media.anonymizationFailedFrames, enabled = anonymizationEnabled),
        )
    }

    private fun updateLiveProviders() {
        liveProviders = targets.values.filter { it.phase == TargetPhase.LIVE }.map { it.provider }
    }

    // --- 미디어 이벤트 ---

    private val mediaEvents = object : DirectMediaEvents {
        override fun onFirstFrame() = env.owner.execute {
            firstFrame = true
            maybeConnected()
        }

        override fun onProcessingFailure() = env.owner.execute {
            // 연결 전 실패는 가림 처리를 준비하지 못한 것입니다. 이후 실패는 송출을 유지한 채 프레임만 버리고
            // 하트비트로 보고하며, 실패가 이어지면 화면에 경고합니다.
            if (!isActive()) return@execute
            if (!connected) return@execute failStart(ConnectionFailureException(ConnectionFailure.GENERIC))
            if (processingFailures.onFailure(env.elapsedRealtimeMillis())) pushUi()
            scheduleProcessingFailureCheck()
        }

        override fun onMicrophoneStatus(status: MicrophoneStatus) = env.owner.execute {
            if (microphone == status) return@execute
            microphone = status
            pushUi()
        }
    }

    /** 실패가 멈추면 경고를 끄도록 실패가 이어지는 동안 주기적으로 확인합니다. */
    private fun scheduleProcessingFailureCheck() {
        if (processingFailureCheck != null) return
        processingFailureCheck = env.owner.schedule(ProcessingFailureWarning.CHECK_INTERVAL_MILLIS) {
            processingFailureCheck = null
            if (!isActive()) return@schedule
            if (processingFailures.check(env.elapsedRealtimeMillis())) pushUi()
            if (processingFailures.pending) scheduleProcessingFailureCheck()
        }
    }

    private fun maybeConnected() {
        if (connected || !firstFrame || session == null || !isActive()) return
        connected = true
        // 서버 확인 대신 첫 가림 처리 프레임을 낸 앱 처리 상태를 확인값으로 알립니다.
        val confirmed = anonymizationState(anonymizationEnabled)
        post { callbacks.onAnonymizationStateConfirmed(confirmed) }
        postConnection(WebRtcConnectionState.CONNECTED)
    }

    private fun anonymizationState(enabled: Boolean) =
        if (enabled) AnonymizationState.ENABLED else AnonymizationState.DISABLED

    private fun failStart(exception: Exception) {
        if (terminal) return
        env.log("start_failed type=${exception.javaClass.simpleName}")
        terminal = true
        val guidance = (exception as? ServerApiException)?.guidance()
        guidance?.let { post { callbacks.onServerError(it) } }
        val failure = (exception as? ConnectionFailureException)?.failure
            ?: if ((exception as? ServerApiException)?.code == "session_already_exists") ConnectionFailure.EXISTING_BROADCAST
            else ConnectionFailure.GENERIC
        invalidateWait()
        runCatching {
            endBroadcast()
            session?.let(::deleteSession)
        }
        releaseResources()
        post { callbacks.onStateChanged(WebRtcConnectionState.FAILED, failure) }
    }

    private fun releaseResources() {
        stopPolling()
        runCatching { closeEngine() }
        runCatching { heartbeat.close() }
        runCatching { supervisor.close() }
        // 미리보기 해제(onLocalMediaCleared)는 카메라 입력이 자원을 놓기 전에 직접 알립니다.
        runCatching { env.media.close() }
        runCatching { env.server.close() }
    }

    // --- 작업과 상태 보고 ---

    private fun isActive(): Boolean = !closed.get() && !terminal

    private fun beginOperation(): Boolean =
        (isActive() && operation.compareAndSet(false, true)).also { if (it) controlEpoch.incrementAndGet() }

    /** 실패하면 서버 오류를 화면 안내로 바꿉니다. [rollback]이면 이번 준비에서 연 연결을 정리합니다. */
    private fun runOperation(rollback: Boolean = false, block: () -> Unit) {
        if (!isActive()) {
            operation.set(false)
            return
        }
        try {
            block()
        } catch (exception: Exception) {
            val provider = operationProvider
            env.log("operation_failed type=${exception.javaClass.simpleName}")
            invalidateWait()
            if (rollback) runCatching { rollbackPreparation() }
            val api = exception as? ServerApiException
            // `broadcast_busy`도 FAILED로 돌려 다시 준비할 수 있게 합니다. 준비 중 상태에 남기면 빠져나올 수 없습니다.
            finish(
                BroadcastState.FAILED,
                api?.let { directServerEvent(it, provider) }
                    ?: DirectEvent.REQUEST.toBroadcastEvent(),
            )
        }
    }

    private var waitToken = 0

    /** 비동기 기다림을 시작합니다. 작업은 끝나지 않은 상태로 남고 종료 요청이 기다림을 끊을 수 있습니다. */
    private fun startWait(kind: Wait, timeout: Boolean = true): Int {
        waitTimeout?.cancel()
        wait = kind
        val token = ++waitToken
        waitTimeout = if (!timeout) null else env.owner.schedule(PUBLISH_WAIT_MILLIS) {
            if (!isCurrentWait(token)) return@schedule
            env.log("publish_wait_timeout kind=$kind")
            clearWait()
            if (kind == Wait.PREPARE_PUBLISHING) {
                rollbackPreparation()
                finish(BroadcastState.FAILED, DirectEvent.PREPARE_FAILED.toBroadcastEvent())
            } else {
                failGoLive(BroadcastState.PREPARED, DirectEvent.NOT_READY.toBroadcastEvent())
            }
        }
        return token
    }

    private fun isCurrentWait(token: Int): Boolean = isActive() && wait != null && token == waitToken

    private fun clearWait() {
        waitTimeout?.cancel()
        waitTimeout = null
        wait = null
        waitToken++
    }

    private fun invalidateWait() = clearWait()

    private fun finish(state: BroadcastState, event: BroadcastEvent? = null) {
        operation.set(false)
        report(state, event)
    }

    private fun report(state: BroadcastState, event: BroadcastEvent? = null) {
        broadcastState = state
        post { callbacks.onBroadcastStateChanged(state, event) }
    }

    private fun pushUi() {
        val next = DirectBroadcastUiState(
            targets = targets.values.mapNotNull { target -> target.local?.let { target.provider to it } }.toMap(),
            microphone = microphone,
            slate = slate.toSet(),
            anonymizationFailing = processingFailures.active,
            preparedDeadlineElapsedMillis = preparedDeadline.takeIf { targets.values.any { it.phase == TargetPhase.PREPARED } },
        )
        if (next == publishedUiState) return
        publishedUiState = next
        post { callbacks.onDirectUiStateChanged(next) }
    }

    private fun postConnection(state: WebRtcConnectionState) = post { callbacks.onStateChanged(state, null) }

    private fun post(block: () -> Unit) {
        env.callbacks.execute { if (!closed.get()) block() }
    }

    private companion object {
        /** prepared는 활동으로 보지 않아 생성·설정 저장 뒤 10분이 지나면 서버가 세션을 지웁니다. */
        const val PREPARED_LIFETIME_MILLIS = 10 * 60_000L
        const val GO_LIVE_ATTEMPTS = 15
        const val GO_LIVE_RETRY_MILLIS = 1_000L
        const val PUBLISH_WAIT_MILLIS = 20_000L
        const val SESSION_POLL_MILLIS = 2_000L
    }
}

/** 인코더 해상도로 송출 방향을 판단합니다. */
internal val DirectPublishProfile.orientation: PublishOrientation
    get() = if (width >= height) PublishOrientation.LANDSCAPE else PublishOrientation.PORTRAIT
