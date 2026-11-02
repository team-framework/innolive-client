package com.framework.innolive.feature.live

import org.webrtc.VideoSink
import org.webrtc.VideoTrack

/** WebRTC의 Java sink 맵은 스레드 안전하지 않습니다. 캡처와 렌더러 그리기는 이 잠금을 쓰지 않습니다. */
internal object VideoTrackLifecycle {
    private val lock = Any()

    fun addSink(track: VideoTrack, sink: VideoSink): Boolean = synchronized(lock) {
        if (track.isDisposed) false else {
            track.addSink(sink)
            true
        }
    }

    fun removeSink(track: VideoTrack, sink: VideoSink) = synchronized(lock) {
        if (!track.isDisposed) track.removeSink(sink)
    }

    // PeerConnection.dispose()는 원격 미리보기가 쓰는 receiver·transceiver 트랙도 함께 해제합니다.
    fun dispose(action: () -> Unit) = synchronized(lock) { action() }
}
