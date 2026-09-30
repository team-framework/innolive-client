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
    let allowConcurrent: Bool?
    enum CodingKeys: String, CodingKey { case provider; case allowConcurrent = "allow_concurrent" }
}

private struct YouTubeEmptyRequest: Encodable {}

private struct AnonymizationRequest: Encodable {
    let enabled: Bool
}

@MainActor
final class YouTubeAPI {
    // iOS 18 소멸자 충돌을 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    private let urlSession: URLSession
    private let serverURLProvider: @MainActor @Sendable (String) -> URL?
    private var accessTokenProvider: (() -> String?)?
    private var refreshSession: (() async -> AuthenticationRefreshResult)?
    private var onInvalidRefresh: (() -> Void)?
    private var authenticationRequestGeneration: UInt = 0

    init(
        urlSession: URLSession = .shared,
        serverURLProvider: @escaping @MainActor @Sendable (String) -> URL? = AuthenticationConfiguration.serverURL(path:)
    ) {
        self.urlSession = urlSession
        self.serverURLProvider = serverURLProvider
    }

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
        guard let accessTokenProvider else { return fallback }
        return accessTokenProvider() ?? ""
    }

    func refreshAuthentication() async -> AuthenticationRefreshResult {
        guard let refreshSession else { return .invalid }
        let result = await refreshSession()
        if case .invalid = result {
            onInvalidRefresh?()
        }
        return result
    }

    func invalidateAuthenticationRequests() {
        authenticationRequestGeneration &+= 1
    }

    func configuration() async throws -> YouTubeConfiguration {
        guard let url = serverURLProvider("/auth/youtube/config") else {
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

    func streamingAccounts(accessToken: String) async throws -> [YouTubeStreamingAccountSummary] {
        try await request(
            path: "/auth/streaming/accounts",
            method: "GET",
            accessToken: accessToken,
            body: Optional<YouTubeEmptyRequest>.none
        )
    }

    func disconnectStreamingAccount(accessToken: String, provider: String = "youtube") async throws {
        guard let url = serverURLProvider("/auth/streaming/accounts/\(provider)") else {
            throw YouTubeAPIError.configuration
        }
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await authenticatedData(for: request, accessToken: accessToken)
        try validate(response, data: data, provider: provider)
        guard response.statusCode == 204 else {
            throw YouTubeAPIError.response
        }
    }

    func broadcastPlanInformation(accessToken: String) async throws -> BroadcastPlanInformation {
        try await request(path: "/users/me/plan", method: "GET", accessToken: accessToken, body: Optional<YouTubeEmptyRequest>.none)
    }

    func createSession(accessToken: String, mode: AIProcessingMode = .server) async throws -> YouTubeBroadcastSession {
        try await request(
            path: "/sessions",
            method: "POST",
            accessToken: accessToken,
            body: mode == .server ? [:] : ["metadata": ["ai_processing": mode.rawValue]],
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
        accessToken: String,
        allowConcurrent: Bool = false
    ) async throws -> YouTubeSessionResponse {
        try await request(
            path: "/sessions/\(session.sessionID)/stream/prepare",
            method: "POST",
            accessToken: accessToken,
            ownerToken: session.ownerToken,
            body: YouTubePrepareStreamRequest(allowConcurrent: allowConcurrent ? true : nil)
        )
    }

    func goLive(
        session: YouTubeBroadcastSession,
        accessToken: String
    ) async throws -> YouTubeStreamState {
        try await streamAction(.goLive, session: session, accessToken: accessToken).stream
    }

    func stopStream(session: YouTubeBroadcastSession, accessToken: String) async throws -> YouTubeStreamState {
        try await streamAction(.stop, session: session, accessToken: accessToken).stream
    }

    func pauseStream(session: YouTubeBroadcastSession, accessToken: String) async throws -> YouTubeStreamState {
        try await streamAction(.pause, session: session, accessToken: accessToken).stream
    }

    func resumeStream(session: YouTubeBroadcastSession, accessToken: String) async throws -> YouTubeStreamState {
        try await streamAction(.resume, session: session, accessToken: accessToken).stream
    }

    enum StreamAction: String {
        case goLive = "golive"
        case stop, pause, resume
    }

    func streamAction(
        _ action: StreamAction,
        session: YouTubeBroadcastSession,
        accessToken: String,
        provider: String? = nil
    ) async throws -> YouTubeSessionResponse {
        return try await request(
            path: "/sessions/\(session.sessionID)/stream/\(action.rawValue)",
            method: "POST",
            accessToken: accessToken,
            ownerToken: session.ownerToken,
            body: Optional<YouTubeEmptyRequest>.none,
            queryItems: provider.map { [URLQueryItem(name: "provider", value: $0)] } ?? []
        )
    }

    func toggleAnonymization(session: YouTubeBroadcastSession, accessToken: String, enabled: Bool) async throws -> YouTubeSessionResponse {
        try await request(
            path: "/sessions/\(session.sessionID)/anonymization",
            method: "PATCH",
            accessToken: accessToken,
            ownerToken: session.ownerToken,
            body: AnonymizationRequest(enabled: enabled)
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
        body: Body? = nil,
        preserveCreatedSession: Bool = false,
        queryItems: [URLQueryItem] = []
    ) async throws -> Response {
        guard let baseURL = serverURLProvider(path),
              var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else {
            throw YouTubeAPIError.configuration
        }
        if !queryItems.isEmpty { components.queryItems = queryItems }
        guard let url = components.url else { throw YouTubeAPIError.configuration }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("Bearer \(currentAccessToken(fallback: accessToken))", forHTTPHeaderField: "Authorization")
        if let ownerToken { request.setValue(ownerToken, forHTTPHeaderField: "X-Session-Owner-Token") }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder().encode(body)
        }
        let (data, httpResponse) = try await authenticatedData(
            for: request, accessToken: accessToken, preserveCreatedSession: preserveCreatedSession
        )
        let provider = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "provider" })?.value
            ?? (path.contains("/chzzk") ? "chzzk" : "youtube")
        try validate(httpResponse, data: data, provider: provider)
        return try decode(Response.self, from: data)
    }

    private func authenticatedData(
        for request: URLRequest,
        accessToken: String,
        preserveCreatedSession: Bool = false
    ) async throws -> (Data, HTTPURLResponse) {
        let requestGeneration = authenticationRequestGeneration
        let initialScope = try? broadcastSessionScope(accessToken: accessToken)
        var request = request
        let initialToken = currentAccessToken(fallback: accessToken)
        guard !initialToken.isEmpty else { throw YouTubeAPIError.unauthorized }
        request.setValue("Bearer \(initialToken)", forHTTPHeaderField: "Authorization")

        var (data, response) = try await perform(request)
        // 생성 후 초기화됐더라도 owner token은 정리용으로 보관해야 한다.
        // 상태 복원 여부는 호출자의 세대 검사에서 결정한다. 인증 재시도는 하지 않는다.
        if preserveCreatedSession,
           let http = response as? HTTPURLResponse, http.statusCode == 201 {
            return (data, http)
        }
        guard requestGeneration == authenticationRequestGeneration else {
            throw YouTubeAPIRequestInvalidated()
        }
        guard let initialHTTPResponse = response as? HTTPURLResponse else {
            throw YouTubeAPIError.response
        }
        if initialHTTPResponse.statusCode == 401,
           let refreshSession {
            let refreshResult = await refreshSession()
            guard requestGeneration == authenticationRequestGeneration else {
                throw YouTubeAPIRequestInvalidated()
            }
            if let initialScope,
               (try? broadcastSessionScope(accessToken: accessToken)) != initialScope {
                throw YouTubeAPIRequestInvalidated()
            }
            switch refreshResult {
            case .refreshed:
                let refreshedToken = currentAccessToken(fallback: accessToken)
                guard !refreshedToken.isEmpty else { throw YouTubeAPIError.unauthorized }
                request.setValue("Bearer \(refreshedToken)", forHTTPHeaderField: "Authorization")
                (data, response) = try await perform(request)
            case .invalid:
                onInvalidRefresh?()
            case .unavailable:
                break
            }
        }
        if preserveCreatedSession,
           let http = response as? HTTPURLResponse, http.statusCode == 201 {
            return (data, http)
        }
        guard requestGeneration == authenticationRequestGeneration else {
            throw YouTubeAPIRequestInvalidated()
        }
        guard !currentAccessToken(fallback: accessToken).isEmpty else {
            throw YouTubeAPIRequestInvalidated()
        }
        if let initialScope, (try? broadcastSessionScope(accessToken: accessToken)) != initialScope {
            throw YouTubeAPIRequestInvalidated()
        }
        guard let httpResponse = response as? HTTPURLResponse else {
            throw YouTubeAPIError.response
        }
        return (data, httpResponse)
    }

    private func perform(_ request: URLRequest) async throws -> (Data, URLResponse) {
        do {
            return try await urlSession.data(for: request)
        } catch {
            throw YouTubeAPIError.transport
        }
    }

    private func validate(_ response: HTTPURLResponse, data: Data, provider: String = "youtube") throws {
        guard !(200..<300).contains(response.statusCode) else { return }
        let envelope = try? JSONDecoder().decode(YouTubeAPIErrorEnvelope.self, from: data)
        let details = envelope?.error.details
        throw YouTubeAPIError.server(BroadcastProblem(
            status: response.statusCode, code: envelope?.error.code,
            message: envelope?.error.message ?? String(localized: "YouTube 요청을 처리하지 못했습니다."),
            provider: details?.provider ?? envelope?.error.provider ?? provider,
            field: details?.field, reason: details?.reason, helpURL: details?.helpURL
        ))
    }

    private func decode<Response: Decodable>(_ type: Response.Type, from data: Data) throws -> Response {
        do {
            return try JSONDecoder().decode(Response.self, from: data)
        } catch {
            throw YouTubeAPIError.response
        }
    }
}

private struct YouTubeAPIRequestInvalidated: Error {}

private struct YouTubeAPIErrorEnvelope: Decodable {
    struct ErrorBody: Decodable {
        struct Details: Decodable {
            let helpURL: URL?
            let provider: String?
            let field: String?
            let reason: String?
            enum CodingKeys: String, CodingKey { case provider, field, reason; case helpURL = "help_url" }
        }
        let code: String?
        let provider: String?
        let message: String?
        let details: Details?
    }
    let error: ErrorBody
}
