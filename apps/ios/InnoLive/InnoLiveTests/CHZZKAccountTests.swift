import Foundation
import UIKit
import SwiftUI
import XCTest
@testable import InnoLive

@MainActor
final class CHZZKAccountTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suite: String!
    private let accounts = """
    [{"provider":"youtube","channel_id":"UCfake","channel_title":"YouTube channel","reconnect_required":false},
     {"provider":"chzzk","channel_id":"chzzk-fake","channel_title":"CHZZK channel","reconnect_required":true}]
    """
    private let youtubeOnly = """
    [{"provider":"youtube","channel_id":"UCfake","channel_title":"YouTube channel","reconnect_required":false}]
    """

    override func setUp() {
        super.setUp()
        suite = "CHZZKAccountTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
        CHZZKTestURLProtocol.reset()
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        CHZZKTestURLProtocol.reset()
        super.tearDown()
    }

    func testConfigEncodesStateWithoutBearerAndConnectSendsOnlyCodeAndState() async throws {
        let api = makeAPI()
        let configuration = try await api.chzzkConfiguration(state: "fake + state")
        XCTAssertEqual(configuration.redirectURI.host, "callback.example.invalid")
        let config = try XCTUnwrap(CHZZKTestURLProtocol.requests.first)
        XCTAssertNil(config.value(forHTTPHeaderField: "Authorization"))
        XCTAssertEqual(URLComponents(url: config.url!, resolvingAgainstBaseURL: false)?.queryItems?.first?.value, "fake + state")
        let response = try await api.connectCHZZK(authorization: .init(code: "fake-code", state: "fake-state"), accessToken: "fake-access")
        let request = try XCTUnwrap(CHZZKTestURLProtocol.requests.last)
        XCTAssertEqual(request.url?.path, "/auth/chzzk/connect")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer fake-access")
        XCTAssertEqual(try JSONSerialization.jsonObject(with: request.body()) as? [String: String],
                       ["code": "fake-code", "state": "fake-state"])
        XCTAssertEqual(response.account.channelTitle, "CHZZK channel")
    }

    func testSuccessfulConnectionAndReconnectionRefreshBothPlatformsWithoutPersistingSecrets() async throws {
        CHZZKTestURLProtocol.accountJSON = accounts
        let browser = CHZZKTestAuthorization()
        let integration = makeIntegration(browser)
        await integration.refreshConnection(accessToken: "fake-access")
        XCTAssertTrue(integration.chzzkAccount?.reconnectRequired == true)
        CHZZKTestURLProtocol.accountJSON = accounts.replacingOccurrences(of: "true", with: "false")
        await integration.connectCHZZK(presenting: UIViewController(), accessToken: "fake-access")
        XCTAssertEqual(integration.chzzkAccount?.channelID, "chzzk-fake")
        XCTAssertFalse(try XCTUnwrap(integration.chzzkAccount).reconnectRequired)
        XCTAssertEqual(integration.connection?.channel.id, "UCfake")
        XCTAssertFalse(integration.isConnectingCHZZK)
        XCTAssertNil(integration.chzzkAccountMessage)
        XCTAssertEqual(CHZZKTestURLProtocol.requests.suffix(3).map { $0.url!.path },
                       ["/auth/chzzk/config", "/auth/chzzk/connect", "/auth/streaming/accounts"])
        let stored = String(describing: defaults.dictionaryRepresentation())
        XCTAssertFalse(stored.contains("fake-code"))
        XCTAssertFalse(stored.contains("fake-access"))
        XCTAssertFalse(stored.contains(browser.states.first!))
        await integration.connectCHZZK(presenting: UIViewController(), accessToken: "fake-access")
        XCTAssertEqual(Set(browser.states).count, 2)
    }

    func testCancelledFailedExpiredAndMismatchedAttemptsNeverPostCode() async {
        for failure in [CHZZKAuthorizationError.cancelled, .failed, .expired, .stateMismatch] {
            CHZZKTestURLProtocol.reset()
            let browser = CHZZKTestAuthorization()
            browser.failure = failure
            let integration = makeIntegration(browser)
            await integration.connectCHZZK(presenting: UIViewController(), accessToken: "fake-access")
            XCTAssertEqual(CHZZKTestURLProtocol.requests.map(\.httpMethod), ["GET"])
            XCTAssertEqual(integration.chzzkAccountMessage, failure.userMessage)
            XCTAssertFalse(integration.isConnectingCHZZK)
        }
    }

    func testIntegrationAlsoRejectsBrowserStateMismatch() async {
        let browser = CHZZKTestAuthorization()
        browser.returnWrongState = true
        let integration = makeIntegration(browser)
        await integration.connectCHZZK(presenting: UIViewController(), accessToken: "fake-access")
        XCTAssertEqual(CHZZKTestURLProtocol.requests.count, 1)
        XCTAssertEqual(integration.chzzkAccountMessage, CHZZKAuthorizationError.stateMismatch.userMessage)
    }

    func testServerInvalidCodeScopeChannelAndUnknownErrorsHaveSafeCHZZKMessages() async {
        for code in ["invalid_auth_code", "chzzk_scope_missing", "chzzk_channel_missing", "chzzk_token_exchange_failed", "internal_error"] {
            CHZZKTestURLProtocol.reset()
            CHZZKTestURLProtocol.connectError = code
            let integration = makeIntegration()
            await integration.connectCHZZK(presenting: UIViewController(), accessToken: "fake-access")
            XCTAssertNil(integration.chzzkAccount)
            let message = integration.chzzkAccountMessage ?? ""
            XCTAssertFalse(message.isEmpty)
            XCTAssertFalse(message.contains("private"))
            XCTAssertFalse(message.contains("YouTube"))
            if code == "invalid_auth_code" { XCTAssertTrue(message.contains("만료")) }
        }
    }

    func testDisconnectOnlyCHZZKPreservesYouTubeAndReloadReadsServer() async throws {
        CHZZKTestURLProtocol.accountJSON = accounts
        let integration = makeIntegration()
        await integration.refreshConnection(accessToken: "fake-access")
        CHZZKTestURLProtocol.accountJSON = youtubeOnly
        await integration.disconnectCHZZKAccount(accessToken: "fake-access")
        XCTAssertNil(integration.chzzkAccount)
        XCTAssertEqual(integration.connection?.channel.id, "UCfake")
        let delete = try XCTUnwrap(CHZZKTestURLProtocol.requests.first { $0.httpMethod == "DELETE" })
        XCTAssertEqual(delete.url?.path, "/auth/streaming/accounts/chzzk")
        XCTAssertEqual(delete.value(forHTTPHeaderField: "Authorization"), "Bearer fake-access")
        let reloaded = makeIntegration()
        await reloaded.refreshConnection(accessToken: "fake-access")
        XCTAssertNil(reloaded.chzzkAccount)
        XCTAssertEqual(reloaded.connection?.channel.id, "UCfake")
    }

    func testDisconnectInUseAndServerFailureKeepBothConnections() async {
        for error in ["streaming_account_in_use", "internal_error"] {
            CHZZKTestURLProtocol.reset()
            CHZZKTestURLProtocol.accountJSON = accounts
            let integration = makeIntegration()
            await integration.refreshConnection(accessToken: "fake-access")
            CHZZKTestURLProtocol.deleteError = error
            await integration.disconnectCHZZKAccount(accessToken: "fake-access")
            XCTAssertNotNil(integration.chzzkAccount)
            XCTAssertEqual(integration.connection?.channel.id, "UCfake")
            XCTAssertNotNil(integration.chzzkAccountMessage)
            XCTAssertFalse(integration.isDisconnectingCHZZK)
        }
    }

    func testDisconnectTransportFailureKeepsBothConnections() async {
        CHZZKTestURLProtocol.accountJSON = accounts
        let integration = makeIntegration()
        await integration.refreshConnection(accessToken: "fake-access")
        CHZZKTestURLProtocol.failDeleteTransport = true
        await integration.disconnectCHZZKAccount(accessToken: "fake-access")
        XCTAssertNotNil(integration.chzzkAccount)
        XCTAssertNotNil(integration.connection)
        XCTAssertNotNil(integration.chzzkAccountMessage)
    }

    func testActiveCHZZKTargetBlocksDisconnectButYouTubeOnlyDoesNot() async throws {
        CHZZKTestURLProtocol.accountJSON = accounts
        let integration = makeIntegration()
        await integration.refreshConnection(accessToken: "fake-access")
        let initial = try snapshot(provider: "youtube", phase: "idle", status: "stopped")
        integration.seedPreparedVideoSessionForTesting(connection: try XCTUnwrap(integration.connection),
            session: YouTubeBroadcastSession(sessionID: "fake-session", ownerToken: "fake-owner", stream: initial.stream),
            videoTrack: YouTubeVideoTrackState(id: "fake-video", kind: "video", readyState: "live"))
        for provider in ["youtube", "chzzk"] {
            let response = try snapshot(provider: provider, phase: "live")
            integration.applyLiveEditingSnapshotForTesting(response)
            XCTAssertEqual(integration.canDisconnectCHZZKAccount, provider == "youtube")
        }
        await integration.disconnectCHZZKAccount(accessToken: "fake-access")
        XCTAssertEqual(CHZZKTestURLProtocol.requests.count, 1)
    }

    func testPolicyBlocksPreparingPreparedPausedUnknownAndResolutionSwitch() throws {
        for phase in ["preparing", "prepared", "going_live", "live", "future_phase"] {
            var state = BroadcastSessionSnapshot()
            state.apply(try snapshot(provider: "chzzk", phase: phase, status: "paused"))
            XCTAssertTrue(StreamingAccountPolicy.isInUse("chzzk", snapshot: state))
            XCTAssertFalse(StreamingAccountPolicy.isInUse("youtube", snapshot: state))
        }
        var state = BroadcastSessionSnapshot()
        state.apply(try snapshot(provider: "chzzk", phase: "idle", status: "stopped", switching: true))
        XCTAssertTrue(StreamingAccountPolicy.isInUse("chzzk", snapshot: state))
    }

    func testResetDuringBrowserCancelsAndNeverConnectsOldAccount() async {
        let browser = CHZZKTestAuthorization()
        browser.shouldWait = true
        let started = expectation(description: "browser started")
        browser.onStart = { started.fulfill() }
        let integration = makeIntegration(browser)
        let task = Task { await integration.connectCHZZK(presenting: UIViewController(), accessToken: "fake-access") }
        await fulfillment(of: [started], timeout: 2)
        let prepared = await integration.startBroadcastPreparation(accessToken: "fake-access",
            permissions: BroadcastPreparationPermissions(hasMediaTransmissionConsent: true,
                cameraAuthorized: true, microphoneAuthorized: true))
        XCTAssertFalse(prepared)
        XCTAssertEqual(CHZZKTestURLProtocol.requests.count, 1)
        integration.reset()
        await task.value
        XCTAssertTrue(browser.wasCancelled)
        XCTAssertNil(integration.chzzkAccount)
        XCTAssertNil(integration.chzzkAccountMessage)
        XCTAssertEqual(CHZZKTestURLProtocol.requests.count, 1)
    }

    func testRefreshFailureKeepsAccountsAndAuthoritativeEmptyResponseClearsThem() async {
        CHZZKTestURLProtocol.accountJSON = accounts
        let integration = makeIntegration()
        await integration.refreshConnection(accessToken: "fake-access")
        CHZZKTestURLProtocol.listError = "internal_error"
        await integration.refreshConnection(accessToken: "fake-access")
        XCTAssertNotNil(integration.chzzkAccount)
        CHZZKTestURLProtocol.listError = nil
        CHZZKTestURLProtocol.accountJSON = "[]"
        await integration.refreshConnection(accessToken: "fake-access")
        XCTAssertNil(integration.chzzkAccount)
        XCTAssertNil(integration.connection)
    }

    func testContractFixturesDecodeMixedProvidersAndCamelCaseChannel() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let fixture = root.appendingPathComponent("contracts/fixtures")
        let accounts = try JSONDecoder().decode([StreamingAccountSummary].self,
            from: Data(contentsOf: fixture.appendingPathComponent("streaming-accounts.v1.json")))
        XCTAssertEqual(accounts.map(\.provider), ["youtube", "chzzk"])
        XCTAssertNotNil(accounts[0].youtubeConnection)
        XCTAssertNil(accounts[1].youtubeConnection)
        XCTAssertTrue(accounts[1].reconnectRequired)
        let connected = try JSONDecoder().decode(CHZZKConnectionResponse.self,
            from: Data(contentsOf: fixture.appendingPathComponent("chzzk-connect.v1.json")))
        XCTAssertEqual(connected.account.channelID, "chzzk-example")
    }

    func testEmptyChannelTitleFallsBackToIDAndUnknownProviderIsPreserved() throws {
        let data = Data("[{\"provider\":\"future\",\"channel_id\":\"id\",\"channel_title\":\"\",\"reconnect_required\":false}]".utf8)
        let accounts = try JSONDecoder().decode([StreamingAccountSummary].self, from: data)
        XCTAssertEqual(accounts[0].displayTitle, "id")
        XCTAssertEqual(accounts[0].provider, "future")
    }

    func testConnectRefreshesOnlyInnoLive401BeforeRetry() async throws {
        CHZZKTestURLProtocol.unauthorizedConnectCount = 1
        var refreshed = false
        let api = makeAPI()
        api.configureAuthentication(accessTokenProvider: { refreshed ? "fake-new" : "fake-old" },
            refreshSession: { refreshed = true; return .refreshed }, onInvalidRefresh: {})
        _ = try await api.connectCHZZK(authorization: .init(code: "fake-code", state: "fake-state"), accessToken: "fake-old")
        XCTAssertEqual(CHZZKTestURLProtocol.requests.count, 2)
        XCTAssertEqual(CHZZKTestURLProtocol.requests.last?.value(forHTTPHeaderField: "Authorization"), "Bearer fake-new")
    }

    func testAccountScreenRendersConnectionAndReconnectionAtLargeTextSizes() async throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive })
        let previousWindow = scene.windows.first { $0.isKeyWindow }
        let authentication = AuthSession(api: AuthenticationAPI(serverURLProvider: { _ in nil }),
            tokenStore: CHZZKEmptyTokenStore(), consentStore: ConsentAcknowledgementStore(userDefaults: defaults))
        for connected in [false, true] {
            CHZZKTestURLProtocol.accountJSON = connected ? accounts : youtubeOnly
            let integration = makeIntegration()
            await integration.refreshConnection(accessToken: "fake-access")
            let controller = UIHostingController(rootView: NavigationStack {
                BroadcastPlatformSelectionView(authentication: authentication, youtube: integration)
            }.dynamicTypeSize(connected ? .accessibility3 : .large))
            let window = UIWindow(windowScene: scene)
            window.frame = scene.coordinateSpace.bounds
            window.overrideUserInterfaceStyle = connected ? .dark : .light
            window.rootViewController = controller
            window.makeKeyAndVisible()
            try await Task.sleep(for: .milliseconds(400))
            window.layoutIfNeeded()
            let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
                XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
            }
            XCTAssertGreaterThan(try XCTUnwrap(image.pngData()).count, 10_000)
            let attachment = XCTAttachment(image: image)
            attachment.name = connected ? "chzzk-reconnect-large-text-dark" : "chzzk-connect-light"
            attachment.lifetime = .keepAlways
            add(attachment)
            window.isHidden = true
            window.rootViewController = nil
            previousWindow?.makeKeyAndVisible()
            XCTAssertEqual(integration.chzzkAccount != nil, connected)
            XCTAssertNotNil(integration.connection)
            XCTAssertFalse(integration.videoUplink.isActive)
        }
    }

    private func makeAPI() -> YouTubeAPI {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [CHZZKTestURLProtocol.self]
        return YouTubeAPI(urlSession: URLSession(configuration: configuration),
                          serverURLProvider: { URL(string: "https://example.invalid\($0)") })
    }

    private func makeIntegration(_ browser: CHZZKTestAuthorization? = nil) -> YouTubeIntegration {
        YouTubeIntegration(preferencesStore: YouTubePreferencesStore(userDefaults: defaults), api: makeAPI(),
                           chzzkAuthorization: browser ?? CHZZKTestAuthorization())
    }

    private func snapshot(provider: String, phase: String, status: String = "streaming", switching: Bool = false) throws -> YouTubeSessionResponse {
        let stream = "{\"status\":\"\(status)\",\"broadcast_phase\":\"\(phase)\",\"publisher_active\":true,\"reconnect_attempts\":0}"
        let json = "{\"stream\":\(stream),\"targets\":[{\"provider\":\"\(provider)\",\"stream\":\(stream)}]"
            + (switching ? ",\"resolution_switch\":{\"status\":\"switching\",\"resolution\":\"720p\",\"targets\":[\"chzzk\"],\"started_at\":\"2026-10-01T00:00:00Z\"}" : "") + "}"
        return try JSONDecoder().decode(YouTubeSessionResponse.self, from: Data(json.utf8))
    }
}

@MainActor
private final class CHZZKTestAuthorization: CHZZKAuthorizing {
    nonisolated deinit {}
    var failure: CHZZKAuthorizationError?
    var returnWrongState = false
    var states: [String] = []
    var shouldWait = false
    var wasCancelled = false
    var onStart: (() -> Void)?
    private var continuation: CheckedContinuation<CHZZKAuthorizationCode, Error>?

    func authorize(configuration: CHZZKConfiguration, attempt: CHZZKAuthorizationAttempt,
                   presenting: UIViewController) async throws -> CHZZKAuthorizationCode {
        states.append(attempt.state)
        if let failure { throw failure }
        if shouldWait {
            return try await withCheckedThrowingContinuation { continuation in
                self.continuation = continuation
                onStart?()
            }
        }
        return CHZZKAuthorizationCode(code: "fake-code", state: returnWrongState ? "wrong" : attempt.state)
    }

    func cancel() {
        wasCancelled = true
        continuation?.resume(throwing: CHZZKAuthorizationError.cancelled)
        continuation = nil
    }
}

private final class CHZZKTestURLProtocol: URLProtocol {
    nonisolated deinit {}
    nonisolated(unsafe) static var requests: [URLRequest] = []
    nonisolated(unsafe) static var accountJSON = "[]"
    nonisolated(unsafe) static var connectError: String?
    nonisolated(unsafe) static var deleteError: String?
    nonisolated(unsafe) static var listError: String?
    nonisolated(unsafe) static var unauthorizedConnectCount = 0
    nonisolated(unsafe) static var failDeleteTransport = false

    static func reset() {
        requests = []; accountJSON = "[]"; connectError = nil; deleteError = nil; listError = nil; unauthorizedConnectCount = 0; failDeleteTransport = false
    }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests.append(request)
        if request.httpMethod == "DELETE", Self.failDeleteTransport {
            client?.urlProtocol(self, didFailWithError: URLError(.timedOut))
            return
        }
        var status = 200
        var data = Data()
        let error: String?
        switch request.url!.path {
        case "/auth/youtube/config":
            data = Data("{\"web_client_id\":\"fake-client\",\"scope\":\"fake-scope\"}".utf8)
            error = nil
        case "/auth/chzzk/config":
            let state = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!.first!.value!
            var authorize = URLComponents(string: "https://chzzk.naver.com/account-interlock")!
            let redirect = "https://callback.example.invalid/auth/chzzk/callback"
            authorize.queryItems = [.init(name: "clientId", value: "fake-client"), .init(name: "redirectUri", value: redirect), .init(name: "state", value: state)]
            data = try! JSONSerialization.data(withJSONObject: ["client_id": "fake-client", "redirect_uri": redirect, "authorize_url": authorize.url!.absoluteString])
            error = nil
        case "/auth/chzzk/connect":
            data = Data("{\"connected\":true,\"provider\":\"chzzk\",\"channel\":{\"channelId\":\"chzzk-fake\",\"channelName\":\"CHZZK channel\",\"nickname\":\"nickname\"}}".utf8)
            error = Self.connectError
        case "/auth/streaming/accounts/chzzk": status = 204; error = Self.deleteError
        case "/auth/streaming/accounts": data = Data(Self.accountJSON.utf8); error = Self.listError
        default: status = 404; error = "not_found"
        }
        if let error { status = 409; data = Data("{\"error\":{\"code\":\"\(error)\",\"message\":\"private code=fake-code token=fake-access\"}}".utf8) }
        if request.url!.path == "/auth/chzzk/connect", Self.unauthorizedConnectCount > 0 {
            Self.unauthorizedConnectCount -= 1
            status = 401
            data = Data("{\"error\":{\"code\":\"unauthorized\"}}".utf8)
        }
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil,
                                       headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

private extension URLRequest {
    func body() throws -> Data {
        if let httpBody { return httpBody }
        guard let stream = httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 1024)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count <= 0 { break }
            data.append(buffer, count: count)
        }
        return data
    }
}

private final class CHZZKEmptyTokenStore: AuthenticationTokenStoring {
    nonisolated deinit {}
    func load() -> AuthenticationTokenPair? { nil }
    func save(_ tokens: AuthenticationTokenPair) throws { XCTFail("Rendering must not sign in") }
    func remove() { XCTFail("Rendering must not sign out") }
}
