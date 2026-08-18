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

private struct YouTubeStartStreamRequest: Encodable {
    let provider = "youtube"
    let privacy = "private"
}

private struct YouTubeEmptyRequest: Encodable {}

final class YouTubeAPI {
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

    func startStream(session: YouTubeBroadcastSession, accessToken: String) async throws -> YouTubeStreamState {
        let response: YouTubeSessionResponse = try await request(
            path: "/sessions/\(session.sessionID)/stream/start",
            method: "POST",
            accessToken: accessToken,
            ownerToken: session.ownerToken,
            body: YouTubeStartStreamRequest()
        )
        return response.stream
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
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        if let ownerToken { request.setValue(ownerToken, forHTTPHeaderField: "X-Session-Owner-Token") }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder().encode(body)
        }
        let (data, response) = try await perform(request)
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
        if response.statusCode == 401 {
            AuthenticationSessionExpiration.notify()
        }
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
