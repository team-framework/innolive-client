package com.framework.innolive.feature.live.privacy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** One recognizer and one inference queue shared by live video and local enrollment. */
internal interface PrivacyFaceRecognitionService {
    val ready: Boolean
    val canSubmit: Boolean
    fun prepare()
    fun awaitPreparation(timeoutMillis: Long): Boolean = ready
    fun retain() = Unit
    fun release() = Unit
    fun takeResult(): PrivacyFaceService.Result?
    fun submitRecognition(image: Bitmap, bounds: Rect, generation: Long, trackId: String,
                          capturedAtSeconds: Double): Boolean
    fun submitReadback(sample: PrivacyFaceReadback, bounds: Rect, generation: Long, trackId: String,
                       capturedAtSeconds: Double): Boolean {
        val image = sample.read()
        try {
            return submitRecognition(image, bounds, generation, trackId, capturedAtSeconds).also {
                if (!it) image.recycle()
            }
        } catch (error: Throwable) { image.recycle(); throw error }
    }
}

internal class PrivacyFaceService private constructor(context: Context) : PrivacyFaceRecognitionService {
    data class Result(
        val generation: Long,
        val trackId: String,
        val capturedAtSeconds: Double,
        val embedding: FloatArray?,
        val sampleAvailable: Boolean,
        val imageBox: RectF? = null,
    )

    private val context = context.applicationContext
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE); task.run() }, "privacy-face")
    }
    private var leases = 0
    private var eviction: java.util.concurrent.ScheduledFuture<*>? = null

    override fun retain() { executor.execute { leases++; eviction?.cancel(false); eviction=null } }
    override fun release() { executor.execute {
        check(leases > 0)
        leases--
        if (leases==0) scheduleEviction()
    } }
    private fun scheduleEviction() {
        eviction?.cancel(false)
        eviction=executor.schedule({
            if (leases==0) {
                try { model?.close() } finally {
                    model=null
                    result.set(null)
                    preparation.releaseReady()
                }
                Log.i("PrivacyFace", "model_released_idle=true")
            }
        },30,java.util.concurrent.TimeUnit.SECONDS)
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val preparation = PrivacyFacePreparationGate()
    private val recognizing = AtomicBoolean(false)
    private val result = AtomicReference<Result?>()
    private val inferenceLock = ReentrantLock()
    @Volatile private var model: PrivacyFaceModel? = null
    val preparationFailed: Boolean get() = preparation.failed
    override val ready: Boolean get() = model != null
    override val canSubmit: Boolean get() = ready && result.get() == null && !recognizing.get()

    override fun prepare() {
        if (ready || !preparation.tryBegin()) return
        executor.execute {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val candidate = PrivacyFaceModel(context)
                try {
                    val sample = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.rgb(102, 128, 153))
                    }
                    try {
                        candidate.predict(sample, floatArrayOf(
                            .34f, .46f, .66f, .46f, .50f, .64f, .37f, .82f, .63f, .82f,
                        ))
                    } finally { sample.recycle() }
                    model = candidate
                } catch (error: Exception) {
                    candidate.close()
                    throw error
                }
                preparation.complete(success = true)
                if (leases==0) scheduleEviction()
                Log.i("PrivacyFace", "model_prepared_ms=${SystemClock.elapsedRealtime() - startedAt}")
            } catch (error: Exception) {
                preparation.complete(success = false)
                Log.w("PrivacyFace", "model_prepare_failed type=${error.javaClass.simpleName}")
            }
        }
    }

    override fun awaitPreparation(timeoutMillis: Long): Boolean {
        // The preparation job runs before this barrier on the same executor.
        val barrier = executor.submit<Boolean> { ready }
        return try {
            barrier.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            barrier.cancel(false)
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            barrier.cancel(false)
            false
        }
    }

    /** A failed model is retried only by the explicit face-management action. */
    fun retryPreparation() {
        if (preparation.allowRetry()) prepare()
    }

    override fun takeResult(): Result? = result.getAndSet(null)

    override fun submitRecognition(image: Bitmap, bounds: Rect, generation: Long, trackId: String,
                          capturedAtSeconds: Double): Boolean =
        submitSample({ image }, { image.recycle() }, bounds, generation, trackId, capturedAtSeconds)

    override fun submitReadback(sample: PrivacyFaceReadback, bounds: Rect, generation: Long, trackId: String,
                                capturedAtSeconds: Double): Boolean {
        var image: Bitmap? = null
        return submitSample({ sample.read().also { image = it } },
            { try { image?.recycle() } finally { sample.close() } }, bounds, generation, trackId, capturedAtSeconds)
    }

    private fun submitSample(read: () -> Bitmap, release: () -> Unit, bounds: Rect, generation: Long,
                             trackId: String, capturedAtSeconds: Double): Boolean {
        if (!ready || result.get() != null || !recognizing.compareAndSet(false, true)) return false
        executor.execute {
            var releaseFailed = false
            val output = try {
                val image = read()
                val recognition = inferenceLock.run {
                    lock()
                    try { model?.recognize(image, enrollment = false) } finally { unlock() }
                }
                Result(generation, trackId, capturedAtSeconds, recognition?.embedding,
                    sampleAvailable = recognition != null,
                    imageBox = recognition?.box?.apply { offset(bounds.left.toFloat(), bounds.top.toFloat()) })
            } catch (_: AmbiguousFaceSampleException) {
                Result(generation, trackId, capturedAtSeconds, null, sampleAvailable = true)
            } catch (_: Exception) {
                Result(generation, trackId, capturedAtSeconds, null, sampleAvailable = true)
            } finally {
                try { release() } catch (error: Exception) {
                    releaseFailed = true
                    Log.w("PrivacyFace", "sample_release_failed type=${error.javaClass.simpleName}")
                }
            }
            result.set(if (releaseFailed) Result(generation, trackId, capturedAtSeconds, null, true) else output)
            recognizing.set(false)
        }
        return true
    }

    fun enroll(image: Bitmap, onComplete: (FloatArray?) -> Unit) {
        if (!ready) { image.recycle(); onComplete(null); return }
        executor.execute {
            eviction?.cancel(false); eviction=null
            val embedding = try {
                inferenceLock.lock()
                try { model?.embedding(image, enrollment = true) } finally { inferenceLock.unlock() }
            }
            catch (_: Exception) { null }
            finally { image.recycle() }
            if (leases==0) scheduleEviction()
            mainHandler.post { onComplete(embedding) }
        }
    }

    companion object {
        @Volatile private var instance: PrivacyFaceService? = null
        fun get(context: Context): PrivacyFaceService = instance ?: synchronized(this) {
            instance ?: PrivacyFaceService(context).also { instance = it }
        }
    }
}

/** Suppresses expensive per-frame reloads after failure while retaining an explicit retry path. */
internal class PrivacyFacePreparationGate {
    private enum class State { IDLE, RUNNING, FAILED, READY }
    private val state = AtomicReference(State.IDLE)

    val failed: Boolean get() = state.get() == State.FAILED
    fun tryBegin(): Boolean = state.compareAndSet(State.IDLE, State.RUNNING)
    fun complete(success: Boolean) {
        check(state.compareAndSet(State.RUNNING, if (success) State.READY else State.FAILED))
    }
    fun releaseReady(): Boolean = state.compareAndSet(State.READY,State.IDLE)
    fun allowRetry(): Boolean = state.compareAndSet(State.FAILED, State.IDLE)
}
