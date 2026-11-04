import Foundation

extension WebRTCVideoUplink {
    /// 방송마다 녹화 sink는 하나이며, 카메라 relay를 다시 만들거나 재연결해도 유지한다.
    /// 세션 소유자는 recorder.stop()을 기다리기 전에 두 미디어 경로를 모두 분리한다.
    func setRecordingSink(_ recorder: LocalBroadcastRecorder?) {
        recordingSink = recorder
        recordingAudioTap.setRecordingSink(recorder)
        cameraFrameRelay?.setRecordingSink(recorder)
    }
}
