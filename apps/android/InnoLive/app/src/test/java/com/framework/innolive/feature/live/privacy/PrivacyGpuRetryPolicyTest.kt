package com.framework.innolive.feature.live.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    @Test fun jniLinkageAndUnsupportedFailuresStayOnProtectedCpuPath() {
        for (error in listOf(NoSuchMethodError(), UnsatisfiedLinkError(), UnsupportedOperationException(), IllegalArgumentException())) {
            val policy=PrivacyGpuRetryPolicy(true)
            policy.record(error,0)
            assertFalse(error.javaClass.simpleName,policy.shouldAttempt(Long.MAX_VALUE))
        }
        for (error in listOf(IllegalStateException(), PrivacyFrameContentException("Too many objects to protect"))) {
            val transient=PrivacyGpuRetryPolicy(true)
            transient.record(error,0)
            assertTrue(error.javaClass.simpleName,transient.shouldAttempt(5_000_000_000L))
        }
    }

    @Test fun crowdFrameFailsAloneAndKeepsTheGpuGraph() {
        val policy=PrivacyGpuRetryPolicy(true)
        val teardowns=mutableListOf<Throwable>()
        assertThrows(PrivacyFrameContentException::class.java) {
            policy.attempt({0L},{throw PrivacyFrameContentException("Too many objects to protect")}) {teardowns+=it}
        }
        assertTrue("the graph is not torn down",teardowns.isEmpty())
        assertFalse("no CPU fallback period is scheduled",policy.shouldAttempt(Long.MAX_VALUE))
        assertEquals("the next frame stays on the GPU","gpu",policy.attempt({0L},{"gpu"}) {teardowns+=it})
        assertTrue(teardowns.isEmpty())
    }

    @Test fun gpuFailureTearsDownForCpuThenRetries() {
        val policy=PrivacyGpuRetryPolicy(true)
        val failure=IllegalStateException("GL error")
        val teardowns=mutableListOf<Throwable>()
        assertNull(policy.attempt({1_000_000_000L},{throw failure}) {teardowns+=it})
        assertEquals(listOf<Throwable>(failure),teardowns)
        assertFalse(policy.shouldAttempt(5_999_999_999L))
        assertTrue(policy.shouldAttempt(6_000_000_000L))
        assertEquals("gpu",policy.attempt({6_000_000_000L},{"gpu"}) {teardowns+=it})
        assertFalse("success clears the backoff",policy.shouldAttempt(Long.MAX_VALUE))
    }

    @Test fun backpressureAndFatalErrorsPassThroughWithoutTeardown() {
        val policy=PrivacyGpuRetryPolicy(true)
        val teardowns=mutableListOf<Throwable>()
        assertThrows(PrivacyGpuBackpressureException::class.java) {
            policy.attempt({0L},{throw PrivacyGpuBackpressureException()}) {teardowns+=it}
        }
        assertThrows(OutOfMemoryError::class.java) {
            policy.attempt({0L},{throw OutOfMemoryError()}) {teardowns+=it}
        }
        assertTrue(teardowns.isEmpty())
        assertNull("unsupported input still falls back permanently",
            policy.attempt({0L},{throw IllegalArgumentException()}) {teardowns+=it})
        assertFalse(policy.shouldAttempt(Long.MAX_VALUE))
    }
}
