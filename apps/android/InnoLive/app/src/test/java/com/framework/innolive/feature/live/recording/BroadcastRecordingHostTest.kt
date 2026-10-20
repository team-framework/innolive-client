package com.framework.innolive.feature.live.recording

import com.framework.innolive.feature.live.CameraFrameAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.webrtc.EglBase
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class BroadcastRecordingHostTest {
    @get:Rule val folder = TemporaryFolder()

    /** 프레임이 오지 않으므로 인코더를 만들지 않습니다. 저장은 영상 없음으로 실패합니다. */
    private object UnusedMedia : RecordingMediaFactory {
        override fun createVideo(name: String, size: RecordingSize, bitrateBps: Int, output: RecordingVideoOutput) =
            error("프레임 없이 영상 인코더를 만들지 않는다")
        override fun createAudio(output: RecordingAudioOutput) = error("음성 없이 음성 인코더를 만들지 않는다")
    }

    private val posted = LinkedBlockingQueue<() -> Unit>()
    private val states = mutableListOf<BroadcastRecordingStatus>()
    private var leases = 0

    private fun host() = BroadcastRecordingHost(
        store = BroadcastRecordingStore(folder.newFolder("recordings")),
        onStateChanged = { states += it.status },
        keepAlive = { leases++; AutoCloseable { leases-- } },
        minimumFreeBytes = 0L,
        mediaFactory = { UnusedMedia },
        postToMain = { posted.put(it) },
    )

    private fun media() = RecordingMediaBindings(
        frameAnalyzer = CameraFrameAnalyzer(),
        eglContext = EglBase.Context { 0L },
        remoteVideoTrack = null,
        onDeviceProcessing = true,
        transport = null,
        sessionId = "s",
        provider = "youtube",
    )

    /** 저장 스레드가 메인 스레드로 넘긴 결과를 실행합니다. */
    private fun runSaveResult() = checkNotNull(posted.poll(5, TimeUnit.SECONDS)) { "저장 결과가 오지 않았다" }.invoke()

    @Test fun saveResultIsShownWhenNoNewRecordingStarted() {
        val host = host()
        host.begin(media())
        host.confirm()
        host.finish(save = true)
        runSaveResult()
        assertEquals(BroadcastRecordingStatus.FAILED, states.last())
        assertEquals(0, leases)
    }

    @Test fun previousSaveResultDoesNotOverwriteTheNewRecording() {
        val host = host()
        host.begin(media())
        host.confirm()
        host.finish(save = true)
        host.begin(media())
        assertEquals(BroadcastRecordingStatus.RECORDING, states.last())
        // 이전 녹화의 저장 실패가 늦게 와도 새 녹화 상태를 덮지 않고 저장 유지만 푼다.
        runSaveResult()
        assertEquals(BroadcastRecordingStatus.RECORDING, states.last())
        assertEquals(0, leases)
        host.finish(save = false)
    }
}
