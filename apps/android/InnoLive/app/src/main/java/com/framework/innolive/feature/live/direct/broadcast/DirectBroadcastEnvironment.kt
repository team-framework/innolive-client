package com.framework.innolive.feature.live.direct.broadcast

import android.media.AudioDeviceInfo
import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.CreatedSession
import com.framework.innolive.feature.live.direct.DirectSessionServer
import com.framework.innolive.feature.live.direct.publish.DirectPublishEngine
import com.framework.innolive.feature.live.direct.publish.DirectPublishProfile
import com.framework.innolive.feature.live.direct.publish.DirectPublisher
import com.framework.innolive.feature.live.direct.publish.PublishOrientation
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/*
 * 직접 송출 상태 머신이 기대는 바깥 동작입니다. 실제 구현은 Android 객체를 쓰고, 단위 테스트는 가짜를 넣어
 * JVM에서 상태 전이를 확인합니다.
 */

/** 세션 정리 저장소입니다. owner token은 생성 응답에만 오므로 만든 즉시 저장합니다. */
internal interface DirectSessionRecovery {
    fun load(): CreatedSession?
    fun loadLegacy(): CreatedSession?
    fun save(session: CreatedSession)
    fun clear()
    fun clearLegacy()
}

/** 카메라·가림 처리·송출 엔진입니다. */
internal interface DirectMediaPipeline : AutoCloseable {
    /** 카메라 분석기를 만들어 미리보기에 연결하고 프레임을 받기 시작합니다. */
    fun open(events: DirectMediaEvents)

    /** 최근 송출 프레임의 방향입니다. 화면 방향을 고정하면 프레임도 그 방향으로 옵니다. */
    val frameOrientation: PublishOrientation

    /** 가림 처리에 실패해 내보내지 않은 누적 프레임 수입니다. */
    val anonymizationFailedFrames: Long

    fun setAnonymizationEnabled(enabled: Boolean)

    fun setPreferredAudioInput(device: AudioDeviceInfo?)

    /** 송출 엔진을 만들어 시작하고 가림 처리 출력을 연결합니다. */
    fun createEngine(profile: DirectPublishProfile, listener: DirectPublisher.Listener): DirectPublishEngine
}

internal interface DirectMediaEvents {
    /** 송출할 수 있는 첫 프레임(가림 처리를 켰으면 가림 처리된 프레임)이 나왔습니다. */
    fun onFirstFrame()

    fun onProcessingFailure()

    fun onMicrophoneStatus(status: MicrophoneStatus)
}

internal fun interface Cancellable {
    fun cancel()
}

/** 상태 머신의 모든 작업을 한 스레드에서 차례로 실행합니다. */
internal interface SerialScheduler {
    fun execute(block: () -> Unit)
    fun schedule(delayMillis: Long, block: () -> Unit): Cancellable
    fun shutdown()
}

internal class ExecutorSerialScheduler(private val executor: ScheduledExecutorService) : SerialScheduler {
    override fun execute(block: () -> Unit) {
        runCatching { executor.execute(block) }
    }

    override fun schedule(delayMillis: Long, block: () -> Unit): Cancellable {
        val future = runCatching { executor.schedule(block, delayMillis, TimeUnit.MILLISECONDS) }.getOrNull()
        return Cancellable { future?.cancel(false) }
    }

    override fun shutdown() = executor.shutdown()
}

/** 상태 머신을 막지 않을 작업을 별도 스레드에서 실행합니다. */
internal interface BackgroundExecutor {
    fun execute(block: () -> Unit)
    fun shutdown()
}

internal class ExecutorBackgroundExecutor(private val executor: ExecutorService) : BackgroundExecutor {
    override fun execute(block: () -> Unit) {
        runCatching { executor.execute(block) }
    }

    override fun shutdown() = executor.shutdown()
}

/** 상태 머신 밖의 의존성 묶음입니다. */
internal class DirectBroadcastEnvironment(
    val server: DirectSessionServer,
    val recovery: DirectSessionRecovery,
    val media: DirectMediaPipeline,
    val heartbeat: (HeartbeatInputSource, HeartbeatListener) -> HeartbeatScheduler,
    val reconnect: (ReconnectActions) -> ReconnectSupervisor,
    val keepAlive: BroadcastKeepAlive,
    val owner: SerialScheduler,
    /** 상태 조회처럼 상태 머신을 막지 않을 요청을 보냅니다. */
    val io: BackgroundExecutor,
    /** 화면 콜백을 보낼 곳입니다(메인 스레드). */
    val callbacks: Executor,
    val elapsedRealtimeMillis: () -> Long,
    val log: (String) -> Unit,
)

/** 세션 생성 시 정해지는 직접 송출 구성입니다. */
internal data class DirectBroadcastConfig(
    val plan: DirectBroadcastPlan,
    /** 세션의 기본 플랫폼입니다. 화면에서 고른 플랫폼입니다. */
    val provider: BroadcastProvider,
    val anonymizationEnabled: Boolean,
) {
    /** 고른 플랫폼이 구성에 없으면 그 플랫폼만 송출합니다. */
    val targets: List<BroadcastProvider> =
        (if (provider in plan.providers) plan.providers else setOf(provider)).sortedBy { it.ordinal }
}
