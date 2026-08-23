import Foundation

private struct WebRTCConfigurationResponse: Decodable {
    let iceServers: [WebRTCIceServer]

    enum CodingKeys: String, CodingKey {
        case iceServers
        case snakeCaseIceServers = "ice_servers"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        iceServers = try container.decodeIfPresent([WebRTCIceServer].self, forKey: .iceServers)
            ?? container.decodeIfPresent([WebRTCIceServer].self, forKey: .snakeCaseIceServers)
            ?? []
    }
}
private struct YouTubeConnectRequest: Encodable {
    let serverAuthCode: String
    let codeSource = "native"

    enum CodingKeys: String, CodingKey {
        case serverAuthCode = "server_auth_code"
        case codeSource = "code_source"
    }
}

private struct YouTubeBroadcastSettingsRequest: Encodable {
    let title: String
    let description: String
    let privacy: String
    let madeForKids: Bool?

    enum CodingKeys: String, CodingKey {
        case title
        case description
        case privacy
        case madeForKids = "made_for_kids"
    }

    init(settings: YouTubeBroadcastSettings) {
        let settings = settings.normalized
        title = settings.title
        description = settings.description
        privacy = settings.privacy.rawValue
        madeForKids = settings.audience?.madeForKidsValue
    }
}

private struct YouTubePrepareStreamRequest: Encodable {
    let provider = "youtube"
}

private struct YouTubeEmptyRequest: Encodable {}

@MainActor
final class YouTubeAPI {
    private var accessTokenProvider: (() -> String?)?
    private var refreshSession: (() async -> AuthenticationRefreshResult)?
    private var onInvalidRefresh: (() -> Void)?

    func configureAuthentication(
        accessTokenProvider: @escaping () -> String?,
        refreshSession: @escaping () async -> AuthenticationRefreshResult,
        onInvalidRefresh: @escaping () -> Void
    ) {
        self.accessTokenProvider = accessTokenProvider
        self.refreshSession = refreshSession
        self.onInvalidRefresh = onInvalidRefresh
    }

    func currentAccessToken(fallback: String) -> String {
        accessTokenProvider?() ?? fallback
    }

    func refreshAuthentication() async -> AuthenticationRefreshResult {
        guard let refreshSession else { return .invalid }
        let result = await refreshSession()
        if case .invalid = result {
            onInvalidRefresh?()
        }
        return result
    }

    func configuration() async throws -> YouTubeConfiguration {
        guard let url = AuthenticationConfiguration.serverURL(path: "/auth/youtube/config") else {
            throw YouTubeAPIError.configuration
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await perform(request)
        guard let httpResponse = response as? HTTPURLResponse else { throw YouTubeAPIError.response }
        if httpResponse.statusCode == 404 { throw YouTubeAPIError.featureUnavailable }
        try validate(httpResponse, data: data)
        let configuration = try decode(YouTubeConfiguration.self, from: data)
        guard !configuration.webClientID.isEmpty, !configuration.scope.isEmpty else {
            throw YouTubeAPIError.response
        }
        return configuration
    }

    func connect(serverAuthCode: String, accessToken: String) async throws -> YouTubeConnectionResponse {
        try await request(
            path: "/auth/youtube/connect",
            method: "POST",
            accessToken: accessToken,
            body: YouTubeConnectRequest(serverAuthCode: serverAuthCode)
        )
    }

    func createSession(accessToken: String) async throws -> YouTubeBroadcastSession {
        try await request(
            path: "/sessions",
            method: "POST",
            accessToken: accessToken,
            body: Optional<YouTubeEmptyRequest>.none
        )
    }

    func webrtcConfiguration(accessToken: String) async throws -> [WebRTCIceServer] {
        let response: WebRTCConfigurationResponse = try await request(
            path: "/webrtc/config",
            method: "GET",
            accessToken: accessToken,
            body: Optional<YouTubeEmptyRequest>.none
        )
        return response.iceServers
    }

    func saveBroadcastSettings(
        session: YouTubeBroadcastSession,
        accessToken: String,
        settings: YouTubeBroadcastSettings
    ) async throws -> YouTubeSessionResponse {
        try await request(
            path: "/sessions/\(session.sessionID)/broadcast",
            method: "PUT",
            accessToken: accessToken,
            ownerToken: session.ownerToken,
            body: YouTubeBroadcastSettingsRequest(settings: settings)
        )
    }

    func prepareStream(
        session: YouTubeBroadcastSession,
        accessToken: String
    ) async throws -> YouTubeSessionResponse {
        try await request(
            path: "/sessions/\(session.sessionID)/stream/prepare",
            method: "POST",
            accessToken: accessToken,
            ownerToken: session.ownerToken,
            body: YouTubePrepareStreamRequest()
        )
    }

    func goLive(
        session: YouTubeBroadcastSession,
        accessToken: String
    ) async throws -> YouTubeStreamState {
        try await request(
            path: "/sessions/\(session.sessionID)/stream/golive",
            method: "POST",
            accessToken: accessToken,
            ownerToken: session.ownerToken,
            body: Optional<YouTubeEmptyRequest>.none
        )
    }

    func stopStream(session: YouTubeBroadcastSession, accessToken: String) async throws -> YouTubeStreamState {
        try await request(
            path: "/sessions/\(session.sessionID)/stream/stop",
            method: "POST",
            accessToken: accessToken,
            ownerToken: session.ownerToken,
            body: Optional<YouTubeEmptyRequest>.none
        )
    }

    func pauseStream(session: YouTubeBroadcastSession, accessToken: String) async throws -> YouTubeStreamState {
        try await request(
            path: "/sessions/\(session.sessionID)/stream/pause",
            method: "POST",
            accessToken: accessToken,
            ownerToken: session.ownerToken,
            body: Optional<YouTubeEmptyRequest>.none
        )
    }

    func resumeStream(session: YouTubeBroadcastSession, accessToken: String) async throws -> YouTubeStreamState {
        try await request(
            path: "/sessions/\(session.sessionID)/stream/resume",
            method: "POST",
            accessToken: accessToken,
            ownerToken: session.ownerToken,
            body: Optional<YouTubeEmptyRequest>.none
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

    private func request<Response: Decodable, Body: Encodable>(
        path: String,
        method: String,
        accessToken: String,
        ownerToken: String? = nil,
        body: Body? = nil
    ) async throws -> Response {
        guard let url = AuthenticationConfiguration.serverURL(path: path) else {
            throw YouTubeAPIError.configuration
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("Bearer \(currentAccessToken(fallback: accessToken))", forHTTPHeaderField: "Authorization")
        if let ownerToken { request.setValue(ownerToken, forHTTPHeaderField: "X-Session-Owner-Token") }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder().encode(body)
        }
        var (data, response) = try await perform(request)
        guard let initialHTTPResponse = response as? HTTPURLResponse else { throw YouTubeAPIError.response }
        if initialHTTPResponse.statusCode == 401,
           let refreshSession {
            switch await refreshSession() {
            case .refreshed:
                request.setValue("Bearer \(currentAccessToken(fallback: accessToken))", forHTTPHeaderField: "Authorization")
                (data, response) = try await perform(request)
            case .invalid:
                onInvalidRefresh?()
            case .unavailable:
                break
            }
        }
        guard let httpResponse = response as? HTTPURLResponse else { throw YouTubeAPIError.response }
        try validate(httpResponse, data: data)
        return try decode(Response.self, from: data)
    }

    private func perform(_ request: URLRequest) async throws -> (Data, URLResponse) {
        do {
            return try await URLSession.shared.data(for: request)
        } catch {
            throw YouTubeAPIError.transport
        }
    }

    private func validate(_ response: HTTPURLResponse, data: Data) throws {
        guard !(200..<300).contains(response.statusCode) else { return }
        let envelope = try? JSONDecoder().decode(YouTubeAPIErrorEnvelope.self, from: data)
        throw YouTubeAPIError.api(
            code: envelope?.error.code,
            fallback: envelope?.error.message ?? "YouTube 요청을 처리하지 못했습니다.",
            helpURL: envelope?.error.details?.helpURL
        )
    }

    private func decode<Response: Decodable>(_ type: Response.Type, from data: Data) throws -> Response {
        do {
            return try JSONDecoder().decode(Response.self, from: data)
        } catch {
            throw YouTubeAPIError.response
        }
    }
}
private struct YouTubeAPIErrorEnvelope: Decodable {
    struct ErrorBody: Decodable {
        struct Details: Decodable { let helpURL: URL?; enum CodingKeys: String, CodingKey { case helpURL = "help_url" } }
        let code: String?
        let message: String?
        let details: Details?
    }
    let error: ErrorBody
}
