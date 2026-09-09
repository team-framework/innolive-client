package com.framework.innolive.feature.face

import java.util.concurrent.AbstractExecutorService
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceFaceApiCloseTest {
    @Test
    fun ownedClientCloseFromMainThreadIsSafeAndIdempotent() {
        val executor = TrackingExecutorService()
        val client = OkHttpClient.Builder()
            .dispatcher(Dispatcher(executor))
            .build()
        val api = ReferenceFaceApi("https://example.com")
        replaceHttpClient(api, client)
        var thrown: Throwable? = null
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                try {
                    api.close()
                    api.close()
                } catch (exception: Throwable) {
                    thrown = exception
                }
            }

            assertTrue(executor.cleanupFinished.await(2, TimeUnit.SECONDS))
            assertTrue(executor.cleanupStartedThread !==
                InstrumentationRegistry.getInstrumentation().targetContext.mainLooper.thread)
            assertTrue(executor.executeCount.get() == 1)
            assertTrue(executor.isShutdown)
            assertTrue(thrown?.toString() ?: "", thrown == null)
        } finally {
            api.close()
            executor.shutdownNow()
        }
    }

    private fun replaceHttpClient(api: ReferenceFaceApi, client: OkHttpClient) {
        ReferenceFaceApi::class.java.getDeclaredField("httpClient").apply {
            isAccessible = true
            set(api, client)
        }
    }

    private class TrackingExecutorService : AbstractExecutorService() {
        private val delegate = Executors.newCachedThreadPool()
        val cleanupFinished = java.util.concurrent.CountDownLatch(1)
        val cleanupStartedThread: Thread?
            get() = cleanupThread
        val executeCount = AtomicInteger()
        private var cleanupThread: Thread? = null

        override fun execute(command: Runnable) {
            executeCount.incrementAndGet()
            delegate.execute {
                cleanupThread = Thread.currentThread()
                try {
                    command.run()
                } finally {
                    cleanupFinished.countDown()
                }
            }
        }

        override fun shutdown() = delegate.shutdown()

        override fun shutdownNow(): MutableList<Runnable> = delegate.shutdownNow()

        override fun isShutdown(): Boolean = delegate.isShutdown

        override fun isTerminated(): Boolean = delegate.isTerminated

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
            delegate.awaitTermination(timeout, unit)
    }
}
