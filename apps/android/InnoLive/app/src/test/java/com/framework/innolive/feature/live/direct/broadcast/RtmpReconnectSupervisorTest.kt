package com.framework.innolive.feature.live.direct.broadcast

import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.BroadcastProvider.CHZZK
import com.framework.innolive.feature.live.BroadcastProvider.YOUTUBE
import com.framework.innolive.feature.live.direct.publish.RtmpFailure
import com.framework.innolive.feature.live.direct.publish.RtmpTargetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RtmpReconnectSupervisorTest {
    private val scheduler = FakeScheduler()
    private val network = FakeNetwork()
    private val actions = RecordingActions(scheduler)
    private val supervisor = RtmpReconnectSupervisor(actions, network, scheduler, scheduler::now)

    @Test
    fun chzzkGivesUpTenSecondsAfterDropEvenWhileRetrying() {
        actions.onReconnect = { provider ->
            // 매번 0.5초 뒤 네트워크 실패로 끝납니다.
            scheduler.schedule(500) { supervisor.onTargetStateChanged(provider, RtmpTargetState.FAILED, RtmpFailure.NETWORK) }
        }
        publish(CHZZK)
        drop(CHZZK)

        scheduler.advanceBy(20_000)

        assertTrue(actions.reconnects.size >= 5)
        assertTrue(actions.reconnects.all { it.second < 10_000 })
        assertEquals(listOf(Triple(CHZZK, RtmpFailure.NETWORK, 10_000L)), actions.giveUps)
    }

    @Test
    fun chzzkGivesUpAtDeadlineWhileAttemptHangs() {
        publish(CHZZK)
        drop(CHZZK)

        scheduler.advanceBy(10_000)

        assertEquals(listOf(CHZZK to 1_000L), actions.reconnects)
        assertEquals(listOf(Triple(CHZZK, RtmpFailure.NETWORK, 10_000L)), actions.giveUps)
    }

    @Test
    fun youtubeRetriesThreeTimesEveryTwoSecondsThenGivesUp() {
        actions.onReconnect = { provider ->
            supervisor.onTargetStateChanged(provider, RtmpTargetState.FAILED, RtmpFailure.NETWORK)
        }
        publish(YOUTUBE)
        drop(YOUTUBE)

        scheduler.advanceBy(30_000)

        assertEquals(listOf(YOUTUBE to 2_000L, YOUTUBE to 4_000L, YOUTUBE to 6_000L), actions.reconnects)
        assertEquals(listOf(Triple(YOUTUBE, RtmpFailure.NETWORK, 6_000L)), actions.giveUps)
    }

    @Test
    fun authOrRejectedGivesUpWithoutRetry() {
        publish(YOUTUBE)
        supervisor.onTargetStateChanged(YOUTUBE, RtmpTargetState.FAILED, RtmpFailure.REJECTED)
        publish(CHZZK)
        drop(CHZZK)
        scheduler.advanceBy(1_000)
        supervisor.onTargetStateChanged(CHZZK, RtmpTargetState.FAILED, RtmpFailure.AUTH)

        scheduler.advanceBy(30_000)

        assertEquals(listOf(CHZZK to 1_000L), actions.reconnects)
        assertEquals(
            listOf(Triple(YOUTUBE, RtmpFailure.REJECTED, 0L), Triple(CHZZK, RtmpFailure.AUTH, 1_000L)),
            actions.giveUps,
        )
    }

    @Test
    fun waitingForNetworkDoesNotConsumeAttempts() {
        actions.onReconnect = { provider ->
            supervisor.onTargetStateChanged(provider, RtmpTargetState.FAILED, RtmpFailure.NETWORK)
        }
        network.available = false
        publish(YOUTUBE)
        drop(YOUTUBE)

        scheduler.advanceBy(20_000)
        assertTrue(actions.reconnects.isEmpty())

        network.restore()
        scheduler.advanceBy(10_000)

        assertEquals(listOf(YOUTUBE to 20_000L, YOUTUBE to 22_000L, YOUTUBE to 24_000L), actions.reconnects)
        assertEquals(listOf(Triple(YOUTUBE, RtmpFailure.NETWORK, 24_000L)), actions.giveUps)
    }

    @Test
    fun chzzkKeepsDeadlineWhileWaitingForNetwork() {
        network.available = false
        publish(CHZZK)
        drop(CHZZK)

        scheduler.advanceBy(15_000)

        assertTrue(actions.reconnects.isEmpty())
        assertEquals(listOf(Triple(CHZZK, RtmpFailure.NETWORK, 10_000L)), actions.giveUps)
    }

    @Test
    fun publishingAgainResetsBudget() {
        var failNext = 2
        actions.onReconnect = { provider ->
            val state = if (failNext-- > 0) RtmpTargetState.FAILED else RtmpTargetState.PUBLISHING
            supervisor.onTargetStateChanged(provider, state, RtmpFailure.NETWORK.takeIf { state == RtmpTargetState.FAILED })
        }
        publish(YOUTUBE)
        drop(YOUTUBE)
        scheduler.advanceBy(10_000)
        assertEquals(3, actions.reconnects.size)
        assertTrue(actions.giveUps.isEmpty())

        actions.reconnects.clear()
        actions.onReconnect = { provider ->
            supervisor.onTargetStateChanged(provider, RtmpTargetState.FAILED, RtmpFailure.NETWORK)
        }
        drop(YOUTUBE)
        scheduler.advanceBy(30_000)

        assertEquals(3, actions.reconnects.size)
        assertEquals(1, actions.giveUps.size)
    }

    @Test
    fun removedTargetIsNotReconnected() {
        publish(CHZZK)
        drop(CHZZK)
        supervisor.onTargetRemoved(CHZZK)
        supervisor.onTargetStateChanged(CHZZK, RtmpTargetState.DISCONNECTED, null)

        scheduler.advanceBy(30_000)

        assertTrue(actions.reconnects.isEmpty())
        assertTrue(actions.giveUps.isEmpty())
    }

    @Test
    fun closeCancelsPendingWorkAndReleasesNetwork() {
        publish(YOUTUBE)
        drop(YOUTUBE)
        supervisor.close()
        supervisor.onTargetStateChanged(CHZZK, RtmpTargetState.PUBLISHING, null)
        supervisor.onTargetStateChanged(CHZZK, RtmpTargetState.DISCONNECTED, null)
        network.restore()

        scheduler.advanceBy(60_000)

        assertTrue(actions.reconnects.isEmpty())
        assertTrue(actions.giveUps.isEmpty())
        assertTrue(network.closed)
    }

    @Test
    fun firstConnectFailureIsLeftToStateMachine() {
        supervisor.onTargetStateChanged(YOUTUBE, RtmpTargetState.CONNECTING, null)
        supervisor.onTargetStateChanged(YOUTUBE, RtmpTargetState.FAILED, RtmpFailure.NETWORK)
        supervisor.onTargetStateChanged(CHZZK, RtmpTargetState.FAILED, RtmpFailure.AUTH)

        scheduler.advanceBy(30_000)

        assertTrue(actions.reconnects.isEmpty())
        assertTrue(actions.giveUps.isEmpty())
    }

    @Test
    fun reconnectExceptionCountsAsFailedAttempt() {
        actions.onReconnect = { throw IllegalStateException("closed") }
        publish(YOUTUBE)
        drop(YOUTUBE)

        scheduler.advanceBy(30_000)

        assertEquals(3, actions.reconnects.size)
        assertEquals(1, actions.giveUps.size)
    }

    private fun publish(provider: BroadcastProvider) =
        supervisor.onTargetStateChanged(provider, RtmpTargetState.PUBLISHING, null)

    private fun drop(provider: BroadcastProvider) =
        supervisor.onTargetStateChanged(provider, RtmpTargetState.DISCONNECTED, null)

    private class RecordingActions(private val scheduler: FakeScheduler) : ReconnectActions {
        val reconnects = mutableListOf<Pair<BroadcastProvider, Long>>()
        val giveUps = mutableListOf<Triple<BroadcastProvider, RtmpFailure?, Long>>()
        var onReconnect: (BroadcastProvider) -> Unit = {}

        override fun reconnect(provider: BroadcastProvider) {
            reconnects += provider to scheduler.now()
            onReconnect(provider)
        }

        override fun giveUp(provider: BroadcastProvider, failure: RtmpFailure?) {
            giveUps += Triple(provider, failure, scheduler.now())
        }
    }

    private class FakeNetwork : NetworkAvailability {
        var available = true
        var closed = false
        private var listener: (() -> Unit)? = null

        override fun isAvailable(): Boolean = available

        override fun start(onAvailable: () -> Unit) {
            listener = onAvailable
        }

        fun restore() {
            available = true
            listener?.invoke()
        }

        override fun close() {
            closed = true
        }
    }

    /** 가상 시간 실행기입니다. [execute]는 바로 실행해 실제 실행기의 순서를 흉내 냅니다. */
    private class FakeScheduler : ReconnectScheduler {
        private class Task(val at: Long, val order: Long, val run: () -> Unit) {
            var cancelled = false
        }

        private var time = 0L
        private var order = 0L
        private val tasks = mutableListOf<Task>()

        fun now(): Long = time

        override fun execute(task: () -> Unit) = task()

        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledReconnectTask {
            val scheduled = Task(time + delayMillis, order++, task)
            tasks += scheduled
            return ScheduledReconnectTask { scheduled.cancelled = true }
        }

        fun advanceBy(millis: Long) {
            val target = time + millis
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= target }
                    .minWithOrNull(compareBy<Task>({ it.at }, { it.order })) ?: break
                tasks -= next
                time = next.at
                next.run()
            }
            time = target
        }

        override fun close() {
            tasks.forEach { it.cancelled = true }
        }
    }
}
