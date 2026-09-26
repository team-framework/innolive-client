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
    fun takeResult(): PrivacyFaceService.Result?
    fun submitRecognition(image: Bitmap, bounds: Rect, generation: Long, trackId: String,
                          capturedAtSeconds: Double): Boolean
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
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val preparation = PrivacyFacePreparationGate()
    private val recognizing = AtomicBoolean(false)
    private val result = AtomicReference<Result?>()
    private val inferenceLock = ReentrantLock()
    @Volatile private var model: PrivacyFaceModel? = null
    val preparationFailed: Boolean get() = preparation.failed
    override val ready: Boolean get() = model != null
    override val canSubmit: Boolean get() = ready && result.get() == null && !recognizing.get()
    val library: PrivacyFaceLibrary? = try { PrivacyFaceLibrary(context) } catch (_: Exception) { null }

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
                Log.i("PrivacyFace", "model_prepared_ms=${SystemClock.elapsedRealtime() - startedAt}")
            } catch (error: Exception) {
                preparation.complete(success = false)
                Log.w("PrivacyFace", "model_prepare_failed type=${error.javaClass.simpleName}")
            }
        }
    }

    /** A failed model is retried only by the explicit face-management action. */
    fun retryPreparation() {
        if (preparation.allowRetry()) prepare()
    }

    override fun takeResult(): Result? = result.getAndSet(null)

    override fun submitRecognition(image: Bitmap, bounds: Rect, generation: Long, trackId: String,
                          capturedAtSeconds: Double): Boolean {
        if (!ready || result.get() != null || !recognizing.compareAndSet(false, true)) return false
        executor.execute {
            val output = try {
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
                image.recycle()
            }
            result.set(output)
            recognizing.set(false)
        }
        return true
    }

    fun enroll(image: Bitmap, onComplete: (FloatArray?) -> Unit) {
        if (!ready) { image.recycle(); onComplete(null); return }
        executor.execute {
            val embedding = try {
                inferenceLock.lock()
                try { model?.embedding(image, enrollment = true) } finally { inferenceLock.unlock() }
            }
            catch (_: Exception) { null }
            finally { image.recycle() }
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
    fun allowRetry(): Boolean = state.compareAndSet(State.FAILED, State.IDLE)
}
