package com.framework.innolive.feature.live

import com.framework.innolive.feature.live.privacy.PrivacyGpuBackpressureException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtectedFrameDeliveryTest {
    private class Frame(val protected: Boolean)

    private class Sink {
        val sent = mutableListOf<Frame>()
        val released = mutableListOf<Frame>()
        val failures = mutableListOf<Throwable>()
    }

    private fun run(sink: Sink, process: () -> Frame, deliver: (Frame) -> Boolean = { sink.sent += it; true }) =
        deliverProtectedFrame(process, deliver, { sink.released += it }, { sink.failures += it })

    @Test fun sendsAndReleasesOnlyTheProtectedOutput() {
        val sink = Sink()
        val output = Frame(protected = true)
        assertEquals(ProtectedFrameOutcome.DELIVERED, run(sink, { output }))
        assertEquals(listOf(output), sink.sent)
        assertEquals(listOf(output), sink.released)
        assertTrue(sink.failures.isEmpty())
    }

    @Test fun processingFailuresDropTheFrameAndReportTheError() {
        val failures = listOf(
            IllegalStateException("model"),
            NoSuchMethodError("TensorType\$Layout.<init>([I[I)V"),
            NoClassDefFoundError("com/google/ai/edge/litert/TensorBufferRequirements"),
            UnsatisfiedLinkError("liblitert_jni.so"),
            ExceptionInInitializerError(),
        )
        for (error in failures) {
            val sink = Sink()
            assertEquals(ProtectedFrameOutcome.FAILED, run(sink, { throw error }))
            assertTrue("original frame must not be sent after ${error.javaClass.simpleName}", sink.sent.isEmpty())
            assertTrue(sink.released.isEmpty())
            assertEquals(1, sink.failures.size)
            assertSame(error, sink.failures.single())
        }
    }

    @Test fun deliveryFailureReleasesOutputAndReportsTheError() {
        val sink = Sink()
        val output = Frame(protected = true)
        val outcome = run(sink, { output }) { throw NoClassDefFoundError("org/webrtc/VideoFrame") }
        assertEquals(ProtectedFrameOutcome.FAILED, outcome)
        assertEquals(listOf(output), sink.released)
        assertEquals(1, sink.failures.size)
    }

    @Test fun gpuBackpressureSkipsWithoutReportingAFailure() {
        val sink = Sink()
        assertEquals(ProtectedFrameOutcome.BUSY, run(sink, { throw PrivacyGpuBackpressureException() }))
        assertTrue(sink.sent.isEmpty())
        assertTrue(sink.failures.isEmpty())
    }

    @Test fun staleRouteReleasesOutputWithoutReportingAFailure() {
        val sink = Sink()
        val output = Frame(protected = true)
        assertEquals(ProtectedFrameOutcome.STALE, run(sink, { output }) { false })
        assertEquals(listOf(output), sink.released)
        assertTrue(sink.failures.isEmpty())
    }

    @Test fun virtualMachineErrorsStillPropagate() {
        val sink = Sink()
        try {
            run(sink, { throw OutOfMemoryError() })
            fail("OutOfMemoryError must propagate")
        } catch (_: OutOfMemoryError) {
        }
        assertTrue(sink.sent.isEmpty())
        assertTrue(sink.failures.isEmpty())
    }
}
