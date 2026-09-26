package com.framework.innolive.feature.live

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class VideoTrackLifecycleTest {
    @Test fun trackShutdownCanFinishBeforeComposeRemovesItsSink() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(
                InstrumentationRegistry.getInstrumentation().targetContext,
            ).createInitializationOptions(),
        )
        val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val source = factory.createVideoSource(false)
        val track = factory.createVideoTrack("preview-lifecycle", source)
        val sink = VideoSink { }
        val disposed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        try {
            assertTrue(VideoTrackLifecycle.addSink(track, sink))
            thread(name = "preview-shutdown") {
                try {
                    VideoTrackLifecycle.dispose { track.dispose() }
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    disposed.countDown()
                }
            }
            assertTrue(disposed.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Track cleanup failed", it) }
            // Compose may run either of these effects after the connection worker has shut down.
            VideoTrackLifecycle.removeSink(track, sink)
            assertFalse(VideoTrackLifecycle.addSink(track, sink))
        } finally {
            VideoTrackLifecycle.dispose { if (!track.isDisposed) track.dispose() }
            source.dispose()
            factory.dispose()
        }
    }
}
