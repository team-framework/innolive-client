import Foundation
@preconcurrency import LiveKitWebRTC

extension WebRTCVideoUplink {
    var videoSendPolicy: VideoUplinkSendPolicy? {
        credentials.flatMap { VideoUplinkSendPolicy.forSession($0.uplinkSessionMode, resolution: $0.broadcastResolution) }
    }

    /// offer 전에 부른다. 재연결도 `preparePeerConnection`으로 새 PeerConnection을 만들므로 같은 경로로 다시 적용된다.
    func applyVideoSendPolicy(startingConnection: Bool) {
        applyVideoSendParameters()
        let bandwidthEstimateApplied = applyBandwidthEstimate(startingConnection: startingConnection)
        #if DEBUG
        logVideoSendPolicy(bandwidthEstimateApplied: bandwidthEstimateApplied)
        #endif
    }

    /// 방송 중 송출 해상도가 바뀌면 같은 연결에 새 범위를 적용한다.
    func setBroadcastResolution(_ resolution: VideoUplinkResolution) {
        guard credentials != nil, credentials?.broadcastResolution != resolution else { return }
        credentials?.broadcastResolution = resolution
        applyVideoSendPolicy(startingConnection: false)
    }

    func applyVideoSendParameters() {
        guard let videoSender else { return }
        let parameters = videoSender.parameters
        VideoUplinkSendPolicy.configure(parameters, policy: videoSendPolicy, directPublishLimited: isDirectPublishBitrateLimited)
        videoSender.parameters = parameters
    }

    /// 추정기 시작값은 연결을 시작할 때만 준다. 방송 중에는 상한만 바꿔 쌓인 추정치를 버리지 않는다.
    @discardableResult
    func applyBandwidthEstimate(startingConnection: Bool) -> Bool? {
        guard let peerConnection, let policy = videoSendPolicy, !isDirectPublishBitrateLimited else { return nil }
        return peerConnection.setBweMinBitrateBps(
            nil,
            currentBitrateBps: startingConnection ? NSNumber(value: policy.startBitrateBps) : nil,
            maxBitrateBps: NSNumber(value: policy.maxBitrateBps)
        )
    }

    #if DEBUG
    /// sender가 실제로 받아들인 값을 다시 읽어 남긴다.
    private func logVideoSendPolicy(bandwidthEstimateApplied: Bool?) {
        guard let videoSender, let credentials else { return }
        let parameters = videoSender.parameters
        let encoding = parameters.encodings.first
        func text(_ value: NSNumber?) -> String { value.map { "\($0)" } ?? "-" }
        let policy = videoSendPolicy
        diagnostics.noteSendPolicy(
            "uplink send policy mode=\(credentials.uplinkSessionMode) resolution=\(credentials.broadcastResolution) "
                + "directLimited=\(isDirectPublishBitrateLimited) startBps=\(policy.map { "\($0.startBitrateBps)" } ?? "-") "
                + "bweApplied=\(bandwidthEstimateApplied.map { "\($0)" } ?? "-") "
                + "degradation=\(text(parameters.degradationPreference)) minBps=\(text(encoding?.minBitrateBps)) "
                + "maxBps=\(text(encoding?.maxBitrateBps)) maxFps=\(text(encoding?.maxFramerate)) "
                + "scale=\(text(encoding?.scaleResolutionDownBy))"
        )
    }
    #endif
}
