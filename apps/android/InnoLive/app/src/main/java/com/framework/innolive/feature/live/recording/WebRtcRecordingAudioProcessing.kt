package com.framework.innolive.feature.live.recording

import android.util.Log
import org.webrtc.ExternalAudioProcessingFactory

/**
 * 녹화 음성 후처리 팩토리를 프로세스에서 하나만 씁니다.
 *
 * webrtc-sdk(m144)의 [ExternalAudioProcessingFactory]는 생성자가 프로세스 전역 음성 처리기를 돌려주고,
 * `destroy()`는 그 전역 처리기를 지운 뒤 포인터를 비우지 않습니다. 연결마다 만들고 해제하면 두 번째 연결부터
 * 해제된 메모리를 써 네이티브 크래시가 납니다(#708). 그래서 한 번 만든 팩토리를 모든 연결이 함께 쓰고 해제하지
 * 않습니다. PeerConnectionFactory는 처리기 참조를 따로 잡고 놓으므로 연결 종료와 상관없이 처리기가 남습니다.
 */
internal object WebRtcRecordingAudioProcessing {
    private val tap = WebRtcRecordingAudioTap()
    private val sinks = RecordingAudioSinkOwnership(tap)
    private var created: ExternalAudioProcessingFactory? = null

    /** 네이티브 라이브러리를 읽은 뒤 부릅니다. 만들지 못하면 null이며, 다음 연결에서 다시 시도합니다. */
    @Synchronized
    fun factory(): ExternalAudioProcessingFactory? = created ?: runCatching {
        ExternalAudioProcessingFactory().also { it.setCapturePostProcessing(tap) }
    }.onFailure { Log.w("LiveConnection", "recording_audio_tap_unavailable type=${it.javaClass.simpleName}") }
        .getOrNull()
        .also { created = it }

    /** [owner] 연결의 녹화 입력을 붙이거나 뗍니다. 다른 연결이 이미 바꿔 붙인 입력은 떼지 않습니다. */
    fun setSink(owner: Any, sink: RecordingAudioSink?) = sinks.set(owner, sink)
}

/**
 * 연결이 겹쳐도 지금 연결의 녹화 입력만 남깁니다. 이전 연결이 늦게 해제되며 입력을 떼더라도 새 연결이 붙인
 * 입력은 그대로 둡니다.
 */
internal class RecordingAudioSinkOwnership(private val tap: WebRtcRecordingAudioTap) {
    private var owner: Any? = null

    @Synchronized
    fun set(owner: Any, sink: RecordingAudioSink?) {
        if (sink != null) {
            this.owner = owner
            tap.sink = sink
        } else if (this.owner === owner) {
            this.owner = null
            tap.sink = null
        }
    }
}
