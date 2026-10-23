package com.framework.innolive.feature.live.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyBroadcastBlurTest {
    @Test fun sigmaFollowsShortSideWithMinimumLikeIos() {
        assertEquals(72, PrivacyBroadcastBlur.sigma(1920, 1080))
        assertEquals(72, PrivacyBroadcastBlur.sigma(1080, 1920))
        assertEquals(48, PrivacyBroadcastBlur.sigma(1280, 720))
        assertEquals(48, PrivacyBroadcastBlur.sigma(640, 360))
        assertEquals(48, PrivacyBroadcastBlur.sigma(1, 1))
        assertEquals(96, PrivacyBroadcastBlur.sigma(2560, 1440))
    }

    @Test fun strengthScalesFromTheDefaultSigmaAndNeverGoesWeaker() {
        assertEquals(72, PrivacyBroadcastBlur.sigma(1920, 1080, PrivacyBroadcastBlur.DEFAULT_STRENGTH))
        assertEquals(108, PrivacyBroadcastBlur.sigma(1920, 1080, 36))
        assertEquals(144, PrivacyBroadcastBlur.sigma(1920, 1080, PrivacyBroadcastBlur.MAX_STRENGTH))
        assertEquals(96, PrivacyBroadcastBlur.sigma(1280, 720, PrivacyBroadcastBlur.MAX_STRENGTH))
        // 영상 조절 하한보다 약한 값이나 상한을 넘는 값은 범위 안으로 맞춥니다.
        assertEquals(72, PrivacyBroadcastBlur.sigma(1920, 1080, 1))
        assertEquals(144, PrivacyBroadcastBlur.sigma(1920, 1080, 64))
    }

    @Test fun reducedSigmaAndSizeMatchTheQuarterResolutionGraph() {
        assertEquals(18.0, PrivacyBroadcastBlur.reducedSigma(PrivacyBroadcastBlur.sigma(1920, 1080)), 0.0)
        assertEquals(12.0, PrivacyBroadcastBlur.reducedSigma(PrivacyBroadcastBlur.sigma(1280, 720)), 0.0)
        assertEquals(480, PrivacyBroadcastBlur.reducedSize(1920))
        assertEquals(270, PrivacyBroadcastBlur.reducedSize(1080))
        assertEquals(1, PrivacyBroadcastBlur.reducedSize(1))
        assertEquals(2, PrivacyBroadcastBlur.reducedSize(5))
        assertEquals(54, PrivacyBroadcastBlur.kernelRadius(18.0))
        assertEquals(5, PrivacyBroadcastBlur.kernelRadius(1.5))
    }

    @Test fun renderEffectRadiusConvertsBackToTheFullResolutionSigma() {
        for ((width, height) in listOf(1920 to 1080, 1280 to 720)) {
            for (strength in listOf(PrivacyBroadcastBlur.DEFAULT_STRENGTH, PrivacyBroadcastBlur.MAX_STRENGTH)) {
                val sigma = PrivacyBroadcastBlur.sigma(width, height, strength)
                val radius = PrivacyBroadcastBlur.renderEffectRadius(sigma)
                assertEquals(sigma.toFloat(), 0.57735f * radius + 0.5f, 0.01f)
            }
        }
    }

    @Test fun pairedShaderCoversEveryTapOnceAndNormalizesWeights() {
        val shader = PrivacyBroadcastBlur.pairedGaussianShader(54, 18.0)
        assertEquals(1 + 2 * 27, Regex("texture2D\\(").findAll(shader).count())
        val weights = Regex("\\)\\*(\\d+\\.\\d+);").findAll(shader).map { it.groupValues[1].toDouble() }.toList()
        val centre = Regex("uv\\)\\*(\\d+\\.\\d+);").find(shader)!!.groupValues[1].toDouble()
        val scale = Regex("value\\*(\\d+\\.\\d+);").find(shader)!!.groupValues[1].toDouble()
        assertEquals(1.0, (centre + 2 * (weights.sum() - centre)) * scale, 1e-6)
        assertFalse(shader.contains("tex1"))
        assertTrue(PrivacyBroadcastBlur.pairedGaussianShader(5, 1.5, maxCore = true).contains("max(blurred,texture2D(tex1,uv))"))
    }

    @Test fun loopShaderUsesConstantBoundsForTheRequestedRadius() {
        val shader = PrivacyBroadcastBlur.loopGaussianShader(36, 12.0)
        assertTrue(shader.contains("for(int i=-36;i<=36;i++)"))
        assertTrue(shader.contains("/288.000000000"))
    }
}
