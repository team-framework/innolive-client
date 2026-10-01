#if DEBUG
import SwiftUI

// 합성 서버 응답으로 실제 편집 폼·저장·오류·재시도를 수동 검증한다.
struct LiveBroadcastEditingValidationView: View {
    @StateObject private var authentication: AuthSession
    @StateObject private var integration: YouTubeIntegration
    private let provider: BroadcastSettingsProvider

    init() {
        provider = ProcessInfo.processInfo.arguments.contains("--live-edit-chzzk") ? .chzzk : .youtube
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [LiveEditingPreviewProtocol.self]
        LiveEditingPreviewProtocol.failNextSave = ProcessInfo.processInfo.arguments.contains("--live-edit-failure")
        let api = YouTubeAPI(urlSession: URLSession(configuration: configuration), serverURLProvider: { URL(string: "https://example.invalid\($0)") })
        let integration = YouTubeIntegration(preferencesStore: YouTubePreferencesStore(userDefaults: UserDefaults(suiteName: "live-edit-preview")!), api: api)
        let session = try! JSONDecoder().decode(YouTubeBroadcastSession.self, from: Data(LiveEditingPreviewProtocol.snapshot.utf8))
        integration.seedPreparedVideoSessionForTesting(connection: .init(provider: "youtube", channel: .init(id: "preview", title: "Preview")), session: session, videoTrack: .init(id: "video", kind: "video", readyState: "live"))
        integration.settingsEditor.editYouTube { $0.title = "InnoLive 라이브 방송"; $0.description = "방송 정보 수정 검증"; $0.audience = .notMadeForKids; $0.categoryID = "20" }
        integration.settingsEditor.editCHZZK { $0.title = "InnoLive 치지직 방송"; $0.tags = ["개발"] }
        _integration = StateObject(wrappedValue: integration)
        _authentication = StateObject(wrappedValue: AuthSession(tokenStore: LiveEditingPreviewTokenStore()))
    }
    var body: some View {
        NavigationStack {
            BroadcastSettingsView(authentication: authentication, youtube: integration, liveProvider: provider)
        }
    }
}

private final class LiveEditingPreviewTokenStore: AuthenticationTokenStoring {
    func save(_ tokens: AuthenticationTokenPair) throws {}
    func load() -> AuthenticationTokenPair? {
        .init(accessToken: "h." + Data("{\"sub\":\"preview\"}".utf8).base64EncodedString() + ".s", refreshToken: "preview")
    }
    func remove() {}
}

private final class LiveEditingPreviewProtocol: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var failNextSave = false
    static let snapshot = """
    {"session_id":"preview","owner_token":"preview-owner","stream":{"status":"streaming","broadcast_phase":"live","publisher_active":true,"reconnect_attempts":0},"targets":[{"provider":"youtube","stream":{"status":"streaming","broadcast_phase":"live","publisher_active":true,"reconnect_attempts":0}},{"provider":"chzzk","stream":{"status":"streaming","broadcast_phase":"live","publisher_active":true,"reconnect_attempts":0}}],"media":{}}
    """
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let isPatch = request.httpMethod == "PATCH"
        let failure = isPatch && Self.failNextSave
        if isPatch { Self.failNextSave = false }
        let body: String
        if failure { body = "{\"error\":{\"code\":\"streaming_update_failed\"}}" }
        else if isPatch { body = Self.snapshot }
        else if request.url?.path.contains("chzzk") == true { body = "{\"categories\":[{\"category_type\":\"GAME\",\"category_id\":\"test-game\",\"category_value\":\"게임\"}]}" }
        else { body = "{\"categories\":[{\"id\":\"20\",\"title\":\"게임\"},{\"id\":\"22\",\"title\":\"인물/블로그\"}]}" }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: failure ? 502 : 200, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
#endif
