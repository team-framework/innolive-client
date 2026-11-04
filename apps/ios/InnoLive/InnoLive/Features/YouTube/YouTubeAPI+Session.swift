import Foundation

private struct WebRTCConfigurationResponse: Decodable {
    let iceServers: [WebRTCIceServer]
    let recovery: WebRTCRecoveryPolicy?

    enum CodingKeys: String, CodingKey {
        case iceServers
        case snakeCaseIceServers = "ice_servers"
        case recovery
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        iceServers = try container.decodeIfPresent([WebRTCIceServer].self, forKey: .iceServers)
            ?? container.decodeIfPresent([WebRTCIceServer].self, forKey: .snakeCaseIceServers)
            ?? []
        // 정책이 깨져 있어도 ICE 서버는 써야 하므로 기본 정책으로 넘어간다.
        recovery = try? container.decodeIfPresent(WebRTCRecoveryPolicy.self, forKey: .recovery)
    }
}

/// `POST /sessions` 본문이다. 서버 처리는 처리 방식 필드를 보내지 않는다.
/// 온디바이스는 공유 계약(direct-publish-v1)의 최상위 `ai_processing`을 보낸다. 처리 방식을
/// 돌려주지 않는 구 서버와 협상하도록 `metadata.ai_processing`도 함께 보낸다(서버는 최상위 값을 먼저 쓴다).
/// 해상도는 처리 방식과 관계없이 선택한 카메라 화질을 보낸다. 생략하면 서버 기본값(720p) 세션이 된다.
/// 얼굴 합성은 서버 처리에서만 보낸다. 블러는 서버 기본값이라 생략한다.
struct YouTubeSessionCreateRequest: Encodable, Equatable {
    let aiProcessing: String?
    let broadcastResolution: String?
    let faceAnonymization: String?
    let metadata: [String: String]?

    init(mode: AIProcessingMode, broadcastResolution: String? = nil, faceAnonymization: FaceAnonymizationMode = .blur) {
        self.broadcastResolution = broadcastResolution
        aiProcessing = mode == .server ? nil : mode.rawValue
        metadata = mode == .server ? nil : ["ai_processing": mode.rawValue]
        self.faceAnonymization = mode == .server && faceAnonymization == .faceSwap ? faceAnonymization.rawValue : nil
    }

    enum CodingKeys: String, CodingKey {
        case metadata
        case aiProcessing = "ai_processing"
        case broadcastResolution = "broadcast_resolution"
        case faceAnonymization = "face_anonymization"
    }
}

extension YouTubeAPI {
    func createSession(accessToken: String, mode: AIProcessingMode = .server, broadcastResolution: String? = nil,
                       faceAnonymization: FaceAnonymizationMode = .blur) async throws -> YouTubeBroadcastSession {
        try await request(
            path: "/sessions",
            method: "POST",
            accessToken: accessToken,
            body: YouTubeSessionCreateRequest(mode: mode, broadcastResolution: broadcastResolution,
                                              faceAnonymization: faceAnonymization),
            preserveCreatedSession: true
        )
    }

    func broadcastSessionScope(accessToken: String) throws -> BroadcastSessionScope {
        try capturedBroadcastSessionScope(accessToken: currentAccessToken(fallback: accessToken))
    }

    func capturedBroadcastSessionScope(accessToken: String) throws -> BroadcastSessionScope {
        guard let server = serverURLProvider("/") else { throw YouTubeAPIError.configuration }
        return try BroadcastSessionScope(server: server, accessToken: accessToken)
    }

    func deleteSession(_ session: StoredBroadcastSession, accessToken: String) async throws {
        guard let url = serverURLProvider("/sessions/\(session.sessionID)") else {
            throw YouTubeAPIError.configuration
        }
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(session.ownerToken, forHTTPHeaderField: "X-Session-Owner-Token")
        let (data, response) = try await authenticatedData(for: request, accessToken: accessToken)
        if response.statusCode == 404,
           let envelope = try? JSONDecoder().decode(YouTubeAPIErrorEnvelope.self, from: data),
           envelope.error.code == "not_found" { return }
        try validate(response, data: data)
        guard response.statusCode == 204 else { throw YouTubeAPIError.response }
    }

    /// ICE 서버와 영상 재연결 정책을 함께 읽는다.
    func webrtcSessionConfiguration(accessToken: String) async throws -> WebRTCConfiguration {
        let response: WebRTCConfigurationResponse = try await request(
            path: "/webrtc/config",
            method: "GET",
            accessToken: accessToken,
            body: Optional<YouTubeEmptyRequest>.none
        )
        return WebRTCConfiguration(iceServers: response.iceServers, recovery: response.recovery ?? .default)
    }

    func sendHeartbeat(session: YouTubeBroadcastSession, accessToken: String,
                       request: DirectHeartbeatRequest) async throws -> DirectHeartbeatResponse {
        try await self.request(
            path: "/sessions/\(session.sessionID)/heartbeat",
            method: "POST",
            accessToken: accessToken,
            ownerToken: session.ownerToken,
            body: request
        )
    }

    func sessionStatus(session: YouTubeBroadcastSession, accessToken: String) async throws -> YouTubeSessionResponse {
        try await request(
            path: "/sessions/\(session.sessionID)",
            method: "GET",
            accessToken: accessToken,
            ownerToken: session.ownerToken,
            body: Optional<YouTubeEmptyRequest>.none
        )
    }
}
