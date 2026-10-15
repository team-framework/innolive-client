package com.framework.innolive.feature.live.direct.broadcast

import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.direct.publish.RtmpFailure
import com.framework.innolive.feature.live.direct.publish.RtmpTargetState
import org.junit.Assert.assertEquals
import org.junit.Test

class RtmpReconnectPolicyTest {
    @Test
    fun budgetsMatchPlatformLimits() {
        assertEquals(10_000L, RtmpReconnectBudget.of(BroadcastProvider.CHZZK).deadlineMillis)
        val youtube = RtmpReconnectBudget.of(BroadcastProvider.YOUTUBE)
        assertEquals(2_000L, youtube.retryDelayMillis)
        assertEquals(3, youtube.maxAttempts)
    }

    @Test
    fun dropStartsOutageAndSchedulesFirstAttempt() {
        val policy = RtmpReconnectPolicy(RtmpReconnectBudget.YOUTUBE)
        policy.onState(RtmpTargetState.PUBLISHING, null, 0)

        assertEquals(RtmpReconnectAction.Schedule(2_000), policy.onState(RtmpTargetState.DISCONNECTED, null, 100))
        assertEquals(45_100L, policy.deadlineAtMillis)
        // 같은 끊김의 중복 콜백은 무시합니다.
        assertEquals(RtmpReconnectAction.None, policy.onState(RtmpTargetState.FAILED, RtmpFailure.NETWORK, 200))
    }

    @Test
    fun networkWaitPollsWithoutConsumingAttempt() {
        val policy = RtmpReconnectPolicy(RtmpReconnectBudget.YOUTUBE)
        policy.onState(RtmpTargetState.PUBLISHING, null, 0)
        policy.onState(RtmpTargetState.DISCONNECTED, null, 0)

        assertEquals(RtmpReconnectAction.Schedule(1_000), policy.onAttemptDue(2_000, networkAvailable = false))
        assertEquals(0, policy.attemptsUsed)
        assertEquals(RtmpReconnectAction.Schedule(0), policy.onNetworkAvailable())
        assertEquals(RtmpReconnectAction.Reconnect, policy.onAttemptDue(2_500, networkAvailable = true))
        assertEquals(1, policy.attemptsUsed)
    }

    @Test
    fun networkCallbackDoesNotShortenRetryDelay() {
        val policy = RtmpReconnectPolicy(RtmpReconnectBudget.YOUTUBE)
        policy.onState(RtmpTargetState.PUBLISHING, null, 0)
        policy.onState(RtmpTargetState.DISCONNECTED, null, 0)

        assertEquals(RtmpReconnectAction.None, policy.onNetworkAvailable())
    }

    @Test
    fun publishingAfterGiveUpStartsFresh() {
        val policy = RtmpReconnectPolicy(RtmpReconnectBudget.CHZZK)
        policy.onState(RtmpTargetState.PUBLISHING, null, 0)
        assertEquals(
            RtmpReconnectAction.GiveUp(RtmpFailure.AUTH),
            policy.onState(RtmpTargetState.FAILED, RtmpFailure.AUTH, 0),
        )
        assertEquals(RtmpReconnectAction.None, policy.onState(RtmpTargetState.DISCONNECTED, null, 10))

        policy.onState(RtmpTargetState.PUBLISHING, null, 20)
        assertEquals(RtmpReconnectAction.Schedule(1_000), policy.onState(RtmpTargetState.DISCONNECTED, null, 30))
    }
}
