package com.framework.innolive.feature.live

import org.webrtc.VideoSink
import org.webrtc.VideoTrack

/** WebRTC's Java sink map is not thread-safe; capture and renderer drawing do not take this lock. */
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

    // PeerConnection.dispose() also disposes receiver/transceiver tracks used by the remote preview.
    fun dispose(action: () -> Unit) = synchronized(lock) { action() }
}
