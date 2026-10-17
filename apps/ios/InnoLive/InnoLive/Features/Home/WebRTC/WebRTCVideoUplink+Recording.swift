import Foundation

extension WebRTCVideoUplink {
    /// One recording sink per broadcast, retained across camera relay recreation/reconnection.
    /// The session owner detaches both media paths before awaiting recorder.stop().
    func setRecordingSink(_ recorder: LocalBroadcastRecorder?) {
        recordingSink = recorder
        recordingAudioTap.setRecordingSink(recorder)
        cameraFrameRelay?.setRecordingSink(recorder)
    }
}
