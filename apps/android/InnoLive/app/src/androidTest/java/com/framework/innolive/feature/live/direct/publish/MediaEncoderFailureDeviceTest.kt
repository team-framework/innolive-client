package com.framework.innolive.feature.live.direct.publish

import android.media.MediaCodec
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.framework.innolive.feature.live.direct.DirectPublishResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/** 인코더 구성이 실패해도 생성자가 시작한 스레드와 코덱을 남기지 않는지 확인합니다. */
@RunWith(AndroidJUnit4::class)
class MediaEncoderFailureDeviceTest {
    private val output = object : EncoderOutput {
        override fun onVideoConfig(sps: ByteBuffer, pps: ByteBuffer) = Unit
        override fun onPacket(packet: EncodedPacket) = Unit
        override fun onError(error: Exception) = Unit
    }

    private fun encoderThreads(): Int =
        Thread.getAllStackTraces().keys.count { it.isAlive && it.name.startsWith("direct-publish-") }

    @Test fun failedVideoConfigurationReleasesThreadAndCodec() {
        // 영상 형식을 오디오 인코더에 구성해 기본 프로파일 재시도까지 실패시킵니다.
        val profile = DirectPublishProfile.of(DirectPublishResolution.HD_720, PublishOrientation.LANDSCAPE)
        val audioCodec = { _: String -> MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC) }
        val before = encoderThreads()
        repeat(2) {
            val error = runCatching { H264SurfaceEncoder(profile, output, audioCodec) }.exceptionOrNull()
            assertTrue("구성이 실패해야 합니다.", error != null)
        }
        // HandlerThread.quitSafely는 비동기라 잠시 기다립니다.
        val deadline = System.nanoTime() + 2_000_000_000L
        while (encoderThreads() > before && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(before, encoderThreads())
    }
}
