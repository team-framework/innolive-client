import Foundation
@preconcurrency import LiveKitWebRTC

extension WebRTCVideoUplink {
    /// 온디바이스 세션의 앱 직접 송출 대상을 연결한다(#481).
    func setDirectMediaSink(_ sink: (any DirectMediaSink)?) {
        directMediaSink = sink
        cameraFrameRelay?.setDirectMediaSink(sink)
    }

    /// 앱이 플랫폼으로 직접 보내는 동안 서버 WebRTC 영상은 세션 유지와 입력 확인에만 쓴다.
    /// 업로드가 두 배가 되지 않도록 서버 쪽 영상 비트레이트를 낮춘다.
    func setDirectPublishBitrateLimited(_ limited: Bool) {
        isDirectPublishBitrateLimited = limited
        applyDirectPublishBitrateLimit()
    }

    func applyDirectPublishBitrateLimit() {
        guard let videoSender else { return }
        let parameters = videoSender.parameters
        for encoding in parameters.encodings {
            encoding.maxBitrateBps = isDirectPublishBitrateLimited
                ? NSNumber(value: 300_000)
                : uplinkBitratePolicy.map { NSNumber(value: $0.maxBps) }
            encoding.maxFramerate = isDirectPublishBitrateLimited ? NSNumber(value: 15) : nil
        }
        videoSender.parameters = parameters
    }
}
