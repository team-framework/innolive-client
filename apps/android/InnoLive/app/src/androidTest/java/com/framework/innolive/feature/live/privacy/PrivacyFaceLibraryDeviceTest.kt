package com.framework.innolive.feature.live.privacy

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import com.framework.innolive.feature.live.SessionRecoveryScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PrivacyFaceLibraryDeviceTest {
    @Test fun localEmbeddingsReloadDeleteAndStayEncrypted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = SessionRecoveryScope("https://face-library-test", "first-account").storageKey
        val library = PrivacyFaceLibrary(context, scope)
        library.deleteAll()
        val embedding = FloatArray(512).apply { this[0] = 1f }
        library.add("Private Person", embedding)
        val first = library.snapshot().single()
        assertEquals("Private Person", first.name)
        assertEquals(1f, first.embedding[0], .0001f)
        val onDisk = PrivacyFaceLibrary.fileForAccount(context, scope).readBytes()
        assertFalse(String(onDisk, Charsets.ISO_8859_1).contains("Private Person"))
        assertEquals(first.id, PrivacyFaceLibrary(context, scope).snapshot().single().id)
        library.delete(first.id)
        assertTrue(PrivacyFaceLibrary(context, scope).snapshot().isEmpty())
        PrivacyFaceLibrary.clearForAccountDeletion(context, scope)
    }

    @Test fun switchingAccountsKeepsFaceLibrariesSeparate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstScope = SessionRecoveryScope("https://face-library-test", "account-a").storageKey
        val secondScope = SessionRecoveryScope("https://face-library-test", "account-b").storageKey
        val first = PrivacyFaceLibrary(context, firstScope)
        val second = PrivacyFaceLibrary(context, secondScope)
        try {
            first.deleteAll()
            second.deleteAll()
            first.add("Account A", FloatArray(512).apply { this[0] = 1f })
            assertTrue(second.snapshot().isEmpty())
            second.add("Account B", FloatArray(512).apply { this[1] = 1f })
            assertEquals("Account A", PrivacyFaceLibrary(context, firstScope).snapshot().single().name)
            assertEquals("Account B", PrivacyFaceLibrary(context, secondScope).snapshot().single().name)
            PrivacyFaceLibrary.clearForAccountDeletion(context, firstScope)
            assertTrue(first.snapshot().isEmpty())
            assertEquals("Account B", second.snapshot().single().name)
        } finally {
            PrivacyFaceLibrary.clearForAccountDeletion(context, firstScope)
            PrivacyFaceLibrary.clearForAccountDeletion(context, secondScope)
        }
    }

    @Test fun unownedLegacyFileIsNeverImportedIntoAnAccount() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = SessionRecoveryScope("https://face-library-test", "legacy-target").storageKey
        val legacy = File(context.noBackupFilesDir, "privacy-local-faces.bin")
        legacy.writeText("unowned face data")
        try {
            assertTrue(PrivacyFaceLibrary(context, scope).snapshot().isEmpty())
            assertFalse(legacy.exists())
        } finally {
            PrivacyFaceLibrary.clearForAccountDeletion(context, scope)
            legacy.delete()
        }
    }

    @Test fun yuNetRunsAtDynamicInputSize() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(70, 120, 160))
        }
        try {
            PrivacyYuNetModel(context).use { model ->
                assertNull(model.oneFace(image, enrollment = false))
            }
        } finally { image.recycle() }
    }

    @Test fun sharedFaceServicePreparesAndWarmsTheRealRecognizer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = PrivacyFaceService.get(context)
        service.prepare()
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (!service.ready && !service.preparationFailed && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(100)
        }
        assertFalse(service.preparationFailed)
        assertTrue(service.ready)
    }

    @Test fun yuNetStrideDecodeRejectsMalformedOutputs() {
        val outputs = mutableMapOf<String, FloatArray>()
        for (stride in listOf(8, 16, 32)) {
            val count = (32 / stride) * (32 / stride)
            outputs["cls_$stride"] = FloatArray(count)
            outputs["obj_$stride"] = FloatArray(count)
            outputs["bbox_$stride"] = FloatArray(count * 4)
            outputs["kps_$stride"] = FloatArray(count * 10)
        }
        outputs.getValue("cls_8")[5] = .9f
        outputs.getValue("obj_8")[5] = .9f
        outputs.getValue("bbox_8")[5 * 4] = .5f
        outputs.getValue("bbox_8")[5 * 4 + 1] = .5f
        val face = PrivacyYuNetDecoding.decode(outputs, 32, 32).single()
        assertEquals(12f, face.box.centerX(), .001f)
        assertEquals(12f, face.box.centerY(), .001f)
        outputs.getValue("kps_8")[0] = Float.NaN
        assertThrows(IllegalArgumentException::class.java) {
            PrivacyYuNetDecoding.decode(outputs, 32, 32)
        }
    }
}
