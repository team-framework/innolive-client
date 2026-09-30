package com.framework.innolive.feature.live.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyGpuRetryPolicyTest {
    @Test fun retriesTransientFailureWithoutRetryingEveryFrame() {
        val policy=PrivacyGpuRetryPolicy(true)
        policy.failed(1_000_000_000L)
        assertFalse(policy.shouldAttempt(5_999_999_999L))
        assertTrue(policy.shouldAttempt(6_000_000_000L))
        policy.failed(6_000_000_000L)
        assertFalse(policy.shouldAttempt(15_999_999_999L))
        assertTrue(policy.shouldAttempt(16_000_000_000L))
        policy.succeeded()
        assertFalse(policy.shouldAttempt(Long.MAX_VALUE))
        policy.failed(0)
        policy.disable()
        assertFalse(policy.shouldAttempt(Long.MAX_VALUE))
    }

    @Test fun permanentUserDisabledGpuNeverRetriesAndFailuresAreCapped() {
        val disabled=PrivacyGpuRetryPolicy(false)
        disabled.failed(0)
        assertFalse(disabled.shouldAttempt(Long.MAX_VALUE))
        val policy=PrivacyGpuRetryPolicy(true)
        repeat(7) {policy.failed(0)}
        assertFalse(policy.shouldAttempt(39_999_999_999L))
        assertTrue(policy.shouldAttempt(40_000_000_000L))
    }
}
