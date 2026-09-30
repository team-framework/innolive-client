package com.framework.innolive.feature.live.privacy

import org.junit.Assert.*
import org.junit.Test

class PrivacyMaskStabilizerTest {
    private val size = PrivacySegmentation.MASK_SIZE

    private fun instance(left: Float, classId: Int, brightX: Int): PrivacySegmentation.InstanceMask {
        val box = PrivacySegmentation.Box(left, 100f, left + 40f, 140f)
        val detection = PrivacySegmentation.Detection(box, .9f, classId, FloatArray(32))
        val bytes = ByteArray(size * size)
        bytes[27 * size + brightX] = -1
        return PrivacySegmentation.InstanceMask(detection, bytes)
    }

    @Test fun oldEdgeFadesAtCurrentPositionWhileNewProtectionIsImmediate() {
        val stabilizer = PrivacyMaskStabilizer()
        stabilizer.apply(listOf(instance(100f, 0, 27)), 1.0)
        val result = stabilizer.apply(listOf(instance(104f, 0, 30)), 1.1)
        assertTrue((result[27 * size + 28].toInt() and 255) in 1..254)
        assertEquals(255, result[27 * size + 30].toInt() and 255)
        assertEquals(0, result[27 * size + 27].toInt() and 255)
    }

    @Test fun disappearanceAndDifferentClassNeverPaintOldObject() {
        val stabilizer = PrivacyMaskStabilizer()
        stabilizer.apply(listOf(instance(100f, 0, 27)), 1.0)
        assertTrue(stabilizer.apply(emptyList(), 1.1).all { it == 0.toByte() })
        stabilizer.apply(listOf(instance(100f, 0, 27)), 1.2)
        val different = stabilizer.apply(listOf(instance(104f, 1, 30)), 1.3)
        assertEquals(0, different[27 * size + 28].toInt() and 255)
        assertEquals(255, different[27 * size + 30].toInt() and 255)
    }

    @Test fun frameGapAndResetDiscardHistory() {
        val stabilizer = PrivacyMaskStabilizer()
        stabilizer.apply(listOf(instance(100f, 0, 27)), 1.0)
        val afterGap = stabilizer.apply(listOf(instance(104f, 0, 30)), 1.21)
        assertEquals(0, afterGap[27 * size + 28].toInt() and 255)
        stabilizer.apply(listOf(instance(100f, 0, 27)), 1.3)
        stabilizer.reset()
        val afterReset = stabilizer.apply(listOf(instance(104f, 0, 30)), 1.4)
        assertEquals(0, afterReset[27 * size + 28].toInt() and 255)
    }
}
