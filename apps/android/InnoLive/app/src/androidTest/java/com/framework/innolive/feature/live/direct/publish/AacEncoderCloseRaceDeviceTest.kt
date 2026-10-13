package com.framework.innolive.feature.live.direct.publish

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.framework.innolive.feature.live.direct.DirectPublishResolution
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** 음성 입력이 들어오는 중 인코더를 닫아도 인코더 스레드에서 예외가 나 앱이 종료되지 않는지 확인합니다. */
@RunWith(AndroidJUnit4::class)
class AacEncoderCloseRaceDeviceTest {
    @Test fun closingWhileQueuingPcmDoesNotThrowOnEncoderThread() {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (thread.name.startsWith("direct-publish-")) uncaught += error else previous?.uncaughtException(thread, error)
        }
        val profile = DirectPublishProfile.of(DirectPublishResolution.HD_720, PublishOrientation.LANDSCAPE)
        val output = object : EncoderOutput {
            override fun onVideoConfig(sps: ByteBuffer, pps: ByteBuffer) = Unit
            override fun onPacket(packet: EncodedPacket) = Unit
            override fun onError(error: Exception) = Unit
        }
        val chunk = ByteArray(profile.audioSampleRate / 100 * 2)
        try {
            repeat(40) { round ->
                val encoder = AacEncoder(profile, output)
                encoder.start()
                val feeding = AtomicBoolean(true)
                val feeder = Thread {
                    var ptsUs = 0L
                    while (feeding.get()) {
                        encoder.queuePcm(chunk, chunk.size, ptsUs)
                        ptsUs += 10_000
                    }
                }.apply { start() }
                Thread.sleep((round % 5 + 1) * 10L)
                encoder.close()
                feeding.set(false)
                feeder.join(1_000)
            }
            // 닫힌 뒤 예약된 drain까지 끝나도록 잠시 기다립니다.
            Thread.sleep(500)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        assertTrue("uncaught=${uncaught.map { it.javaClass.simpleName + ": " + it.message }}", uncaught.isEmpty())
    }
}
