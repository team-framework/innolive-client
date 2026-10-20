package com.framework.innolive.feature.live.direct.broadcast

import android.media.AudioDeviceInfo
import com.framework.innolive.feature.live.AnonymizationFailure
import com.framework.innolive.feature.live.AnonymizationState
import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.ChzzkBroadcastSettings
import com.framework.innolive.feature.live.ConnectionFailure
import com.framework.innolive.feature.live.LiveBroadcastCallbacks
import com.framework.innolive.feature.live.WebRtcConnectionState
import com.framework.innolive.feature.live.direct.publish.DirectPublishEngine
import com.framework.innolive.feature.live.direct.publish.DirectPublishProfile
import com.framework.innolive.feature.live.direct.publish.DirectPublisher
import com.framework.innolive.feature.live.direct.publish.PublishOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDevicePreviewTransportTest {
    private val scheduler = ManualScheduler()
    private val media = FakeMedia()
    private val connections = mutableListOf<Pair<WebRtcConnectionState, ConnectionFailure?>>()
    private val anonymizationConfirmations = mutableListOf<AnonymizationState>()
    private var signalingStarted = 0

    private fun transport(anonymizationEnabled: Boolean = true) = OnDevicePreviewTransport(
        callbacks = LiveBroadcastCallbacks(
            onStateChanged = { state, failure -> connections += state to failure },
            onRemoteTrackChanged = {},
            onLocalMediaReady = { _, _ -> },
            onLocalMediaCleared = {},
            onBroadcastStateChanged = { _, _ -> },
            onAnonymizationStateConfirmed = { anonymizationConfirmations += it },
            onLocalVideoTrackChanged = {},
            onInitialSignalingStarted = { signalingStarted++ },
            onSessionSnapshotChanged = {},
            onServerError = {},
            onUplinkVideoStats = {},
        ),
        media = media,
        initialAnonymizationEnabled = anonymizationEnabled,
        owner = scheduler,
        callbackExecutor = { it.run() },
    )

    @Test
    fun `첫 가림 처리 프레임이 나오면 서버 없이 연결됨을 알린다`() {
        val preview = transport()

        preview.start()
        scheduler.runUntilIdle()
        assertEquals(listOf(WebRtcConnectionState.CONNECTING to null), connections)
        assertEquals(1, signalingStarted)
        assertTrue(media.opened)

        media.events.onFirstFrame()
        scheduler.runUntilIdle()

        assertEquals(WebRtcConnectionState.CONNECTED to null, connections.last())
        assertEquals(listOf(AnonymizationState.ENABLED), anonymizationConfirmations)
    }

    @Test
    fun `가림 처리를 끈 미리보기는 꺼짐을 확인값으로 알린다`() {
        val preview = transport(anonymizationEnabled = false)
        preview.start()
        scheduler.runUntilIdle()

        media.events.onFirstFrame()
        scheduler.runUntilIdle()

        assertEquals(listOf(AnonymizationState.DISABLED), anonymizationConfirmations)
    }

    @Test
    fun `첫 프레임 전 가림 처리 실패는 연결 실패로 알리고 카메라를 닫는다`() {
        val preview = transport()
        preview.start()
        scheduler.runUntilIdle()

        media.events.onProcessingFailure()
        scheduler.runUntilIdle()

        assertEquals(WebRtcConnectionState.FAILED to ConnectionFailure.GENERIC, connections.last())
        assertTrue(media.closed)
        media.events.onFirstFrame()
        scheduler.runUntilIdle()
        assertFalse(connections.any { it.first == WebRtcConnectionState.CONNECTED })
    }

    @Test
    fun `연결 뒤 가림 처리 실패는 미리보기를 유지한다`() {
        val preview = transport()
        preview.start()
        scheduler.runUntilIdle()
        media.events.onFirstFrame()
        scheduler.runUntilIdle()

        media.events.onProcessingFailure()
        scheduler.runUntilIdle()

        assertEquals(WebRtcConnectionState.CONNECTED to null, connections.last())
        assertFalse(media.closed)
    }

    @Test
    fun `카메라를 열지 못하면 연결 실패로 알린다`() {
        media.failOpen = true
        val preview = transport()

        preview.start()
        scheduler.runUntilIdle()

        assertEquals(WebRtcConnectionState.FAILED to ConnectionFailure.GENERIC, connections.last())
        assertTrue(media.closed)
    }

    @Test
    fun `비식별화 전환은 앱 가림 처리만 바꾸고 확인값을 돌려준다`() {
        val preview = transport()
        preview.start()
        scheduler.runUntilIdle()
        media.events.onFirstFrame()
        scheduler.runUntilIdle()
        var result: Pair<AnonymizationState?, AnonymizationFailure?>? = null

        preview.setAnonymizationEnabled(false) { state, failure -> result = state to failure }
        scheduler.runUntilIdle()

        assertEquals(false, media.anonymization)
        assertEquals(AnonymizationState.DISABLED to null, result)
        assertEquals(AnonymizationState.DISABLED, anonymizationConfirmations.last())
    }

    @Test
    fun `비식별화 전환 실패는 요청 실패로 돌려준다`() {
        val preview = transport()
        preview.start()
        scheduler.runUntilIdle()
        media.failAnonymization = true
        var result: Pair<AnonymizationState?, AnonymizationFailure?>? = null

        preview.setAnonymizationEnabled(false) { state, failure -> result = state to failure }
        scheduler.runUntilIdle()

        assertEquals(null to AnonymizationFailure.REQUEST, result)
    }

    @Test
    fun `방송 준비와 라이브 요청은 받지 않는다`() {
        val preview = transport()
        preview.start()
        scheduler.runUntilIdle()
        media.events.onFirstFrame()
        scheduler.runUntilIdle()
        val settings = BroadcastSettings("제목", "설명", "private", false, "22")
        val chzzk = ChzzkBroadcastSettings("치지직", "GAME", "game-1", emptyList())

        assertFalse(preview.prepareBroadcast(settings, false))
        assertFalse(preview.prepareBroadcast(chzzk))
        assertFalse(preview.prepareTargets(settings, chzzk, false))
        assertFalse(preview.goLive {})
        assertFalse(media.engineCreated)
    }

    @Test
    fun `닫으면 카메라를 한 번만 닫고 완료를 알린 뒤 콜백을 보내지 않는다`() {
        val preview = transport()
        preview.start()
        scheduler.runUntilIdle()
        var completed = 0

        preview.close { completed++ }
        preview.close { completed++ }
        scheduler.runUntilIdle()
        media.events.onFirstFrame()
        scheduler.runUntilIdle()

        assertEquals(1, media.closeCount)
        assertEquals(2, completed)
        assertFalse(connections.any { it.first == WebRtcConnectionState.CONNECTED })
    }

    @Test
    fun `열기 전에 닫으면 카메라를 열지 않는다`() {
        val preview = transport()
        preview.start()
        preview.close(null)

        scheduler.runUntilIdle()

        assertFalse(media.opened)
    }

    private class FakeMedia : DirectMediaPipeline {
        lateinit var events: DirectMediaEvents
        var opened = false
        var failOpen = false
        var anonymization: Boolean? = null
        var failAnonymization = false
        var engineCreated = false
        var closeCount = 0
        val closed: Boolean get() = closeCount > 0

        override fun open(events: DirectMediaEvents) {
            this.events = events
            if (failOpen) throw IllegalStateException("카메라를 열지 못했습니다.")
            opened = true
        }

        override val frameOrientation: PublishOrientation = PublishOrientation.PORTRAIT
        override val anonymizationFailedFrames: Long = 0

        override fun setAnonymizationEnabled(enabled: Boolean) {
            if (failAnonymization) throw IllegalStateException("가림 처리 전환 실패")
            anonymization = enabled
        }

        override fun setPreferredAudioInput(device: AudioDeviceInfo?) = Unit

        override fun createEngine(profile: DirectPublishProfile, listener: DirectPublisher.Listener): DirectPublishEngine {
            engineCreated = true
            error("미리보기는 송출 엔진을 만들지 않습니다.")
        }

        override fun close() {
            closeCount++
        }
    }
}
