package com.framework.innolive.feature.live.direct.broadcast

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
import com.framework.innolive.feature.live.CreatedSession
import com.framework.innolive.feature.live.LiveBroadcastCallbacks
import com.framework.innolive.feature.live.ServerApiException
import com.framework.innolive.feature.live.SessionNotice
import com.framework.innolive.feature.live.SessionSnapshot
import com.framework.innolive.feature.live.WebRtcConnectionState
import com.framework.innolive.feature.live.direct.DirectGoLiveResult
import com.framework.innolive.feature.live.direct.DirectIngest
import com.framework.innolive.feature.live.direct.DirectPrepareResult
import com.framework.innolive.feature.live.direct.DirectPublishResolution
import com.framework.innolive.feature.live.direct.DirectSessionRejectedException
import com.framework.innolive.feature.live.direct.DirectSessionServer
import com.framework.innolive.feature.live.direct.DirectTargetFailure
import com.framework.innolive.feature.live.direct.HeartbeatStopReason
import com.framework.innolive.feature.live.direct.IngestUrl
import com.framework.innolive.feature.live.direct.publish.DirectPublishEngine
import com.framework.innolive.feature.live.direct.publish.DirectPublishProfile
import com.framework.innolive.feature.live.direct.publish.DirectPublishStats
import com.framework.innolive.feature.live.direct.publish.DirectPublisher
import com.framework.innolive.feature.live.direct.publish.PublishOrientation
import com.framework.innolive.feature.live.direct.publish.RtmpFailure
import com.framework.innolive.feature.live.direct.publish.RtmpTargetState
import com.framework.innolive.feature.live.direct.publish.TargetPublishStats
import com.framework.innolive.ui.text.ServerErrorAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DirectBroadcastControllerTest {
    private val youtubeUrl = IngestUrl.parse("rtmps://a.rtmp.youtube.com/live2/youtube-key")
    private val chzzkUrl = IngestUrl.parse("rtmp://global-rtmp.lip2.navercorp.com:8080/relay/chzzk-key")
    private val settings = BroadcastSettings("제목", "설명", "private", false, "22")
    private val chzzkSettings = ChzzkBroadcastSettings("치지직", "GAME", "game-1", emptyList())

    private val scheduler = ManualScheduler()
    private val io = ImmediateBackgroundExecutor()
    private val server = FakeServer()
    private val recovery = FakeRecovery()
    private val media = FakeMedia()
    private val heartbeat = FakeHeartbeat()
    private val supervisor = FakeSupervisor()
    private val keepAlive = FakeKeepAlive()
    private val logs = mutableListOf<String>()
    private val states = mutableListOf<Pair<BroadcastState, BroadcastEvent?>>()
    private val connections = mutableListOf<Pair<WebRtcConnectionState, ConnectionFailure?>>()
    private val uiStates = mutableListOf<DirectBroadcastUiState>()
    private val snapshots = mutableListOf<SessionSnapshot>()
    private val serverErrors = mutableListOf<String>()
    private val anonymizationConfirmations = mutableListOf<AnonymizationState>()
    private var now = 1_000L

    private fun controller(
        plan: DirectBroadcastPlan = DirectBroadcastPlan(),
        provider: BroadcastProvider = BroadcastProvider.YOUTUBE,
        io: BackgroundExecutor = this.io,
        anonymizationEnabled: Boolean = true,
    ) = DirectBroadcastController(
        LiveBroadcastCallbacks(
            onStateChanged = { state, failure -> connections += state to failure },
            onRemoteTrackChanged = {},
            onLocalMediaReady = { _, _ -> },
            onLocalMediaCleared = {},
            onBroadcastStateChanged = { state, event -> states += state to event },
            onAnonymizationStateConfirmed = { anonymizationConfirmations += it },
            onLocalVideoTrackChanged = {},
            onInitialSignalingStarted = {},
            onSessionSnapshotChanged = { snapshots += it },
            onServerError = { serverErrors += it.code },
            onUplinkVideoStats = {},
            onDirectUiStateChanged = { uiStates += it },
        ),
        DirectBroadcastConfig(plan, provider, anonymizationEnabled = anonymizationEnabled),
        DirectBroadcastEnvironment(
            server = server,
            recovery = recovery,
            media = media,
            heartbeat = { inputs, listener ->
                heartbeat.inputs = inputs
                heartbeat.listener = listener
                heartbeat
            },
            reconnect = { actions ->
                supervisor.actions = actions
                supervisor
            },
            keepAlive = keepAlive,
            owner = scheduler,
            io = io,
            callbacks = { it.run() },
            elapsedRealtimeMillis = { now },
            log = logs::add,
        ),
    )

    private fun connected(controller: DirectBroadcastController): DirectBroadcastController {
        controller.start()
        scheduler.runUntilIdle()
        media.events.onFirstFrame()
        scheduler.runUntilIdle()
        return controller
    }

    private fun preparedYouTube(): DirectBroadcastController {
        val controller = connected(controller())
        assertTrue(controller.prepareBroadcast(settings, false))
        scheduler.runUntilIdle()
        media.engine.emit(BroadcastProvider.YOUTUBE, RtmpTargetState.PUBLISHING)
        scheduler.runUntilIdle()
        assertEquals(BroadcastState.PREPARED, states.last().first)
        return controller
    }

    private fun live(controller: DirectBroadcastController) {
        assertTrue(controller.goLive {})
        scheduler.runUntilIdle()
    }

    // --- 세션 ---

    @Test fun startDeletesStaleSessionAndPersistsNewSessionBeforeConnected() {
        val stale = session("stale")
        recovery.stored = stale
        val controller = controller()
        controller.start()
        scheduler.runUntilIdle()

        assertEquals(listOf("delete:stale", "create:youtube:720p"), server.calls.take(2))
        assertEquals("session-1", recovery.stored?.sessionId)
        // 첫 가림 처리 프레임 전에는 연결되지 않은 것으로 봅니다.
        assertEquals(listOf(WebRtcConnectionState.CONNECTING), connections.map { it.first })

        media.events.onFirstFrame()
        scheduler.runUntilIdle()
        assertEquals(WebRtcConnectionState.CONNECTED, connections.last().first)
    }

    @Test fun failedPersistenceDeletesCreatedSession() {
        recovery.failSave = true
        controller().start()
        scheduler.runUntilIdle()

        assertTrue("delete:session-1" in server.calls)
        assertEquals(WebRtcConnectionState.FAILED, connections.last().first)
        assertTrue(media.closed)
    }

    @Test fun rejectedSessionThatCouldNotBeDeletedIsKeptForCleanup() {
        server.createFailure = DirectSessionRejectedException("거부", session("orphan"))
        controller().start()
        scheduler.runUntilIdle()

        assertEquals("orphan", recovery.stored?.sessionId)
        assertEquals(WebRtcConnectionState.FAILED, connections.last().first)
    }

    @Test fun existingSessionConflictDeletesLegacyRecordAndRetries() {
        server.createFailure = ServerApiException("session_already_exists", statusCode = 409)
        server.createFailureOnce = true
        recovery.legacy = session("legacy")
        connected(controller())

        assertEquals(listOf("create:youtube:720p", "delete:legacy", "create:youtube:720p"), server.calls.take(3))
        assertNull(recovery.legacy)
        assertEquals(WebRtcConnectionState.CONNECTED, connections.last().first)
    }

    @Test fun existingSessionConflictWithoutRecordShowsCleanupGuidance() {
        server.createFailure = ServerApiException("session_already_exists", statusCode = 409)
        controller().start()
        scheduler.runUntilIdle()

        assertEquals(listOf("session_already_exists"), serverErrors)
        assertEquals(WebRtcConnectionState.FAILED to ConnectionFailure.EXISTING_BROADCAST, connections.last())
    }

    @Test fun sessionUsesPlanResolutionAndSelectedProvider() {
        connected(controller(DirectBroadcastPlan(DirectPublishResolution.FHD, setOf(BroadcastProvider.CHZZK)),
            BroadcastProvider.CHZZK))
        assertEquals("create:chzzk:fhd", server.calls.first())
    }

    // --- 준비 ---

    @Test fun youTubeConnectsWithPrepareUrlAndBecomesPreparedOnlyWhenPublishing() {
        val controller = connected(controller())
        assertTrue(controller.prepareBroadcast(settings, false))
        scheduler.runUntilIdle()

        assertEquals(listOf("settings:youtube", "prepare:youtube:false"), server.calls.drop(1))
        assertEquals(listOf("connect:youtube"), media.engine.calls)
        assertSame(youtubeUrl, media.engine.urls[BroadcastProvider.YOUTUBE])
        assertEquals(BroadcastState.PREPARING, states.last().first)
        // 송출이 시작되기 전에는 라이브 전환을 받지 않습니다.
        assertFalse(controller.goLive {})

        media.engine.emit(BroadcastProvider.YOUTUBE, RtmpTargetState.PUBLISHING)
        scheduler.runUntilIdle()
        assertEquals(BroadcastState.PREPARED, states.last().first)
        assertEquals(LocalTargetStatus.PUBLISHING, uiStates.last().targets[BroadcastProvider.YOUTUBE])
        assertEquals(now + 10 * 60_000L, uiStates.last().preparedDeadlineElapsedMillis)
    }

    @Test fun firstConnectFailureDuringPreparationStopsYouTube() {
        val controller = connected(controller())
        controller.prepareBroadcast(settings, false)
        scheduler.runUntilIdle()
        media.engine.emit(BroadcastProvider.YOUTUBE, RtmpTargetState.FAILED, RtmpFailure.AUTH)
        scheduler.runUntilIdle()

        assertTrue("stop:youtube" in server.calls)
        assertTrue(media.engine.closed)
        assertEquals(BroadcastState.FAILED, states.last().first)
        assertEquals(BroadcastEvent.Failure(BroadcastFailure.YOUTUBE_PREPARE), states.last().second)
    }

    @Test fun publishingWaitTimesOutAndRollsBack() {
        val controller = connected(controller())
        controller.prepareBroadcast(settings, false)
        scheduler.runUntilIdle()
        scheduler.advance(20_000)

        assertTrue("stop:youtube" in server.calls)
        assertEquals(BroadcastState.FAILED, states.last().first)
    }

    @Test fun publisherFailureEndsPreparedBroadcastAndDeletesSession() {
        preparedYouTube()
        media.engine.failPublisher(IllegalStateException("encoder"))
        scheduler.runUntilIdle()

        assertTrue("delete:session-1" in server.calls)
        assertTrue(media.engine.closed)
        assertEquals(BroadcastState.FAILED, states.last().first)
        assertTrue(logs.any { it.startsWith("publisher_failed") })
    }

    @Test fun channelAlreadyLiveAsksForConfirmationAndRetriesConcurrently() {
        val controller = connected(controller())
        server.prepareFailures += ServerApiException("channel_already_live", statusCode = 409)
        controller.prepareBroadcast(settings, false)
        scheduler.runUntilIdle()

        val (state, event) = states.last()
        assertEquals(BroadcastState.FAILED, state)
        assertEquals(ServerErrorAction.CONFIRM_CONCURRENT, (event as BroadcastEvent.ApiFailure).guidance.action)
        assertNull(media.engineOrNull)

        assertTrue(controller.prepareBroadcast(settings, true))
        scheduler.runUntilIdle()
        assertEquals("prepare:youtube:true", server.calls.last())
    }

    @Test fun planBlockedPreparationShowsPlanGuidance() {
        val controller = connected(controller())
        server.prepareFailures += ServerApiException("plan_simulcast_not_allowed", statusCode = 403)
        controller.prepareBroadcast(settings, false)
        scheduler.runUntilIdle()

        val event = states.last().second as BroadcastEvent.ApiFailure
        assertEquals(ServerErrorAction.PLAN, event.guidance.action)
    }

    @Test fun missingSessionOnPrepareRecreatesSessionOnce() {
        val controller = connected(controller())
        server.settingsFailures += ServerApiException("not_found", statusCode = 404)
        controller.prepareBroadcast(settings, false)
        scheduler.runUntilIdle()

        assertEquals(listOf("create:youtube:720p", "settings:youtube", "create:youtube:720p", "settings:youtube",
            "prepare:youtube:false"), server.calls)
        assertEquals("session-2", recovery.stored?.sessionId)
    }

    @Test fun blankChzzkSettingsUseServerDefaults() {
        val controller = connected(controller(DirectBroadcastPlan(providers = setOf(BroadcastProvider.CHZZK)),
            BroadcastProvider.CHZZK))
        controller.prepareBroadcast(ChzzkBroadcastSettings())
        scheduler.runUntilIdle()

        assertEquals(listOf("defaults:chzzk", "settings:chzzk", "prepare:chzzk:false"), server.calls.drop(1))
        assertEquals(chzzkSettings, server.savedChzzk)
        // 치지직은 golive 전에 연결하지 않습니다.
        assertNull(media.engineOrNull)
        assertEquals(BroadcastState.PREPARED, states.last().first)
    }

    // --- 라이브 전환 ---

    @Test fun goLiveRetriesNotReadyEverySecondKeepingRtmpUp() {
        val controller = preparedYouTube()
        repeat(3) { server.goLiveFailures += ServerApiException("broadcast_not_ready", statusCode = 409) }
        var accepted = false
        assertTrue(controller.goLive { accepted = true })
        scheduler.runUntilIdle()
        assertTrue(accepted)
        assertEquals(listOf("starting"), keepAlive.calls)
        assertEquals(1, server.calls.count { it == "golive" })

        scheduler.advance(1_000)
        scheduler.advance(1_000)
        scheduler.advance(1_000)
        assertEquals(4, server.calls.count { it == "golive" })
        assertEquals(BroadcastState.LIVE, states.last().first)
        assertFalse("disconnect:youtube" in media.engine.calls)
        assertEquals(listOf("start:session-1"), heartbeat.calls)
        assertNull(uiStates.last().preparedDeadlineElapsedMillis)
    }

    @Test fun goLiveGivesUpAfterFifteenNotReadyAndStaysPrepared() {
        val controller = preparedYouTube()
        repeat(15) { server.goLiveFailures += ServerApiException("broadcast_not_ready", statusCode = 409) }
        controller.goLive {}
        scheduler.runUntilIdle()
        repeat(14) { scheduler.advance(1_000) }

        assertEquals(15, server.calls.count { it == "golive" })
        assertEquals(BroadcastState.PREPARED, states.last().first)
        assertFalse("disconnect:youtube" in media.engine.calls)
        assertEquals(listOf("starting", "ended"), keepAlive.calls)
        // 다시 시도할 수 있습니다.
        assertTrue(controller.goLive {})
    }

    @Test fun goLiveAfterPreparedSessionExpiredStartsOverWithNewSession() {
        val controller = preparedYouTube()
        server.goLiveFailures += ServerApiException("not_found", statusCode = 404)
        controller.goLive {}
        scheduler.runUntilIdle()

        assertEquals(BroadcastState.FAILED, states.last().first)
        assertTrue("disconnect:youtube" in media.engine.calls)
        assertNull(recovery.stored)

        assertTrue(controller.prepareBroadcast(settings, false))
        scheduler.runUntilIdle()
        assertEquals("create:youtube:720p", server.calls[server.calls.size - 3])
    }

    @Test fun stopDuringGoLiveIsReportedAsStopped() {
        val controller = preparedYouTube()
        server.goLiveFailures += ServerApiException("broadcast_stopped", statusCode = 409)
        controller.goLive {}
        scheduler.runUntilIdle()

        assertEquals(BroadcastState.IDLE to BroadcastEvent.Failure(BroadcastFailure.YOUTUBE_STOPPED), states.last())
        assertTrue("delete:session-1" in server.calls)
    }

    @Test fun simulcastConnectsChzzkOnlyFromGoLiveAndOnce() {
        val controller = connected(controller(DirectBroadcastPlan(providers = setOf(BroadcastProvider.YOUTUBE,
            BroadcastProvider.CHZZK))))
        assertTrue(controller.prepareDirectBroadcast(settings, chzzkSettings, false))
        scheduler.runUntilIdle()
        assertEquals(listOf("settings:youtube", "prepare:youtube:false", "settings:chzzk", "prepare:chzzk:false"),
            server.calls.drop(1))
        assertEquals(listOf("connect:youtube"), media.engine.calls)
        media.engine.emit(BroadcastProvider.YOUTUBE, RtmpTargetState.PUBLISHING)
        scheduler.runUntilIdle()

        server.goLiveResult = DirectGoLiveResult(BroadcastState.LIVE,
            listOf(DirectIngest(BroadcastProvider.CHZZK, chzzkUrl)), emptyList())
        live(controller)
        assertEquals(listOf("connect:youtube", "connect:chzzk"), media.engine.calls)
        assertSame(chzzkUrl, media.engine.urls[BroadcastProvider.CHZZK])
        assertEquals(BroadcastState.LIVE, states.last().first)
        assertEquals(1, server.calls.count { it == "golive" })
    }

    @Test fun simulcastWithOnlyYouTubeSettingsPreparesChzzkWithServerDefaults() {
        val controller = connected(controller(DirectBroadcastPlan(providers = setOf(BroadcastProvider.YOUTUBE,
            BroadcastProvider.CHZZK))))
        assertTrue(controller.prepareBroadcast(settings, false))
        scheduler.runUntilIdle()
        assertEquals(listOf("settings:youtube", "prepare:youtube:false", "defaults:chzzk", "settings:chzzk",
            "prepare:chzzk:false"), server.calls.drop(1))
        assertEquals(chzzkSettings, server.savedChzzk)
    }

    @Test fun simulcastWithoutYouTubeSettingsDoesNotPrepare() {
        val controller = connected(controller(DirectBroadcastPlan(providers = setOf(BroadcastProvider.YOUTUBE,
            BroadcastProvider.CHZZK)), BroadcastProvider.CHZZK))
        assertTrue(controller.prepareBroadcast(chzzkSettings))
        scheduler.runUntilIdle()
        assertEquals(1, server.calls.size)
        assertEquals(BroadcastState.FAILED, states.last().first)
    }

    @Test fun failedTargetIsCleanedUpWhileOtherContinues() {
        val controller = connected(controller(DirectBroadcastPlan(providers = setOf(BroadcastProvider.YOUTUBE,
            BroadcastProvider.CHZZK))))
        controller.prepareDirectBroadcast(settings, chzzkSettings, false)
        scheduler.runUntilIdle()
        media.engine.emit(BroadcastProvider.YOUTUBE, RtmpTargetState.PUBLISHING)
        scheduler.runUntilIdle()
        server.goLiveResult = DirectGoLiveResult(BroadcastState.LIVE, emptyList(),
            listOf(DirectTargetFailure("chzzk", "streaming_golive_failed", null)))
        live(controller)

        assertTrue("stop:chzzk" in server.calls)
        assertFalse("connect:chzzk" in media.engine.calls)
        assertEquals(BroadcastState.LIVE, states.last().first)
        assertNotNull(states.last().second)
        assertEquals(listOf(BroadcastProvider.YOUTUBE), heartbeat.inputs!!.current()!!.targets.map { it.provider })
    }

    @Test fun missingChzzkIngestEndsBroadcast() {
        val controller = connected(controller(DirectBroadcastPlan(providers = setOf(BroadcastProvider.CHZZK)),
            BroadcastProvider.CHZZK))
        controller.prepareBroadcast(chzzkSettings)
        scheduler.runUntilIdle()
        server.goLiveResult = DirectGoLiveResult(BroadcastState.LIVE, emptyList(), emptyList())
        live(controller)

        assertTrue("stop:chzzk" in server.calls)
        assertTrue("delete:session-1" in server.calls)
        assertEquals(BroadcastState.FAILED, states.last().first)
    }

    @Test fun chzzkFirstConnectFailureStopsThatProvider() {
        val controller = connected(controller(DirectBroadcastPlan(providers = setOf(BroadcastProvider.CHZZK)),
            BroadcastProvider.CHZZK))
        controller.prepareBroadcast(chzzkSettings)
        scheduler.runUntilIdle()
        server.goLiveResult = DirectGoLiveResult(BroadcastState.LIVE,
            listOf(DirectIngest(BroadcastProvider.CHZZK, chzzkUrl)), emptyList())
        live(controller)
        media.engine.emit(BroadcastProvider.CHZZK, RtmpTargetState.FAILED, RtmpFailure.REJECTED)
        scheduler.runUntilIdle()

        assertTrue("stop:chzzk" in server.calls)
        assertTrue("disconnect:chzzk" in media.engine.calls)
        assertEquals(BroadcastState.IDLE, states.last().first)
        // 처음 연결 실패는 재연결 대상이 아닙니다.
        assertTrue(supervisor.changes.isEmpty())
    }

    @Test fun engineIsRebuiltWhenLockedOrientationDiffers() {
        val controller = preparedYouTube()
        val first = media.engine
        assertEquals(PublishOrientation.LANDSCAPE, first.profile.let { if (it.width >= it.height) PublishOrientation.LANDSCAPE else PublishOrientation.PORTRAIT })
        media.orientation = PublishOrientation.PORTRAIT
        controller.goLive {}
        scheduler.runUntilIdle()

        assertTrue(first.closed)
        val rebuilt = media.engine
        assertEquals(720, rebuilt.profile.width)
        assertEquals(listOf("connect:youtube"), rebuilt.calls)
        assertEquals(0, server.calls.count { it == "golive" })
        rebuilt.emit(BroadcastProvider.YOUTUBE, RtmpTargetState.PUBLISHING)
        scheduler.runUntilIdle()
        assertEquals(1, server.calls.count { it == "golive" })
        assertEquals(BroadcastState.LIVE, states.last().first)
    }

    // --- 일시 중지·종료 ---

    @Test fun pauseAndResumeUseSlateWithoutServerCalls() {
        val controller = preparedYouTube()
        live(controller)
        val callsBefore = server.calls.size
        controller.pauseBroadcast()
        scheduler.runUntilIdle()
        assertEquals(BroadcastState.PAUSED, states.last().first)
        assertEquals(listOf(true), media.engine.slates)
        assertEquals(setOf(SlateReason.USER_PAUSE), uiStates.last().slate)

        controller.setSlate(SlateReason.BACKGROUND, true)
        controller.resumeBroadcast()
        scheduler.runUntilIdle()
        // 백그라운드 대기 화면은 사용자가 재개해도 유지합니다.
        assertEquals(BroadcastState.LIVE, states.last().first)
        assertEquals(listOf(true), media.engine.slates)
        controller.setSlate(SlateReason.BACKGROUND, false)
        scheduler.runUntilIdle()
        assertEquals(listOf(true, false), media.engine.slates)
        assertEquals(callsBefore, server.calls.size)
    }

    @Test fun stopSendsStopThenDisconnectsAndDeletesSession() {
        val controller = preparedYouTube()
        live(controller)
        server.stopFailure = IOException("network")
        controller.stopBroadcast()
        scheduler.runUntilIdle()

        assertEquals(listOf("stop:youtube", "delete:session-1"), server.calls.takeLast(2))
        assertTrue("disconnect:youtube" in media.engine.calls)
        assertTrue(media.engine.closed)
        assertNull(recovery.stored)
        assertEquals(BroadcastState.IDLE, states.last().first)
        assertEquals(listOf("start:session-1", "stop"), heartbeat.calls)
        assertEquals(listOf("starting", "ended"), keepAlive.calls)
        assertEquals(listOf(BroadcastProvider.YOUTUBE), supervisor.removed)

        // 다음 방송은 새 세션으로 시작합니다.
        controller.prepareBroadcast(settings, false)
        scheduler.runUntilIdle()
        assertTrue("create:youtube:720p" in server.calls.takeLast(3))
        assertEquals("session-2", recovery.stored?.sessionId)
    }

    @Test fun cancellingWhileWaitingForPublishingStopsPreparation() {
        val controller = connected(controller())
        controller.prepareBroadcast(settings, false)
        scheduler.runUntilIdle()
        controller.stopBroadcast()
        scheduler.runUntilIdle()

        assertTrue("stop:youtube" in server.calls)
        assertEquals(BroadcastState.IDLE, states.last().first)
        scheduler.advance(30_000)
        assertEquals(BroadcastState.IDLE, states.last().first)
    }

    // --- 서버 종료 ---

    @Test fun heartbeatStopTargetsDisconnectsWithoutStopAndEndsSession() {
        val controller = preparedYouTube()
        live(controller)
        heartbeat.listener!!.onStopTargets(listOf(BroadcastProvider.YOUTUBE))
        scheduler.runUntilIdle()

        assertFalse("stop:youtube" in server.calls)
        assertTrue("disconnect:youtube" in media.engine.calls)
        assertTrue("delete:session-1" in server.calls)
        assertEquals(BroadcastState.IDLE to DirectBroadcastNotice.TargetStoppedByServer(BroadcastProvider.YOUTUBE).toBroadcastEvent(), states.last())
    }

    @Test fun heartbeatResultAfterStopIsIgnored() {
        val controller = preparedYouTube()
        live(controller)
        val listener = heartbeat.listener!!
        controller.stopBroadcast()
        scheduler.runUntilIdle()
        controller.prepareBroadcast(settings, false)
        scheduler.runUntilIdle()
        media.engine.emit(BroadcastProvider.YOUTUBE, RtmpTargetState.PUBLISHING)
        scheduler.runUntilIdle()
        val calls = media.engine.calls.toList()

        listener.onStopAll(HeartbeatStopReason.SESSION_GONE)
        scheduler.runUntilIdle()
        assertEquals(calls, media.engine.calls)
        assertEquals(BroadcastState.PREPARED, states.last().first)
    }

    @Test fun heartbeatSessionGoneForgetsSessionWithoutDelete() {
        val controller = preparedYouTube()
        live(controller)
        heartbeat.listener!!.onStopAll(HeartbeatStopReason.SESSION_GONE)
        scheduler.runUntilIdle()

        assertFalse("delete:session-1" in server.calls)
        assertNull(recovery.stored)
        assertEquals(BroadcastState.IDLE, states.last().first)
    }

    @Test fun polledIdlePhaseDisconnectsEndedTarget() {
        val controller = preparedYouTube()
        live(controller)
        server.sessionPayload = """{"session_id":"session-1","targets":[{"provider":"youtube","stream":{"status":"idle","broadcast_phase":"idle"}}]}"""
        scheduler.advance(2_000)

        assertTrue("disconnect:youtube" in media.engine.calls)
        assertEquals(BroadcastState.IDLE, states.last().first)
        assertTrue(snapshots.isNotEmpty())
    }

    @Test fun pollRequestedBeforeGoLiveDoesNotEndLiveBroadcast() {
        val pending = ArrayDeque<Runnable>()
        val controller = connected(controller(io = DeferredBackgroundExecutor(pending)))
        // 준비 전 IDLE에서 보낸 조회가 서버 idle 응답으로 방송 시작 뒤에 돌아옵니다.
        scheduler.advance(2_000)
        assertEquals(1, pending.size)
        server.sessionPayload = """{"session_id":"session-1","targets":[{"provider":"youtube","stream":{"status":"idle","broadcast_phase":"idle"}}]}"""
        val stale = pending.removeFirst()

        assertTrue(controller.prepareBroadcast(settings, false))
        scheduler.runUntilIdle()
        media.engine.emit(BroadcastProvider.YOUTUBE, RtmpTargetState.PUBLISHING)
        scheduler.runUntilIdle()
        live(controller)
        assertEquals(BroadcastState.LIVE, states.last().first)

        stale.run()
        scheduler.runUntilIdle()

        assertEquals(BroadcastState.LIVE, states.last().first)
        assertFalse("disconnect:youtube" in media.engine.calls)
        assertFalse("delete:session-1" in server.calls)

        // 폴링은 이어지고, 방송 중에 보낸 조회의 idle 응답은 반영합니다.
        scheduler.advance(2_000)
        pending.removeFirst().run()
        scheduler.runUntilIdle()
        assertTrue("disconnect:youtube" in media.engine.calls)
        assertEquals(BroadcastState.IDLE, states.last().first)
    }

    @Test fun reconnectSupervisorDrivesReconnectAndGiveUp() {
        val controller = preparedYouTube()
        live(controller)
        media.engine.emit(BroadcastProvider.YOUTUBE, RtmpTargetState.DISCONNECTED)
        scheduler.runUntilIdle()
        assertEquals(LocalTargetStatus.RECONNECTING, uiStates.last().targets[BroadcastProvider.YOUTUBE])
        assertEquals(RtmpTargetState.DISCONNECTED, supervisor.changes.last())

        supervisor.actions!!.reconnect(BroadcastProvider.YOUTUBE)
        scheduler.runUntilIdle()
        assertTrue("reconnect:youtube" in media.engine.calls)

        supervisor.actions!!.giveUp(BroadcastProvider.YOUTUBE, RtmpFailure.NETWORK)
        scheduler.runUntilIdle()
        assertTrue("stop:youtube" in server.calls)
        assertEquals(BroadcastState.IDLE, states.last().first)
    }

    @Test fun heartbeatReportsLiveTargetsWithEngineStats() {
        val controller = preparedYouTube()
        assertNull(heartbeat.inputs!!.current())
        live(controller)
        media.engine.connectedProviders += BroadcastProvider.YOUTUBE
        media.failedFrames = 3

        val inputs = heartbeat.inputs!!.current()!!
        assertTrue(inputs.targets.single().connected)
        assertEquals(3L, inputs.anonymization.failedFrames)
        assertEquals(true, inputs.anonymization.enabled)
        assertEquals(30.0, inputs.video.fps, 0.0)
    }

    // --- 기타 ---

    @Test fun microphoneStatusIsPublished() {
        connected(controller())
        media.events.onMicrophoneStatus(MicrophoneStatus.UNAVAILABLE)
        scheduler.runUntilIdle()
        assertEquals(MicrophoneStatus.UNAVAILABLE, uiStates.last().microphone)
    }

    @Test fun anonymizationIsToggledLocally() {
        val controller = connected(controller())
        var confirmed: AnonymizationState? = null
        controller.setAnonymizationEnabled(false) { state, _ -> confirmed = state }
        scheduler.runUntilIdle()
        assertEquals(AnonymizationState.DISABLED, confirmed)
        assertEquals(false, media.anonymization)
    }

    @Test fun connectionConfirmsInitialAnonymizationEnabled() {
        val controller = controller()
        controller.start()
        scheduler.runUntilIdle()
        assertEquals(emptyList<AnonymizationState>(), anonymizationConfirmations)

        media.events.onFirstFrame()
        scheduler.runUntilIdle()
        assertEquals(listOf(AnonymizationState.ENABLED), anonymizationConfirmations)
        assertEquals(WebRtcConnectionState.CONNECTED, connections.last().first)
    }

    @Test fun connectionConfirmsInitialAnonymizationDisabled() {
        connected(controller(anonymizationEnabled = false))
        assertEquals(listOf(AnonymizationState.DISABLED), anonymizationConfirmations)
    }

    @Test fun localAnonymizationToggleUpdatesConfirmedState() {
        val controller = connected(controller())
        controller.setAnonymizationEnabled(false) { _, _ -> }
        scheduler.runUntilIdle()
        controller.setAnonymizationEnabled(true) { _, _ -> }
        scheduler.runUntilIdle()

        assertEquals(
            listOf(AnonymizationState.ENABLED, AnonymizationState.DISABLED, AnonymizationState.ENABLED),
            anonymizationConfirmations,
        )
    }

    @Test fun failedLocalAnonymizationToggleKeepsConfirmedState() {
        val controller = connected(controller())
        media.failAnonymization = true
        var failure: AnonymizationFailure? = null
        controller.setAnonymizationEnabled(false) { _, error -> failure = error }
        scheduler.runUntilIdle()

        assertEquals(AnonymizationFailure.REQUEST, failure)
        assertEquals(listOf(AnonymizationState.ENABLED), anonymizationConfirmations)
    }

    @Test fun anonymizationIsNotConfirmedAfterClose() {
        val controller = controller()
        controller.start()
        scheduler.runUntilIdle()
        controller.close()
        media.events.onFirstFrame()
        controller.setAnonymizationEnabled(false) { _, _ -> }
        scheduler.runUntilIdle()

        assertEquals(emptyList<AnonymizationState>(), anonymizationConfirmations)
    }

    @Test fun processingFailureBeforeFirstFrameFailsConnection() {
        val controller = controller()
        controller.start()
        scheduler.runUntilIdle()
        media.events.onProcessingFailure()
        scheduler.runUntilIdle()
        assertEquals(WebRtcConnectionState.FAILED, connections.last().first)
        assertTrue("delete:session-1" in server.calls)
    }

    @Test fun closeEndsBroadcastAndDeletesSession() {
        val controller = preparedYouTube()
        live(controller)
        var completed = false
        controller.close { completed = true }
        scheduler.runUntilIdle()

        assertTrue(completed)
        assertTrue("stop:youtube" in server.calls)
        assertTrue("delete:session-1" in server.calls)
        assertTrue(media.closed)
        assertTrue(server.closed)
        assertTrue(heartbeat.closed)
        assertTrue(supervisor.closed)
    }

    @Test fun closeShutsDownStatusIoExecutor() {
        val controller = preparedYouTube()
        var completed = false
        controller.close { completed = true }
        assertFalse(io.shutdown)
        scheduler.runUntilIdle()

        assertTrue(completed)
        assertTrue(io.shutdown)
    }

    @Test fun repeatedCloseCompletesOnlyAfterCleanup() {
        val controller = preparedYouTube()
        live(controller)
        val completed = mutableListOf<String>()
        controller.close { completed += "first" }
        controller.close {
            assertTrue(server.closed)
            assertTrue("delete:session-1" in server.calls)
            completed += "second"
        }

        assertEquals(emptyList<String>(), completed)
        scheduler.runUntilIdle()
        assertEquals(listOf("first", "second"), completed)
    }

    @Test fun closeAfterCleanupCompletesImmediately() {
        val controller = preparedYouTube()
        live(controller)
        controller.close()
        scheduler.runUntilIdle()

        var completed = false
        controller.close { completed = true }
        assertTrue(completed)
    }

    @Test fun ingestUrlNeverReachesLogs() {
        val controller = preparedYouTube()
        live(controller)
        controller.stopBroadcast()
        scheduler.runUntilIdle()
        assertTrue(logs.none { "key" in it })
    }

    // --- 서버 오류 뒤 종료·재시도 가능 여부 ---

    /**
     * youtube-broadcast-v1.md: 재시도가 끝나도 준비 상태를 유지해 사용자가 다시 시도하거나 취소할 수 있게 한다.
     * android-broadcast-orientation.md: 방송 시작이 실패해 prepared로 돌아가거나 종료가 성공하면 잠금을 해제한다.
     * 직접 송출은 서버 스냅샷으로 방송 상태를 전진시키지 않으므로, 서버 오류 뒤에도 종료는 받아야 합니다.
     */
    private fun assertStopIsAcceptedAfterGoLiveError(code: String) {
        val controller = preparedYouTube()
        server.goLiveFailures += ServerApiException(code, statusCode = 409)
        assertTrue(controller.goLive {})
        scheduler.runUntilIdle()
        assertEquals(1, server.calls.count { it == "golive" })

        controller.stopBroadcast()
        scheduler.runUntilIdle()
        assertTrue("$code 응답 뒤 종료가 무시됩니다: ${states.last().first}", "stop:youtube" in server.calls)
        assertEquals(BroadcastState.IDLE, states.last().first)
        assertEquals(listOf("starting", "ended"), keepAlive.calls)
    }

    @Test fun busyGoLiveResponseStillAcceptsStop() = assertStopIsAcceptedAfterGoLiveError("broadcast_busy")

    @Test fun goingLiveResponseStillAcceptsStop() = assertStopIsAcceptedAfterGoLiveError("broadcast_going_live")

    @Test fun busyPrepareResponseStillAcceptsStopAndNewPreparation() {
        val controller = connected(controller())
        server.prepareFailures += ServerApiException("broadcast_busy", statusCode = 409)
        assertTrue(controller.prepareBroadcast(settings, false))
        scheduler.runUntilIdle()
        assertEquals(1, server.calls.count { it.startsWith("prepare:") })

        controller.stopBroadcast()
        scheduler.runUntilIdle()
        assertTrue("busy 준비 응답 뒤 고착: ${states.last().first}", controller.prepareBroadcast(settings, false))
    }

    /**
     * youtube-broadcast-v1.md: live 단계에서는 방송 설정을 잠근다. 일시 중지도 서버 단계는 live이므로
     * 설정 저장이 라이브 방송을 IDLE로 되돌리면 안 됩니다.
     */
    @Test fun savingSettingsWhilePausedDoesNotResetTheLiveBroadcast() {
        val controller = preparedYouTube()
        live(controller)
        controller.pauseBroadcast()
        scheduler.runUntilIdle()
        assertEquals(BroadcastState.PAUSED, states.last().first)
        val settingsCalls = server.calls.count { it == "settings:youtube" }

        controller.saveBroadcastSettings(settings)
        scheduler.runUntilIdle()

        assertEquals(settingsCalls, server.calls.count { it == "settings:youtube" })
        assertEquals(BroadcastState.PAUSED, states.last().first)
        assertEquals(listOf("start:session-1"), heartbeat.calls)
    }

    // --- 라이브 전환 실패 뒤 복귀 ---

    /** android-broadcast-orientation.md: 방송 시작이 실패해 prepared로 돌아가면 잠금을 해제한다. */
    @Test fun goLivePublishingWaitTimeoutReturnsToPreparedAndAllowsRetry() {
        val controller = preparedYouTube()
        media.orientation = PublishOrientation.PORTRAIT
        assertTrue(controller.goLive {})
        scheduler.runUntilIdle()
        assertEquals(BroadcastState.GOING_LIVE, states.last().first)
        assertEquals(0, server.calls.count { it == "golive" })

        scheduler.advance(20_000)

        assertEquals(0, server.calls.count { it == "golive" })
        assertEquals(BroadcastState.PREPARED to DirectEvent.NOT_READY.toBroadcastEvent(), states.last())
        assertEquals(listOf("starting", "ended"), keepAlive.calls)
        assertTrue(controller.goLive {})
    }

    @Test fun goLiveNetworkFailureReturnsToPreparedKeepingRtmpUp() {
        val controller = preparedYouTube()
        server.goLiveFailures += IOException("network")
        assertTrue(controller.goLive {})
        scheduler.runUntilIdle()

        assertEquals(BroadcastState.PREPARED to DirectEvent.REQUEST.toBroadcastEvent(), states.last())
        assertFalse("disconnect:youtube" in media.engine.calls)
        assertEquals(listOf("starting", "ended"), keepAlive.calls)
        assertTrue(controller.goLive {})
    }

    // --- 하트비트 종료 사유와 알림 ---

    /** direct-publish-v1.md: 409 heartbeat_not_applicable은 라이브 대상 없음. 모든 연결을 끊고 세션을 끝냅니다. */
    @Test fun heartbeatNotApplicableEndsEveryTargetWithoutStopAndDeletesSession() {
        val controller = preparedYouTube()
        live(controller)
        heartbeat.listener!!.onStopAll(HeartbeatStopReason.NOT_APPLICABLE)
        scheduler.runUntilIdle()

        assertFalse("stop:youtube" in server.calls)
        assertTrue("disconnect:youtube" in media.engine.calls)
        assertTrue("delete:session-1" in server.calls)
        assertNull(recovery.stored)
        assertEquals(BroadcastState.IDLE to DirectEvent.SERVER_STOPPED.toBroadcastEvent(), states.last())
        assertEquals(listOf("start:session-1", "stop"), heartbeat.calls)
        assertEquals(listOf("starting", "ended"), keepAlive.calls)
    }

    /** direct-publish-v1.md: 403 forbidden은 소유권 상실. 지울 수 없는 세션은 기록만 비우고 모든 연결을 끊습니다. */
    @Test fun heartbeatOwnershipLostForgetsSessionWithoutDeleteOrStop() {
        val controller = preparedYouTube()
        live(controller)
        heartbeat.listener!!.onStopAll(HeartbeatStopReason.OWNERSHIP_LOST)
        scheduler.runUntilIdle()

        assertFalse("stop:youtube" in server.calls)
        assertFalse("delete:session-1" in server.calls)
        assertTrue("disconnect:youtube" in media.engine.calls)
        assertTrue(media.engine.closed)
        assertNull(recovery.stored)
        assertEquals(BroadcastState.IDLE to DirectEvent.SERVER_STOPPED.toBroadcastEvent(), states.last())

        // 다음 방송은 새 세션으로 시작합니다.
        assertTrue(controller.prepareBroadcast(settings, false))
        scheduler.runUntilIdle()
        assertEquals("session-2", recovery.stored?.sessionId)
    }

    @Test fun heartbeatNoticesAndRemainingTimeUpdateTheSnapshot() {
        val controller = preparedYouTube()
        live(controller)
        heartbeat.listener!!.onNotices(listOf(SessionNotice("broadcast_limit_10m", "2026-10-12T08:30:00Z")), 540)
        scheduler.runUntilIdle()

        val limited = snapshots.last()
        assertEquals(listOf("broadcast_limit_10m"), limited.notices!!.map { it.code })
        assertEquals(BroadcastRemainingTime.Seconds(540), limited.remainingTime)
        assertEquals(540L, limited.lastRemainingSeconds)
        assertFalse(limited.isRemainingTimeStale)

        heartbeat.listener!!.onNotices(emptyList(), null)
        scheduler.runUntilIdle()
        assertEquals(BroadcastRemainingTime.UnlimitedOrInactive, snapshots.last().remainingTime)
        assertEquals(540L, snapshots.last().lastRemainingSeconds)
        assertEquals(emptyList<SessionNotice>(), snapshots.last().notices)
    }

    // --- 동시 송출의 부분 종료 ---

    private fun liveSimulcast(): DirectBroadcastController {
        val controller = connected(controller(DirectBroadcastPlan(providers = setOf(BroadcastProvider.YOUTUBE,
            BroadcastProvider.CHZZK))))
        assertTrue(controller.prepareDirectBroadcast(settings, chzzkSettings, false))
        scheduler.runUntilIdle()
        media.engine.emit(BroadcastProvider.YOUTUBE, RtmpTargetState.PUBLISHING)
        scheduler.runUntilIdle()
        server.goLiveResult = DirectGoLiveResult(BroadcastState.LIVE,
            listOf(DirectIngest(BroadcastProvider.CHZZK, chzzkUrl)), emptyList())
        live(controller)
        media.engine.emit(BroadcastProvider.CHZZK, RtmpTargetState.PUBLISHING)
        scheduler.runUntilIdle()
        assertEquals(BroadcastState.LIVE, states.last().first)
        assertEquals(listOf(BroadcastProvider.YOUTUBE, BroadcastProvider.CHZZK),
            heartbeat.inputs!!.current()!!.targets.map { it.provider })
        return controller
    }

    /** direct-publish-v1.md: stop_targets[]의 대상만 즉시 끊고, 사용자가 stop하지 않은 다른 대상은 유지합니다. */
    @Test fun heartbeatStopTargetsForOneProviderKeepsTheOtherLive() {
        val controller = liveSimulcast()
        heartbeat.listener!!.onStopTargets(listOf(BroadcastProvider.CHZZK))
        scheduler.runUntilIdle()

        assertTrue("disconnect:chzzk" in media.engine.calls)
        assertFalse("disconnect:youtube" in media.engine.calls)
        assertFalse("stop:chzzk" in server.calls)
        assertFalse("delete:session-1" in server.calls)
        assertEquals(
            BroadcastState.LIVE to DirectBroadcastNotice.TargetStoppedByServer(BroadcastProvider.CHZZK).toBroadcastEvent(),
            states.last(),
        )
        assertEquals(listOf(BroadcastProvider.YOUTUBE), heartbeat.inputs!!.current()!!.targets.map { it.provider })
        assertEquals(listOf("start:session-1"), heartbeat.calls)
        assertEquals(listOf(BroadcastProvider.CHZZK), supervisor.removed)

        // 이미 끝난 대상을 서버가 다시 알려도 남은 대상에는 영향이 없습니다.
        heartbeat.listener!!.onStopTargets(listOf(BroadcastProvider.CHZZK))
        scheduler.runUntilIdle()
        assertEquals(BroadcastState.LIVE, states.last().first)

        controller.stopBroadcast()
        scheduler.runUntilIdle()
        assertEquals(listOf("stop:youtube", "delete:session-1"), server.calls.takeLast(2))
        assertEquals(BroadcastState.IDLE, states.last().first)
    }

    @Test fun reconnectGiveUpForOneProviderKeepsTheOtherLive() {
        liveSimulcast()
        media.engine.emit(BroadcastProvider.CHZZK, RtmpTargetState.DISCONNECTED)
        scheduler.runUntilIdle()
        assertEquals(LocalTargetStatus.RECONNECTING, uiStates.last().targets[BroadcastProvider.CHZZK])
        assertEquals(LocalTargetStatus.PUBLISHING, uiStates.last().targets[BroadcastProvider.YOUTUBE])

        supervisor.actions!!.giveUp(BroadcastProvider.CHZZK, RtmpFailure.NETWORK)
        scheduler.runUntilIdle()

        assertTrue("stop:chzzk" in server.calls)
        assertTrue("disconnect:chzzk" in media.engine.calls)
        assertFalse("stop:youtube" in server.calls)
        assertFalse("delete:session-1" in server.calls)
        assertEquals(BroadcastState.LIVE to directConnectionLostEvent(BroadcastProvider.CHZZK), states.last())
        assertEquals(listOf(BroadcastProvider.YOUTUBE), heartbeat.inputs!!.current()!!.targets.map { it.provider })
        assertNull(uiStates.last().targets[BroadcastProvider.CHZZK])
        assertEquals(listOf("start:session-1"), heartbeat.calls)
    }

    @Test fun chzzkFirstConnectFailureDuringSimulcastKeepsYouTubeLive() {
        val controller = connected(controller(DirectBroadcastPlan(providers = setOf(BroadcastProvider.YOUTUBE,
            BroadcastProvider.CHZZK))))
        assertTrue(controller.prepareDirectBroadcast(settings, chzzkSettings, false))
        scheduler.runUntilIdle()
        media.engine.emit(BroadcastProvider.YOUTUBE, RtmpTargetState.PUBLISHING)
        scheduler.runUntilIdle()
        server.goLiveResult = DirectGoLiveResult(BroadcastState.LIVE,
            listOf(DirectIngest(BroadcastProvider.CHZZK, chzzkUrl)), emptyList())
        live(controller)
        media.engine.emit(BroadcastProvider.CHZZK, RtmpTargetState.FAILED, RtmpFailure.REJECTED)
        scheduler.runUntilIdle()

        assertTrue("stop:chzzk" in server.calls)
        assertTrue("disconnect:chzzk" in media.engine.calls)
        assertFalse("stop:youtube" in server.calls)
        assertFalse("delete:session-1" in server.calls)
        assertEquals(BroadcastState.LIVE to DirectEvent.CONNECT_FAILED.toBroadcastEvent(), states.last())
        assertEquals(listOf(BroadcastProvider.YOUTUBE), heartbeat.inputs!!.current()!!.targets.map { it.provider })
        // 처음 연결 실패는 재연결 대상이 아닙니다.
        assertTrue(supervisor.changes.none { it == RtmpTargetState.FAILED })
    }

    @Test fun supervisorCallbacksAfterStopDoNotReconnectOrSendStop() {
        val controller = preparedYouTube()
        live(controller)
        val actions = supervisor.actions!!
        controller.stopBroadcast()
        scheduler.runUntilIdle()
        val serverCalls = server.calls.toList()
        val engineCalls = media.engine.calls.toList()
        val reported = states.size

        actions.reconnect(BroadcastProvider.YOUTUBE)
        actions.giveUp(BroadcastProvider.YOUTUBE, RtmpFailure.NETWORK)
        scheduler.runUntilIdle()

        assertEquals(serverCalls, server.calls)
        assertEquals(engineCalls, media.engine.calls)
        assertEquals(reported, states.size)
    }

    // --- 세션 조회 404·일시 오류 ---

    @Test fun polled404WhilePreparedFailsPreparationAndForgetsSession() {
        val controller = preparedYouTube()
        server.fetchFailure = ServerApiException("not_found", statusCode = 404)
        scheduler.advance(2_000)

        assertTrue("disconnect:youtube" in media.engine.calls)
        assertFalse("stop:youtube" in server.calls)
        assertFalse("delete:session-1" in server.calls)
        assertNull(recovery.stored)
        assertEquals(BroadcastState.FAILED to DirectEvent.PREPARED_EXPIRED.toBroadcastEvent(), states.last())

        server.fetchFailure = null
        assertTrue(controller.prepareBroadcast(settings, false))
        scheduler.runUntilIdle()
        assertEquals("session-2", recovery.stored?.sessionId)
    }

    @Test fun polled404WhileLiveEndsBroadcastWithoutStopOrDelete() {
        val controller = preparedYouTube()
        live(controller)
        server.fetchFailure = ServerApiException("not_found", statusCode = 404)
        scheduler.advance(2_000)

        assertFalse("stop:youtube" in server.calls)
        assertFalse("delete:session-1" in server.calls)
        assertTrue("disconnect:youtube" in media.engine.calls)
        assertNull(recovery.stored)
        assertEquals(BroadcastState.IDLE to DirectEvent.SERVER_STOPPED.toBroadcastEvent(), states.last())
        assertEquals(listOf("start:session-1", "stop"), heartbeat.calls)
        assertEquals(listOf("starting", "ended"), keepAlive.calls)
    }

    @Test fun transientPollFailureMarksRemainingTimeStaleUntilTheNextSuccess() {
        preparedYouTube()
        server.sessionPayload = """{"session_id":"session-1","broadcast_remaining_seconds":600}"""
        scheduler.advance(2_000)
        assertEquals(BroadcastRemainingTime.Seconds(600), snapshots.last().remainingTime)
        assertFalse(snapshots.last().isRemainingTimeStale)

        server.fetchFailure = IOException("timeout")
        scheduler.advance(2_000)
        assertTrue(snapshots.last().isRemainingTimeStale)
        assertEquals(BroadcastRemainingTime.Seconds(600), snapshots.last().remainingTime)

        server.fetchFailure = null
        scheduler.advance(2_000)
        assertFalse(snapshots.last().isRemainingTimeStale)
    }

    // --- 가짜 의존성 ---

    private fun session(id: String) = CreatedSession(id, "owner-$id", AnonymizationState.DISABLED)

    private inner class FakeServer : DirectSessionServer {
        val calls = mutableListOf<String>()
        var createFailure: Exception? = null
        var createFailureOnce = false
        val settingsFailures = ArrayDeque<Exception>()
        val prepareFailures = ArrayDeque<Exception>()
        val goLiveFailures = ArrayDeque<Exception>()
        var goLiveResult = DirectGoLiveResult(BroadcastState.LIVE, emptyList(), emptyList())
        var stopFailure: Exception? = null
        var fetchFailure: Exception? = null
        var savedChzzk: ChzzkBroadcastSettings? = null
        var sessionPayload = """{"session_id":"session-1"}"""
        var closed = false
        private var created = 0

        override fun createSession(provider: BroadcastProvider, resolution: DirectPublishResolution): CreatedSession {
            calls += "create:${provider.wireValue}:${resolution.wireValue}"
            createFailure?.let {
                if (createFailureOnce) createFailure = null
                throw it
            }
            created++
            return CreatedSession("session-$created", "owner-$created", AnonymizationState.DISABLED, provider)
        }

        override fun saveYouTubeSettings(session: CreatedSession, settings: BroadcastSettings) {
            calls += "settings:youtube"
            settingsFailures.removeFirstOrNull()?.let { throw it }
        }

        override fun saveChzzkSettings(session: CreatedSession, settings: ChzzkBroadcastSettings) {
            calls += "settings:chzzk"
            savedChzzk = settings
        }

        override fun chzzkDefaults(session: CreatedSession): ChzzkBroadcastSettings {
            calls += "defaults:chzzk"
            return chzzkSettings
        }

        override fun prepare(session: CreatedSession, provider: BroadcastProvider, allowConcurrent: Boolean): DirectPrepareResult {
            calls += "prepare:${provider.wireValue}:$allowConcurrent"
            prepareFailures.removeFirstOrNull()?.let { throw it }
            return DirectPrepareResult(BroadcastState.PREPARED,
                if (provider == BroadcastProvider.YOUTUBE) youtubeUrl else null)
        }

        override fun goLive(session: CreatedSession, provider: BroadcastProvider): DirectGoLiveResult {
            calls += "golive"
            goLiveFailures.removeFirstOrNull()?.let { throw it }
            return goLiveResult
        }

        override fun stop(session: CreatedSession, provider: BroadcastProvider): BroadcastState? {
            calls += "stop:${provider.wireValue}"
            stopFailure?.let { throw it }
            return BroadcastState.IDLE
        }

        override fun deleteSession(session: CreatedSession) {
            calls += "delete:${session.sessionId}"
        }

        override fun fetchSession(session: CreatedSession): String {
            fetchFailure?.let { throw it }
            return sessionPayload
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeRecovery : DirectSessionRecovery {
        var stored: CreatedSession? = null
        var legacy: CreatedSession? = null
        var failSave = false
        override fun load() = stored
        override fun loadLegacy() = legacy
        override fun save(session: CreatedSession) {
            if (failSave) throw IOException("keystore")
            stored = session
        }
        override fun clear() {
            stored = null
        }
        override fun clearLegacy() {
            legacy = null
        }
    }

    private class FakeEngine(override val profile: DirectPublishProfile, val listener: DirectPublisher.Listener) :
        DirectPublishEngine {
        val calls = mutableListOf<String>()
        val urls = mutableMapOf<BroadcastProvider, IngestUrl>()
        val slates = mutableListOf<Boolean>()
        val connectedProviders = mutableSetOf<BroadcastProvider>()
        var closed = false

        fun emit(provider: BroadcastProvider, state: RtmpTargetState, failure: RtmpFailure? = null) =
            listener.onTargetStateChanged(provider, state, failure)

        fun failPublisher(error: Exception) = listener.onPublisherFailed(error)

        override fun connect(provider: BroadcastProvider, ingestUrl: IngestUrl) {
            calls += "connect:${provider.wireValue}"
            urls[provider] = ingestUrl
        }

        override fun reconnect(provider: BroadcastProvider) {
            calls += "reconnect:${provider.wireValue}"
        }

        override fun disconnect(provider: BroadcastProvider) {
            calls += "disconnect:${provider.wireValue}"
        }

        override fun setSlate(active: Boolean) {
            if (slates.lastOrNull() != active && (slates.isNotEmpty() || active)) slates += active
        }

        override fun stats() = DirectPublishStats(
            connectedProviders.map { TargetPublishStats(it, true, 4000, 0) }, 30.0, 0, profile.width, profile.height)

        override fun close() {
            closed = true
        }
    }

    private class FakeMedia : DirectMediaPipeline {
        lateinit var events: DirectMediaEvents
        var engineOrNull: FakeEngine? = null
        val engine: FakeEngine get() = checkNotNull(engineOrNull)
        var orientation = PublishOrientation.LANDSCAPE
        var failedFrames = 0L
        var anonymization: Boolean? = null
        var failAnonymization = false
        var closed = false

        override fun open(events: DirectMediaEvents) {
            this.events = events
        }

        override val frameOrientation: PublishOrientation get() = orientation
        override val anonymizationFailedFrames: Long get() = failedFrames

        override fun setAnonymizationEnabled(enabled: Boolean) {
            if (failAnonymization) throw IllegalStateException("가림 처리 전환 실패")
            anonymization = enabled
        }

        override fun setPreferredAudioInput(device: AudioDeviceInfo?) = Unit

        override fun createEngine(profile: DirectPublishProfile, listener: DirectPublisher.Listener): DirectPublishEngine =
            FakeEngine(profile, listener).also { engineOrNull = it }

        override fun close() {
            closed = true
        }
    }

    private class FakeHeartbeat : HeartbeatScheduler {
        var inputs: HeartbeatInputSource? = null
        var listener: HeartbeatListener? = null
        val calls = mutableListOf<String>()
        var closed = false
        override fun start(session: CreatedSession) {
            calls += "start:${session.sessionId}"
        }
        override fun stop() {
            calls += "stop"
        }
        override fun close() {
            closed = true
        }
    }

    private class FakeSupervisor : ReconnectSupervisor {
        var actions: ReconnectActions? = null
        val changes = mutableListOf<RtmpTargetState>()
        val removed = mutableListOf<BroadcastProvider>()
        var closed = false
        override fun onTargetStateChanged(provider: BroadcastProvider, state: RtmpTargetState, failure: RtmpFailure?) {
            changes += state
        }
        override fun onTargetRemoved(provider: BroadcastProvider) {
            removed += provider
        }
        override fun close() {
            closed = true
        }
    }

    private class FakeKeepAlive : BroadcastKeepAlive {
        val calls = mutableListOf<String>()
        override fun onBroadcastStarting() {
            calls += "starting"
        }
        override fun onBroadcastEnded() {
            calls += "ended"
        }
    }
}

/** 받은 작업을 바로 실행하고 종료 여부를 기록합니다. */
internal class ImmediateBackgroundExecutor : BackgroundExecutor {
    var shutdown = false
        private set

    override fun execute(block: () -> Unit) {
        if (!shutdown) block()
    }

    override fun shutdown() {
        shutdown = true
    }
}

/** 시간을 직접 옮기는 직렬 실행기입니다. */
internal class ManualScheduler : SerialScheduler {
    private class Task(val at: Long, val order: Long, val block: () -> Unit, var cancelled: Boolean = false)

    private val queue = ArrayDeque<() -> Unit>()
    private val timed = mutableListOf<Task>()
    private var now = 0L
    private var order = 0L

    override fun execute(block: () -> Unit) {
        queue.addLast(block)
    }

    override fun schedule(delayMillis: Long, block: () -> Unit): Cancellable {
        val task = Task(now + delayMillis, order++, block)
        timed += task
        return Cancellable { task.cancelled = true }
    }

    override fun shutdown() = Unit

    fun runUntilIdle() {
        var steps = 0
        while (queue.isNotEmpty()) {
            check(++steps <= 10_000) { "작업이 끝나지 않습니다." }
            queue.removeFirst()()
        }
    }

    fun advance(millis: Long) {
        val end = now + millis
        runUntilIdle()
        while (true) {
            val next = timed.filter { !it.cancelled && it.at <= end }.minWithOrNull(compareBy({ it.at }, { it.order })) ?: break
            timed.remove(next)
            now = next.at
            next.block()
            runUntilIdle()
        }
        timed.removeAll { it.cancelled }
        now = end
    }
}

/** 받은 작업을 큐에 보관해 테스트가 실행 시점을 정하게 합니다. */
internal class DeferredBackgroundExecutor(private val queue: ArrayDeque<Runnable>) : BackgroundExecutor {
    override fun execute(block: () -> Unit) {
        queue.addLast(Runnable(block))
    }

    override fun shutdown() = Unit
}
