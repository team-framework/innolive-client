package com.framework.innolive.feature.live.direct.publish

import com.framework.innolive.feature.live.direct.DirectPublishResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectAudioInputTest {
    private val profile = DirectPublishProfile.of(DirectPublishResolution.HD_720, PublishOrientation.LANDSCAPE)
    private val chunk = profile.audioSampleRate / 100 * 2 // 10ms, mono 16bit
    private var nowUs = 1_000_000L
    private val received = mutableListOf<Triple<ByteArray, Int, Long>>()
    private val states = mutableListOf<MicrophoneState>()
    private var mic: FakeMic? = null
    private var micFailure: Exception? = null

    private inner class FakeMic : PcmSource {
        lateinit var sink: PcmSink
        var closed = false
        lateinit var onError: (Exception) -> Unit
        lateinit var onRoute: (Int?, Boolean) -> Unit
        override fun start(sink: PcmSink) {
            this.sink = sink
        }
        override fun close() {
            closed = true
        }
    }

    private fun input(): DirectAudioInput = DirectAudioInput(profile, { _, onError, onRoute ->
        micFailure?.let { throw it }
        FakeMic().also {
            it.onError = onError
            it.onRoute = onRoute
            mic = it
        }
    }, null, states::add, nowUs = { nowUs }, keeperFactory = null).also { input ->
        input.start { data, size, time -> received += Triple(data.copyOf(size), size, time) }
    }

    @Test fun microphonePcmPassesThroughWithIncreasingTime() {
        input()
        mic!!.sink.onPcm(ByteArray(chunk) { 1 }, chunk, nowUs)
        mic!!.sink.onPcm(ByteArray(chunk) { 2 }, chunk, nowUs + 9_000) // 지터로 1ms 겹침
        assertEquals(2, received.size)
        assertEquals(nowUs + 10_000, received[1].third)
        assertEquals(listOf(MicrophoneState.OK), states)
    }

    @Test fun mutedMicrophoneSendsSilenceOfSameLength() {
        val input = input()
        input.setMuted(true)
        mic!!.sink.onPcm(ByteArray(chunk) { 7 }, chunk, nowUs)
        assertEquals(chunk, received.single().second)
        assertTrue(received.single().first.all { it == 0.toByte() })
    }

    @Test fun unavailableMicrophonePublishesSilence() {
        micFailure = SecurityException("권한 없음")
        val input = input()
        assertEquals(listOf(MicrophoneState.UNAVAILABLE), states)
        nowUs += 500_000
        input.fillSilence()
        assertEquals(50, received.size)
        assertTrue(received.all { (data, _, _) -> data.all { it == 0.toByte() } })
        assertTrue(received.zipWithNext().all { (a, b) -> b.third > a.third })
    }

    @Test fun silenceIsNotAddedWhileMicrophoneIsFresh() {
        val input = input()
        nowUs += 100_000
        mic!!.sink.onPcm(ByteArray(chunk), chunk, nowUs - 10_000)
        input.fillSilence()
        assertEquals(1, received.size)
    }

    @Test fun microphoneErrorAndSilencedRouteAreReported() {
        input()
        mic!!.onRoute(3, true)
        assertEquals(MicrophoneState.SILENCED, states.last())
        mic!!.onError(IllegalStateException("읽기 실패"))
        assertEquals(MicrophoneState.UNAVAILABLE, states.last())
        assertTrue(mic!!.closed)
    }

    @Test fun deviceChangeReopensMicrophoneAndDropsOldOutput() {
        val input = input()
        val old = mic!!
        input.setPreferredDevice(null)
        assertTrue(old.closed)
        old.sink.onPcm(ByteArray(chunk), chunk, nowUs)
        assertTrue(received.isEmpty())
        mic!!.sink.onPcm(ByteArray(chunk), chunk, nowUs)
        assertEquals(1, received.size)
    }
}
