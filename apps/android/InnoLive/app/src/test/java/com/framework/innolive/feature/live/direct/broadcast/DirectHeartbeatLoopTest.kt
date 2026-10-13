package com.framework.innolive.feature.live.direct.broadcast

import com.framework.innolive.feature.live.AnonymizationState
import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.CreatedSession
import com.framework.innolive.feature.live.SessionNotice
import com.framework.innolive.feature.live.direct.DeviceThermal
import com.framework.innolive.feature.live.direct.DirectHeartbeat
import com.framework.innolive.feature.live.direct.HeartbeatAnonymization
import com.framework.innolive.feature.live.direct.HeartbeatDevice
import com.framework.innolive.feature.live.direct.HeartbeatResult
import com.framework.innolive.feature.live.direct.HeartbeatStopReason
import com.framework.innolive.feature.live.direct.HeartbeatTarget
import com.framework.innolive.feature.live.direct.HeartbeatVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DirectHeartbeatLoopTest {
    private val session = CreatedSession("session-1", "owner-token", AnonymizationState.ENABLED)
    private val timer = FakeTimer()
    private val listener = RecordingListener()
    private val sentAt = mutableListOf<Long>()
    private val sent = mutableListOf<DirectHeartbeat>()
    private var inputs: HeartbeatInputs? = liveInputs()
    private var result: HeartbeatResult = accepted()
    private var sendDurationMillis = 0L

    private val loop = DirectHeartbeatLoop(
        send = { _, heartbeat ->
            sentAt += timer.now
            sent += heartbeat
            timer.now += sendDurationMillis
            result
        },
        inputs = { inputs },
        listener = listener,
        device = { HeartbeatDevice(thermal = DeviceThermal.FAIR, batteryPct = 80, charging = false) },
        model = " Pixel 8 ",
        timer = timer,
        wallClock = { Instant.parse("2026-10-13T00:00:00Z") },
    )

    @Test fun sendsImmediatelyThenEvery15Seconds() {
        loop.start(session)
        timer.advanceTo(0)
        assertEquals(listOf(0L), sentAt)

        timer.advanceTo(44_999)
        assertEquals(listOf(0L, 15_000L, 30_000L), sentAt)
        timer.advanceTo(45_000)
        assertEquals(listOf(0L, 15_000L, 30_000L, 45_000L), sentAt)
    }

    @Test fun assemblesHeartbeatFromInputsDeviceAndModel() {
        loop.start(session)
        timer.advanceTo(0)

        val heartbeat = sent.single()
        assertEquals(liveInputs().targets, heartbeat.targets)
        assertEquals(liveInputs().video, heartbeat.video)
        assertEquals("Pixel 8", heartbeat.model)
        assertEquals(DeviceThermal.FAIR, heartbeat.device?.thermal)
        assertEquals(Instant.parse("2026-10-13T00:00:00Z"), heartbeat.sentAt)
    }

    @Test fun slowSendSkipsMissedBeatsWithoutOverlapOrCatchUp() {
        sendDurationMillis = 40_000
        loop.start(session)
        timer.advanceTo(0)
        assertEquals(1, timer.pending)

        sendDurationMillis = 0
        timer.advanceTo(100_000)
        // 0초 요청이 40초에 끝났으므로 15·30초는 건너뛰고 45초부터 보냅니다.
        assertEquals(listOf(0L, 45_000L, 60_000L, 75_000L, 90_000L), sentAt)
        assertEquals(1, timer.pending)
    }

    @Test fun skipsSendingWhileInputsAreMissingButKeepsRunning() {
        inputs = null
        loop.start(session)
        timer.advanceTo(15_000)
        assertTrue(sentAt.isEmpty())

        inputs = liveInputs()
        timer.advanceTo(30_000)
        assertEquals(listOf(30_000L), sentAt)
    }

    @Test fun emptyTargetsAreSkipped() {
        inputs = liveInputs().copy(targets = emptyList())
        loop.start(session)
        timer.advanceTo(0)
        assertTrue(sentAt.isEmpty())
        assertEquals(1, timer.pending)
    }

    @Test fun stopCancelsPendingBeat() {
        loop.start(session)
        timer.advanceTo(0)
        loop.stop()
        timer.advanceTo(60_000)
        assertEquals(listOf(0L), sentAt)
        assertEquals(0, timer.pending)
    }

    @Test fun stopAllStopsLoopAndNotifiesListener() {
        result = HeartbeatResult.StopAll(HeartbeatStopReason.SESSION_GONE)
        loop.start(session)
        timer.advanceTo(60_000)

        assertEquals(listOf(0L), sentAt)
        assertEquals(listOf(HeartbeatStopReason.SESSION_GONE), listener.stopAll)
        assertEquals(0, timer.pending)
    }

    @Test fun restartAfterStopAllRunsNewLoop() {
        result = HeartbeatResult.StopAll(HeartbeatStopReason.NOT_APPLICABLE)
        loop.start(session)
        timer.advanceTo(0)

        result = accepted()
        loop.start(session)
        timer.advanceTo(15_000)
        assertEquals(listOf(0L, 0L, 15_000L), sentAt)
    }

    @Test fun acceptedPropagatesStopTargetsAndNotices() {
        val notice = SessionNotice("broadcast_limit_reached", "2026-10-13T00:00:00Z")
        result = accepted(listOf(BroadcastProvider.CHZZK), listOf(notice), 120)
        loop.start(session)
        timer.advanceTo(0)

        assertEquals(listOf(listOf(BroadcastProvider.CHZZK)), listener.stopTargets)
        assertEquals(listOf(listOf(notice) to 120L), listener.notices)
        assertEquals(1, timer.pending)
    }

    @Test fun acceptedWithoutStopTargetsOnlyReportsNotices() {
        loop.start(session)
        timer.advanceTo(0)

        assertTrue(listener.stopTargets.isEmpty())
        assertEquals(listOf(emptyList<SessionNotice>() to null), listener.notices)
    }

    @Test fun invalidReportsFieldAndKeepsRunning() {
        result = HeartbeatResult.Invalid("video.fps")
        loop.start(session)
        timer.advanceTo(15_000)

        assertEquals(listOf<String?>("video.fps", "video.fps"), listener.invalid)
        assertEquals(2, sentAt.size)
    }

    @Test fun unauthorizedAndRetryLaterKeepRunningWithoutCallbacks() {
        result = HeartbeatResult.Unauthorized
        loop.start(session)
        timer.advanceTo(0)
        result = HeartbeatResult.RetryLater
        timer.advanceTo(15_000)

        assertEquals(2, sentAt.size)
        assertTrue(listener.stopAll.isEmpty() && listener.invalid.isEmpty() && listener.notices.isEmpty())
        assertEquals(1, timer.pending)
    }

    @Test fun failingInputSourceKeepsLoopRunning() {
        val throwing = DirectHeartbeatLoop(
            send = { _, _ -> sentAt += timer.now; accepted() },
            inputs = { error("broken") },
            listener = listener,
            device = { null },
            model = "Pixel",
            timer = timer,
        )
        throwing.start(session)
        timer.advanceTo(15_000)
        assertTrue(sentAt.isEmpty())
        assertEquals(1, timer.pending)
    }

    @Test fun responseAfterStopIsDropped() {
        lateinit var target: DirectHeartbeatLoop
        target = DirectHeartbeatLoop(
            send = { _, _ ->
                target.stop()
                HeartbeatResult.StopAll(HeartbeatStopReason.OWNERSHIP_LOST)
            },
            inputs = { liveInputs() },
            listener = listener,
            device = { null },
            model = "Pixel",
            timer = timer,
        )
        target.start(session)
        timer.advanceTo(30_000)
        assertTrue(listener.stopAll.isEmpty())
        assertEquals(0, timer.pending)
    }

    @Test fun realExecutorNeverOverlapsSends() {
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val calls = CountDownLatch(4)
        val realTimer = ExecutorHeartbeatTimer()
        val real = DirectHeartbeatLoop(
            send = { _, _ ->
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
                Thread.sleep(30)
                inFlight.decrementAndGet()
                calls.countDown()
                HeartbeatResult.RetryLater
            },
            inputs = { liveInputs() },
            listener = listener,
            device = { null },
            model = "Pixel",
            timer = realTimer,
            intervalMillis = 10,
        )
        try {
            real.start(session)
            assertTrue(calls.await(5, TimeUnit.SECONDS))
        } finally {
            real.close()
        }
        assertEquals(1, maxInFlight.get())
    }

    @Test fun nextDueAtSkipsMissedSlots() {
        assertEquals(15_000L, nextDueAt(0, 1_000, 15_000))
        assertEquals(45_000L, nextDueAt(0, 40_000, 15_000))
        assertEquals(30_000L, nextDueAt(0, 15_000, 15_000))
    }

    @Test fun buildReturnsNullWithoutTargetsAndFallsBackForBlankModel() {
        val at = Instant.EPOCH
        assertNull(buildDirectHeartbeat(liveInputs().copy(targets = emptyList()), null, "Pixel", at))
        assertEquals("unknown", buildDirectHeartbeat(liveInputs(), null, "  ", at)?.model)
    }

    private fun liveInputs() = HeartbeatInputs(
        targets = listOf(HeartbeatTarget(BroadcastProvider.YOUTUBE, connected = true, bitrateKbps = 4500, reconnects = 0)),
        video = HeartbeatVideo(fps = 30.0, droppedFrames = 2, width = 1280, height = 720),
        anonymization = HeartbeatAnonymization(failedFrames = 0, enabled = true),
    )

    private fun accepted(
        stopTargets: List<BroadcastProvider> = emptyList(),
        notices: List<SessionNotice> = emptyList(),
        remaining: Long? = null,
    ) = HeartbeatResult.Accepted(stopTargets, notices, remaining)

    private class FakeTimer : HeartbeatTimer {
        var now = 0L
        private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val pending: Int get() = tasks.size

        override fun nowMillis(): Long = now

        override fun schedule(delayMillis: Long, task: () -> Unit): () -> Unit {
            val entry = (now + delayMillis) to task
            tasks += entry
            return { tasks.remove(entry) }
        }

        override fun close() = tasks.clear()

        /** 예정 시각이 [time] 이하인 작업을 순서대로 실행합니다. 실행 중 시계가 더 나아갈 수 있습니다. */
        fun advanceTo(time: Long) {
            while (true) {
                val next = tasks.minByOrNull { it.first }?.takeIf { it.first <= time } ?: break
                tasks.remove(next)
                now = maxOf(now, next.first)
                next.second()
            }
            now = maxOf(now, time)
        }
    }

    private class RecordingListener : HeartbeatListener {
        val stopTargets = mutableListOf<List<BroadcastProvider>>()
        val stopAll = mutableListOf<HeartbeatStopReason>()
        val notices = mutableListOf<Pair<List<SessionNotice>, Long?>>()
        val invalid = mutableListOf<String?>()

        override fun onStopTargets(providers: List<BroadcastProvider>) {
            stopTargets += providers
        }

        override fun onStopAll(reason: HeartbeatStopReason) {
            stopAll += reason
        }

        override fun onNotices(notices: List<SessionNotice>, broadcastRemainingSeconds: Long?) {
            this.notices += notices to broadcastRemainingSeconds
        }

        override fun onInvalid(field: String?) {
            invalid += field
        }
    }
}
