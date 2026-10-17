import Foundation
import XCTest
@testable import InnoLive

@MainActor
final class BroadcastSessionLifecycleTests: XCTestCase {
    private var store: MemoryBroadcastSessionStore!
    private var defaults: UserDefaults!
    private var suite: String!
    private let old = StoredBroadcastSession(sessionID: "old", ownerToken: "old-owner")
    private var token: String { Self.token("user-a") }

    override func setUp() {
        super.setUp()
        store = MemoryBroadcastSessionStore()
        suite = "broadcast-lifecycle-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
        SessionLifecycleURLProtocol.responses = []
        SessionLifecycleURLProtocol.requests = []
        SessionLifecycleURLProtocol.beforeResponse = nil
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        SessionLifecycleURLProtocol.beforeResponse = nil
        super.tearDown()
    }

    private func seedExhaustedPlan(_ integration: YouTubeIntegration) async throws {
        struct Stub: PlanAPIClient {
            func userPlan(accessToken: String) async throws -> UserPlan {
                try JSONDecoder().decode(UserPlan.self, from: Data(#"{"plan":"beam","allowed_modes":["720p_single"],"monthly_broadcast_seconds":100,"max_per_broadcast_seconds":3600,"device_modes":["720p_single"]}"#.utf8))
            }
            func userUsage(accessToken: String) async throws -> UserUsage {
                try JSONDecoder().decode(UserUsage.self, from: Data(#"{"plan":"beam","used_seconds":100,"remaining_seconds":0,"device_remaining_seconds":3600,"available_by_mode":[{"mode":"720p_single","allowed":true,"seconds":0,"multiplier":1}]}"#.utf8))
            }
        }
        await integration.planStore.refresh(api: Stub(), accessToken: token)
    }

    func testOnDeviceFallbackDeletesOldSessionBeforeCreatingWithoutGoLive() async throws {
        let integration = makeIntegration()
        SessionLifecycleURLProtocol.responses = [.created("old"), .empty(204), .created("device", mode: "on_device")]
        _ = await integration.prepareSession(accessToken: token)
        try await seedExhaustedPlan(integration)
        XCTAssertTrue(integration.offersOnDevicePreparation)
        let switched = await integration.switchPreparationToOnDevice(accessToken: token)
        XCTAssertTrue(switched)
        XCTAssertNil(integration.session)
        XCTAssertEqual(integration.planProcessingMode, .onDevice)
        let created = await integration.prepareSession(accessToken: token, broadcastResolution: "fhd")
        XCTAssertTrue(created)
        XCTAssertEqual(methods, ["POST", "DELETE", "POST"])
        let last = try XCTUnwrap(SessionLifecycleURLProtocol.requests.last)
        let json = try screenBlurRequestBody(last)
        XCTAssertEqual(json["ai_processing"] as? String, "on_device")
        XCTAssertEqual(json["broadcast_resolution"] as? String, "fhd")
        XCTAssertFalse(SessionLifecycleURLProtocol.requests.contains { $0.url!.path.contains("golive") })
    }

    func testOnDeviceFallbackDeleteFailureBlocksNewSession() async throws {
        let integration = makeIntegration()
        SessionLifecycleURLProtocol.responses = [.created("old"), .empty(503)]
        _ = await integration.prepareSession(accessToken: token)
        try await seedExhaustedPlan(integration)
        let switched = await integration.switchPreparationToOnDevice(accessToken: token)
        XCTAssertFalse(switched)
        XCTAssertEqual(methods, ["POST", "DELETE"])
        XCTAssertEqual(integration.planProcessingMode, .server)
        XCTAssertNotNil(try store.load(scope: scope()))
    }

    func testOnDeviceFallbackRetriesRetainedSessionCleanup() async throws {
        let integration = makeIntegration()
        SessionLifecycleURLProtocol.responses = [.created("old"), .empty(503), .empty(204), .created("device", mode: "on_device")]
        _ = await integration.prepareSession(accessToken: token)
        try await seedExhaustedPlan(integration)
        let first = await integration.switchPreparationToOnDevice(accessToken: token)
        XCTAssertFalse(first)
        XCTAssertNil(integration.session)
        let retry = await integration.switchPreparationToOnDevice(accessToken: token)
        XCTAssertTrue(retry)
        XCTAssertNil(try store.load(scope: scope()))
        let created = await integration.prepareSession(accessToken: token)
        XCTAssertTrue(created)
        XCTAssertEqual(methods, ["POST", "DELETE", "DELETE", "POST"])
    }

    func testRetainedSessionCleanupBlocksChannelChanges() async throws {
        let integration = makeIntegration()
        SessionLifecycleURLProtocol.responses = [
            .init(status: 200, body: #"[{"id":"a","provider":"youtube","channel_id":"uca","channel_title":"A","reconnect_required":false},{"id":"b","provider":"youtube","channel_id":"ucb","channel_title":"B","reconnect_required":false}]"#),
            .empty(204)
        ]
        await integration.refreshConnection(accessToken: token)
        try store.save(old, scope: scope())
        try await seedExhaustedPlan(integration)
        var checked = false
        SessionLifecycleURLProtocol.beforeResponse = { request in
            guard request.httpMethod == "DELETE" else { return }
            checked = true
            XCTAssertTrue(integration.isSwitchingToOnDevice)
            XCTAssertTrue(integration.isYouTubeAccountChangeBlocked)
            XCTAssertTrue(integration.isCHZZKAccountChangeBlocked)
            XCTAssertFalse(integration.canSelectYouTubeChannel)
            integration.selectYouTubeChannel("b", accessToken: self.token)
            XCTAssertEqual(integration.selectedYouTubeAccountID, "a")
            guard let account = integration.youtubeAccounts.last else { XCTFail("Expected connected channel"); return }
            XCTAssertFalse(integration.canDisconnectYouTubeChannel(account))
            await integration.disconnectYouTubeAccount(accessToken: self.token, accountID: "b")
        }
        let switched = await integration.switchPreparationToOnDevice(accessToken: token)
        XCTAssertTrue(switched)
        XCTAssertTrue(checked)
        XCTAssertEqual(methods, ["GET", "DELETE"])
        XCTAssertEqual(integration.youtubeAccounts.count, 2)
    }

    func testAccountRefreshBlocksOnDeviceSwitch() async throws {
        let integration = makeIntegration()
        try await seedExhaustedPlan(integration)
        SessionLifecycleURLProtocol.responses = [.init(status: 200, body: "[]")]
        SessionLifecycleURLProtocol.beforeResponse = { _ in
            XCTAssertTrue(integration.isYouTubeConnectionOperationInProgress)
            let switched = await integration.switchPreparationToOnDevice(accessToken: self.token)
            XCTAssertFalse(switched)
        }
        await integration.refreshConnection(accessToken: token)
        XCTAssertEqual(integration.planProcessingMode, .server)
        XCTAssertEqual(methods, ["GET"])
    }

    func testAccountResetDuringFallbackRejectsLateSwitch() async throws {
        let integration = makeIntegration()
        SessionLifecycleURLProtocol.responses = [.created("old"), .empty(204)]
        _ = await integration.prepareSession(accessToken: token)
        try await seedExhaustedPlan(integration)
        SessionLifecycleURLProtocol.beforeResponse = { request in
            if request.httpMethod == "DELETE" { integration.reset() }
        }
        let switched = await integration.switchPreparationToOnDevice(accessToken: token)
        XCTAssertFalse(switched)
        XCTAssertEqual(integration.planProcessingMode, .server)
        XCTAssertFalse(integration.isSwitchingToOnDevice)
        XCTAssertEqual(methods, ["POST", "DELETE"])
    }

    func testColdLaunchDeletesPreviousBeforeCreatingAndSavesCredentials() async throws {
        try store.save(old, scope: scope())
        SessionLifecycleURLProtocol.responses = [.empty(204), .created("new")]
        let integration = makeIntegration()
        let result = await integration.prepareSession(accessToken: token)
        XCTAssertTrue(result)
        XCTAssertEqual(methods, ["DELETE", "POST"])
        XCTAssertEqual(try store.load(scope: scope())?.sessionID, "new")
        XCTAssertEqual(integration.session?.sessionID, "new")
    }

    func testAnotherLaunchDeletesTheLastCreatedSession() async throws {
        SessionLifecycleURLProtocol.responses = [.created("first"), .empty(204), .created("second")]
        let first = makeIntegration()
        let firstResult = await first.prepareSession(accessToken: token)
        XCTAssertTrue(firstResult)
        let second = makeIntegration()
        let secondResult = await second.prepareSession(accessToken: token)
        XCTAssertTrue(secondResult)
        XCTAssertEqual(methods, ["POST", "DELETE", "POST"])
        XCTAssertEqual(SessionLifecycleURLProtocol.requests[1].url?.path, "/sessions/first")
    }

    func testExistingInMemorySessionIsReusedWithoutDeletion() async {
        SessionLifecycleURLProtocol.responses = [.created("current")]
        let integration = makeIntegration()
        let first = await integration.prepareSession(accessToken: token)
        let second = await integration.prepareSession(accessToken: token)
        XCTAssertTrue(first && second)
        XCTAssertEqual(methods, ["POST"])
    }

    func testDeletionFailureRetainsRecordAndRetryCleansBeforeCreation() async throws {
        try store.save(old, scope: scope())
        SessionLifecycleURLProtocol.responses = [.empty(503), .empty(204), .created("new")]
        let integration = makeIntegration()
        let failed = await integration.prepareSession(accessToken: token)
        XCTAssertFalse(failed)
        XCTAssertEqual(methods, ["DELETE"])
        XCTAssertEqual(try store.load(scope: scope()), old)
        XCTAssertNotNil(integration.errorMessage)
        let retried = await integration.prepareSession(accessToken: token)
        XCTAssertTrue(retried)
        XCTAssertEqual(methods, ["DELETE", "DELETE", "POST"])
        XCTAssertNil(integration.errorMessage)
    }

    func testMissingSessionAllowsCreation() async throws {
        try store.save(old, scope: scope())
        SessionLifecycleURLProtocol.responses = [.init(status: 404, body: "{\"error\":{\"code\":\"not_found\"}}"), .created("new")]
        let result = await makeIntegration().prepareSession(accessToken: token)
        XCTAssertTrue(result)
        XCTAssertEqual(methods, ["DELETE", "POST"])
    }

    func testStoreReadFailureBlocksCreation() async {
        store.failLoad = true
        let result = await makeIntegration().prepareSession(accessToken: token)
        XCTAssertFalse(result)
        XCTAssertTrue(methods.isEmpty)
    }

    func testStorageFailureDeletesCreatedSessionAndDoesNotConnect() async {
        store.failSave = true
        SessionLifecycleURLProtocol.responses = [.created("new"), .empty(204)]
        let integration = makeIntegration()
        let result = await integration.prepareSession(accessToken: token)
        XCTAssertFalse(result)
        XCTAssertNil(integration.session)
        XCTAssertEqual(methods, ["POST", "DELETE"])
    }

    func testStorageAndCleanupFailureRetainsInMemoryCleanupForRetry() async {
        store.failSave = true
        SessionLifecycleURLProtocol.responses = [.created("new"), .empty(503), .empty(204), .created("retry")]
        let integration = makeIntegration()
        let failed = await integration.prepareSession(accessToken: token)
        XCTAssertFalse(failed)
        store.failSave = false
        let retried = await integration.prepareSession(accessToken: token)
        XCTAssertTrue(retried)
        XCTAssertEqual(methods, ["POST", "DELETE", "DELETE", "POST"])
    }

    func testOtherAccountRecordIsNotDeleted() async throws {
        try store.save(old, scope: scope())
        SessionLifecycleURLProtocol.responses = [.created("user-b-session")]
        let result = await makeIntegration().prepareSession(accessToken: Self.token("user-b"))
        XCTAssertTrue(result)
        XCTAssertEqual(methods, ["POST"])
        XCTAssertEqual(try store.load(scope: scope()), old)
    }

    func testExplicitEndDeletesSessionAndClearsJournal() async throws {
        SessionLifecycleURLProtocol.responses = [.created("new"), .empty(204)]
        let integration = makeIntegration()
        _ = await integration.prepareSession(accessToken: token)
        await integration.endBroadcast(accessToken: token)
        XCTAssertNil(integration.session)
        XCTAssertNil(try store.load(scope: scope()))
        XCTAssertEqual(methods, ["POST", "DELETE"])
    }

    func testEndFailureAndResetPreserveJournal() async throws {
        SessionLifecycleURLProtocol.responses = [.created("new"), .empty(503)]
        let integration = makeIntegration()
        _ = await integration.prepareSession(accessToken: token)
        await integration.endBroadcast(accessToken: token)
        integration.reset()
        XCTAssertEqual(try store.load(scope: scope())?.sessionID, "new")
        XCTAssertNil(integration.session)
    }

    func testVideoFailureDeletesStoredSession() async throws {
        SessionLifecycleURLProtocol.responses = [.created("new"), .empty(204)]
        let integration = makeIntegration()
        _ = await integration.prepareSession(accessToken: token)
        await integration.recoverFromVideoUplinkFailure(accessToken: token)
        XCTAssertNil(try store.load(scope: scope()))
        XCTAssertNil(integration.session)
        XCTAssertEqual(methods, ["POST", "DELETE"])
    }

    func testConcurrentPreparationCreatesOnlyOneSession() async {
        SessionLifecycleURLProtocol.responses = [.created("new")]
        let integration = makeIntegration()
        async let first = integration.prepareSession(accessToken: token)
        async let second = integration.prepareSession(accessToken: token)
        let results = await (first, second)
        XCTAssertTrue(results.0 && results.1)
        XCTAssertEqual(methods, ["POST"])
    }

    func testResetDuringCreateDoesNotRestoreSessionAndKeepsCleanupRecord() async throws {
        SessionLifecycleURLProtocol.responses = [.created("new")]
        let integration = makeIntegration()
        SessionLifecycleURLProtocol.beforeResponse = { _ in integration.reset() }
        let result = await integration.prepareSession(accessToken: token)
        XCTAssertFalse(result)
        XCTAssertNil(integration.session)
        XCTAssertEqual(try store.load(scope: scope())?.sessionID, "new")
    }

    func testAccountChangesDuringDeletionPreventCreation() async throws {
        try store.save(old, scope: scope())
        SessionLifecycleURLProtocol.responses = [.empty(204)]
        var currentToken = token
        let api = makeAPI()
        api.configureAuthentication(accessTokenProvider: { currentToken }, refreshSession: { .invalid }, onInvalidRefresh: {})
        let integration = makeIntegration(api: api)
        SessionLifecycleURLProtocol.beforeResponse = { _ in currentToken = Self.token("user-b") }
        let result = await integration.prepareSession(accessToken: token)
        XCTAssertFalse(result)
        XCTAssertNil(integration.session)
        XCTAssertEqual(methods, ["DELETE"])
    }

    func testAccountChangeDuringRefreshDoesNotRetryWithNewAccount() async throws {
        try store.save(old, scope: scope())
        SessionLifecycleURLProtocol.responses = [.empty(401)]
        var currentToken = token
        let api = makeAPI()
        api.configureAuthentication(
            accessTokenProvider: { currentToken },
            refreshSession: { currentToken = Self.token("user-b"); return .refreshed },
            onInvalidRefresh: {}
        )
        let result = await makeIntegration(api: api).prepareSession(accessToken: token)
        XCTAssertFalse(result)
        XCTAssertEqual(methods, ["DELETE"])
        XCTAssertEqual(try store.load(scope: scope()), old)
    }

    func testKeychainRemovalFailureBlocksNewSessionUntilRetried() async throws {
        try store.save(old, scope: scope())
        store.failRemove = true
        SessionLifecycleURLProtocol.responses = [
            .empty(204), .init(status: 404, body: "{\"error\":{\"code\":\"not_found\"}}"), .created("new")
        ]
        let integration = makeIntegration()
        let failed = await integration.prepareSession(accessToken: token)
        XCTAssertFalse(failed)
        XCTAssertEqual(methods, ["DELETE"])
        store.failRemove = false
        let retried = await integration.prepareSession(accessToken: token)
        XCTAssertTrue(retried)
        XCTAssertEqual(methods, ["DELETE", "DELETE", "POST"])
    }

    func testEndWhileCreatingDeletesLateSessionWithoutRestoringIt() async throws {
        SessionLifecycleURLProtocol.responses = [.created("new"), .empty(204)]
        let integration = makeIntegration()
        var ending: Task<Void, Never>?
        SessionLifecycleURLProtocol.beforeResponse = { request in
            if request.httpMethod == "POST" {
                ending = Task { await integration.endBroadcast(accessToken: self.token) }
            }
        }
        _ = await integration.prepareSession(accessToken: token)
        await ending?.value
        XCTAssertNil(integration.session)
        XCTAssertNil(try store.load(scope: scope()))
        XCTAssertEqual(methods, ["POST", "DELETE"])
    }

    func testOnDeviceLegacyServerRequiresConfirmedAIDisabled() async throws {
        SessionLifecycleURLProtocol.responses = [.created("local"), .snapshot(enabled: false)]
        let integration = makeIntegration(mode: .onDevice)
        let ready = await integration.prepareSession(accessToken: token)
        XCTAssertTrue(ready)
        XCTAssertEqual(integration.session?.processingMode, .onDevice)
        XCTAssertTrue(integration.isAnonymizationEnabled)
        XCTAssertEqual(methods, ["POST", "PATCH"])
        XCTAssertEqual(SessionLifecycleURLProtocol.requests[1].url?.path, "/sessions/local/anonymization")
    }

    func testOnDeviceRefusesUnconfirmedLegacyServerAndDeletesSession() async {
        for confirmation in [true, nil] as [Bool?] {
            SessionLifecycleURLProtocol.requests = []
            SessionLifecycleURLProtocol.responses = [.created("unsafe"), .snapshot(enabled: confirmation), .empty(204)]
            let integration = makeIntegration(mode: .onDevice)
            let ready = await integration.prepareSession(accessToken: token)
            XCTAssertFalse(ready)
            XCTAssertNil(integration.session)
            XCTAssertEqual(methods, ["POST", "PATCH", "DELETE"])
        }
    }

    func testLocalSelectionCreatesSwitchableSessionAndConfirmsServerOff() async {
        SessionLifecycleURLProtocol.responses = [.created("local", mode: "server"), .snapshot(enabled: false)]
        let integration = makeIntegration(mode: .onDevice)
        let ready = await integration.prepareSession(accessToken: token)
        XCTAssertTrue(ready)
        XCTAssertEqual(methods, ["POST", "PATCH"])
        XCTAssertEqual(integration.session?.aiProcessing, "server")
        XCTAssertEqual(integration.session?.processingMode, .onDevice)
    }

    func testMismatchedModeNeverStartsVideoAndDeletesSession() async {
        SessionLifecycleURLProtocol.responses = [.created("wrong", mode: "on_device"), .empty(204)]
        let integration = makeIntegration(mode: .server)
        let ready = await integration.prepareSession(accessToken: token)
        XCTAssertFalse(ready)
        XCTAssertNil(integration.session)
        XCTAssertEqual(methods, ["POST", "DELETE"])
    }

    func testOnDeviceSelectionCreatesDirectPublishSessionWithoutServerAIRequest() async throws {
        SessionLifecycleURLProtocol.responses = [.created("direct", mode: "on_device")]
        let integration = makeIntegration(mode: .onDevice)
        let ready = await integration.prepareSession(accessToken: token)
        XCTAssertTrue(ready)
        XCTAssertEqual(methods, ["POST"])
        let body = try XCTUnwrap(SessionLifecycleURLProtocol.requests.first.flatMap(Self.body))
        // 공유 계약(direct-publish-v1)은 최상위 ai_processing을 쓴다. metadata는 구 서버 협상용이다.
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(body.utf8)) as? [String: Any])
        XCTAssertEqual(json["ai_processing"] as? String, "on_device")
        XCTAssertEqual((json["metadata"] as? [String: String])?["ai_processing"], "on_device")
        XCTAssertTrue(integration.isDirectPublishSession)
        XCTAssertEqual(integration.session?.processingMode, .onDevice)
        XCTAssertTrue(integration.isAnonymizationEnabled)
    }

    func testServerSelectionKeepsServerSessionBody() async throws {
        SessionLifecycleURLProtocol.responses = [.created("server", mode: "server")]
        let integration = makeIntegration(mode: .server)
        _ = await integration.prepareSession(accessToken: token)
        let body = try XCTUnwrap(SessionLifecycleURLProtocol.requests.first.flatMap(Self.body))
        XCTAssertFalse(body.contains("ai_processing"))
        XCTAssertFalse(integration.isDirectPublishSession)
    }

    func testDirectPublishSessionRejectsInPlaceSwitchToServer() async {
        SessionLifecycleURLProtocol.responses = [.created("direct", mode: "on_device")]
        let integration = makeIntegration(mode: .onDevice)
        _ = await integration.prepareSession(accessToken: token)
        let changed = await integration.changeAIProcessingMode(.server, accessToken: token)
        XCTAssertFalse(changed)
        XCTAssertNotNil(integration.errorMessage)
        XCTAssertEqual(integration.session?.sessionID, "direct")
        XCTAssertNil(defaults.string(forKey: AIProcessingMode.storageKey))
        XCTAssertEqual(methods, ["POST"])
    }

    func testServerTransportCreatesServerSessionAndConfirmsServerAIOff() async throws {
        SessionLifecycleURLProtocol.responses = [.created("relay", mode: "server"), .snapshot(enabled: false)]
        let integration = makeIntegration(mode: .onDevice, transport: .server)
        XCTAssertEqual(integration.planProcessingMode, .server)
        let ready = await integration.prepareSession(accessToken: token, broadcastResolution: "fhd")
        XCTAssertTrue(ready)
        XCTAssertEqual(methods, ["POST", "PATCH"])
        let body = try XCTUnwrap(SessionLifecycleURLProtocol.requests.first.flatMap(Self.body))
        XCTAssertFalse(body.contains("ai_processing"))
        // 서버 송출도 선택한 카메라 화질로 세션을 만든다. 생략하면 서버 기본값 720p 세션이 된다.
        XCTAssertEqual(try screenBlurRequestBody(SessionLifecycleURLProtocol.requests[0])["broadcast_resolution"] as? String, "fhd")
        let patch = SessionLifecycleURLProtocol.requests[1]
        XCTAssertEqual(patch.url?.path, "/sessions/relay/anonymization")
        XCTAssertEqual(try screenBlurRequestBody(patch)["enabled"] as? Bool, false)
        XCTAssertFalse(integration.isDirectPublishSession)
        XCTAssertEqual(integration.session?.processingMode, .onDevice)
        XCTAssertTrue(integration.isAnonymizationEnabled)
        XCTAssertEqual(integration.planProcessingMode, .server)
    }

    func testServerSessionRetriesAt720pWhenPlanRejectsFHD() async throws {
        SessionLifecycleURLProtocol.responses = [.planResolutionRejected, .created("server", mode: "server")]
        let integration = makeIntegration(mode: .server)
        let ready = await integration.prepareSession(accessToken: token, broadcastResolution: "fhd")
        XCTAssertTrue(ready)
        XCTAssertEqual(methods, ["POST", "POST"])
        let resolutions = try SessionLifecycleURLProtocol.requests.map { try screenBlurRequestBody($0)["broadcast_resolution"] as? String }
        XCTAssertEqual(resolutions, ["fhd", "720p"])
        XCTAssertEqual(integration.session?.sessionID, "server")
        XCTAssertNil(integration.errorMessage)
    }

    func testServerTransportRetriesAt720pWhenPlanRejectsFHD() async throws {
        SessionLifecycleURLProtocol.responses = [.planResolutionRejected, .created("relay", mode: "server"), .snapshot(enabled: false)]
        let integration = makeIntegration(mode: .onDevice, transport: .server)
        let ready = await integration.prepareSession(accessToken: token, broadcastResolution: "fhd")
        XCTAssertTrue(ready)
        XCTAssertEqual(methods, ["POST", "POST", "PATCH"])
        let resolutions = try SessionLifecycleURLProtocol.requests.prefix(2).map { try screenBlurRequestBody($0)["broadcast_resolution"] as? String }
        XCTAssertEqual(resolutions, ["fhd", "720p"])
        XCTAssertEqual(integration.session?.processingMode, .onDevice)
        XCTAssertNil(integration.errorMessage)
    }

    func testDirectPublishSessionKeepsPlanRejectionWithoutRetry() async {
        SessionLifecycleURLProtocol.responses = [.planResolutionRejected]
        let integration = makeIntegration(mode: .onDevice)
        let ready = await integration.prepareSession(accessToken: token, broadcastResolution: "fhd")
        XCTAssertFalse(ready)
        XCTAssertEqual(methods, ["POST"])
        XCTAssertNil(integration.session)
        XCTAssertNotNil(integration.errorMessage)
    }

    func testServerTransportRejectsDirectPublishSessionResponse() async {
        SessionLifecycleURLProtocol.responses = [.created("direct", mode: "on_device"), .empty(204)]
        let integration = makeIntegration(mode: .onDevice, transport: .server)
        let ready = await integration.prepareSession(accessToken: token)
        XCTAssertFalse(ready)
        XCTAssertNil(integration.session)
        XCTAssertEqual(methods, ["POST", "DELETE"])
    }

    func testDirectTransportBillsDeviceTimeBeforeSessionExists() {
        XCTAssertEqual(makeIntegration(mode: .onDevice, transport: .direct).planProcessingMode, .onDevice)
        XCTAssertEqual(makeIntegration(mode: .server, transport: .server).planProcessingMode, .server)
    }

    func testServerTimeFallbackUsesDirectPublishEvenWithServerTransport() async throws {
        let integration = makeIntegration(mode: .onDevice, transport: .server)
        try await seedExhaustedPlan(integration)
        XCTAssertTrue(integration.offersOnDevicePreparation)
        let switched = await integration.switchPreparationToOnDevice(accessToken: token)
        XCTAssertTrue(switched)
        XCTAssertEqual(integration.planProcessingMode, .onDevice)
        SessionLifecycleURLProtocol.responses = [.created("device", mode: "on_device")]
        let created = await integration.prepareSession(accessToken: token)
        XCTAssertTrue(created)
        let json = try screenBlurRequestBody(XCTUnwrap(SessionLifecycleURLProtocol.requests.last))
        XCTAssertEqual(json["ai_processing"] as? String, "on_device")
        XCTAssertTrue(integration.isDirectPublishSession)
    }

    func testDirectTransportBlocksInPlaceSwitchOfServerSessionToOnDevice() async {
        SessionLifecycleURLProtocol.responses = [.created("same", mode: "server")]
        let integration = makeIntegration(transport: .direct)
        _ = await integration.prepareSession(accessToken: token)
        let changed = await integration.changeAIProcessingMode(.onDevice, accessToken: token)
        XCTAssertFalse(changed)
        XCTAssertNotNil(integration.errorMessage)
        XCTAssertEqual(integration.session?.processingMode, .server)
        XCTAssertNil(defaults.string(forKey: AIProcessingMode.storageKey))
        XCTAssertEqual(methods, ["POST"])
    }

    func testTransportChangeIsSavedOnlyWithoutOnDeviceSession() async {
        let integration = makeIntegration(mode: .onDevice, transport: .direct)
        XCTAssertTrue(integration.changeOnDeviceTransport(.server))
        XCTAssertEqual(defaults.string(forKey: OnDeviceTransport.storageKey), "server")
        XCTAssertEqual(integration.planProcessingMode, .server)

        SessionLifecycleURLProtocol.responses = [.created("relay", mode: "server"), .snapshot(enabled: false)]
        _ = await integration.prepareSession(accessToken: token)
        XCTAssertEqual(integration.session?.processingMode, .onDevice)
        XCTAssertFalse(integration.changeOnDeviceTransport(.direct))
        XCTAssertNotNil(integration.errorMessage)
        XCTAssertEqual(defaults.string(forKey: OnDeviceTransport.storageKey), "server")
    }

    func testServerTransportIsRejectedForPlanWithoutServerTime() async throws {
        let integration = makeIntegration(mode: .onDevice, transport: .direct)
        await integration.planStore.refresh(api: try DeviceOnlyPlanStub(), accessToken: token)
        XCTAssertFalse(integration.changeOnDeviceTransport(.server))
        XCTAssertNotNil(integration.errorMessage)
        XCTAssertNil(defaults.string(forKey: OnDeviceTransport.storageKey))
        XCTAssertTrue(integration.changeOnDeviceTransport(.direct))
    }

    private static func body(_ request: URLRequest) -> String? {
        if let data = request.httpBody { return String(decoding: data, as: UTF8.self) }
        guard let stream = request.httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 1_024)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            guard count > 0 else { break }
            data.append(buffer, count: count)
        }
        return String(decoding: data, as: UTF8.self)
    }

    func testChangingModeKeepsSessionAndStoredCredentialsInBothDirections() async throws {
        SessionLifecycleURLProtocol.responses = [.created("same", mode: "server"), .snapshot(enabled: true), .snapshot(enabled: false), .snapshot(enabled: true)]
        let integration = makeIntegration(transport: .server)
        _ = await integration.prepareSession(accessToken: token)
        await integration.toggleAnonymization(accessToken: token)
        let local = await integration.changeAIProcessingMode(.onDevice, accessToken: token)
        XCTAssertTrue(local)
        XCTAssertEqual(integration.session?.processingMode, .onDevice)
        XCTAssertTrue(integration.isAnonymizationEnabled)
        let server = await integration.changeAIProcessingMode(.server, accessToken: token)
        XCTAssertTrue(server)
        XCTAssertEqual(integration.session?.processingMode, .server)
        XCTAssertEqual(integration.session?.sessionID, "same")
        XCTAssertEqual(try store.load(scope: scope())?.sessionID, "same")
        XCTAssertEqual(methods, ["POST", "PATCH", "PATCH", "PATCH"])
        XCTAssertFalse(integration.isAIProcessingUnconfirmed)
    }

    func testFailedServerOffKeepsLocalProtectionAndAllowsSameModeRetry() async {
        SessionLifecycleURLProtocol.responses = [.created("same"), .snapshot(enabled: true), .empty(503), .snapshot(enabled: false)]
        let integration = makeIntegration(transport: .server)
        _ = await integration.prepareSession(accessToken: token)
        await integration.toggleAnonymization(accessToken: token)
        let failed = await integration.changeAIProcessingMode(.onDevice, accessToken: token)
        XCTAssertFalse(failed)
        XCTAssertEqual(integration.session?.processingMode, .onDevice)
        XCTAssertTrue(integration.isAnonymizationEnabled)
        XCTAssertTrue(integration.isAIProcessingUnconfirmed)
        await integration.prepareYouTubeStream(accessToken: token)
        XCTAssertNotNil(integration.errorMessage)
        await integration.toggleAnonymization(accessToken: token)
        XCTAssertTrue(integration.isAnonymizationEnabled)
        XCTAssertNotNil(integration.errorMessage)
        XCTAssertEqual(methods, ["POST", "PATCH", "PATCH"])
        let retried = await integration.changeAIProcessingMode(.onDevice, accessToken: token)
        XCTAssertTrue(retried)
        XCTAssertFalse(integration.isAIProcessingUnconfirmed)
        XCTAssertEqual(methods, ["POST", "PATCH", "PATCH", "PATCH"])
    }

    func testServerModeDoesNotReleaseLocalProtectionWithoutConfirmedServerAI() async {
        SessionLifecycleURLProtocol.responses = [.created("same"), .snapshot(enabled: false), .snapshot(enabled: false)]
        let integration = makeIntegration(mode: .onDevice)
        _ = await integration.prepareSession(accessToken: token)
        let changed = await integration.changeAIProcessingMode(.server, accessToken: token)
        XCTAssertFalse(changed)
        XCTAssertEqual(integration.session?.processingMode, .onDevice)
        XCTAssertTrue(integration.isAnonymizationEnabled)
        XCTAssertTrue(integration.isAIProcessingUnconfirmed)
        XCTAssertEqual(methods, ["POST", "PATCH", "PATCH"])
    }

    func testResetDuringSwitchCannotRestoreSessionOrPersistLateMode() async {
        SessionLifecycleURLProtocol.responses = [.created("same"), .snapshot(enabled: false), .snapshot(enabled: true)]
        let integration = makeIntegration(mode: .onDevice)
        _ = await integration.prepareSession(accessToken: token)
        SessionLifecycleURLProtocol.beforeResponse = { _ in integration.reset() }
        let changed = await integration.changeAIProcessingMode(.server, accessToken: token)
        XCTAssertFalse(changed)
        XCTAssertNil(integration.session)
        XCTAssertNil(defaults.string(forKey: AIProcessingMode.storageKey))
    }

    private var methods: [String] { SessionLifecycleURLProtocol.requests.compactMap(\.httpMethod) }

    func testScreenBlurUsesAuthenticatedPatchAndConfirmedResponse() async throws {
        SessionLifecycleURLProtocol.responses = [.created("blur"), .blur(true), .blur(false)]
        let integration = makeIntegration()
        _ = await integration.prepareSession(accessToken: token)
        XCTAssertFalse(integration.isScreenBlurEnabled)
        await integration.toggleScreenBlur(accessToken: token)
        XCTAssertTrue(integration.isScreenBlurEnabled)
        let request = try XCTUnwrap(SessionLifecycleURLProtocol.requests.last)
        XCTAssertEqual(request.httpMethod, "PATCH")
        XCTAssertEqual(request.url?.path, "/sessions/blur/screen-blur")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer \(token)")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Session-Owner-Token"), "owner")
        let body = try screenBlurRequestBody(request)
        XCTAssertEqual(body["enabled"] as? Bool, true)
        await integration.toggleScreenBlur(accessToken: token)
        XCTAssertEqual(try screenBlurRequestBody(XCTUnwrap(SessionLifecycleURLProtocol.requests.last))["enabled"] as? Bool, false)
        XCTAssertFalse(integration.isScreenBlurEnabled)
        XCTAssertFalse(integration.isTogglingScreenBlur)
        XCTAssertEqual(methods, ["POST", "PATCH", "PATCH"])
    }

    func testScreenBlurFailureAndMissingFieldPreserveConfirmedValue() async {
        SessionLifecycleURLProtocol.responses = [.created("blur"), .blur(true), .empty(503), .snapshot(enabled: true)]
        let integration = makeIntegration(transport: .server)
        _ = await integration.prepareSession(accessToken: token)
        await integration.toggleScreenBlur(accessToken: token)
        for _ in 0..<2 {
            await integration.toggleScreenBlur(accessToken: token)
            XCTAssertTrue(integration.isScreenBlurEnabled)
            XCTAssertFalse(integration.isTogglingScreenBlur)
            XCTAssertNotNil(integration.errorMessage)
        }
        let changed = await integration.changeAIProcessingMode(.onDevice, accessToken: token)
        XCTAssertFalse(changed)
    }

    func testScreenBlurIgnoresResponseAfterReset() async {
        SessionLifecycleURLProtocol.responses = [.created("blur"), .blur(true)]
        let integration = makeIntegration()
        _ = await integration.prepareSession(accessToken: token)
        SessionLifecycleURLProtocol.beforeResponse = { request in
            if request.httpMethod == "PATCH" { integration.reset() }
        }
        await integration.toggleScreenBlur(accessToken: token)
        XCTAssertNil(integration.session)
        XCTAssertFalse(integration.isScreenBlurEnabled)
        XCTAssertFalse(integration.isTogglingScreenBlur)
    }

    func testScreenBlurUnavailableForOnDeviceProcessing() async {
        SessionLifecycleURLProtocol.responses = [.created("local"), .snapshot(enabled: false)]
        let integration = makeIntegration(mode: .onDevice)
        _ = await integration.prepareSession(accessToken: token)
        XCTAssertFalse(integration.canToggleScreenBlur)
        await integration.toggleScreenBlur(accessToken: token)
        XCTAssertEqual(methods, ["POST", "PATCH"])
        XCTAssertFalse(integration.isScreenBlurEnabled)
    }

    func testMosaicConfigFixtureDecodesNullAsServerDefault() throws {
        let response = try JSONDecoder().decode(YouTubeSessionResponse.self, from: ContractTestFixtures.data(named: "session-mosaic-config.v1"))
        let config = try XCTUnwrap(response.media.mosaicConfig)
        XCTAssertEqual(config, BroadcastMosaicConfig(blurRadius: 40, pixelSize: nil, maskMargin: 16))
        XCTAssertEqual(BroadcastMosaicConfig().effectiveBlurRadius, 24)
        XCTAssertEqual(BroadcastMosaicConfig().effectiveMaskMargin, 8)
        let legacy = try JSONDecoder().decode(YouTubeSessionResponse.self, from: Data(SessionLifecycleURLProtocol.Response.blur(false).body.utf8))
        XCTAssertNil(legacy.media.mosaicConfig)
    }

    func testMosaicConfigUsesAuthenticatedPutAndKeepsPixelSize() async throws {
        SessionLifecycleURLProtocol.responses = [
            .created("mosaic"),
            .mosaic("{\"blur_radius\":null,\"pixel_size\":4,\"mask_margin\":null}"),
            .mosaic("{\"blur_radius\":64,\"pixel_size\":4,\"mask_margin\":0}"),
        ]
        let integration = makeIntegration()
        _ = await integration.prepareSession(accessToken: token)
        XCTAssertTrue(integration.canChangeMosaicConfig)
        await integration.updateMosaicConfig(BroadcastMosaicConfig(pixelSize: 4), accessToken: token)
        XCTAssertEqual(integration.mosaicConfig.pixelSize, 4)
        await integration.updateMosaicConfig(integration.mosaicConfig.adjusted(blurRadius: 99, maskMargin: -3), accessToken: token)
        let request = try XCTUnwrap(SessionLifecycleURLProtocol.requests.last)
        XCTAssertEqual(request.httpMethod, "PUT")
        XCTAssertEqual(request.url?.path, "/sessions/mosaic/mosaic-config")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer \(token)")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Session-Owner-Token"), "owner")
        let body = try screenBlurRequestBody(request)
        XCTAssertEqual(body["blur_radius"] as? Double, 64)
        XCTAssertEqual(body["pixel_size"] as? Int, 4)
        XCTAssertEqual(body["mask_margin"] as? Int, 0)
        XCTAssertEqual(integration.mosaicConfig, BroadcastMosaicConfig(blurRadius: 64, pixelSize: 4, maskMargin: 0))
        XCTAssertFalse(integration.isChangingMosaicConfig)
        XCTAssertEqual(methods, ["POST", "PUT", "PUT"])
    }

    func testMosaicConfigDefaultsOmitFields() async throws {
        SessionLifecycleURLProtocol.responses = [.created("mosaic"), .mosaic("{\"blur_radius\":null,\"pixel_size\":null,\"mask_margin\":null}")]
        let integration = makeIntegration()
        _ = await integration.prepareSession(accessToken: token)
        await integration.updateMosaicConfig(BroadcastMosaicConfig(blurRadius: 30).adjusted(blurRadius: nil, maskMargin: nil), accessToken: token)
        XCTAssertEqual(try screenBlurRequestBody(XCTUnwrap(SessionLifecycleURLProtocol.requests.last)).count, 0)
        XCTAssertEqual(integration.mosaicConfig, BroadcastMosaicConfig())
    }

    func testMosaicConfigFailureAndMissingFieldPreserveConfirmedValue() async {
        SessionLifecycleURLProtocol.responses = [
            .created("mosaic"),
            .mosaic("{\"blur_radius\":40,\"pixel_size\":null,\"mask_margin\":16}"),
            .empty(400),
            .blur(false),
        ]
        let integration = makeIntegration()
        _ = await integration.prepareSession(accessToken: token)
        await integration.updateMosaicConfig(BroadcastMosaicConfig(blurRadius: 40, maskMargin: 16), accessToken: token)
        for _ in 0..<2 {
            await integration.updateMosaicConfig(BroadcastMosaicConfig(blurRadius: 10, maskMargin: 2), accessToken: token)
            XCTAssertEqual(integration.mosaicConfig, BroadcastMosaicConfig(blurRadius: 40, maskMargin: 16))
            XCTAssertFalse(integration.isChangingMosaicConfig)
            XCTAssertNotNil(integration.mosaicConfigErrorMessage)
        }
    }

    func testMosaicConfigSendsLatestValueQueuedDuringRequest() async throws {
        SessionLifecycleURLProtocol.responses = [
            .created("mosaic"),
            .mosaic("{\"blur_radius\":30,\"pixel_size\":null,\"mask_margin\":null}"),
            .mosaic("{\"blur_radius\":50,\"pixel_size\":null,\"mask_margin\":20}"),
        ]
        let integration = makeIntegration()
        _ = await integration.prepareSession(accessToken: token)
        SessionLifecycleURLProtocol.beforeResponse = { [token] request in
            guard request.httpMethod == "PUT", SessionLifecycleURLProtocol.requests.count == 2 else { return }
            await integration.updateMosaicConfig(BroadcastMosaicConfig(blurRadius: 40), accessToken: token)
            await integration.updateMosaicConfig(BroadcastMosaicConfig(blurRadius: 50, maskMargin: 20), accessToken: token)
        }
        await integration.updateMosaicConfig(BroadcastMosaicConfig(blurRadius: 30), accessToken: token)
        XCTAssertEqual(methods, ["POST", "PUT", "PUT"])
        XCTAssertEqual(try screenBlurRequestBody(XCTUnwrap(SessionLifecycleURLProtocol.requests.last))["blur_radius"] as? Double, 50)
        XCTAssertEqual(integration.mosaicConfig, BroadcastMosaicConfig(blurRadius: 50, maskMargin: 20))
        XCTAssertFalse(integration.isChangingMosaicConfig)
    }

    func testMosaicConfigIgnoresResponseAfterReset() async {
        SessionLifecycleURLProtocol.responses = [.created("mosaic"), .mosaic("{\"blur_radius\":40,\"pixel_size\":null,\"mask_margin\":null}")]
        let integration = makeIntegration()
        _ = await integration.prepareSession(accessToken: token)
        SessionLifecycleURLProtocol.beforeResponse = { request in
            if request.httpMethod == "PUT" { integration.reset() }
        }
        await integration.updateMosaicConfig(BroadcastMosaicConfig(blurRadius: 40), accessToken: token)
        XCTAssertNil(integration.session)
        XCTAssertEqual(integration.mosaicConfig, BroadcastMosaicConfig())
        XCTAssertFalse(integration.isChangingMosaicConfig)
    }

    func testMosaicConfigUnavailableForOnDeviceProcessing() async {
        SessionLifecycleURLProtocol.responses = [.created("local"), .snapshot(enabled: false)]
        let integration = makeIntegration(mode: .onDevice)
        _ = await integration.prepareSession(accessToken: token)
        XCTAssertFalse(integration.canChangeMosaicConfig)
        await integration.updateMosaicConfig(BroadcastMosaicConfig(blurRadius: 40), accessToken: token)
        XCTAssertEqual(methods, ["POST", "PATCH"])
        XCTAssertEqual(integration.mosaicConfig, BroadcastMosaicConfig())
    }

    private func screenBlurRequestBody(_ request: URLRequest) throws -> [String: Any] {
        var data = request.httpBody ?? Data()
        if data.isEmpty, let input = request.httpBodyStream {
            input.open()
            defer { input.close() }
            var buffer = [UInt8](repeating: 0, count: 1024)
            while input.hasBytesAvailable {
                let count = input.read(&buffer, maxLength: buffer.count)
                guard count > 0 else { break }
                data.append(contentsOf: buffer.prefix(count))
            }
        }
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private static func token(_ user: String) -> String {
        "header." + Data("{\"sub\":\"\(user)\"}".utf8).base64EncodedString() + ".signature"
    }

    private func scope() throws -> BroadcastSessionScope {
        try BroadcastSessionScope(server: XCTUnwrap(URL(string: "https://example.invalid/")), accessToken: token)
    }

    private func makeAPI() -> YouTubeAPI {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SessionLifecycleURLProtocol.self]
        return YouTubeAPI(urlSession: URLSession(configuration: configuration), serverURLProvider: {
            URL(string: "https://example.invalid\($0)")
        })
    }

    private func makeIntegration(api: YouTubeAPI? = nil, mode: AIProcessingMode = .server, transport: OnDeviceTransport = .direct) -> YouTubeIntegration {
        YouTubeIntegration(preferencesStore: YouTubePreferencesStore(userDefaults: defaults), api: api ?? makeAPI(), sessionStore: store, aiModeProvider: { mode }, localModelsAvailable: { true },
                           persistAIProcessingMode: { [defaults = defaults!] mode in defaults.set(mode.rawValue, forKey: AIProcessingMode.storageKey) },
                           transportProvider: { [defaults = defaults!] in
                               OnDeviceTransport(rawValue: defaults.string(forKey: OnDeviceTransport.storageKey) ?? "") ?? transport
                           },
                           persistTransport: { [defaults = defaults!] in defaults.set($0.rawValue, forKey: OnDeviceTransport.storageKey) })
    }
}

/// 서버 시간이 없는 Glow 요금제다(공유 fixture).
private final class DeviceOnlyPlanStub: PlanAPIClient {
    let snapshot: PlanSnapshot
    init() throws {
        struct Fixture: Decodable { let plan: UserPlan; let usage: UserUsage }
        let fixtures = try JSONDecoder().decode([Fixture].self, from: ContractTestFixtures.data(named: "plan-usage-processing.v1"))
        let glow = try XCTUnwrap(fixtures.first { $0.plan.plan == "glow" })
        snapshot = PlanSnapshot(plan: glow.plan, usage: glow.usage)
    }
    func userPlan(accessToken: String) async throws -> UserPlan { snapshot.plan }
    func userUsage(accessToken: String) async throws -> UserUsage { snapshot.usage }
}

@MainActor
private final class MemoryBroadcastSessionStore: BroadcastSessionStoring {
    // iOS 18의 isolated deinit 런타임 오류를 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    var records: [String: StoredBroadcastSession] = [:]
    var failSave = false
    var failLoad = false
    var failRemove = false
    func load(scope: BroadcastSessionScope) throws -> StoredBroadcastSession? {
        if failLoad { throw AuthenticationError.storage }
        return records[scope.storageKey]
    }
    func save(_ session: StoredBroadcastSession, scope: BroadcastSessionScope) throws {
        if failSave { throw AuthenticationError.storage }
        records[scope.storageKey] = session
    }
    func remove(scope: BroadcastSessionScope) throws {
        if failRemove { throw AuthenticationError.storage }
        records.removeValue(forKey: scope.storageKey)
    }
}

private final class SessionLifecycleURLProtocol: URLProtocol {
    // iOS 18의 isolated deinit 런타임 오류를 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    struct Response {
        let status: Int
        let body: String
        static func snapshot(enabled: Bool?) -> Self {
            let flag = enabled.map { String($0) } ?? "null"
            return .init(status: 200, body: "{\"stream\":{\"status\":\"idle\",\"publisher_active\":false,\"reconnect_attempts\":0},\"media\":{\"anonymization_enabled\":\(flag)}}")
        }
        static func blur(_ enabled: Bool) -> Self {
            .init(status: 200, body: "{\"stream\":{\"status\":\"idle\",\"publisher_active\":false,\"reconnect_attempts\":0},\"media\":{\"screen_blur_enabled\":\(enabled)}}")
        }
        static func mosaic(_ config: String) -> Self {
            .init(status: 200, body: "{\"stream\":{\"status\":\"idle\",\"publisher_active\":false,\"reconnect_attempts\":0},\"media\":{\"mosaic_config\":\(config)}}")
        }
        static func empty(_ status: Int) -> Self { .init(status: status, body: "") }
        static let planResolutionRejected = Self(status: 403, body: "{\"error\":{\"code\":\"plan_resolution_not_allowed\"}}")
        static func created(_ id: String, mode: String? = nil) -> Self {
            let extra = mode.map { "\"ai_processing\":\"\($0)\"," } ?? ""
            return .init(status: 201, body: "{\(extra)\"session_id\":\"\(id)\",\"owner_token\":\"owner\",\"stream\":{\"status\":\"idle\",\"publisher_active\":false,\"reconnect_attempts\":0}}")
        }
    }
    @MainActor static var responses: [Response] = []
    @MainActor static var requests: [URLRequest] = []
    @MainActor static var beforeResponse: ((URLRequest) async -> Void)?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Task { @MainActor in
            Self.requests.append(request)
            guard !Self.responses.isEmpty else {
                client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
                return
            }
            let response = Self.responses.removeFirst()
            await Self.beforeResponse?(request)
            let http = HTTPURLResponse(url: request.url!, statusCode: response.status, httpVersion: nil, headerFields: nil)!
            client?.urlProtocol(self, didReceive: http, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(response.body.utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
    }
    override func stopLoading() {}
}
