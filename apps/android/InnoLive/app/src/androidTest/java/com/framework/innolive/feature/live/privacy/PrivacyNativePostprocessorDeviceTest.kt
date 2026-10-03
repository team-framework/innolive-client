package com.framework.innolive.feature.live.privacy

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class PrivacyNativePostprocessorDeviceTest {
    private fun predictions(count: Int, shift: Float = 0f): FloatArray = FloatArray(38 * 8400).apply {
        for (i in 0 until count) {
            this[i] = 32f + (i % 10) * 64 + shift
            this[8400 + i] = 32f + (i / 10) * 64
            this[2 * 8400 + i] = 45f
            this[3 * 8400 + i] = 45f
            this[(4 + i % 2) * 8400 + i] = .9f
            for (c in 0 until 32) this[(6 + c) * 8400 + i] = (c % 5 - 2) * .125f
        }
    }

    private fun set(processor: PrivacyNativePostprocessor, predictions: FloatArray, prototypes: FloatArray) {
        processor.predictions.asFloatBuffer().put(predictions)
        processor.prototypes.asFloatBuffer().put(prototypes)
    }

    @Test fun decodeAndTemporalMasksMatchReferenceAcrossMotionGapsExemptionsAndReset() {
        val random = Random(326)
        val proto = FloatArray(32 * 160 * 160) { random.nextFloat() * 2 - 1 }
        PrivacyNativePostprocessor().use { native ->
            val reference = PrivacyMaskStabilizer()
            for ((frame, time) in listOf(1.0,1.033,1.067,1.101,1.50,1.49,1.523,1.56).withIndex()) {
                val values = predictions(if (frame == 3) 0 else 8, frame * .37f)
                set(native, values, proto)
                val expected = PrivacySegmentation.detections(values)
                val actual = native.decode()
                assertEquals(expected.size, actual.size)
                expected.indices.forEach { i ->
                    assertEquals(expected[i].box, actual[i].box)
                    assertEquals(expected[i].classId, actual[i].classId)
                    assertEquals(expected[i].score, actual[i].score, 0f)
                    assertArrayEquals(expected[i].coefficients, actual[i].coefficients, 0f)
                }
                val exempt = if (frame == 1 || frame == 6) setOf(0,2) else emptySet()
                val expectedMask = reference.apply(PrivacyNativeSegmentation.instanceMasks(
                    PrivacySegmentation.protectedDetections(expected,exempt),proto),time)
                val actualMask = native.mask(exempt,time)
                assertArrayEquals("frame=$frame",expectedMask,actualMask)
                assertEquals(expectedMask.count { it.toInt() != 0 },native.maskPixels)
                if (frame == 6) { native.reset(); reference.reset() }
            }
        }
    }

    @Test fun nmsTiesClippingAndEmptyPrototypePreserveProtection() {
        val values = predictions(3).apply {
            this[1]=this[0];this[8401]=this[8400] // Different classes survive.
            this[2]=this[0];this[8402]=this[8400] // Equal-score same class is suppressed.
            this[0]=1.3f;this[1]=1.3f;this[2]=1.3f
        }
        val proto = FloatArray(32 * 160 * 160)
        PrivacyNativePostprocessor().use { native ->
            set(native,values,proto)
            val expected = PrivacySegmentation.detections(values)
            assertEquals(expected.map { it.box },native.decode().map { it.box })
            val mask = native.mask(emptySet(),1.0)
            assertArrayEquals(PrivacySegmentation.union(PrivacyNativeSegmentation.instanceMasks(expected,proto)),mask)
            assertTrue(native.maskPixels>0)
        }
    }

    @Test fun malformedOutputsAndInvalidStateCannotGrantAnExceptionOrReuseAnOldMask() {
        PrivacyNativePostprocessor().use { native ->
            val values=predictions(1);val proto=FloatArray(32*160*160)
            set(native,values,proto)
            native.decode();native.mask(emptySet(),1.0)
            assertThrows(IllegalStateException::class.java) {native.mask(emptySet(),1.033)}
            for (value in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY)) {
                proto[proto.lastIndex]=value;set(native,values,proto)
                assertThrows(IllegalArgumentException::class.java) {native.decode()}
                assertThrows(IllegalStateException::class.java) {native.mask(emptySet(),1.1)}
                proto[proto.lastIndex]=0f
                values[values.lastIndex]=value;set(native,values,proto)
                assertThrows(IllegalArgumentException::class.java) {native.decode()}
                values[values.lastIndex]=0f
            }
            values[2*8400]=-1f;set(native,values,proto)
            assertThrows(IllegalArgumentException::class.java) {native.decode()}
            val tooMany=predictions(101).apply { this[100]=62f;this[8400+100]=62f }
            set(native,tooMany,proto)
            // Add one more non-overlapping class/box than the maximum.
            assertThrows(IllegalArgumentException::class.java) {native.decode()}
        }
    }

    @Test fun retainedJavaMaskIsIndependentOfTheNextNativeFrame() {
        PrivacyNativePostprocessor().use { native ->
            val proto=FloatArray(32*160*160)
            set(native,predictions(8),proto);native.decode()
            val first=native.mask(emptySet(),1.0);val saved=first.copyOf()
            repeat(10) {i ->
                set(native,predictions(if(i%2==0)0 else 8,i.toFloat()),proto)
                native.decode();native.mask(emptySet(),1.033+i*.033)
            }
            assertArrayEquals(saved,first)
            native.reset();set(native,predictions(0),proto);native.decode()
            assertEquals(0,native.mask(emptySet(),2.0).count {it.toInt()!=0})
        }
    }

    @Test fun compareCompletePostprocessingBundleWithSameModelOutputs() {
        val random=Random(327)
        val proto=FloatArray(32*160*160) {random.nextFloat()*2-1}
        for (count in listOf(0,1,8)) PrivacyNativePostprocessor().use {native ->
            val reference=PrivacyMaskStabilizer()
            val old=ArrayList<Double>();val next=ArrayList<Double>()
            repeat(45) {sample ->
                val values=predictions(count,(sample%5)*.37f)
                set(native,values,proto)
                var before=ByteArray(0);var after=ByteArray(0)
                fun baseline() {
                    val start=System.nanoTime()
                    assertTrue(PrivacyNativePixels.finiteFloats(values))
                    val checked=PrivacyValidatedPrototypes.validate(proto)
                    val objects=PrivacySegmentation.detections(values)
                    before=reference.apply(PrivacyNativeSegmentation.instanceMasks(objects,checked),1.0+sample*.033)
                    if(sample>=5) old+=(System.nanoTime()-start)/1e6
                }
                fun optimized() {
                    val start=System.nanoTime();native.decode()
                    after=native.mask(emptySet(),1.0+sample*.033)
                    if(sample>=5) next+=(System.nanoTime()-start)/1e6
                }
                if(sample%2==0) {baseline();optimized()} else {optimized();baseline()}
                assertArrayEquals("count=$count sample=$sample",before,after)
            }
            fun median(samples:List<Double>)=samples.sorted()[samples.size/2]
            Log.i("PrivacyBatch","batch=1 stage=postprocess objects=$count baseline_p50_ms=${median(old)} optimized_p50_ms=${median(next)} identical=true")
        }
    }
}
