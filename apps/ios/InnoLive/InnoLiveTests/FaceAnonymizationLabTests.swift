import XCTest
@testable import InnoLive

@MainActor
final class FaceAnonymizationLabTests: XCTestCase {
    private var api: YouTubeAPI!

    override func setUp() {
        super.setUp()
        FaceAnonymizationLabURLProtocol.requests = []
        FaceAnonymizationLabURLProtocol.responses = []
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [FaceAnonymizationLabURLProtocol.self]
        api = YouTubeAPI(urlSession: URLSession(configuration: configuration), serverURLProvider: { URL(string: "https://example.invalid\($0)") })
    }

    private func encodedObject(_ value: some Encodable) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(value)) as? [String: Any])
    }

    func testDefaultModeIsBlur() {
        let key = FaceAnonymizationMode.storageKey
        let previous = UserDefaults.standard.string(forKey: key)
        defer { UserDefaults.standard.set(previous, forKey: key) }
        UserDefaults.standard.removeObject(forKey: key)
        XCTAssertEqual(FaceAnonymizationMode.selected, .blur)
    }

    func testSessionRequestSendsFaceSwapOnlyForServerProcessing() throws {
        let swap = try encodedObject(YouTubeSessionCreateRequest(mode: .server, faceAnonymization: .faceSwap))
        XCTAssertEqual(swap as? [String: String], ["face_anonymization": "face_swap"])
        // 블러는 서버 기본값이라 생략한다. 온디바이스 + 합성은 서버가 400으로 거절한다.
        XCTAssertTrue(try encodedObject(YouTubeSessionCreateRequest(mode: .server, faceAnonymization: .blur)).isEmpty)
        let onDevice = try encodedObject(YouTubeSessionCreateRequest(mode: .onDevice, faceAnonymization: .faceSwap))
        XCTAssertNil(onDevice["face_anonymization"])
    }

    func testAdministratorCheckFollowsAdminMeStatus() async throws {
        FaceAnonymizationLabURLProtocol.responses = [
            (200, Data(#"{"user_id":"550e8400-e29b-41d4-a716-446655440000"}"#.utf8)),
            (403, Data(#"{"error":{"code":"forbidden","message":"Administrator access is required."}}"#.utf8)),
            (404, Data()),
        ]
        let isAdmin = try await api.isAdministrator(accessToken: "token")
        let forbidden = try await api.isAdministrator(accessToken: "token")
        let missingRoute = try await api.isAdministrator(accessToken: "token")
        XCTAssertTrue(isAdmin)
        XCTAssertFalse(forbidden)
        XCTAssertFalse(missingRoute)
        let request = try XCTUnwrap(FaceAnonymizationLabURLProtocol.requests.first)
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.url?.path, "/admin/me")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer token")
    }

    func testCreateSessionSendsFaceSwap() async throws {
        FaceAnonymizationLabURLProtocol.responses = [
            (201, Data(#"{"session_id":"s1","owner_token":"o1","broadcast_resolution":"720p","face_anonymization":"face_swap","stream":{"status":"idle","publisher_active":false,"reconnect_attempts":0},"media":{}}"#.utf8)),
        ]
        _ = try await api.createSession(accessToken: "token", mode: .server, faceAnonymization: .faceSwap)
        let request = try XCTUnwrap(FaceAnonymizationLabURLProtocol.requests.first)
        XCTAssertEqual(request.url?.path, "/sessions")
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: Self.body(of: request)) as? [String: Any])
        XCTAssertEqual(body["face_anonymization"] as? String, "face_swap")
    }

    func testFaceSwapReservesDescriptionRoomForServerNotice() {
        // 서버 FaceSwapDescriptionNotice와 빈 줄 길이만큼 유튜브 한도에서 뺀다.
        let notice = "※ 이 방송에는 AI를 이용한 얼굴 합성이 포함되어 있습니다.\n\n"
        XCTAssertEqual(YouTubeBroadcastSettings.maxFaceSwapDescriptionLength,
                       YouTubeBroadcastSettings.maxDescriptionLength - notice.unicodeScalars.count)
        var settings = YouTubeBroadcastSettings.defaultValue
        settings.description = String(repeating: "설", count: YouTubeBroadcastSettings.maxFaceSwapDescriptionLength)
        XCTAssertNil(settings.validation(faceSwap: true)["description"])
        settings.description += "설"
        XCTAssertNotNil(settings.validation(faceSwap: true)["description"])
        XCTAssertNil(settings.validation(faceSwap: false)["description"])
    }

    func testEditorAppliesFaceSwapLimitOnlyForServerProcessing() throws {
        let suite = "face-swap-description-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        var mode = AIProcessingMode.server
        let integration = YouTubeIntegration(preferencesStore: YouTubePreferencesStore(userDefaults: defaults), api: api,
                                             aiModeProvider: { mode }, faceAnonymizationProvider: { .faceSwap })
        integration.settingsEditor.editYouTube {
            $0.description = String(repeating: "설", count: YouTubeBroadcastSettings.maxFaceSwapDescriptionLength + 1)
        }
        XCTAssertTrue(integration.usesFaceSwap)
        XCTAssertNotNil(integration.settingsEditor.validation["description"])
        // 온디바이스는 합성을 쓰지 않아 유튜브 기본 한도를 쓴다.
        mode = .onDevice
        XCTAssertFalse(integration.usesFaceSwap)
        XCTAssertNil(integration.settingsEditor.validation["description"])
    }

    private static func body(of request: URLRequest) -> Data {
        if let body = request.httpBody { return body }
        guard let input = request.httpBodyStream else { return Data() }
        input.open()
        defer { input.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 1024)
        while input.hasBytesAvailable {
            let count = input.read(&buffer, maxLength: buffer.count)
            if count <= 0 { break }
            data.append(buffer, count: count)
        }
        return data
    }
}

private final class FaceAnonymizationLabURLProtocol: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var requests: [URLRequest] = []
    nonisolated(unsafe) static var responses: [(Int, Data)] = []
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests.append(request)
        let (status, body) = Self.responses.isEmpty ? (500, Data()) : Self.responses.removeFirst()
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil,
            headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
