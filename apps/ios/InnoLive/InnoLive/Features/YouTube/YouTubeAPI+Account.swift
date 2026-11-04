import Foundation

private struct CHZZKConnectRequest: Encodable {
    let code: String
    let state: String
}

private struct YouTubeConnectRequest: Encodable {
    let serverAuthCode: String
    let codeSource = "native"

    enum CodingKeys: String, CodingKey {
        case serverAuthCode = "server_auth_code"
        case codeSource = "code_source"
    }
}

struct AdministratorCheck: Equatable {
    let isAdministrator: Bool
    let serverDate: Date?
}

private struct LocalTakeUploadRequest: Encodable {
    let takes: [LocalTakeUsage]
}

private struct LocalTakeUploadResponse: Decodable {
    let takeIDs: [UUID]
    enum CodingKeys: String, CodingKey { case takeIDs = "take_ids" }
}

extension YouTubeAPI {
    func userPlan(accessToken: String) async throws -> UserPlan {
        try await request(path: "/users/me/plan", method: "GET", accessToken: accessToken,
                          body: Optional<YouTubeEmptyRequest>.none)
    }

    /// `GET /admin/me`가 200이면 관리자다. 403·404는 관리자가 아니다.
    /// 응답의 `Date` 헤더는 오프라인 이용 기간의 서버 시각 기준으로 쓴다.
    func isAdministrator(accessToken: String) async throws -> AdministratorCheck {
        guard let url = serverURLProvider("/admin/me") else { throw YouTubeAPIError.configuration }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await authenticatedData(for: request, accessToken: accessToken)
        let serverDate = OfflineGrant.serverDate(fromHTTPDate: response.value(forHTTPHeaderField: "Date"))
        if response.statusCode == 403 || response.statusCode == 404 {
            return AdministratorCheck(isAdministrator: false, serverDate: serverDate)
        }
        try validate(response, data: data)
        return AdministratorCheck(isAdministrator: true, serverDate: serverDate)
    }

    func userUsage(accessToken: String) async throws -> UserUsage {
        try await request(path: "/users/me/usage", method: "GET", accessToken: accessToken,
                          body: Optional<YouTubeEmptyRequest>.none)
    }

    /// 서버가 저장했거나 이미 가지고 있던 촬영 ID를 돌려준다.
    func uploadLocalTakes(_ takes: [LocalTakeUsage], accessToken: String) async throws -> [UUID] {
        guard let url = serverURLProvider("/users/me/usage/local-takes") else { throw YouTubeAPIError.configuration }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try LocalTakeUsageLedger.encoder.encode(LocalTakeUploadRequest(takes: takes))
        let (data, response) = try await authenticatedData(for: request, accessToken: accessToken)
        try validate(response, data: data)
        return try decode(LocalTakeUploadResponse.self, from: data).takeIDs
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

    func chzzkConfiguration(state: String) async throws -> CHZZKConfiguration {
        guard !state.isEmpty, let baseURL = serverURLProvider("/auth/chzzk/config"),
              var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else {
            throw CHZZKAuthorizationError.configuration
        }
        components.queryItems = [URLQueryItem(name: "state", value: state)]
        guard let url = components.url else { throw CHZZKAuthorizationError.configuration }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await perform(request)
        guard let response = response as? HTTPURLResponse else { throw YouTubeAPIError.response }
        try validate(response, data: data)
        return try decode(CHZZKConfiguration.self, from: data)
    }

    func connectCHZZK(authorization: CHZZKAuthorizationCode, accessToken: String) async throws -> CHZZKConnectionResponse {
        let response: CHZZKConnectionResponse = try await request(
            path: "/auth/chzzk/connect", method: "POST", accessToken: accessToken,
            body: CHZZKConnectRequest(code: authorization.code, state: authorization.state)
        )
        guard response.connected, response.provider == "chzzk", !response.channel.id.isEmpty else {
            throw YouTubeAPIError.response
        }
        return response
    }

    func streamingAccounts(accessToken: String) async throws -> [YouTubeStreamingAccountSummary] {
        try await request(
            path: "/auth/streaming/accounts",
            method: "GET",
            accessToken: accessToken,
            body: Optional<YouTubeEmptyRequest>.none
        )
    }

    func disconnectStreamingAccount(accessToken: String, provider: String = "youtube", accountID: String? = nil) async throws {
        guard let url = serverURLProvider("/auth/streaming/accounts/\(provider)" + (accountID.map { "/" + $0 } ?? "")) else {
            throw YouTubeAPIError.configuration
        }
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await authenticatedData(for: request, accessToken: accessToken)
        try validate(response, data: data)
        guard response.statusCode == 204 else {
            throw YouTubeAPIError.response
        }
    }

    func youtubeCategories(accessToken: String) async throws -> [YouTubeBroadcastCategory] {
        let response: YouTubeCategoryResponse = try await request(
            path: "/auth/youtube/categories", method: "GET", accessToken: accessToken,
            body: Optional<YouTubeEmptyRequest>.none)
        return response.categories
    }

    func chzzkCategories(query: String, accessToken: String) async throws -> [CHZZKBroadcastCategory] {
        let response: CHZZKCategoryResponse = try await request(
            path: "/auth/chzzk/categories", method: "GET", accessToken: accessToken,
            body: Optional<YouTubeEmptyRequest>.none,
            queryItems: [URLQueryItem(name: "query", value: query)])
        return response.categories
    }
}
