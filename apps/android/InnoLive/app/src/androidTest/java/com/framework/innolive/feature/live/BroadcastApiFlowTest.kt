package com.framework.innolive.feature.live

import android.util.Base64
import com.framework.innolive.feature.youtube.canChangeYouTubeAccount
import com.framework.innolive.ui.text.serverErrorGuidance
import com.framework.innolive.ui.text.ServerErrorAction
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean

// 제품의 요청 생성·응답 처리·작업 큐를 실행한다. 네트워크와 미디어 연결은 검증하지 않는다.
class BroadcastApiFlowTest {
    private val settings = BroadcastSettings("검증", "검증 설명", "private", false, "22")
    private val accessToken = accessTokenFor("broadcast-api-user")

    @Test fun lostPrepareResponseBlocksAccountChangeUntilPreparationIsCancelled() {
        Harness().use { h ->
            h.prepareResponseToLose = snapshot("prepared", "idle")
            assertTrue(h.connection.prepareBroadcast(settings))
            h.awaitState(BroadcastState.FAILED)
            assertEquals(snapshot("prepared", "idle"), h.getPayload)
            assertFalse(canChangeYouTubeAccount(true, h.states.last(), false, false, false))

            h.startPolling()
            h.awaitState(BroadcastState.PREPARED)
            assertFalse(canChangeYouTubeAccount(true, h.states.last(), false, false, false))
            h.stopStatus = 500
            h.connection.stopBroadcast()
            h.awaitState(BroadcastState.PREPARED)
            assertFalse(canChangeYouTubeAccount(true, h.states.last(), false, false, false))

            h.stopStatus = 200
            h.payloads["stop"] = snapshot("idle", "stopped")
            h.connection.stopBroadcast()
            h.awaitState(BroadcastState.IDLE)
            h.getPayload = snapshot("idle", "stopped")
            assertTrue(canChangeYouTubeAccount(true, h.states.last(), false, false, false))
            assertEquals(1, h.requests.count { it.url.encodedPath.endsWith("stream/prepare") })
            assertEquals(2, h.requests.count { it.url.encodedPath.endsWith("stream/stop") })
        }
    }

    @Test fun failedPreparationStaysBlockedWhenPollingFailsAndUnlocksOnServerIdle() {
        Harness().use { h ->
            h.prepareResponseToLose = snapshot("prepared", "idle")
            assertTrue(h.connection.prepareBroadcast(settings))
            h.awaitState(BroadcastState.FAILED)
            h.getStatus = 503
            h.startPolling()
            h.awaitGetCount(1)
            assertEquals(BroadcastState.FAILED, h.states.last())
            assertFalse(canChangeYouTubeAccount(true, h.states.last(), false, false, false))

            h.getPayload = snapshot("idle", "stopped")
            h.getStatus = 200
            h.awaitState(BroadcastState.IDLE)
            assertTrue(canChangeYouTubeAccount(true, h.states.last(), false, false, false))
        }
    }

    @Test fun disconnectedMediaCannotStartOrResumeBroadcastOnTheServer() {
        Harness().use { h ->
            assertTrue(h.connection.prepareBroadcast(settings))
            h.awaitState(BroadcastState.PREPARED)
            h.setMediaConnected(false)
            assertTrue(h.connection.goLive())
            h.awaitState(BroadcastState.PREPARED)
            assertFalse(h.requests.any { it.url.encodedPath.endsWith("stream/golive") })

            h.setMediaConnected(true)
            assertTrue(h.connection.goLive())
            h.awaitState(BroadcastState.LIVE)
            h.connection.pauseBroadcast()
            h.awaitState(BroadcastState.PAUSED)
            h.setMediaConnected(false)
            h.connection.resumeBroadcast()
            assertFalse(h.requests.any { it.url.encodedPath.endsWith("stream/resume") })
        }
    }

    @Test fun successfulStopCancelsScheduledRecovery() {
        Harness().use { h ->
            assertTrue(h.connection.prepareBroadcast(settings))
            h.awaitState(BroadcastState.PREPARED)
            val future = h.installPendingRecovery()
            h.connection.stopBroadcast()
            h.awaitState(BroadcastState.IDLE)
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (!future.isCancelled && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(20)
            }
            assertTrue("종료 후 복구 작업이 취소되어야 합니다", future.isCancelled)
        }
    }

    @Test fun offBroadcastCanGoLiveToggleRecoverFromFailureAndStopWithoutDeletingSession() {
        Harness().use { h ->
            h.patch(false, AnonymizationState.DISABLED)
            assertTrue(h.connection.prepareBroadcast(settings))
            h.awaitState(BroadcastState.PREPARED)
            assertFalse(h.requests.any { it.url.encodedPath.endsWith("golive") })
            assertFalse(h.connection.prepareBroadcast(settings))
            h.connection.goLive()
            h.awaitState(BroadcastState.LIVE)
            h.connection.pauseBroadcast()
            h.awaitState(BroadcastState.PAUSED)
            h.connection.resumeBroadcast()
            h.awaitState(BroadcastState.LIVE)
            h.patch(true, AnonymizationState.ENABLED)
            h.patch(false, AnonymizationState.DISABLED)
            val statesBeforeFailure = h.states.toList()
            h.patchStatus = 503
            val failed = h.patchResult(true)
            assertNull(failed.first)
            assertNotNull(failed.second)
            assertEquals(statesBeforeFailure, h.states.toList())
            h.patchStatus = 200
            h.patch(true, AnonymizationState.ENABLED)
            h.connection.stopBroadcast()
            h.awaitState(BroadcastState.IDLE)
            assertFalse(h.requests.any { it.method == "DELETE" })
            assertTrue(h.connection.prepareBroadcast(settings))
            h.awaitState(BroadcastState.PREPARED)
            h.connection.stopBroadcast()
            h.awaitState(BroadcastState.IDLE)
            assertEquals(2, h.requests.count { it.url.encodedPath.endsWith("stream/prepare") })
            assertEquals(1, h.requests.count { it.url.encodedPath.endsWith("stream/golive") })
            assertEquals(1, h.requests.count { it.url.encodedPath.endsWith("stream/pause") })
            assertEquals(1, h.requests.count { it.url.encodedPath.endsWith("stream/resume") })
            val save = h.requests.first { it.method == "PUT" }
            val payload = JSONObject(h.body(save))
            assertEquals("private", payload.getString("privacy"))
            assertFalse(payload.getBoolean("made_for_kids"))
            for (request in h.requests) {
                assertEquals("Bearer $accessToken", request.header("Authorization"))
                assertEquals("test-owner", request.header("X-Session-Owner-Token"))
                assertTrue(request.url.encodedPath.startsWith("/sessions/test-session/"))
            }
        }
    }

    @Test fun pauseAndResumeFailureKeepTheLastUsableBroadcastState() {
        Harness().use { h ->
            assertTrue(h.connection.prepareBroadcast(settings))
            h.awaitState(BroadcastState.PREPARED)
            h.connection.goLive()
            h.awaitState(BroadcastState.LIVE)

            h.pauseStatus = 500
            h.serverErrorCode = "streaming_reconnect_required"
            h.connection.pauseBroadcast()
            h.awaitState(BroadcastState.LIVE)
            assertEquals(
                BroadcastEvent.ApiFailure(serverErrorGuidance("streaming_reconnect_required")!!),
                h.outcomes.last().second,
            )

            h.pauseStatus = 200
            h.connection.pauseBroadcast()
            h.awaitState(BroadcastState.PAUSED)
            h.resumeStatus = 500
            h.connection.resumeBroadcast()
            h.awaitState(BroadcastState.PAUSED)
            assertEquals(
                BroadcastEvent.ApiFailure(serverErrorGuidance("streaming_reconnect_required")!!),
                h.outcomes.last().second,
            )
        }
    }

    @Test fun settingsSaveEmitsLocalizedSuccessEventAfterReturningToIdle() {
        Harness().use { h ->
            h.connection.saveBroadcastSettings(settings)
            h.awaitState(BroadcastState.IDLE)

            assertEquals(BroadcastEvent.SettingsSaved, h.outcomes.last().second)
        }
    }

    @Test fun unknownPauseAndResumeErrorsRetainTheServerMessage() {
        Harness().use { h ->
            assertTrue(h.connection.prepareBroadcast(settings))
            h.awaitState(BroadcastState.PREPARED)
            h.connection.goLive()
            h.awaitState(BroadcastState.LIVE)

            h.pauseStatus = 500
            h.serverErrorCode = "provider_rate_limited"
            h.serverErrorMessage = "The provider is temporarily rate-limited."
            h.connection.pauseBroadcast()
            h.awaitState(BroadcastState.LIVE)
            assertEquals(
                BroadcastEvent.ServerMessage("The provider is temporarily rate-limited."),
                h.outcomes.last().second,
            )

            h.pauseStatus = 200
            h.connection.pauseBroadcast()
            h.awaitState(BroadcastState.PAUSED)
            h.resumeStatus = 500
            h.serverErrorMessage = "The provider is still rate-limited."
            h.connection.resumeBroadcast()
            h.awaitState(BroadcastState.PAUSED)
            assertEquals(
                BroadcastEvent.ServerMessage("The provider is still rate-limited."),
                h.outcomes.last().second,
            )
        }
    }

    @Test fun failedSettingsSaveDoesNotPrepareAndExplicitRetrySucceeds() {
        Harness().use { h ->
            h.settingsStatus = 500
            assertTrue(h.connection.prepareBroadcast(settings))
            h.awaitState(BroadcastState.FAILED)
            assertEquals(listOf("PUT"), h.requests.map { it.method })
            h.settingsStatus = 200
            assertTrue(h.connection.prepareBroadcast(settings))
            h.awaitState(BroadcastState.PREPARED)
            assertEquals(listOf("broadcast", "broadcast", "prepare"), h.requests.map { it.url.pathSegments.last() })
        }
    }

    @Test fun serverFreeFormErrorIsReducedToTypedBroadcastFailure() {
        Harness().use { h ->
            h.settingsStatus = 500
            h.serverErrorMessage = "internal upstream error: retry-id=abc123"

            assertTrue(h.connection.prepareBroadcast(settings))
            h.awaitState(BroadcastState.FAILED)

            assertEquals(BroadcastEvent.Failure(BroadcastFailure.REQUEST), h.outcomes.last().second)
        }
    }

    @Test fun notReadyGoLiveRetriesAndStopFailureDoesNotReportIdle() {
        Harness().use { h ->
            assertTrue(h.connection.prepareBroadcast(settings))
            h.awaitState(BroadcastState.PREPARED)
            h.goLiveNotReady = true
            h.connection.goLive()
            h.awaitState(BroadcastState.LIVE)
            assertEquals(2, h.requests.count { it.url.encodedPath.endsWith("golive") })
            h.stopStatus = 500
            h.connection.stopBroadcast()
            h.awaitState(BroadcastState.LIVE)
            assertFalse(h.states.contains(BroadcastState.IDLE))
            h.stopStatus = 200
            h.connection.stopBroadcast()
            h.awaitState(BroadcastState.IDLE)
        }
    }

    @Test fun acceptedGoLiveLocksBeforeItsRequestAndFailureCanRetry() {
        Harness().use { h ->
            var acceptedCount = 0
            assertFalse(h.connection.goLive { acceptedCount++ })
            assertEquals(0, acceptedCount)
            assertTrue(h.connection.prepareBroadcast(settings))
            h.awaitState(BroadcastState.PREPARED)

            h.goLiveStatus = 500
            assertTrue(h.connection.goLive {
                acceptedCount++
                h.goLiveAccepted.set(true)
                assertFalse(h.connection.goLive { acceptedCount++ })
            })
            h.awaitState(BroadcastState.PREPARED)
            assertTrue(h.acceptedAtGoLiveRequest)
            assertEquals(1, acceptedCount)

            h.goLiveStatus = 200
            assertTrue(h.connection.goLive { acceptedCount++ })
            h.awaitState(BroadcastState.LIVE)
            assertEquals(2, acceptedCount)
            assertFalse(h.connection.goLive { acceptedCount++ })
            assertEquals(2, acceptedCount)
        }
    }

    @Test fun completedStateCallbackCanStartTheNextBroadcastOperation() {
        lateinit var h: Harness
        h = Harness(Executor { it.run() }) { state ->
            when (state) {
                BroadcastState.PREPARED -> h.connection.goLive()
                BroadcastState.LIVE -> h.connection.stopBroadcast()
                else -> Unit
            }
        }
        h.use {
            assertTrue(h.connection.prepareBroadcast(settings))
            h.awaitState(BroadcastState.IDLE)
            assertEquals(1, h.requests.count { it.url.encodedPath.endsWith("stream/golive") })
            assertEquals(1, h.requests.count { it.url.encodedPath.endsWith("stream/stop") })
        }
    }

    @Test fun closeDeletesOnlyItsSessionOnceAndCompletesEvenWhenServerReturns404() {
        val h = Harness()
        h.deleteStatus = 404
        h.close()
        h.close()
        val deletes = h.requests.filter { it.method == "DELETE" }
        assertEquals(1, deletes.size)
        assertEquals("/sessions/test-session", deletes.single().url.encodedPath)
        assertEquals("test-owner", deletes.single().header("X-Session-Owner-Token"))
        assertFalse(h.connection.prepareBroadcast(settings))
    }

    @Test fun partialResponsesPreserveSessionFieldsAndServerStateWinsOverHttpSuccess() {
        Harness().use { h ->
            h.payloads["prepare"] = snapshot("prepared", "idle")
            h.payloads["golive"] = """{"status":"stopped","broadcast_phase":"idle","targets":[]}"""
            assertTrue(h.connection.prepareBroadcast(settings))
            h.awaitState(BroadcastState.PREPARED)
            val prepared = h.awaitSnapshot { it.remainingTime == BroadcastRemainingTime.Seconds(120) }
            assertEquals("broadcast_limit_30m", prepared.notices!!.single().code)
            h.connection.goLive()
            h.awaitState(BroadcastState.IDLE)
            val stopped = h.awaitSnapshot { it.targets?.isEmpty() == true }
            assertEquals(prepared.notices, stopped.notices)
            assertEquals(prepared.remainingTime, stopped.remainingTime)
        }
    }

    @Test fun delayedGetCannotUndoSuccessfulPauseAndPollingStopsOnClose() {
        val h = Harness()
        try {
            h.payloads["prepare"] = snapshot("prepared", "idle")
            h.payloads["golive"] = snapshot("live", "streaming")
            h.payloads["pause"] = """{"status":"paused","broadcast_phase":"live"}"""
            h.connection.prepareBroadcast(settings)
            h.awaitState(BroadcastState.PREPARED)
            h.connection.goLive()
            h.awaitState(BroadcastState.LIVE)
            h.awaitSnapshot { it.broadcastState() == BroadcastState.LIVE }
            h.getPayload = snapshot("live", "streaming")
            h.blockGet = true
            h.startPolling()
            assertTrue(h.getEntered.await(5, TimeUnit.SECONDS))
            h.connection.pauseBroadcast()
            h.awaitState(BroadcastState.PAUSED)
            h.getPayload = snapshot("live", "paused")
            h.blockGet = false
            h.releaseGet.countDown()
            h.awaitSnapshot { it.broadcastState() == BroadcastState.PAUSED }
            // 다음 GET까지 기다려 이전 live 응답이 일시정지 결과를 되돌리지 않았는지 확인합니다.
            h.awaitGetCount(2)
            assertEquals(BroadcastState.PAUSED, h.states.last())
            assertEquals(BroadcastState.PAUSED, h.snapshots.last().broadcastState())
        } finally {
            h.releaseGet.countDown()
            h.close()
        }
        assertTrue(h.pollingJob!!.isCancelled)
    }

    @Test fun pollingReportsServerStopAndTransientFailureKeepsLastSnapshot() {
        Harness().use { h ->
            h.payloads["prepare"] = snapshot("prepared", "idle")
            h.payloads["golive"] = snapshot("live", "streaming")
            h.connection.prepareBroadcast(settings)
            h.awaitState(BroadcastState.PREPARED)
            h.connection.goLive()
            h.awaitState(BroadcastState.LIVE)
            h.awaitSnapshot { it.broadcastState() == BroadcastState.LIVE }
            h.getStatus = 503
            h.startPolling()
            h.awaitGetCount(1)
            assertEquals(BroadcastState.LIVE, h.snapshots.last().broadcastState())
            h.getPayload = snapshot("idle", "stopped")
            h.getStatus = 200
            h.awaitState(BroadcastState.IDLE)
            assertTrue(h.awaitSnapshot { it.broadcastState() == BroadcastState.IDLE }.visibleTargets.isEmpty())
        }
    }

    @Test fun skippedQueuedPollCallbackIsPublishedAgainOnNextPoll() {
        val queued = LinkedBlockingQueue<Runnable>()
        val holdCallbacks = AtomicBoolean(false)
        val executor = Executor { callback ->
            if (holdCallbacks.get()) queued.add(callback) else callback.run()
        }
        Harness(broadcastCallbackExecutor = executor).use { h ->
            h.payloads["prepare"] = snapshot("prepared", "idle")
            h.payloads["golive"] = snapshot("live", "streaming")
            h.connection.prepareBroadcast(settings)
            h.awaitState(BroadcastState.PREPARED)
            h.connection.goLive()
            h.awaitState(BroadcastState.LIVE)
            holdCallbacks.set(true)
            h.getPayload = snapshot("idle", "stopped")
            h.startPolling()
            val oldSnapshotCallback = checkNotNull(queued.poll(5, TimeUnit.SECONDS))
            val oldStateCallback = checkNotNull(queued.poll(5, TimeUnit.SECONDS))
            h.patch(false, AnonymizationState.DISABLED)
            oldSnapshotCallback.run()
            oldStateCallback.run()
            assertEquals(BroadcastState.LIVE, h.states.last())
            holdCallbacks.set(false)
            while (true) (queued.poll() ?: break).run()
            h.awaitState(BroadcastState.IDLE)
        }
    }

    @Test fun unauthorizedPollRefreshesOnceAndPublishesRetriedResponse() {
        Harness().use { h ->
            h.getReplies.add(401 to "{}")
            h.getReplies.add(200 to snapshot("prepared", "idle"))
            h.startPolling()
            h.awaitState(BroadcastState.PREPARED)
            assertEquals(1, h.refreshCount.get())
            val requests = h.requests.filter { it.method == "GET" }
            assertEquals(2, requests.size)
            assertEquals("Bearer $accessToken", requests[0].header("Authorization"))
            assertEquals("Bearer $accessToken.refreshed", requests[1].header("Authorization"))
            assertEquals("test-owner", requests[1].header("X-Session-Owner-Token"))
        }
    }

    @Test fun missingSessionOrFailedRefreshEndsPollingThroughExistingFailurePath() {
        for (status in listOf(404, 401)) {
            Harness().use { h ->
                h.getReplies.add(status to "{}")
                h.refreshFails = status == 401
                h.startPolling()
                assertEquals(ConnectionFailure.DISCONNECTED, h.connectionFailures.poll(5, TimeUnit.SECONDS))
                assertEquals(if (status == 401) 1 else 0, h.refreshCount.get())
                if (status == 401) assertEquals(ServerErrorAction.LOGIN, h.serverGuidance.poll(5, TimeUnit.SECONDS)?.action)
                else assertTrue(h.serverGuidance.isEmpty())
            }
        }
    }

    @Test fun unknownServerPhaseDoesNotClaimSuccessfulPreparation() {
        Harness().use { h ->
            h.payloads["prepare"] = snapshot("future_phase", "future_status")
            h.connection.prepareBroadcast(settings)
            h.awaitSnapshot { it.targets?.singleOrNull()?.broadcastPhase == "future_phase" }
            assertFalse(h.states.contains(BroadcastState.PREPARED))
            assertFalse(h.connection.goLive())
        }
    }

    @Test fun concurrentLiveIsNotRetriedUntilExplicitConsentAndOnlyThenSendsTheFlag() {
        Harness().use { h ->
            h.prepareStatus = 409
            h.serverErrorCode = "channel_already_live"
            h.connection.prepareBroadcast(settings)
            h.awaitState(BroadcastState.FAILED)
            assertEquals(ServerErrorAction.CONFIRM_CONCURRENT,
                (h.outcomes.last().second as BroadcastEvent.ApiFailure).guidance.action)
            assertEquals(1, h.requests.count { it.url.encodedPath.endsWith("prepare") })
            val original = h.requests.single { it.url.encodedPath.endsWith("prepare") }
            assertFalse(JSONObject(h.body(original)).has("allow_concurrent"))
            h.prepareStatus = 200
            assertTrue(h.connection.prepareBroadcast(settings, allowConcurrent = true))
            h.awaitState(BroadcastState.PREPARED)
            val accepted = h.requests.last { it.url.encodedPath.endsWith("prepare") }
            assertTrue(JSONObject(h.body(accepted)).getBoolean("allow_concurrent"))
            assertEquals(2, h.requests.count { it.url.encodedPath.endsWith("prepare") })
        }
    }

    @Test fun unauthorizedMutationRefreshesOnceAndSecond401StillProducesLoginGuidance() {
        for (retryStatus in listOf(200, 401)) Harness().use { h ->
            h.mutationReplies.add(401 to "{}")
            h.mutationReplies.add(retryStatus to "{}")
            h.connection.saveBroadcastSettings(settings)
            h.awaitState(if (retryStatus == 200) BroadcastState.IDLE else BroadcastState.FAILED)
            assertEquals(1, h.refreshCount.get())
            val requests = h.requests.filter { it.method == "PUT" }
            assertEquals(2, requests.size)
            assertEquals("Bearer $accessToken.refreshed", requests.last().header("Authorization"))
            assertEquals("test-owner", requests.last().header("X-Session-Owner-Token"))
            if (retryStatus == 401) assertEquals(ServerErrorAction.LOGIN,
                (h.outcomes.last().second as BroadcastEvent.ApiFailure).guidance.action)
        }
    }

    @Test fun busyErrorsWaitForPollingWithoutModalOrAutomaticMutationRetry() {
        Harness().use { h ->
            h.prepareStatus = 409
            h.serverErrorCode = "broadcast_busy"
            h.connection.prepareBroadcast(settings)
            h.awaitState(BroadcastState.PREPARING)
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (h.outcomes.size < 3 && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
            assertEquals(3, h.outcomes.size)
            assertNull(h.outcomes.last().second)
            assertEquals(1, h.requests.count { it.url.encodedPath.endsWith("prepare") })
            h.getPayload = snapshot("prepared", "idle")
            h.startPolling()
            h.awaitState(BroadcastState.PREPARED)
        }
    }

    @Test fun staleNegotiationDoesNotFailTheCurrentConnection() {
        Harness().use { h ->
            h.connection.prepareBroadcast(settings)
            h.awaitState(BroadcastState.PREPARED)
            val before = h.states.toList()
            WebRtcConnection::class.java.getDeclaredMethod("handleServerMessage", String::class.java)
                .apply { isAccessible = true }.invoke(h.connection,
                    """{"type":"error","error":{"code":"stale_negotiation","message":"old offer"}}""")
            assertEquals(before, h.states.toList())
            assertTrue(h.connectionFailures.isEmpty())
            assertTrue(h.connection.goLive())
            h.awaitState(BroadcastState.LIVE)
        }
    }

    private fun snapshot(phase: String, status: String) = """{"session_id":"test-session","provider":"youtube",
        "targets":[{"provider":"youtube","stream":{"status":"$status","broadcast_phase":"$phase"}}],
        "notices":[{"code":"broadcast_limit_30m","at":"2026-09-30T00:00:00Z"}],
        "broadcast_remaining_seconds":120}"""

    private class Harness(
        private val broadcastCallbackExecutor: Executor? = null,
        private val onBroadcastState: (BroadcastState) -> Unit = {},
    ) : AutoCloseable {
        val snapshots = CopyOnWriteArrayList<SessionSnapshot>()
        private val snapshotEvents = LinkedBlockingQueue<SessionSnapshot>()
        val payloads = java.util.concurrent.ConcurrentHashMap<String, String>()
        val getReplies = LinkedBlockingQueue<Pair<Int, String>>()
        val mutationReplies = LinkedBlockingQueue<Pair<Int, String>>()
        val connectionFailures = LinkedBlockingQueue<ConnectionFailure>()
        val serverGuidance = java.util.concurrent.LinkedBlockingQueue<com.framework.innolive.ui.text.ServerErrorGuidance>()
        val refreshCount = java.util.concurrent.atomic.AtomicInteger()
        @Volatile var refreshFails = false
        @Volatile var getPayload = "{}"
        @Volatile var getStatus = 200
        @Volatile var blockGet = false
        val getEntered = CountDownLatch(1)
        val releaseGet = CountDownLatch(1)
        var pollingJob: kotlinx.coroutines.Job? = null
        val requests = CopyOnWriteArrayList<Request>()
        val states = CopyOnWriteArrayList<BroadcastState>()
        val outcomes = CopyOnWriteArrayList<Pair<BroadcastState, BroadcastEvent?>>()
        private val events = LinkedBlockingQueue<BroadcastState>()
        @Volatile var patchStatus = 200
        @Volatile var settingsStatus = 200
        @Volatile var prepareStatus = 200
        @Volatile var prepareResponseToLose: String? = null
        @Volatile var stopStatus = 200
        @Volatile var pauseStatus = 200
        @Volatile var resumeStatus = 200
        @Volatile var deleteStatus = 204
        @Volatile var goLiveNotReady = false
        @Volatile var goLiveStatus = 200
        val goLiveAccepted = AtomicBoolean(false)
        @Volatile var acceptedAtGoLiveRequest = false
        @Volatile var serverErrorMessage: String? = null
        @Volatile var serverErrorCode: String? = null
        val connection = WebRtcConnection(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            serverUrl = "https://example.test",
            accessToken = accessTokenFor("broadcast-api-user"),
            refreshAccessToken = {
                refreshCount.incrementAndGet()
                if (refreshFails) throw java.io.IOException("fixture refresh failed")
                accessTokenFor("broadcast-api-user") + ".refreshed"
            },
            initialAnonymizationEnabled = false,
            preferredAudioInput = null,
            onStateChanged = { state, failure ->
                if (state == WebRtcConnectionState.FAILED && failure != null) connectionFailures.add(failure)
            }, onRemoteTrackChanged = {},
            onLocalMediaReady = { _, _ -> }, onLocalMediaCleared = {},
            onBroadcastStateChanged = { state, event ->
                states.add(state)
                outcomes.add(state to event)
                events.add(state)
                onBroadcastState(state)
            },
            onAnonymizationStateConfirmed = {},
            onSessionSnapshotChanged = { snapshot ->
                snapshots.add(snapshot)
                snapshotEvents.add(snapshot)
            },
            broadcastCallbackExecutor = broadcastCallbackExecutor,
            onServerError = { serverGuidance.add(it) },
        )

        init {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests.add(request)
                var payload = "{}"
                val mutationReply = if (request.method != "GET" && request.method != "DELETE") mutationReplies.poll() else null
                val status = when {
                    mutationReply != null -> { payload = mutationReply.second; mutationReply.first }
                    request.method == "GET" -> {
                        val reply = getReplies.poll() ?: (getStatus to getPayload)
                        payload = reply.second
                        getEntered.countDown()
                        if (blockGet) check(releaseGet.await(5, TimeUnit.SECONDS))
                        reply.first
                    }
                    request.method == "DELETE" -> deleteStatus
                    request.method == "PUT" -> settingsStatus
                    request.method == "PATCH" -> {
                        val enabled = JSONObject(body(request)).getBoolean("enabled")
                        payload = """{"session_id":"test-session","media":{"anonymization_enabled":$enabled}}"""
                        patchStatus
                    }
                    request.url.encodedPath.endsWith("golive") && goLiveNotReady -> {
                        goLiveNotReady = false
                        payload = """{"error":{"code":"broadcast_not_ready","message":"not ready"}}"""
                        409
                    }
                    request.url.encodedPath.endsWith("golive") -> {
                        acceptedAtGoLiveRequest = goLiveAccepted.get()
                        goLiveStatus
                    }
                    request.url.encodedPath.endsWith("prepare") -> {
                        prepareResponseToLose?.let { appliedServerState ->
                            getPayload = appliedServerState
                            throw java.io.IOException("Prepare response lost after server applied it")
                        }
                        prepareStatus
                    }
                    request.url.encodedPath.endsWith("stop") -> stopStatus
                    request.url.encodedPath.endsWith("pause") -> pauseStatus
                    request.url.encodedPath.endsWith("resume") -> resumeStatus
                    else -> 200
                }
                if (status < 400 && request.method != "GET") {
                    payload = payloads[request.url.pathSegments.last()] ?: payload
                }
                if (status >= 400 && payload == "{}" &&
                    (serverErrorCode != null || serverErrorMessage != null)
                ) {
                    payload = JSONObject()
                        .put(
                            "error",
                            JSONObject()
                                .put("code", serverErrorCode ?: "unexpected_failure")
                                .put("message", serverErrorMessage.orEmpty()),
                        )
                        .toString()
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(status).message("fixture").body(payload.toResponseBody()).build()
            }.build()
            // 테스트에서만 연결 완료 세션과 HTTP 응답을 주입한다. 실제 DNS나 외부 계정은 사용하지 않는다.
            field("httpClient", client)
            field("session", CreatedSession("test-session", "test-owner", AnonymizationState.DISABLED))
            field("peerConnectionConnected", true)
            field("audioInputVerified", true)
        }

        private fun field(name: String, value: Any) {
            WebRtcConnection::class.java.getDeclaredField(name).apply { isAccessible = true; set(connection, value) }
        }

        fun startPolling() {
            WebRtcConnection::class.java.getDeclaredMethod("startSessionPolling", CreatedSession::class.java)
                .apply { isAccessible = true }.invoke(connection,
                    CreatedSession("test-session", "test-owner", AnonymizationState.DISABLED))
            pollingJob = WebRtcConnection::class.java.getDeclaredField("sessionStatusJob")
                .apply { isAccessible = true }.get(connection) as kotlinx.coroutines.Job
        }

        fun awaitSnapshot(matches: (SessionSnapshot) -> Boolean): SessionSnapshot {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (true) {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "세션 상태 대기 시간 초과" }
                val snapshot = snapshotEvents.poll(remaining, TimeUnit.NANOSECONDS)
                    ?: error("세션 상태 콜백 없음")
                if (matches(snapshot)) return snapshot
            }
        }

        fun awaitGetCount(count: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (requests.count { it.method == "GET" } < count && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue(requests.count { it.method == "GET" } >= count)
        }

        fun setMediaConnected(connected: Boolean) = field("peerConnectionConnected", connected)

        fun installPendingRecovery(): ScheduledFuture<*> {
            val window = WebRtcRecoveryWindow(WebRtcRecoveryPolicy())
            window.begin(SystemClock.elapsedRealtime())
            field("recoveryWindow", window)
            val timer = WebRtcConnection::class.java.getDeclaredField("timerExecutor")
                .apply { isAccessible = true }.get(connection) as ScheduledExecutorService
            return timer.schedule({}, 30, TimeUnit.SECONDS).also { field("recoveryTask", it) }
        }

        fun body(request: Request): String = Buffer().also { request.body?.writeTo(it) }.readUtf8()

        fun awaitState(expected: BroadcastState) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (true) {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "상태 대기 시간 초과: $expected, 실제 $states" }
                val next = events.poll(remaining, TimeUnit.NANOSECONDS)
                assertNotNull("상태 콜백 없음: $expected", next)
                if (next == expected) return
                assertNotEquals("예상하지 않은 실패: $expected", BroadcastState.FAILED, next)
            }
        }

        fun patchResult(enabled: Boolean): Pair<AnonymizationState?, AnonymizationFailure?> {
            val completed = CountDownLatch(1)
            var result: Pair<AnonymizationState?, AnonymizationFailure?>? = null
            connection.setAnonymizationEnabled(enabled) { state, error ->
                result = state to error
                completed.countDown()
            }
            assertTrue("PATCH 응답 대기", completed.await(10, TimeUnit.SECONDS))
            return checkNotNull(result)
        }

        fun patch(enabled: Boolean, expected: AnonymizationState) {
            val result = patchResult(enabled)
            assertEquals(expected, result.first)
            assertNull(result.second)
        }

        override fun close() {
            val closed = CountDownLatch(1)
            connection.close { closed.countDown() }
            assertTrue("연결 정리 대기", closed.await(10, TimeUnit.SECONDS))
        }
    }

    private companion object {
        fun accessTokenFor(user: String): String {
            val payload = Base64.encodeToString(
                "{\"sub\":\"$user\"}".toByteArray(),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
            return "header.$payload.signature"
        }
    }
}
