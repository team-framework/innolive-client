import Foundation
@preconcurrency import LiveKitWebRTC

extension WebRTCVideoUplink {
    func sendOffer(_ sdp: String) {
        guard let credentials, let accessToken else {
            fail("영상 연결 인증 정보가 없습니다.")
            return
        }
        sendSignal(.offer(session: credentials, accessToken: accessToken, sdp: sdp))
    }

    func connectSignaling(to url: URL) {
        let task = URLSession.shared.webSocketTask(with: url)
        webSocketTask = task
        task.resume()
        receiveNextMessage(for: task)
    }

    private func receiveNextMessage(for task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            guard let self else { return }
            Task { @MainActor [self] in
                guard self.webSocketTask === task else { return }
                switch result {
                case let .success(message):
                    self.handleWebSocketMessage(message)
                    self.receiveNextMessage(for: task)
                case .failure:
                    if !self.isStopping, self.state != .connected {
                        self.fail("영상 서버와의 연결이 끊겼습니다.")
                    }
                }
            }
        }
    }

    private func handleWebSocketMessage(_ message: URLSessionWebSocketTask.Message) {
        let data: Data
        switch message {
        case let .string(value):
            guard let valueData = value.data(using: .utf8) else { return }
            data = valueData
        case let .data(value):
            data = value
        @unknown default:
            return
        }

        guard let signal = try? decoder.decode(WebRTCServerSignal.self, from: data) else { return }
        switch signal.type {
        case "answer":
            guard let sdp = signal.sdp else {
                fail("영상 서버의 WebRTC 응답이 올바르지 않습니다.")
                return
            }
            applyRemoteAnswer(sdp)
        case "ice_candidate":
            addRemoteCandidate(signal)
        case "error":
            if signal.error?.code == "unauthorized" {
                AuthenticationSessionExpiration.notify()
            }
            fail(signal.error?.message ?? "영상 서버가 WebRTC 연결을 거부했습니다.")
        default:
            break
        }
    }

    private func applyRemoteAnswer(_ sdp: String) {
        guard let peerConnection else {
            fail("WebRTC 영상 연결이 준비되지 않았습니다.")
            return
        }
        updateState(.connecting, "영상 서버 응답을 적용하는 중…")
        let answer = LKRTCSessionDescription(type: .answer, sdp: sdp)
        peerConnection.setRemoteDescription(answer) { [weak self] error in
            Task { @MainActor [weak self] in
                guard let self, !self.isStopping else { return }
                if let error {
                    self.fail("영상 서버의 WebRTC 응답을 적용하지 못했습니다: \(error.localizedDescription)")
                    return
                }
                self.flushRemoteCandidates()
            }
        }
    }

    private func addRemoteCandidate(_ signal: WebRTCServerSignal) {
        guard peerConnection?.remoteDescription != nil else {
            pendingRemoteCandidates.append(signal)
            return
        }
        applyRemoteCandidate(signal)
    }

    private func applyRemoteCandidate(_ signal: WebRTCServerSignal) {
        guard let peerConnection,
              let candidateSDP = signal.candidate,
              let lineIndex = signal.sdpMLineIndex else { return }
        let candidate = LKRTCIceCandidate(
            sdp: candidateSDP,
            sdpMLineIndex: Int32(lineIndex),
            sdpMid: signal.sdpMid
        )
        peerConnection.add(candidate) { [weak self] error in
            guard let error else { return }
            Task { @MainActor [weak self] in
                self?.fail("영상 서버의 ICE 후보를 적용하지 못했습니다: \(error.localizedDescription)")
            }
        }
    }

    private func flushRemoteCandidates() {
        let candidates = pendingRemoteCandidates
        pendingRemoteCandidates.removeAll()
        candidates.forEach(applyRemoteCandidate)
    }

    func sendLocalCandidate(sdp: String, sdpMid: String?, sdpMLineIndex: Int) {
        guard let credentials, let accessToken else { return }
        sendSignal(
            .candidate(
                session: credentials,
                accessToken: accessToken,
                candidate: sdp,
                sdpMid: sdpMid,
                sdpMLineIndex: sdpMLineIndex
            )
        )
    }

    private func sendSignal(_ signal: WebRTCClientSignal) {
        guard let webSocketTask else {
            fail("영상 서버 WebSocket이 열려 있지 않습니다.")
            return
        }
        do {
            let data = try encoder.encode(signal)
            guard let text = String(data: data, encoding: .utf8) else {
                fail("영상 신호를 만들지 못했습니다.")
                return
            }
            webSocketTask.send(.string(text)) { [weak self] error in
                guard let error else { return }
                Task { @MainActor [weak self] in
                    self?.fail("영상 신호 전송에 실패했습니다: \(error.localizedDescription)")
                }
            }
        } catch {
            fail("영상 신호를 만들지 못했습니다.")
        }
    }

    func signalingURL(from serverURL: URL) -> URL {
        var components = URLComponents(url: serverURL, resolvingAgainstBaseURL: false)!
        components.scheme = serverURL.scheme == "https" ? "wss" : "ws"
        components.path = "/signaling"
        components.query = nil
        components.fragment = nil
        return components.url!
    }
}

private struct WebRTCClientSignal: Encodable {
    let type: String
    let sessionID: String
    let ownerToken: String
    let accessToken: String
    let sdp: String?
    let candidate: String?
    let sdpMid: String?
    let sdpMLineIndex: Int?

    enum CodingKeys: String, CodingKey {
        case type
        case sessionID = "session_id"
        case ownerToken = "owner_token"
        case accessToken = "access_token"
        case sdp
        case candidate
        case sdpMid
        case sdpMLineIndex
    }

    static func offer(session: WebRTCSessionCredentials, accessToken: String, sdp: String) -> Self {
        Self(
            type: "offer",
            sessionID: session.sessionID,
            ownerToken: session.ownerToken,
            accessToken: accessToken,
            sdp: sdp,
            candidate: nil,
            sdpMid: nil,
            sdpMLineIndex: nil
        )
    }

    static func candidate(
        session: WebRTCSessionCredentials,
        accessToken: String,
        candidate: String?,
        sdpMid: String?,
        sdpMLineIndex: Int?
    ) -> Self {
        Self(
            type: "ice_candidate",
            sessionID: session.sessionID,
            ownerToken: session.ownerToken,
            accessToken: accessToken,
            sdp: nil,
            candidate: candidate,
            sdpMid: sdpMid,
            sdpMLineIndex: sdpMLineIndex
        )
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(type, forKey: .type)
        try container.encode(sessionID, forKey: .sessionID)
        try container.encode(ownerToken, forKey: .ownerToken)
        try container.encode(accessToken, forKey: .accessToken)
        if type == "offer" {
            try container.encode(sdp, forKey: .sdp)
        } else {
            try container.encode(candidate, forKey: .candidate)
            try container.encode(sdpMid, forKey: .sdpMid)
            try container.encode(sdpMLineIndex, forKey: .sdpMLineIndex)
        }
    }
}

struct WebRTCServerSignal: Decodable {
    struct ErrorBody: Decodable {
        let code: String?
        let message: String?
    }

    let type: String
    let sdp: String?
    let candidate: String?
    let sdpMid: String?
    let sdpMLineIndex: Int?
    let error: ErrorBody?

    enum CodingKeys: String, CodingKey {
        case type
        case sdp
        case candidate
        case sdpMid
        case sdpMLineIndex
        case snakeCaseSDPMid = "sdp_mid"
        case snakeCaseSDPMLineIndex = "sdp_mline_index"
        case error
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        type = try container.decode(String.self, forKey: .type)
        sdp = try container.decodeIfPresent(String.self, forKey: .sdp)
        candidate = try container.decodeIfPresent(String.self, forKey: .candidate)
        sdpMid = try container.decodeIfPresent(String.self, forKey: .sdpMid)
            ?? container.decodeIfPresent(String.self, forKey: .snakeCaseSDPMid)
        sdpMLineIndex = try container.decodeIfPresent(Int.self, forKey: .sdpMLineIndex)
            ?? container.decodeIfPresent(Int.self, forKey: .snakeCaseSDPMLineIndex)
        error = try container.decodeIfPresent(ErrorBody.self, forKey: .error)
    }
}
