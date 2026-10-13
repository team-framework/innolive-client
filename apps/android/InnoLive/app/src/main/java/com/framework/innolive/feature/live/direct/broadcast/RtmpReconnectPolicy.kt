package com.framework.innolive.feature.live.direct.broadcast

import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.direct.publish.RtmpFailure
import com.framework.innolive.feature.live.direct.publish.RtmpTargetState

/** 플랫폼별 재연결 예산입니다. 마감은 연결이 끊긴 시점부터 잽니다. */
internal data class RtmpReconnectBudget(
    val retryDelayMillis: Long,
    val maxAttempts: Int,
    val deadlineMillis: Long,
    /** 네트워크를 기다리는 동안 다시 확인하는 간격입니다. 콜백을 놓쳐도 이어지게 합니다. */
    val networkPollMillis: Long = 1_000,
) {
    init {
        require(retryDelayMillis >= 0 && maxAttempts > 0 && deadlineMillis > 0 && networkPollMillis > 0)
    }

    companion object {
        /** 치지직은 RTMP가 끊기고 13~14초 뒤 방송을 닫으므로 10초 안에 다시 붙어야 합니다. */
        val CHZZK = RtmpReconnectBudget(retryDelayMillis = 1_000, maxAttempts = Int.MAX_VALUE, deadlineMillis = 10_000)

        /** iOS와 같이 2초 간격 3회입니다. 네트워크 대기는 autoStop(약 1분)보다 먼저 끝냅니다. */
        val YOUTUBE = RtmpReconnectBudget(retryDelayMillis = 2_000, maxAttempts = 3, deadlineMillis = 45_000)

        fun of(provider: BroadcastProvider): RtmpReconnectBudget = when (provider) {
            BroadcastProvider.CHZZK -> CHZZK
            BroadcastProvider.YOUTUBE -> YOUTUBE
        }
    }
}

/** [RtmpReconnectPolicy]가 호출자에게 요청하는 동작입니다. */
internal sealed interface RtmpReconnectAction {
    data object None : RtmpReconnectAction

    /** 기존 예약을 지우고 [delayMillis] 뒤 [RtmpReconnectPolicy.onAttemptDue]를 부릅니다. */
    data class Schedule(val delayMillis: Long) : RtmpReconnectAction

    /** 지금 재연결합니다. */
    data object Reconnect : RtmpReconnectAction

    /** 재연결을 포기합니다. 예약을 모두 지웁니다. */
    data class GiveUp(val failure: RtmpFailure) : RtmpReconnectAction

    /** 송출이 돌아왔습니다. 예약을 모두 지웁니다. */
    data object Reset : RtmpReconnectAction
}

/**
 * 플랫폼 하나의 재연결 판단입니다. 시간과 네트워크 상태를 인자로 받는 순수 로직이며 스레드에 안전하지 않습니다.
 *
 * - 한 번이라도 송출한 연결만 재연결합니다. 첫 연결 실패는 상태 머신이 처리합니다.
 * - AUTH·REJECTED는 재시도하지 않습니다.
 * - 네트워크가 없으면 시도 횟수를 쓰지 않고 기다리지만 마감은 그대로 지킵니다.
 * - PUBLISHING이 되면 예산을 처음으로 되돌립니다.
 */
internal class RtmpReconnectPolicy(private val budget: RtmpReconnectBudget) {
    private var published = false
    private var outageStartedAt: Long? = null
    private var attempts = 0
    private var attemptInFlight = false
    private var waitingForNetwork = false
    private var gaveUp = false

    /** 진행 중인 끊김의 마감입니다. 끊김이 없으면 null입니다. */
    val deadlineAtMillis: Long? get() = outageStartedAt?.plus(budget.deadlineMillis)

    val attemptsUsed: Int get() = attempts

    fun onState(state: RtmpTargetState, failure: RtmpFailure?, nowMillis: Long): RtmpReconnectAction = when (state) {
        RtmpTargetState.PUBLISHING -> {
            val recovering = outageStartedAt != null
            published = true
            gaveUp = false
            clearOutage()
            if (recovering) RtmpReconnectAction.Reset else RtmpReconnectAction.None
        }
        RtmpTargetState.IDLE, RtmpTargetState.CONNECTING -> RtmpReconnectAction.None
        RtmpTargetState.FAILED, RtmpTargetState.DISCONNECTED -> onDrop(failure, nowMillis)
    }

    fun onAttemptDue(nowMillis: Long, networkAvailable: Boolean): RtmpReconnectAction {
        val deadline = deadlineAtMillis
        if (gaveUp || deadline == null || attemptInFlight) return RtmpReconnectAction.None
        if (nowMillis >= deadline) return giveUp(RtmpFailure.NETWORK)
        if (!networkAvailable) {
            waitingForNetwork = true
            return RtmpReconnectAction.Schedule(budget.networkPollMillis)
        }
        if (attempts >= budget.maxAttempts) return giveUp(RtmpFailure.NETWORK)
        waitingForNetwork = false
        attempts++
        attemptInFlight = true
        return RtmpReconnectAction.Reconnect
    }

    /** 네트워크를 기다리던 중이면 바로 시도합니다. 재시도 간격을 기다리는 중에는 앞당기지 않습니다. */
    fun onNetworkAvailable(): RtmpReconnectAction =
        if (!gaveUp && waitingForNetwork && outageStartedAt != null) RtmpReconnectAction.Schedule(0)
        else RtmpReconnectAction.None

    /** 마감 시각에 부릅니다. 진행 중인 시도가 있어도 포기합니다. */
    fun onDeadline(nowMillis: Long): RtmpReconnectAction {
        val deadline = deadlineAtMillis
        return if (!gaveUp && deadline != null && nowMillis >= deadline) giveUp(RtmpFailure.NETWORK)
        else RtmpReconnectAction.None
    }

    private fun onDrop(failure: RtmpFailure?, nowMillis: Long): RtmpReconnectAction {
        if (!published || gaveUp) return RtmpReconnectAction.None
        if (failure == RtmpFailure.AUTH || failure == RtmpFailure.REJECTED) return giveUp(failure)
        if (outageStartedAt == null) {
            outageStartedAt = nowMillis
            return RtmpReconnectAction.Schedule(budget.retryDelayMillis)
        }
        // 이미 예약했거나 네트워크를 기다리는 중입니다.
        if (!attemptInFlight) return RtmpReconnectAction.None
        attemptInFlight = false
        val deadline = checkNotNull(deadlineAtMillis)
        if (attempts >= budget.maxAttempts || nowMillis >= deadline) return giveUp(RtmpFailure.NETWORK)
        return RtmpReconnectAction.Schedule(budget.retryDelayMillis)
    }

    private fun giveUp(failure: RtmpFailure): RtmpReconnectAction {
        gaveUp = true
        clearOutage()
        return RtmpReconnectAction.GiveUp(failure)
    }

    private fun clearOutage() {
        outageStartedAt = null
        attempts = 0
        attemptInFlight = false
        waitingForNetwork = false
    }
}
