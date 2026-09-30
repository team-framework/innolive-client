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
        SessionLifecycleURLProtocol.beforeResponseAsync = nil
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        SessionLifecycleURLProtocol.beforeResponse = nil
        SessionLifecycleURLProtocol.beforeResponseAsync = nil
        super.tearDown()
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

    func testDeletedUnsavedSessionDoesNotHideReplacementJournal() async throws {
        store.failSave = true
        SessionLifecycleURLProtocol.responses = [.created("unsaved"), .empty(204), .empty(204), .created("current")]
        let replacement = StoredBroadcastSession(sessionID: "replacement", ownerToken: "replacement-owner")
        SessionLifecycleURLProtocol.beforeResponse = { request in
            guard request.httpMethod == "DELETE" else { return }
            SessionLifecycleURLProtocol.beforeResponse = nil
            self.store.failSave = false
            try! self.store.save(replacement, scope: self.scope())
        }
        let integration = makeIntegration()

        let firstReady = await integration.prepareSession(accessToken: token)
        XCTAssertFalse(firstReady)
        XCTAssertEqual(try store.load(scope: scope()), replacement)
        let replacementReady = await integration.prepareSession(accessToken: token)
        XCTAssertTrue(replacementReady)
        XCTAssertEqual(methods, ["POST", "DELETE", "DELETE", "POST"])
        XCTAssertEqual(SessionLifecycleURLProtocol.requests[2].url?.path, "/sessions/replacement")
        XCTAssertEqual(try store.load(scope: scope())?.sessionID, "current")
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

    func testResetAndNewPrepareForSameAccountWaitsForLateCreateJournal() async throws {
        SessionLifecycleURLProtocol.responses = [.created("obsolete"), .empty(204), .created("current")]
        let integration = makeIntegration()
        var replacement: Task<Bool, Never>?
        SessionLifecycleURLProtocol.beforeResponseAsync = { request in
            guard request.httpMethod == "POST", replacement == nil else { return }
            integration.reset()
            replacement = Task { await integration.prepareSession(accessToken: self.token) }
            for _ in 0..<100 where !integration.isPreparingSession {
                await Task.yield()
            }
            XCTAssertTrue(integration.isPreparingSession)
        }

        let obsoleteReady = await integration.prepareSession(accessToken: token)
        let replacementReady = await replacement?.value

        XCTAssertFalse(obsoleteReady)
        XCTAssertEqual(replacementReady, true)
        XCTAssertEqual(methods, ["POST", "DELETE", "POST"])
        XCTAssertEqual(SessionLifecycleURLProtocol.requests[1].url?.path, "/sessions/obsolete")
        XCTAssertEqual(integration.session?.sessionID, "current")
        XCTAssertEqual(try store.load(scope: scope())?.sessionID, "current")
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
        XCTAssertEqual(try store.load(scope: scope()), old)
        XCTAssertNil(integration.errorMessage)
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
        let integration = makeIntegration(mode: .onDevice)
        let ready = await integration.prepareSession(accessToken: token)
        XCTAssertFalse(ready)
        XCTAssertNil(integration.session)
        XCTAssertEqual(methods, ["POST", "DELETE"])
    }

    func testChangingModeKeepsSessionAndStoredCredentialsInBothDirections() async throws {
        SessionLifecycleURLProtocol.responses = [.created("same", mode: "server"), .snapshot(enabled: true), .snapshot(enabled: false), .snapshot(enabled: true)]
        let integration = makeIntegration()
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
        let integration = makeIntegration()
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

    func testCreationConflictCleansOnlySavedCurrentScopeAndRetriesOnce() async throws {
        let otherScope = try BroadcastSessionScope(server: XCTUnwrap(URL(string: "https://example.invalid/")), accessToken: Self.token("user-b"))
        try store.save(.init(sessionID: "other", ownerToken: "other-owner"), scope: otherScope)
        SessionLifecycleURLProtocol.responses = [.conflict, .empty(204), .created("retry")]
        saveJournalDuringFirstCreate()
        let integration = makeIntegration()
        let ready = await integration.prepareSession(accessToken: token)
        XCTAssertTrue(ready)
        XCTAssertEqual(methods, ["POST", "DELETE", "POST"])
        XCTAssertEqual(SessionLifecycleURLProtocol.requests[1].url?.path, "/sessions/old")
        XCTAssertEqual(SessionLifecycleURLProtocol.requests[1].value(forHTTPHeaderField: "X-Session-Owner-Token"), "old-owner")
        XCTAssertEqual(try store.load(scope: otherScope)?.sessionID, "other")
        XCTAssertEqual(try store.load(scope: scope())?.sessionID, "retry")
    }

    func testCreationConflictWithoutCurrentJournalDoesNotDeleteOrRetry() async throws {
        try store.save(old, scope: scope())
        SessionLifecycleURLProtocol.responses = [.conflict]
        let integration = makeIntegration()
        let ready = await integration.prepareSession(accessToken: Self.token("user-b"))
        XCTAssertFalse(ready)
        XCTAssertEqual(methods, ["POST"])
        XCTAssertEqual(try store.load(scope: scope()), old)
        XCTAssertEqual(integration.errorMessage, cleanupGuidance)
    }

    func testOtherServerJournalIsNotUsedToRecoverCreationConflict() async throws {
        let otherServer = try BroadcastSessionScope(server: XCTUnwrap(URL(string: "https://other.invalid/")), accessToken: token)
        try store.save(old, scope: otherServer)
        SessionLifecycleURLProtocol.responses = [.conflict]
        let integration = makeIntegration()
        let ready = await integration.prepareSession(accessToken: token)
        XCTAssertFalse(ready)
        XCTAssertEqual(methods, ["POST"])
        XCTAssertEqual(try store.load(scope: otherServer), old)
        XCTAssertEqual(integration.errorMessage, cleanupGuidance)
    }

    func testCreationConflictDeletionFailureRetainsJournalWithoutRetry() async throws {
        SessionLifecycleURLProtocol.responses = [.conflict, .empty(503)]
        saveJournalDuringFirstCreate()
        let integration = makeIntegration()
        let ready = await integration.prepareSession(accessToken: token)
        XCTAssertFalse(ready)
        XCTAssertEqual(methods, ["POST", "DELETE"])
        XCTAssertEqual(try store.load(scope: scope()), old)
        XCTAssertEqual(integration.errorMessage, cleanupGuidance)
    }

    func testCreationConflictRetriesOnlyOnceAndShowsCleanupGuidanceOnFailure() async {
        for retryResponse in [SessionLifecycleURLProtocol.Response.conflict, .empty(503)] {
            SessionLifecycleURLProtocol.requests = []
            SessionLifecycleURLProtocol.responses = [.conflict, .empty(204), retryResponse]
            saveJournalDuringFirstCreate()
            let integration = makeIntegration()
            let ready = await integration.prepareSession(accessToken: token)
            XCTAssertFalse(ready)
            XCTAssertEqual(methods, ["POST", "DELETE", "POST"])
            XCTAssertNil(integration.session)
            XCTAssertEqual(integration.errorMessage, cleanupGuidance)
        }
    }

    func testSameAccountTokenRefreshKeepsCleanupScopeAndCreatesSession() async throws {
        try store.save(old, scope: scope())
        SessionLifecycleURLProtocol.responses = [.empty(401), .empty(204), .created("new")]
        let refreshedToken = token + "-refreshed"
        var currentToken = token
        let api = makeAPI()
        api.configureAuthentication(accessTokenProvider: { currentToken }, refreshSession: {
            currentToken = refreshedToken
            return .refreshed
        }, onInvalidRefresh: {})
        let ready = await makeIntegration(api: api).prepareSession(accessToken: token)
        XCTAssertTrue(ready)
        XCTAssertEqual(methods, ["DELETE", "DELETE", "POST"])
        XCTAssertEqual(SessionLifecycleURLProtocol.requests.last?.value(forHTTPHeaderField: "Authorization"), "Bearer \(refreshedToken)")
    }

    func testLateCreateForPreviousAccountCannotOverwriteNewSession() async throws {
        SessionLifecycleURLProtocol.responses = [.created("previous-account"), .created("current-account")]
        var currentToken = token
        let api = makeAPI()
        api.configureAuthentication(accessTokenProvider: { currentToken }, refreshSession: { .invalid }, onInvalidRefresh: {})
        let integration = makeIntegration(api: api)
        SessionLifecycleURLProtocol.beforeResponseAsync = { _ in
            SessionLifecycleURLProtocol.beforeResponseAsync = nil
            currentToken = Self.token("user-b")
            integration.reset()
            let ready = await integration.prepareSession(accessToken: currentToken)
            XCTAssertTrue(ready)
        }
        let ready = await integration.prepareSession(accessToken: token)
        XCTAssertFalse(ready)
        XCTAssertEqual(integration.session?.sessionID, "current-account")
        XCTAssertEqual(try store.load(scope: scope())?.sessionID, "previous-account")
        XCTAssertNil(integration.errorMessage)
        XCTAssertFalse(integration.isPreparingSession)
    }

    func testLateEndDeletionCannotClearNewAccountSession() async throws {
        SessionLifecycleURLProtocol.responses = [.created("previous-account"), .empty(204), .created("current-account")]
        var currentToken = token
        let api = makeAPI()
        api.configureAuthentication(accessTokenProvider: { currentToken }, refreshSession: { .invalid }, onInvalidRefresh: {})
        let integration = makeIntegration(api: api)
        _ = await integration.prepareSession(accessToken: token)
        SessionLifecycleURLProtocol.beforeResponseAsync = { _ in
            SessionLifecycleURLProtocol.beforeResponseAsync = nil
            currentToken = Self.token("user-b")
            integration.reset()
            let ready = await integration.prepareSession(accessToken: currentToken)
            XCTAssertTrue(ready)
        }
        await integration.endBroadcast(accessToken: token)
        XCTAssertEqual(integration.session?.sessionID, "current-account")
        XCTAssertEqual(methods, ["POST", "DELETE", "POST"])
        XCTAssertNil(integration.errorMessage)
    }

    func testDeletionDoesNotRemoveReplacementJournal() async throws {
        try store.save(old, scope: scope())
        SessionLifecycleURLProtocol.responses = [.empty(204)]
        let replacement = StoredBroadcastSession(sessionID: "replacement", ownerToken: "replacement-owner")
        SessionLifecycleURLProtocol.beforeResponse = { _ in
            try! self.store.save(replacement, scope: self.scope())
        }
        let ready = await makeIntegration().prepareSession(accessToken: token)
        XCTAssertFalse(ready)
        XCTAssertEqual(try store.load(scope: scope()), replacement)
        XCTAssertEqual(methods, ["DELETE"])
    }

    func testLogoutDeletesBeforeRemovingAuthenticationAndPreservesFailureJournal() async throws {
        for status in [204, 503] {
            SessionLifecycleURLProtocol.requests = []
            SessionLifecycleURLProtocol.responses = [.created("logout-session"), .empty(status)]
            let tokens = LifecycleAuthenticationTokenStore(accessToken: token)
            let authentication = AuthSession(tokenStore: tokens)
            authentication.restore()
            let integration = makeIntegration()
            integration.configureAuthentication(authentication)
            _ = await integration.prepareSession(accessToken: token)
            SessionLifecycleURLProtocol.beforeResponse = { request in
                if request.httpMethod == "DELETE" { XCTAssertNotNil(tokens.load()) }
            }
            await integration.signOut(authentication: authentication)
            XCTAssertFalse(authentication.isAuthenticated)
            XCTAssertNil(tokens.load())
            XCTAssertNil(integration.session)
            XCTAssertEqual(methods, ["POST", "DELETE"])
            XCTAssertEqual(try store.load(scope: scope())?.sessionID, status == 204 ? nil : "logout-session")
            SessionLifecycleURLProtocol.beforeResponse = nil
            try store.remove(scope: scope())
        }
    }

    func testLateLogoutCannotSignOutNewAccount() async throws {
        SessionLifecycleURLProtocol.responses = [.created("previous-account"), .empty(204), .created("current-account")]
        let tokens = LifecycleAuthenticationTokenStore(accessToken: token)
        let authentication = AuthSession(tokenStore: tokens)
        authentication.restore()
        let integration = makeIntegration()
        integration.configureAuthentication(authentication)
        _ = await integration.prepareSession(accessToken: token)
        SessionLifecycleURLProtocol.beforeResponseAsync = { _ in
            SessionLifecycleURLProtocol.beforeResponseAsync = nil
            tokens.save(.init(accessToken: Self.token("user-b"), refreshToken: "refresh-b"))
            integration.reset()
            let ready = await integration.prepareSession(accessToken: authentication.currentAccessToken())
            XCTAssertTrue(ready)
        }
        await integration.signOut(authentication: authentication)
        XCTAssertTrue(authentication.isAuthenticated)
        XCTAssertEqual(authentication.currentAccessToken(), Self.token("user-b"))
        XCTAssertEqual(integration.session?.sessionID, "current-account")
        XCTAssertNil(integration.errorMessage)
    }

    func testBackgroundPreservesSessionAndJournalWithoutDelete() async throws {
        SessionLifecycleURLProtocol.responses = [.created("current")]
        let integration = makeIntegration()
        _ = await integration.prepareSession(accessToken: token)
        await integration.handleAppBecameActive(accessToken: token)
        await integration.handleAppMovedToBackground(accessToken: token)
        XCTAssertEqual(integration.session?.sessionID, "current")
        XCTAssertEqual(try store.load(scope: scope())?.sessionID, "current")
        XCTAssertEqual(methods, ["POST"])
    }

    private var cleanupGuidance: String {
        String(localized: "이전 방송 세션을 정리하지 못했습니다. 이전 방송을 종료한 뒤 다시 시도해 주세요.")
    }

    private func saveJournalDuringFirstCreate() {
        SessionLifecycleURLProtocol.beforeResponse = { request in
            guard request.httpMethod == "POST" else { return }
            SessionLifecycleURLProtocol.beforeResponse = nil
            try! self.store.save(self.old, scope: self.scope())
        }
    }

    private var methods: [String] { SessionLifecycleURLProtocol.requests.compactMap(\.httpMethod) }

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

    private func makeIntegration(api: YouTubeAPI? = nil, mode: AIProcessingMode = .server) -> YouTubeIntegration {
        YouTubeIntegration(preferencesStore: YouTubePreferencesStore(userDefaults: defaults), api: api ?? makeAPI(), sessionStore: store, aiModeProvider: { mode }, localModelsAvailable: { true },
                           persistAIProcessingMode: { [defaults = defaults!] mode in defaults.set(mode.rawValue, forKey: AIProcessingMode.storageKey) })
    }
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
        static var conflict: Self { .init(status: 409, body: "{\"error\":{\"code\":\"session_already_exists\"}}") }
        static func snapshot(enabled: Bool?) -> Self {
            let flag = enabled.map { String($0) } ?? "null"
            return .init(status: 200, body: "{\"stream\":{\"status\":\"idle\",\"publisher_active\":false,\"reconnect_attempts\":0},\"media\":{\"anonymization_enabled\":\(flag)}}")
        }
        static func empty(_ status: Int) -> Self { .init(status: status, body: "") }
        static func created(_ id: String, mode: String? = nil) -> Self {
            let extra = mode.map { "\"ai_processing\":\"\($0)\"," } ?? ""
            return .init(status: 201, body: "{\(extra)\"session_id\":\"\(id)\",\"owner_token\":\"owner\",\"stream\":{\"status\":\"idle\",\"publisher_active\":false,\"reconnect_attempts\":0}}")
        }
    }
    @MainActor static var responses: [Response] = []
    @MainActor static var requests: [URLRequest] = []
    @MainActor static var beforeResponse: ((URLRequest) -> Void)?
    @MainActor static var beforeResponseAsync: ((URLRequest) async -> Void)?

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
            Self.beforeResponse?(request)
            await Self.beforeResponseAsync?(request)
            let http = HTTPURLResponse(url: request.url!, statusCode: response.status, httpVersion: nil, headerFields: nil)!
            client?.urlProtocol(self, didReceive: http, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(response.body.utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
    }
    override func stopLoading() {}
}

@MainActor
private final class LifecycleAuthenticationTokenStore: AuthenticationTokenStoring {
    nonisolated deinit {}

    private var tokens: AuthenticationTokenPair?

    init(accessToken: String) {
        tokens = .init(accessToken: accessToken, refreshToken: "refresh")
    }

    func save(_ tokens: AuthenticationTokenPair) { self.tokens = tokens }
    func load() -> AuthenticationTokenPair? { tokens }
    func remove() { tokens = nil }
}
