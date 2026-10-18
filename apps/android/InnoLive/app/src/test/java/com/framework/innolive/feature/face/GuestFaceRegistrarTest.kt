package com.framework.innolive.feature.face

import android.graphics.Bitmap
import com.framework.innolive.R
import com.framework.innolive.ui.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class GuestFaceRegistrarTest {
    private val backend = FakeBackend()
    private val target = GuestFaceTarget.OnDevice(accountScope = "account", onChanged = {})

    @Test
    fun registeredToastClearsAfterTwoSeconds() = runTest {
        val registrar = registrar()
        val frame = frame()

        registrar.register(frame, 0, "guest", target)
        assertEquals(GuestFaceRegistrar.Status.Registering, registrar.status)
        runCurrent()

        assertEquals(GuestFaceRegistrar.Status.Registered(), registrar.status)
        assertEquals(1, released(frame))
        advanceTimeBy(1_999)
        assertEquals(GuestFaceRegistrar.Status.Registered(), registrar.status)
        advanceTimeBy(2)
        assertNull(registrar.status)
    }

    @Test
    fun faceThatCannotBePreparedAsksToSelectAgain() = runTest {
        val registrar = registrar()
        backend.prepareResult = { null }

        registrar.register(frame(), 0, "guest", target)
        runCurrent()
        registrar.retry()
        runCurrent()

        assertEquals(
            GuestFaceRegistrar.Status.Failed(UiText.Resource(R.string.guest_face_not_confirmed), canRetry = false),
            registrar.status,
        )
        assertTrue(backend.registered.isEmpty())
    }

    @Test
    fun preparationErrorFailsInsteadOfCrashing() = runTest {
        val registrar = registrar()
        val frame = frame()
        backend.prepareResult = { error("Registration image copy failed") }

        registrar.register(frame, 0, "guest", target)
        runCurrent()

        assertEquals(false, (registrar.status as GuestFaceRegistrar.Status.Failed).canRetry)
        assertEquals(1, released(frame))
    }

    @Test
    fun failedUploadRetriesWithTheSameImage() = runTest {
        val registrar = registrar()
        var attempts = 0
        backend.registerHandler = {
            attempts += 1
            if (attempts == 1) throw GuestFaceRegistrationException(R.string.local_face_store_error)
            true
        }

        registrar.register(frame(), 0, "guest", target)
        runCurrent()
        assertEquals(
            GuestFaceRegistrar.Status.Failed(UiText.Resource(R.string.local_face_store_error), canRetry = true),
            registrar.status,
        )

        registrar.retry()
        assertEquals(GuestFaceRegistrar.Status.Registering, registrar.status)
        runCurrent()

        assertEquals(GuestFaceRegistrar.Status.Registered(), registrar.status)
        assertEquals(1, backend.prepared)
        assertEquals(2, backend.registered.size)
        assertSame(backend.registered[0], backend.registered[1])
    }

    @Test
    fun newRegistrationCancelsThePreviousOneWithoutLosingItsStatus() = runTest {
        val registrar = registrar()
        var attempts = 0
        backend.registerHandler = {
            attempts += 1
            if (attempts == 1) {
                // 취소를 다른 예외로 바꿔 던지는 라이브러리도 새 등록의 상태를 덮어쓰면 안 됩니다.
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    throw IllegalStateException("connection closed")
                }
            }
            true
        }
        val first = frame()
        val second = frame()

        registrar.register(first, 0, "first", target)
        runCurrent()
        registrar.register(second, 0, "second", target)
        runCurrent()

        assertEquals(GuestFaceRegistrar.Status.Registered(), registrar.status)
        assertEquals(listOf("first", "second"), backend.names)
        assertEquals(1, released(first))
        assertEquals(1, released(second))
        advanceTimeBy(2_001)
        assertNull(registrar.status)
    }

    @Test
    fun registrationCancelledBeforeItStartsStillReleasesItsFrame() = runTest {
        val registrar = registrar()
        val first = frame()
        val second = frame()

        registrar.register(first, 0, "first", target)
        registrar.register(second, 1, "second", target)
        runCurrent()

        assertEquals(1, released(first))
        assertEquals(1, released(second))
        assertEquals(1, backend.prepared)
        assertEquals(listOf("second"), backend.names)
    }

    @Test
    fun dismissCancelsTheUploadAndClearsTheToast() = runTest {
        val registrar = registrar()
        val uploading = CompletableDeferred<Unit>()
        backend.registerHandler = {
            uploading.complete(Unit)
            awaitCancellation()
        }

        registrar.register(frame(), 0, "guest", target)
        runCurrent()
        assertTrue(uploading.isCompleted)
        registrar.dismiss()
        advanceTimeBy(5_000)

        assertNull(registrar.status)
        registrar.retry()
        assertNull(registrar.status)
    }

    @Test
    fun registeredTimeoutDoesNotClearANewerRegistration() = runTest {
        val registrar = registrar()
        registrar.register(frame(), 0, "first", target)
        runCurrent()
        backend.registerHandler = { awaitCancellation() }

        registrar.register(frame(), 0, "second", target)
        advanceTimeBy(5_000)

        assertEquals(GuestFaceRegistrar.Status.Registering, registrar.status)
    }

    @Test
    fun unsavedDevicePhotoIsReportedLonger() = runTest {
        val registrar = registrar()
        backend.registerHandler = { false }

        registrar.register(frame(), 0, "guest", target)
        runCurrent()

        val registered = GuestFaceRegistrar.Status.Registered(
            notice = UiText.Resource(R.string.face_registered_local_photo_failed),
        )
        assertEquals(registered, registrar.status)
        advanceTimeBy(3_999)
        assertEquals(registered, registrar.status)
        advanceTimeBy(2)
        assertNull(registrar.status)
    }

    @Test
    fun cancellationFromInsideTheUploadCanBeRetried() = runTest {
        val registrar = registrar()
        backend.registerHandler = { throw CancellationException("request timed out") }

        registrar.register(frame(), 0, "guest", target)
        runCurrent()

        assertEquals(true, (registrar.status as GuestFaceRegistrar.Status.Failed).canRetry)
    }

    @Test
    fun closeReleasesTheBackend() = runTest {
        val registrar = registrar()

        registrar.close()

        assertTrue(backend.closed)
    }

    private fun TestScope.registrar() = GuestFaceRegistrar(backgroundScope, backend, EmptyCoroutineContext)

    private fun frame() = FaceSelectionFrame(
        image = FakeImage(),
        width = 1080,
        height = 1920,
        faces = listOf(FaceSelectionCandidate(FaceBox(0.3f, 0.3f, 0.6f, 0.5f), isSelectable = true)),
    )

    private fun released(frame: FaceSelectionFrame): Int = (frame.image as FakeImage).released

    private class FakeImage : FaceSelectionImage {
        var released = 0

        override fun toUprightBitmap(): Bitmap = throw UnsupportedOperationException()

        override fun release() {
            released += 1
        }
    }

    private class FakeBackend : GuestFaceRegistrationBackend {
        var prepareResult: () -> ByteArray? = { byteArrayOf(1, 2, 3) }
        var registerHandler: suspend () -> Boolean = { true }
        var prepared = 0
        val registered = mutableListOf<ByteArray>()
        val names = mutableListOf<String>()
        var closed = false

        override fun prepare(frame: FaceSelectionFrame, faceIndex: Int): ByteArray? {
            prepared += 1
            return prepareResult()
        }

        override suspend fun register(jpeg: ByteArray, name: String, target: GuestFaceTarget): Boolean {
            registered += jpeg
            names += name
            return registerHandler()
        }

        override fun close() {
            closed = true
        }
    }
}
