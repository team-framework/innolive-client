package com.framework.innolive.feature.live.privacy

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class PrivacyNativeSegmentationDeviceTest {
    private fun detection(box: PrivacySegmentation.Box, coefficients: FloatArray) =
        PrivacySegmentation.Detection(box,.9f,0,coefficients)

    @Test fun vectorAndScalarTailMatchKotlinBitForBitAtZeroThreshold() {
        val random = Random(320)
        val proto = FloatArray(32*160*160) { random.nextFloat()*2-1 }
        val weights = FloatArray(32) { random.nextFloat()*2-1 }
        // Cancellation and zero must preserve the >0 decision, with no fused multiply/add.
        for (c in 0 until 32) proto[c*160*160+5*160+5] = if(c%2==0)1f else -1f
        for (width in listOf(1,3,4,5,159,160)) {
            val objects = listOf(detection(PrivacySegmentation.Box(0f,0f,width*4f,640f), weights),
                detection(PrivacySegmentation.Box(0f,0f,width*4f,640f), FloatArray(32) { 1f }))
            val expected = PrivacySegmentation.instanceMasks(objects,proto)
            val actual = PrivacyNativeSegmentation.instanceMasks(objects,proto)
            expected.indices.forEach { assertArrayEquals("width=$width object=$it",expected[it].bytes,actual[it].bytes) }
        }
    }

    @Test fun multipleBoxesClippingAndEmptyMaskProtectionMatchReference() {
        val proto = FloatArray(32*160*160) { -1f }
        val weights = FloatArray(32) { 1f }
        val objects = listOf(
            detection(PrivacySegmentation.Box(17.2f,12.8f,120.4f,99.1f),weights),
            detection(PrivacySegmentation.Box(620f,620f,640f,640f),weights),
            detection(PrivacySegmentation.Box(8f,8f,8f,8f),weights))
        val expected = PrivacySegmentation.instanceMasks(objects,proto)
        val actual = PrivacyNativeSegmentation.instanceMasks(objects,proto)
        expected.indices.forEach { assertArrayEquals(expected[it].bytes,actual[it].bytes) }
        assertEquals(-1,actual.first().bytes[10*160+10].toInt())
        assertEquals(0,actual.last().bytes.count { it.toInt()!=0 })
    }

    @Test fun invalidNumbersFailEvenWhenNoObjectsNeedProtection() {
        val proto = FloatArray(32*160*160)
        proto[proto.lastIndex] = Float.NaN
        assertThrows(IllegalArgumentException::class.java) { PrivacyNativeSegmentation.instanceMasks(emptyList(),proto) }
        proto.fill(1f)
        val objects=listOf(detection(PrivacySegmentation.Box(0f,0f,640f,640f),FloatArray(32) { Float.MAX_VALUE }))
        assertThrows(IllegalArgumentException::class.java) { PrivacyNativeSegmentation.instanceMasks(objects,proto) }
    }

    @Test fun compareSameMasksAndLatencyWithoutCameraOrModelVariance() {
        val random=Random(321)
        val proto=FloatArray(32*160*160) { random.nextFloat()*2-1 }
        for (count in listOf(0,1,8)) {
            val objects=(0 until count).map { index ->
                val left=(index%4)*120f; val top=(index/4)*240f
                detection(PrivacySegmentation.Box(left,top,left+160f,top+240f),FloatArray(32) { random.nextFloat()*2-1 })
            }
            repeat(5) { PrivacySegmentation.instanceMasks(objects,proto,PrivacyNativePixels::finiteFloats); PrivacyNativeSegmentation.instanceMasks(objects,proto) }
            val oldTimes=mutableListOf<Double>(); val newTimes=mutableListOf<Double>()
            repeat(30) { iteration ->
                var expected: List<PrivacySegmentation.InstanceMask> = emptyList()
                var actual: List<PrivacySegmentation.InstanceMask> = emptyList()
                fun old() { val t=System.nanoTime(); expected=PrivacySegmentation.instanceMasks(objects,proto,PrivacyNativePixels::finiteFloats); oldTimes+=(System.nanoTime()-t)/1e6 }
                fun next() { val t=System.nanoTime(); actual=PrivacyNativeSegmentation.instanceMasks(objects,proto); newTimes+=(System.nanoTime()-t)/1e6 }
                if(iteration%2==0) { old(); next() } else { next(); old() }
                expected.indices.forEach { assertArrayEquals(expected[it].bytes,actual[it].bytes) }
            }
            Log.i("PrivacyStages", "stage=mask count=$count kotlin_p50_ms=${oldTimes.sorted()[15]} native_p50_ms=${newTimes.sorted()[15]} identical=true")
        }
    }
}
