package com.framework.innolive.feature.live.direct.broadcast

import android.content.Context
import android.os.Build
import android.util.Log
import com.framework.innolive.BuildConfig
import com.framework.innolive.feature.live.CreatedSession
import com.framework.innolive.feature.live.direct.DirectHeartbeat
import com.framework.innolive.feature.live.direct.DirectPublishApi
import com.framework.innolive.feature.live.direct.HeartbeatDevice
import com.framework.innolive.feature.live.direct.HeartbeatResult
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.Locale

/** 하트비트 주기입니다(contracts/api/direct-publish-v1.md). */
internal const val HEARTBEAT_INTERVAL_MILLIS = 15_000L

/** 하트비트 예약과 단조 시계입니다. 단위 테스트에서 가짜 시계로 바꿉니다. */
internal interface HeartbeatTimer : AutoCloseable {
    /** 단조 증가 시각(ms)입니다. 벽시계가 바뀌어도 주기가 흔들리지 않게 합니다. */
    fun nowMillis(): Long

    /** [delayMillis] 뒤 [task]를 한 번 실행합니다. 돌려준 함수로 취소합니다. */
    fun schedule(delayMillis: Long, task: () -> Unit): () -> Unit
}

/** 전용 백그라운드 스레드 하나로 실행합니다. 쉬는 동안은 스레드를 돌려줍니다. */
internal class ExecutorHeartbeatTimer : HeartbeatTimer {
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "direct-heartbeat").apply { isDaemon = true }
    }.apply {
        setKeepAliveTime(30, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
        removeOnCancelPolicy = true
    }

    override fun nowMillis(): Long = System.nanoTime() / 1_000_000

    override fun schedule(delayMillis: Long, task: () -> Unit): () -> Unit {
        if (executor.isShutdown) return {}
        val future: ScheduledFuture<*> = executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
        // 진행 중인 요청은 끊지 않습니다. 결과는 루프가 세대 번호로 버립니다.
        return { future.cancel(false) }
    }

    override fun close() {
        executor.shutdown()
    }
}

/**
 * 직접 송출 하트비트 루프입니다(C5). [start] 즉시 한 번 보내고 이후 [intervalMillis]마다 보냅니다.
 *
 * - 한 번에 한 요청만 보냅니다. 다음 예약은 앞 요청이 끝난 뒤에 잡습니다.
 * - 요청이 길어져 지난 주기는 다시 보내지 않고 다음 주기 시각으로 건너뜁니다.
 * - [inputs]가 null이면 그 주기는 보내지 않고 루프는 유지합니다.
 * - [stop] 뒤에 도착한 응답은 리스너로 전달하지 않습니다. 단, 전달을 시작한 뒤의 [stop]과는 겹칠 수 있습니다.
 * - 리스너는 하트비트 스레드에서 불립니다.
 */
internal class DirectHeartbeatLoop(
    private val send: (CreatedSession, DirectHeartbeat) -> HeartbeatResult,
    private val inputs: HeartbeatInputSource,
    private val listener: HeartbeatListener,
    private val device: () -> HeartbeatDevice?,
    private val model: String,
    private val timer: HeartbeatTimer = ExecutorHeartbeatTimer(),
    private val wallClock: () -> Instant = Instant::now,
    private val intervalMillis: Long = HEARTBEAT_INTERVAL_MILLIS,
    private val log: (String) -> Unit = {},
) : HeartbeatScheduler {
    private val lock = Any()
    private var generation = 0L
    private var running = false
    private var cancelPending: (() -> Unit)? = null

    init {
        require(intervalMillis > 0) { "하트비트 주기는 0보다 커야 합니다." }
    }

    override fun start(session: CreatedSession) {
        synchronized(lock) {
            cancelLocked()
            running = true
            val current = ++generation
            scheduleLocked(current, session, dueAt = timer.nowMillis(), delayMillis = 0)
        }
    }

    override fun stop() {
        synchronized(lock) { cancelLocked() }
    }

    /** 루프를 멈추고 스레드를 정리합니다. 이후 다시 시작할 수 없습니다. */
    override fun close() {
        stop()
        timer.close()
    }

    private fun cancelLocked() {
        generation++
        running = false
        cancelPending?.invoke()
        cancelPending = null
    }

    private fun isCurrent(current: Long): Boolean = synchronized(lock) { running && generation == current }

    private fun scheduleLocked(current: Long, session: CreatedSession, dueAt: Long, delayMillis: Long) {
        cancelPending = timer.schedule(delayMillis) { beat(current, session, dueAt) }
    }

    private fun beat(current: Long, session: CreatedSession, dueAt: Long) {
        if (!isCurrent(current)) return
        var keepRunning = true
        try {
            val result = sendOnce(session) ?: return
            if (!isCurrent(current)) return
            keepRunning = deliver(current, result)
        } catch (exception: Exception) {
            // 원문에 세션 값이 섞일 수 있어 종류만 남깁니다.
            log("heartbeat failed: ${exception.javaClass.simpleName}")
        } finally {
            if (keepRunning) scheduleNext(current, session, dueAt)
        }
    }

    /** 보낼 대상이 없으면 null입니다. */
    private fun sendOnce(session: CreatedSession): HeartbeatResult? {
        val heartbeat = buildDirectHeartbeat(
            inputs = inputs.current() ?: return null,
            device = readDeviceSafely(),
            model = model,
            sentAt = wallClock(),
        ) ?: return null
        return send(session, heartbeat)
    }

    private fun readDeviceSafely(): HeartbeatDevice? = try {
        device()
    } catch (exception: Exception) {
        log("heartbeat device read failed: ${exception.javaClass.simpleName}")
        null
    }

    /** 루프를 계속할지 돌려줍니다. */
    private fun deliver(current: Long, result: HeartbeatResult): Boolean = when (result) {
        is HeartbeatResult.Accepted -> {
            if (result.stopTargets.isNotEmpty()) listener.onStopTargets(result.stopTargets)
            listener.onNotices(result.notices, result.broadcastRemainingSeconds)
            true
        }
        is HeartbeatResult.StopAll -> {
            // 리스너가 다시 start를 불러도 새 루프가 유지되도록 먼저 멈춥니다.
            synchronized(lock) { if (generation == current) cancelLocked() }
            log("heartbeat stop all: ${result.reason}")
            listener.onStopAll(result.reason)
            false
        }
        is HeartbeatResult.Invalid -> {
            log("heartbeat invalid field: ${result.field}")
            listener.onInvalid(result.field)
            true
        }
        HeartbeatResult.Unauthorized -> {
            log("heartbeat unauthorized")
            true
        }
        HeartbeatResult.RetryLater -> true
    }

    private fun scheduleNext(current: Long, session: CreatedSession, dueAt: Long) {
        synchronized(lock) {
            if (!running || generation != current) return
            val now = timer.nowMillis()
            val next = nextDueAt(dueAt, now, intervalMillis)
            scheduleLocked(current, session, next, next - now)
        }
    }
}

/** [previousDueAt] 다음 주기 시각입니다. 이미 지난 주기는 건너뛰어 재전송하지 않습니다. */
internal fun nextDueAt(previousDueAt: Long, now: Long, intervalMillis: Long): Long {
    val next = previousDueAt + intervalMillis
    if (next > now) return next
    val missed = (now - next) / intervalMillis + 1
    return next + missed * intervalMillis
}

/** 송출 상태와 기기 상태를 합칩니다. 보고할 대상이 없으면 null입니다. */
internal fun buildDirectHeartbeat(
    inputs: HeartbeatInputs,
    device: HeartbeatDevice?,
    model: String,
    sentAt: Instant,
): DirectHeartbeat? {
    if (inputs.targets.isEmpty()) return null
    return DirectHeartbeat(
        sentAt = sentAt,
        targets = inputs.targets,
        video = inputs.video,
        anonymization = inputs.anonymization,
        model = model.trim().ifEmpty { UNKNOWN_MODEL },
        device = device,
    )
}

private const val UNKNOWN_MODEL = "unknown"

/** debug 빌드의 성능 확인 로그입니다. 하트비트 측정값만 담습니다. */
internal fun describeHeartbeatForLog(heartbeat: DirectHeartbeat): String = buildString {
    append("heartbeat_stats fps=").append(String.format(Locale.ROOT, "%.1f", heartbeat.video.fps))
    append(" dropped=").append(heartbeat.video.droppedFrames)
    append(" failed=").append(heartbeat.anonymization.failedFrames)
    heartbeat.anonymization.inferenceMsP95?.let { append(" infer_p95=").append(String.format(Locale.ROOT, "%.1f", it)) }
    heartbeat.targets.forEach { target ->
        append(" ").append(target.provider.wireValue).append("=")
        append(if (target.connected) "on" else "off")
        target.bitrateKbps?.let { append(",").append(it).append("kbps") }
        target.reconnects?.let { append(",r").append(it) }
    }
    heartbeat.device?.let { device ->
        device.thermal?.let { append(" thermal=").append(it.wireValue) }
        device.batteryPct?.let { append(" battery=").append(it) }
        device.charging?.let { append(" charging=").append(it) }
    }
}
private const val HEARTBEAT_LOG_TAG = "DirectPublish"

/** 기본 송출 경로가 쓰는 하트비트 루프입니다. */
internal fun createDirectHeartbeatLoop(
    context: Context,
    api: DirectPublishApi,
    inputs: HeartbeatInputSource,
    listener: HeartbeatListener,
): HeartbeatScheduler {
    val deviceHealth = DeviceHealthReader(context)
    return DirectHeartbeatLoop(
        send = { session, heartbeat ->
            // 기기 성능 확인용입니다. 값만 남기고 세션·주소는 넣지 않습니다.
            if (BuildConfig.DEBUG) Log.i(HEARTBEAT_LOG_TAG, describeHeartbeatForLog(heartbeat))
            api.heartbeat(session, heartbeat)
        },
        inputs = inputs,
        listener = listener,
        device = deviceHealth::read,
        model = Build.MODEL.orEmpty(),
        log = { message -> Log.i(HEARTBEAT_LOG_TAG, message) },
    )
}
