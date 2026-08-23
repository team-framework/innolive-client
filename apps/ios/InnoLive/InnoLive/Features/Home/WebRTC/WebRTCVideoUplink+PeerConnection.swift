import Foundation
@preconcurrency import LiveKitWebRTC

extension WebRTCVideoUplink {
    func preparePeerConnection(iceServers: [WebRTCIceServer]) throws {
        let configuration = LKRTCConfiguration()
        configuration.sdpSemantics = .unifiedPlan
        configuration.continualGatheringPolicy = .gatherContinually
        configuration.iceServers = iceServers.map {
            LKRTCIceServer(urlStrings: $0.urls, username: $0.username, credential: $0.credential)
        }
        let constraints = LKRTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let peerConnection = peerConnectionFactory.peerConnection(
            with: configuration,
            constraints: constraints,
            delegate: self
        ) else {
            throw WebRTCVideoUplinkError.failed("WebRTC 영상 연결을 만들지 못했습니다.")
        }
        guard let localVideoTrack else {
            throw WebRTCVideoUplinkError.failed("카메라 영상 트랙을 만들지 못했습니다.")
        }
        guard let localAudioTrack else {
            throw WebRTCVideoUplinkError.failed("마이크 오디오 트랙을 만들지 못했습니다.")
        }

        let audioTransceiverConfiguration = LKRTCRtpTransceiverInit()
        audioTransceiverConfiguration.direction = .sendOnly
        audioTransceiverConfiguration.streamIds = ["innolive-audio-stream"]
        guard let audioTransceiver = peerConnection.addTransceiver(
            with: localAudioTrack,
            init: audioTransceiverConfiguration
        ) else {
            throw WebRTCVideoUplinkError.failed("마이크 오디오 송신기를 만들지 못했습니다.")
        }

        let audioCodecs = peerConnectionFactory
            .rtpSenderCapabilities(forKind: kLKRTCMediaStreamTrackKindAudio)
            .codecs
        let opusCodecs = audioCodecs.filter { $0.name.caseInsensitiveCompare("opus") == .orderedSame }
        guard !opusCodecs.isEmpty else {
            throw WebRTCVideoUplinkError.failed("서버와 호환되는 Opus 오디오 코덱을 찾지 못했습니다.")
        }
        do {
            try audioTransceiver.setCodecPreferences(
                opusCodecs,
                error: ()
            )
        } catch {
            throw WebRTCVideoUplinkError.failed("서버 호환 오디오 코덱을 설정하지 못했습니다.")
        }

        let transceiverConfiguration = LKRTCRtpTransceiverInit()
        transceiverConfiguration.direction = .sendRecv
        transceiverConfiguration.streamIds = ["innolive-camera-stream"]
        guard let transceiver = peerConnection.addTransceiver(
            with: localVideoTrack,
            init: transceiverConfiguration
        ) else {
            throw WebRTCVideoUplinkError.failed("카메라 영상 송신기를 만들지 못했습니다.")
        }

        // 프로덕션 Pion 서버가 실제 RTP 수신까지 검증한 VP8을 첫 번째로 제안한다.
        // iOS WebRTC의 기본 H.264 High Profile은 서버에 등록된 Baseline Profile과
        // 일치하지 않아 ICE 연결 후에도 서버 OnTrack이 호출되지 않는다.
        let videoCodecs = peerConnectionFactory
            .rtpSenderCapabilities(forKind: kLKRTCMediaStreamTrackKindVideo)
            .codecs
        let vp8Codecs = videoCodecs.filter { $0.name.caseInsensitiveCompare("VP8") == .orderedSame }
        guard !vp8Codecs.isEmpty else {
            throw WebRTCVideoUplinkError.failed("서버와 호환되는 VP8 영상 코덱을 찾지 못했습니다.")
        }
        do {
            try transceiver.setCodecPreferences(
                vp8Codecs + videoCodecs.filter { $0.name.caseInsensitiveCompare("VP8") != .orderedSame },
                error: ()
            )
        } catch {
            throw WebRTCVideoUplinkError.failed("서버 호환 영상 코덱을 설정하지 못했습니다.")
        }

        self.peerConnection = peerConnection
        audioSender = audioTransceiver.sender
        videoSender = transceiver.sender
    }

    func createAndSendOffer() {
        guard let peerConnection else {
            fail("WebRTC 영상 연결이 준비되지 않았습니다.")
            return
        }
        updateState(.connecting, "카메라 영상을 서버에 연결하는 중…")
        let constraints = LKRTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        peerConnection.offer(for: constraints) { [weak self] description, error in
            Task { @MainActor [weak self] in
                guard let self, !self.isStopping else { return }
                if let error {
                    self.fail("카메라 영상의 WebRTC offer를 만들지 못했습니다: \(error.localizedDescription)")
                    return
                }
                guard let description else {
                    self.fail("카메라 영상의 WebRTC offer가 비어 있습니다.")
                    return
                }
                do {
                    try await peerConnection.setLocalDescription(description)
                    self.sendOffer(description.sdp)
                } catch {
                    self.fail("카메라 영상의 WebRTC offer를 적용하지 못했습니다: \(error.localizedDescription)")
                }
            }
        }
    }

    private func verifyOutboundVideo() {
        guard outboundVerificationTask == nil,
              let peerConnection,
              let videoSender else { return }

        outboundVerificationTask = Task { [weak self] in
            for _ in 0..<40 {
                guard let self, !Task.isCancelled, !self.isStopping else { return }
                let packetsSent = await self.outboundVideoPackets(
                    peerConnection: peerConnection,
                    sender: videoSender
                )
                if packetsSent > 0 {
                    self.updateState(.connected, "카메라 영상 연결됨 · 서버 수신 확인 중")
                    self.completeStartIfNeeded()
                    self.outboundVerificationTask = nil
                    return
                }
                try? await Task.sleep(for: .milliseconds(250))
            }

            guard let self, !Task.isCancelled, !self.isStopping else { return }
            self.outboundVerificationTask = nil
            self.fail("카메라는 열렸지만 영상 패킷이 서버로 전송되지 않았습니다. 방송을 다시 시작해 주세요.")
        }
    }

    private func outboundVideoPackets(peerConnection: LKRTCPeerConnection, sender: LKRTCRtpSender) async -> UInt64 {
        await withCheckedContinuation { continuation in
            peerConnection.statistics(for: sender) { report in
                let packetsSent = report.statistics.values.reduce(UInt64(0)) { total, statistic in
                    guard statistic.type == "outbound-rtp" else { return total }
                    let packetValue = statistic.values["packetsSent"] as? NSNumber
                    return total + (packetValue?.uint64Value ?? 0)
                }
                continuation.resume(returning: packetsSent)
            }
        }
    }

}
extension WebRTCVideoUplink: LKRTCPeerConnectionDelegate {
    nonisolated func peerConnection(
        _ peerConnection: LKRTCPeerConnection,
        didChange stateChanged: LKRTCSignalingState
    ) { }

    nonisolated func peerConnection(_ peerConnection: LKRTCPeerConnection, didAdd stream: LKRTCMediaStream) {
        guard let track = stream.videoTracks.first else { return }
        Task { @MainActor [weak self] in
            self?.setRemoteVideoTrack(track)
        }
    }

    nonisolated func peerConnection(_ peerConnection: LKRTCPeerConnection, didRemove stream: LKRTCMediaStream) { }

    nonisolated func peerConnectionShouldNegotiate(_ peerConnection: LKRTCPeerConnection) { }

    nonisolated func peerConnection(
        _ peerConnection: LKRTCPeerConnection,
        didChange newState: LKRTCIceConnectionState
    ) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            switch newState {
            case .disconnected, .failed:
                self.handlePeerConnectionInterruption()
            case .closed:
                if !self.isStopping, !self.isReconnectInProgress {
                    self.fail("WebRTC 영상 연결이 종료되었습니다.")
                }
            default:
                break
            }
        }
    }

    nonisolated func peerConnection(
        _ peerConnection: LKRTCPeerConnection,
        didChange newState: LKRTCIceGatheringState
    ) { }

    nonisolated func peerConnection(
        _ peerConnection: LKRTCPeerConnection,
        didGenerate candidate: LKRTCIceCandidate
    ) {
        let sdp = candidate.sdp
        let sdpMid = candidate.sdpMid
        let sdpMLineIndex = Int(candidate.sdpMLineIndex)
        Task { @MainActor [weak self] in
            self?.sendLocalCandidate(sdp: sdp, sdpMid: sdpMid, sdpMLineIndex: sdpMLineIndex)
        }
    }

    nonisolated func peerConnection(
        _ peerConnection: LKRTCPeerConnection,
        didRemove candidates: [LKRTCIceCandidate]
    ) { }

    nonisolated func peerConnection(
        _ peerConnection: LKRTCPeerConnection,
        didOpen dataChannel: LKRTCDataChannel
    ) { }

    nonisolated func peerConnection(
        _ peerConnection: LKRTCPeerConnection,
        didChange newState: LKRTCPeerConnectionState
    ) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            switch newState {
            case .connected:
                self.updateState(.connecting, "영상 패킷 전송을 확인하는 중…")
                self.verifyOutboundVideo()
            case .disconnected, .failed:
                self.handlePeerConnectionInterruption()
            case .closed:
                if !self.isStopping, !self.isReconnectInProgress {
                    self.fail("WebRTC 영상 연결이 종료되었습니다.")
                }
            default:
                break
            }
        }
    }

    nonisolated func peerConnection(
        _ peerConnection: LKRTCPeerConnection,
        didAdd rtpReceiver: LKRTCRtpReceiver,
        streams mediaStreams: [LKRTCMediaStream]
    ) {
        guard let track = rtpReceiver.track as? LKRTCVideoTrack else { return }
        Task { @MainActor [weak self] in
            self?.setRemoteVideoTrack(track)
        }
    }
}
