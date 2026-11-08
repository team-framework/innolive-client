import Foundation

// 다른 파일의 빈 본문 요청이 이 타입으로 응답 형식을 정한다.
struct YouTubeEmptyRequest: Encodable {}

@MainActor
final class YouTubeAPI: PlanAPIClient {
    // iOS 18 소멸자 충돌을 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    private let urlSession: URLSession
    // 다른 파일의 extension이 호출한다. private 멤버는 같은 파일에서만 보인다.
    let serverURLProvider: @MainActor @Sendable (String) -> URL?
    private var accessTokenProvider: (() -> String?)?
    private var refreshSession: (() async -> AuthenticationRefreshResult)?
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
        refreshSession: @escaping () async -> AuthenticationRefreshResult
    ) {
        self.accessTokenProvider = accessTokenProvider
        self.refreshSession = refreshSession
    }

    func currentAccessToken(fallback: String) -> String {
        guard let accessTokenProvider else { return fallback }
        return accessTokenProvider() ?? ""
    }

    func refreshAuthentication() async -> AuthenticationRefreshResult {
        guard let refreshSession else { return .invalid }
        return await refreshSession()
    }

    func invalidateAuthenticationRequests() {
        authenticationRequestGeneration &+= 1
    }

    enum StreamAction: String {
        case goLive = "golive"
        case stop, pause, resume
    }

    func request<Response: Decodable, Body: Encodable>(
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
        try validate(httpResponse, data: data, extractFieldErrors: path.hasSuffix("/broadcast") || path.hasSuffix("/broadcast/live"))
        return try decode(Response.self, from: data)
    }

    func authenticatedData(
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
            case .invalid, .unavailable:
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

    func perform(_ request: URLRequest) async throws -> (Data, URLResponse) {
        do {
            return try await urlSession.data(for: request)
        } catch {
            throw YouTubeAPIError.transport
        }
    }

    func validate(_ response: HTTPURLResponse, data: Data, extractFieldErrors: Bool = false) throws {
        guard !(200..<300).contains(response.statusCode) else { return }
        let envelope = try? JSONDecoder().decode(YouTubeAPIErrorEnvelope.self, from: data)
        #if DEBUG
        // 알려진 공개 오류 식별자만 기록한다. 응답 본문이나 요청 자격 증명은 기록하지 않는다.
        let probeCodes: Set<String> = ["streaming_rate_limited", "streaming_quota_exceeded",
            "broadcast_prepared", "broadcast_live", "streaming_reconnect_required", "streaming_prepare_failed"]
        let probeCode = envelope?.error.code ?? "unknown"
        PrivacyPipelineProbe.count(probeCodes.contains(probeCode) ? "api_\(probeCode)" : "api_http_\(response.statusCode)")
        #endif
        if extractFieldErrors, envelope?.error.code == "field_not_changeable_live",
           let fields = envelope?.error.details?.fields, let first = fields.first {
            throw BroadcastSettingsFieldError(field: first, reason: "", fields: fields, isNotChangeableLive: true)
        }
        if extractFieldErrors, envelope?.error.code == "bad_request", let field = envelope?.error.details?.field {
            throw BroadcastSettingsFieldError(field: field, reason: envelope?.error.details?.reason ?? "")
        }
        throw YouTubeAPIError.api(
            code: envelope?.error.code,
            fallback: String(localized: "방송 요청을 처리하지 못했습니다."),
            helpURL: envelope?.error.details?.helpURL
        )
    }

    func decode<Response: Decodable>(_ type: Response.Type, from data: Data) throws -> Response {
        do {
            return try JSONDecoder().decode(Response.self, from: data)
        } catch {
            throw YouTubeAPIError.response
        }
    }
}

private struct YouTubeAPIRequestInvalidated: Error {}

// 세션 삭제가 404 not_found를 직접 판별한다.
struct YouTubeAPIErrorEnvelope: Decodable {
    struct ErrorBody: Decodable {
        struct Details: Decodable { let helpURL: URL?; let field: String?; let fields: [String]?; let reason: String?; enum CodingKeys: String, CodingKey { case helpURL = "help_url"; case field, fields, reason } }
        let code: String?
        let message: String?
        let details: Details?
    }
    let error: ErrorBody
}
