package com.framework.innolive.feature.live.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyProcessingFailureTest {
    @Test fun exceptionsAndJniLinkageErrorsEndOnlyTheProcessingAttempt() {
        listOf(
            RuntimeException(), IllegalStateException(),
            NoSuchMethodError(), NoClassDefFoundError(), UnsatisfiedLinkError(), ExceptionInInitializerError(),
        ).forEach { assertTrue(it.javaClass.simpleName, it.isPrivacyProcessingFailure()) }
    }

    @Test fun virtualMachineErrorsAreNotSwallowed() {
        listOf(OutOfMemoryError(), StackOverflowError(), AssertionError())
            .forEach { assertFalse(it.javaClass.simpleName, it.isPrivacyProcessingFailure()) }
    }
}
