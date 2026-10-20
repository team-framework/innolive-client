package com.framework.innolive.feature.live.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class RecordingLatencyEstimatorTest {
    /** 장면 움직임을 흉내 낸 16×16 격자 시계열입니다. 시각(µs)마다 같은 장면을 돌려줍니다. */
    private class Scene(seed: Int) {
        private val random = Random(seed)
        private val frames = mutableListOf<FloatArray>()
        private var current = FloatArray(256) { random.nextFloat() * 255f }

        init {
            repeat(1_000) {
                // 움직임 크기가 시간에 따라 크게 바뀌어야 상관이 뚜렷합니다.
                val burst = if (random.nextFloat() < 0.15f) 30f else 2f
                current = FloatArray(256) { index -> (current[index] + (random.nextFloat() - 0.5f) * burst).coerceIn(0f, 255f) }
                frames += current
            }
        }

        fun at(timeUs: Long): FloatArray = frames[(timeUs / 33_333L).toInt().coerceIn(0, frames.lastIndex)]
    }

    @Test fun serverRoundTripDelayIsMeasuredFromMotion() {
        val scene = Scene(1)
        val estimator = RecordingLatencyEstimator()
        val delayUs = 430_000L
        val noise = Random(2)
        var estimate: Long? = null
        for (frame in 0 until 600) {
            val captureUs = frame * 33_333L
            estimator.addRaw(captureUs, scene.at(captureUs))
            // 서버가 일부 프레임을 버리고, 돌려받은 프레임은 왕복 지연 뒤 화면 시각을 가진다.
            if (frame % 7 != 3) {
                val processed = scene.at(captureUs).map { (it * 0.9f + 10f + (noise.nextFloat() - 0.5f) * 3f) }.toFloatArray()
                estimator.addProcessed(captureUs + delayUs + noise.nextLong(-3_000L, 3_000L), processed)
            }
            if (frame % 60 == 59) estimate = estimator.estimate() ?: estimate
        }
        val measured = checkNotNull(estimate)
        assertEquals(delayUs.toDouble(), measured.toDouble(), 20_000.0)
    }

    @Test fun staticSceneGivesNoEstimate() {
        val estimator = RecordingLatencyEstimator()
        val still = FloatArray(256) { 128f }
        for (frame in 0 until 300) {
            estimator.addRaw(frame * 33_333L, still)
            estimator.addProcessed(frame * 33_333L + 300_000L, still)
        }
        assertNull(estimator.estimate())
        assertNull(estimator.offsetUs)
    }
}
