package com.framework.innolive.feature.live.privacy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** One recognizer and one inference queue shared by live video and local enrollment. */
internal class PrivacyFaceService private constructor(context: Context) {
    data class Result(
        val generation: Long,
        val trackId: String,
        val capturedAtSeconds: Double,
        val embedding: FloatArray?,
        val sampleAvailable: Boolean,
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
    val ready: Boolean get() = model != null
    val library: PrivacyFaceLibrary? = try { PrivacyFaceLibrary(context) } catch (_: Exception) { null }

    fun prepare() {
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

    fun takeResult(): Result? = result.getAndSet(null)

    fun submitRecognition(image: Bitmap, generation: Long, trackId: String,
                          capturedAtSeconds: Double): Boolean {
        if (!ready || result.get() != null || !recognizing.compareAndSet(false, true)) return false
        executor.execute {
            val output = try {
                val embedding = inferenceLock.run {
                    lock()
                    try { model?.embedding(image, enrollment = false) } finally { unlock() }
                }
                Result(generation, trackId, capturedAtSeconds, embedding,
                    sampleAvailable = embedding != null)
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

    /** Return false when another inference is active; an unverified frame stays protected. */
    fun verifyCurrentFace(image: Bitmap, expectedId: String,
                          entries: List<PrivacyRegisteredFace>): Boolean {
        if (!inferenceLock.tryLock()) return false
        return try {
            val embedding = model?.embedding(image, enrollment = false) ?: return false
            PrivacyFaceMath.match(embedding, entries) == expectedId
        } catch (_: Exception) {
            false
        } finally {
            inferenceLock.unlock()
        }
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
