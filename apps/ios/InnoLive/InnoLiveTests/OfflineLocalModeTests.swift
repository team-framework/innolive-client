import XCTest
@testable import InnoLive

/// 무선 연결을 끈 관리자 로컬 모드다. 모든 요청은 오프라인으로 실패하고 기록되며, 핵심 경로는 요청을 하나도 보내면 안 된다.
@MainActor
final class OfflineLocalModeTests: XCTestCase {
    private let account = "550e8400-e29b-41d4-a716-446655440000"
    private var suite: String!
    private var defaults: UserDefaults!
    private var directory: URL!

    override func setUp() {
        super.setUp()
        suite = "OfflineLocalModeTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(suite)
        OfflineURLProtocol.reset()
        URLProtocol.registerClass(OfflineURLProtocol.self)
    }

    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: OperatingModeMonitor.alwaysLocalKey)
        URLProtocol.unregisterClass(OfflineURLProtocol.self)
        defaults.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: directory)
        super.tearDown()
    }

    private var token: String {
        let payload = Data(#"{"sub":"\#(account)"}"#.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return "eyJhbGciOiJIUzI1NiJ9.\(payload).sig"
    }

    private func makeIntegration(grant: OfflineGrant?) -> YouTubeIntegration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [OfflineURLProtocol.self]
        let api = YouTubeAPI(urlSession: URLSession(configuration: configuration),
                             serverURLProvider: { URL(string: "https://example.invalid\($0)") })
        let token = token
        api.configureAuthentication(accessTokenProvider: { token }, refreshSession: { .unavailable }, onInvalidRefresh: {})
        return YouTubeIntegration(
            preferencesStore: YouTubePreferencesStore(userDefaults: defaults), api: api,
            localModelsAvailable: { true },
            offlineGrantStore: MemoryOfflineGrantStore(grant),
            modelReadiness: ModelReadinessStore(available: true, loaders: [.init(name: "bundled") {}], defaults: defaults),
            planStore: PlanStore(cacheURL: directory.appendingPathComponent("PlanSnapshot.json"))
        )
    }

    func testExpiredGrantLaunchAndTakeMakeNoNetworkRequests() async throws {
        let monthAgo = Date.now.addingTimeInterval(-30 * 24 * 60 * 60)
        let youtube = makeIntegration(grant: OfflineGrant(accountID: account, isAdministrator: true,
                                                          serverDate: monthAgo, receivedAt: monthAgo))

        youtube.planStore.restore(accountID: account)
        XCTAssertTrue(youtube.storedAdministrator(accessToken: token))
        XCTAssertTrue(youtube.isOfflineGrantStale)
        await youtube.modelReadiness.warm()
        XCTAssertTrue(youtube.modelReadiness.isReady)
        XCTAssertNoThrow(try youtube.startLocalTake(), "an expired grant does not block a local take")
        for _ in 0..<5 { await Task.yield() }

        XCTAssertEqual(OfflineURLProtocol.recorded, [], "the offline local-mode path made a network request")
    }

    func testOtherAccountGrantStillBlocksLocalTake() async {
        let youtube = makeIntegration(grant: OfflineGrant(accountID: "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
                                                          isAdministrator: true, serverDate: .now, receivedAt: .now))
        XCTAssertFalse(youtube.storedAdministrator(accessToken: token))
        await youtube.modelReadiness.warm()
        XCTAssertThrowsError(try youtube.startLocalTake())
        XCTAssertEqual(OfflineURLProtocol.recorded, [])
    }

    private let allowed = BroadcastPreparationPermissions(hasMediaTransmissionConsent: true, cameraAuthorized: true,
                                                          microphoneAuthorized: true)

    func testLocalModeDecisionNeedsTheAdministratorMonitor() {
        let youtube = makeIntegration(grant: nil)
        youtube.operatingMode.setAlwaysLocal(true)
        XCTAssertFalse(youtube.startsPrivacyOnlyLocally, "only the admin monitor decides local mode")

        youtube.operatingMode.start { nil }
        defer { youtube.operatingMode.stop() }
        XCTAssertTrue(youtube.startsPrivacyOnlyLocally)
    }

    func testLocalModeStartNeedsNoMediaTransmissionConsent() {
        let youtube = makeIntegration(grant: nil)
        XCTAssertTrue(youtube.transmitsMedia(to: [.youtube]))
        youtube.operatingMode.setAlwaysLocal(true)
        youtube.operatingMode.start { nil }
        defer { youtube.operatingMode.stop() }

        XCTAssertFalse(youtube.transmitsMedia(to: []), "a local take records on this device only")
        XCTAssertFalse(youtube.transmitsMedia(to: [.youtube, .chzzk]), "local mode ignores selected platforms")
    }

    func testLocalModePrivacyStartNeverCreatesSession() async {
        let youtube = makeIntegration(grant: nil)
        youtube.operatingMode.setAlwaysLocal(true)
        youtube.operatingMode.start { nil }
        defer { youtube.operatingMode.stop() }
        var handedOffCamera = false

        let started = await youtube.startBroadcastPreparation(accessToken: token, providers: [], permissions: allowed,
                                                              handoffCamera: { handedOffCamera = true })

        XCTAssertFalse(started)
        XCTAssertFalse(handedOffCamera, "a local start without an administrator grant keeps the camera preview")
        XCTAssertEqual(youtube.errorMessage, OfflineGrant.unconfirmedMessage)
        XCTAssertNil(youtube.session)
        XCTAssertNil(youtube.preparationStatus)
        XCTAssertEqual(OfflineURLProtocol.recorded, [], "the local-mode privacy start made a network request")
    }

    func testNonAdministratorOfflineStartKeepsTheSessionRoute() async {
        let youtube = makeIntegration(grant: nil)

        let started = await youtube.startBroadcastPreparation(accessToken: token, providers: [], permissions: allowed)

        XCTAssertFalse(started)
        XCTAssertEqual(youtube.preparationStatus?.failedPhase, .creatingSession)
        XCTAssertTrue(OfflineURLProtocol.recorded.contains { $0.hasSuffix("/sessions") }, "\(OfflineURLProtocol.recorded)")
    }

    func testLocalCaptureStopMakesNoServerRequest() async {
        let youtube = makeIntegration(grant: nil)
        youtube.localCaptureID = UUID()
        XCTAssertTrue(youtube.isPrivacyOnlySession, "the home 비식별화 종료 control covers a local capture")

        await youtube.cancelBroadcastPreparation(accessToken: token)

        XCTAssertNil(youtube.localCaptureID)
        XCTAssertEqual(OfflineURLProtocol.recorded, [])
    }
}

private final class MemoryOfflineGrantStore: OfflineGrantStoring {
    nonisolated deinit {}
    private var grant: OfflineGrant?
    init(_ grant: OfflineGrant?) { self.grant = grant }
    func save(_ grant: OfflineGrant) { self.grant = grant }
    func load() -> OfflineGrant? { grant }
    func remove() { grant = nil }
}

private final class OfflineURLProtocol: StubURLProtocol {
    static var recorded: [String] { requests.map { $0.url?.absoluteString ?? "?" } }

    override class var fallbackResponse: Response { .failure(.notConnectedToInternet) }
}
