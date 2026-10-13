package com.framework.innolive.feature.live.direct.broadcast

import android.os.SystemClock
import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.direct.publish.RtmpFailure
import com.framework.innolive.feature.live.direct.publish.RtmpTargetState
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 검증된 인터넷 연결 여부입니다. */
internal interface NetworkAvailability : AutoCloseable {
    fun isAvailable(): Boolean

    /** 검증된 인터넷이 생길 때마다 [onAvailable]을 부릅니다. 아무 스레드에서나 올 수 있습니다. */
    fun start(onAvailable: () -> Unit)
}

internal fun interface ScheduledReconnectTask {
    fun cancel()
}

/** 재연결 판단을 한 줄로 실행합니다. 모든 작업은 같은 스레드에서 순서대로 돌아야 합니다. */
internal interface ReconnectScheduler : AutoCloseable {
    fun execute(task: () -> Unit)

    fun schedule(delayMillis: Long, task: () -> Unit): ScheduledReconnectTask
}

/** 전용 스레드 하나로 도는 기본 실행기입니다. 닫힌 뒤의 요청은 버립니다. */
internal class ExecutorReconnectScheduler : ReconnectScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "rtmp-reconnect").apply { isDaemon = true }
    }

    override fun execute(task: () -> Unit) {
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
        }
    }

    override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledReconnectTask = try {
        val future = executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
        ScheduledReconnectTask { future.cancel(false) }
    } catch (_: RejectedExecutionException) {
        ScheduledReconnectTask {}
    }

    override fun close() {
        executor.shutdownNow()
    }
}

/**
 * 플랫폼별 [RtmpReconnectPolicy]를 실행하는 재연결 감시자입니다(C6).
 *
 * 상태 콜백과 네트워크 콜백을 [scheduler] 한 스레드로 모아 처리합니다. [ReconnectActions]도 그 스레드에서
 * 부르며 메인 스레드가 아닙니다. [close] 뒤에는 재연결하지 않습니다.
 */
internal class RtmpReconnectSupervisor(
    private val actions: ReconnectActions,
    private val network: NetworkAvailability,
    private val scheduler: ReconnectScheduler = ExecutorReconnectScheduler(),
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
    private val budgetOf: (BroadcastProvider) -> RtmpReconnectBudget = RtmpReconnectBudget::of,
) : ReconnectSupervisor {
    private class Entry(val policy: RtmpReconnectPolicy) {
        var attemptTask: ScheduledReconnectTask? = null
        var deadlineTask: ScheduledReconnectTask? = null

        fun cancelTasks() {
            attemptTask?.cancel()
            attemptTask = null
            deadlineTask?.cancel()
            deadlineTask = null
        }
    }

    /** [scheduler] 스레드에서만 다룹니다. */
    private val entries = mutableMapOf<BroadcastProvider, Entry>()
    private val closed = AtomicBoolean(false)

    init {
        network.start { post { onNetworkAvailable() } }
    }

    override fun onTargetStateChanged(provider: BroadcastProvider, state: RtmpTargetState, failure: RtmpFailure?) {
        post {
            val entry = entries.getOrPut(provider) { Entry(RtmpReconnectPolicy(budgetOf(provider))) }
            apply(provider, entry, entry.policy.onState(state, failure, nowMillis()))
        }
    }

    override fun onTargetRemoved(provider: BroadcastProvider) {
        post { entries.remove(provider)?.cancelTasks() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { network.close() }
        // 예약은 실행기와 함께 버리고, 이미 꺼낸 작업은 closed 확인에서 멈춥니다.
        scheduler.close()
    }

    private fun post(task: () -> Unit) {
        if (closed.get()) return
        scheduler.execute { if (!closed.get()) task() }
    }

    private fun onNetworkAvailable() {
        entries.forEach { (provider, entry) -> apply(provider, entry, entry.policy.onNetworkAvailable()) }
    }

    private fun apply(provider: BroadcastProvider, entry: Entry, action: RtmpReconnectAction) {
        when (action) {
            RtmpReconnectAction.None -> Unit
            is RtmpReconnectAction.Schedule -> {
                entry.attemptTask?.cancel()
                entry.attemptTask = scheduleFor(provider, entry, action.delayMillis) {
                    entry.attemptTask = null
                    entry.policy.onAttemptDue(nowMillis(), network.isAvailable())
                }
                scheduleDeadline(provider, entry)
            }
            RtmpReconnectAction.Reconnect -> reconnect(provider, entry)
            is RtmpReconnectAction.GiveUp -> {
                entry.cancelTasks()
                actions.giveUp(provider, action.failure)
            }
            RtmpReconnectAction.Reset -> entry.cancelTasks()
        }
    }

    private fun scheduleDeadline(provider: BroadcastProvider, entry: Entry) {
        if (entry.deadlineTask != null) return
        val deadline = entry.policy.deadlineAtMillis ?: return
        entry.deadlineTask = scheduleFor(provider, entry, (deadline - nowMillis()).coerceAtLeast(0)) {
            entry.deadlineTask = null
            entry.policy.onDeadline(nowMillis())
        }
    }

    private fun reconnect(provider: BroadcastProvider, entry: Entry) {
        try {
            actions.reconnect(provider)
        } catch (_: RuntimeException) {
            // 시작조차 못 한 시도는 네트워크 실패로 보고 다음 판단에 맡깁니다.
            apply(provider, entry, entry.policy.onState(RtmpTargetState.FAILED, RtmpFailure.NETWORK, nowMillis()))
        }
    }

    /** 대상이 지워졌거나 바뀐 뒤에 도착한 예약은 버립니다. */
    private fun scheduleFor(
        provider: BroadcastProvider,
        entry: Entry,
        delayMillis: Long,
        decide: () -> RtmpReconnectAction,
    ): ScheduledReconnectTask = scheduler.schedule(delayMillis) {
        if (!closed.get() && entries[provider] === entry) apply(provider, entry, decide())
    }
}
