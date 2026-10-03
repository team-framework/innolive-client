import Foundation
import XCTest
@testable import InnoLive

@MainActor
final class UpgradeOfferTests: XCTestCase {
    private var integration: YouTubeIntegration!
    private var api: YouTubeAPI!
    private var suite: String!
    private var token: String { "h." + Data("{\"sub\":\"upgrade-user\"}".utf8).base64EncodedString() + ".s" }

    override func setUp() {
        suite = "upgrade-offer-\(UUID())"
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [UpgradeURLProtocol.self]
        api = YouTubeAPI(urlSession: URLSession(configuration: configuration), serverURLProvider: { URL(string: "https://example.invalid\($0)") })
        let defaults = UserDefaults(suiteName: suite)!
        integration = YouTubeIntegration(preferencesStore: YouTubePreferencesStore(userDefaults: defaults), api: api,
            consentStore: ConsentAcknowledgementStore(userDefaults: defaults), pollingInterval: .seconds(60))
        UpgradeURLProtocol.requests = []
        UpgradeURLProtocol.responses = []
    }

    override func tearDown() {
        integration.reset()
        UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite)
        UpgradeURLProtocol.requests = []
        UpgradeURLProtocol.responses = []
    }

    private func body(expires: Date = Date().addingTimeInterval(30), selected: Bool = false, offer: Bool = true,
                      settings: Bool = false, restart: Bool = true, switching: Bool = false) -> String {
        let stream = "{\"status\":\"streaming\",\"broadcast_phase\":\"live\",\"publisher_active\":true,\"reconnect_attempts\":0}"
        let targets = settings ? "[\"youtube\",\"chzzk\"]" : "[\"youtube\"]"
        let mode = settings ? "fhd_multi" : "fhd_single"
        let expiration = ISO8601DateFormatter().string(from: expires)
        let selection = selected ? ",\"selected\":\"\(mode)\"" : ""
        let pending = "{\"resolution\":\"fhd\",\"mode\":\"\(mode)\",\"units_from\":1,\"units_to\":2,\"remaining_seconds_after\":3600,\"expires_at\":\"\(expiration)\"\(selection),\"options\":[{\"mode\":\"\(mode)\",\"resolution\":\"fhd\",\"targets\":\(targets),\"units_to\":2,\"remaining_seconds_after\":3600,\"needs_settings\":\(settings),\"restarts_broadcast\":\(restart),\"restart_effects\":[{\"provider\":\"youtube\",\"same_link\":false},{\"provider\":\"chzzk\",\"same_link\":true,\"gap_seconds\":15}]}]}"
        let transition = switching ? "{\"status\":\"switching\",\"resolution\":\"fhd\",\"targets\":\(targets),\"started_at\":\"2026-10-03T00:00:00Z\"}" : "null"
        return "{\"session_id\":\"upgrade-session\",\"owner_token\":\"fake-owner\",\"broadcast_resolution\":\"720p\",\"stream\":\(stream),\"targets\":[{\"provider\":\"youtube\",\"stream\":\(stream)}],\"media\":{},\"upgrade_offer\":\(offer ? pending : "null"),\"resolution_switch\":\(transition)}"
    }

    private func seed(_ json: String? = nil) throws {
        let session = try JSONDecoder().decode(YouTubeBroadcastSession.self, from: Data((json ?? body()).utf8))
        integration.seedPreparedVideoSessionForTesting(connection: .init(provider: "youtube", channel: .init(id: "channel", title: "YT")),
            session: session, videoTrack: .init(id: "video", kind: "video", readyState: "live"))
        apply(json ?? body())
    }

    private func apply(_ json: String) {
        integration.applyLiveEditingSnapshotForTesting(try! JSONDecoder().decode(YouTubeSessionResponse.self, from: Data(json.utf8)))
    }

    private func enqueue(_ json: String, status: Int = 200, delay: TimeInterval = 0) {
        UpgradeURLProtocol.responses.append(.init(body: Data(json.utf8), status: status, delay: delay))
    }

    func testExpiredOfferClosesWithoutEndingBroadcast() throws {
        try seed(body(expires: Date().addingTimeInterval(-1)))
        XCTAssertNil(integration.responseState.details.upgradeOffer)
        XCTAssertNotNil(integration.session)
        XCTAssertTrue(integration.hasStartedYouTubeBroadcast)
        XCTAssertTrue(UpgradeURLProtocol.requests.isEmpty)
    }

    func testDeadlineClosesOfferWithoutPollingAndStaleSnapshotCannotReopenIt() async throws {
        let json = body(expires: Date().addingTimeInterval(1.5))
        try seed(json)
        XCTAssertNotNil(integration.upgradeOffer)
        try await Task.sleep(for: .seconds(1.6))
        XCTAssertNil(integration.upgradeOffer)
        apply(body())
        XCTAssertNil(integration.upgradeOffer)
        XCTAssertTrue(integration.hasStartedYouTubeBroadcast)
        XCTAssertTrue(UpgradeURLProtocol.requests.isEmpty)
    }

    func testServerDisappearanceClosesSelectedOfferAndKeepsDraft() throws {
        try seed(body(selected: true, settings: true))
        integration.settingsEditor.editCHZZK { $0.title = "retry draft" }
        apply(body(offer: false))
        XCTAssertNil(integration.upgradeOffer)
        XCTAssertNil(integration.selectedUpgradeOption)
        XCTAssertEqual(integration.settingsEditor.chzzk.title, "retry draft")
        XCTAssertNotNil(integration.session)
    }

    func testPartialResponsePreservesOffer() throws {
        try seed()
        apply("{\"status\":\"streaming\",\"broadcast_phase\":\"live\",\"publisher_active\":true,\"reconnect_attempts\":0}")
        XCTAssertNotNil(integration.upgradeOffer)
    }

    func testCancelRestartConfirmationNeverSelectsOrSwitches() async throws {
        for settings in [false, true] {
            integration.reset()
            try seed(body(settings: settings))
            let accepted = await integration.chooseUpgradeOption(mode: settings ? "fhd_multi" : "fhd_single", accessToken: token)
            XCTAssertFalse(accepted)
            XCTAssertNotNil(integration.upgradeOffer)
            XCTAssertTrue(UpgradeURLProtocol.requests.isEmpty)
        }
    }

    func testImmediateAcceptUsesModeAPIAndClosesWholeOffer() async throws {
        try seed()
        enqueue(body(offer: false, switching: true), status: 202)
        let accepted = await integration.chooseUpgradeOption(mode: "fhd_single", restartConfirmed: true, accessToken: token)
        XCTAssertTrue(accepted)
        let request = try XCTUnwrap(UpgradeURLProtocol.requests.first)
        XCTAssertEqual(request.httpMethod, "PUT")
        XCTAssertEqual(request.url?.path, "/sessions/upgrade-session/broadcast-mode")
        XCTAssertEqual(try payload(request)["resolution"] as? String, "fhd")
        XCTAssertEqual(try payload(request)["targets"] as? [String], ["youtube"])
        XCTAssertEqual(UpgradeURLProtocol.requests.count, 1)
        XCTAssertNil(integration.upgradeOffer)
        XCTAssertTrue(integration.isChangingBroadcastMode)
        XCTAssertNotNil(integration.session)
    }

    func testNoRestartOptionDoesNotRequireConfirmation() async throws {
        try seed(body(restart: false))
        enqueue(body(offer: false, switching: true), status: 202)
        let accepted = await integration.chooseUpgradeOption(mode: "fhd_single", accessToken: token)
        XCTAssertTrue(accepted)
        XCTAssertEqual(UpgradeURLProtocol.requests.count, 1)
    }

    func testSelectionUsesModeOnlyAndServerExtendedExpiration() async throws {
        try seed(body(settings: true))
        let expires = Date().addingTimeInterval(180)
        enqueue(body(expires: expires, selected: true, settings: true))
        let selected = await integration.chooseUpgradeOption(mode: "fhd_multi", restartConfirmed: true, accessToken: token)
        XCTAssertTrue(selected)
        let request = try XCTUnwrap(UpgradeURLProtocol.requests.first)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/sessions/upgrade-session/upgrade-offer/select")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer " + token)
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Session-Owner-Token"), "fake-owner")
        XCTAssertEqual(try payload(request) as NSDictionary, ["mode": "fhd_multi"] as NSDictionary)
        XCTAssertEqual(integration.selectedUpgradeOption?.mode, "fhd_multi")
        XCTAssertEqual(try XCTUnwrap(integration.upgradeOffer?.expirationDate).timeIntervalSince(expires), 0, accuracy: 1)
        XCTAssertEqual(integration.upgradeSettingsProviders, [.chzzk])
        let resumed = await integration.chooseUpgradeOption(mode: "fhd_multi", restartConfirmed: true, accessToken: token)
        XCTAssertTrue(resumed)
        XCTAssertEqual(UpgradeURLProtocol.requests.count, 1)
    }

    func testDeclineUsesDeleteWithoutBodyAndKeepsBroadcast() async throws {
        try seed()
        enqueue(body(offer: false))
        let declined = await integration.declineUpgradeOffer(accessToken: token)
        XCTAssertTrue(declined)
        let request = try XCTUnwrap(UpgradeURLProtocol.requests.first)
        XCTAssertEqual(request.httpMethod, "DELETE")
        XCTAssertEqual(request.url?.path, "/sessions/upgrade-session/upgrade-offer")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Session-Owner-Token"), "fake-owner")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer " + token)
        XCTAssertNil(request.httpBody)
        XCTAssertNil(request.httpBodyStream)
        XCTAssertNil(integration.upgradeOffer)
        XCTAssertTrue(integration.hasStartedYouTubeBroadcast)
    }

    func testOfferNotFoundClosesOnlyOfferForSelectDeclineAndAccept() async throws {
        for action in 0..<3 {
            integration.reset()
            UpgradeURLProtocol.requests = []
            try seed(body(settings: action == 0))
            enqueue("{\"error\":{\"code\":\"upgrade_offer_not_found\"}}", status: 404)
            if action == 1 { _ = await integration.declineUpgradeOffer(accessToken: token) }
            else { _ = await integration.chooseUpgradeOption(mode: action == 0 ? "fhd_multi" : "fhd_single", restartConfirmed: true, accessToken: token) }
            XCTAssertNil(integration.upgradeOffer)
            XCTAssertNil(integration.upgradeOfferError)
            XCTAssertNil(integration.broadcastModeError)
            XCTAssertNil(integration.errorMessage)
            XCTAssertNotNil(integration.session)
            XCTAssertTrue(integration.hasStartedYouTubeBroadcast)
            XCTAssertFalse(integration.isChangingBroadcastMode)
            XCTAssertEqual(UpgradeURLProtocol.requests.count, 1)
        }
    }

    func testDeclineFailureRetainsOfferForRetry() async throws {
        try seed()
        enqueue("{\"error\":{\"code\":\"internal_error\"}}", status: 500)
        let declined = await integration.declineUpgradeOffer(accessToken: token)
        XCTAssertFalse(declined)
        XCTAssertNotNil(integration.upgradeOffer)
        XCTAssertNotNil(integration.upgradeOfferError)
        enqueue(body(offer: false))
        let retried = await integration.declineUpgradeOffer(accessToken: token)
        XCTAssertTrue(retried)
    }

    func testPendingSelectionBlocksCompetingModeAndLiveEditing() async throws {
        try seed(body(settings: true))
        enqueue(body(expires: Date().addingTimeInterval(180), selected: true, settings: true), delay: 0.1)
        let task = Task { await integration.chooseUpgradeOption(mode: "fhd_multi", restartConfirmed: true, accessToken: token) }
        await waitForRequest()
        XCTAssertTrue(integration.isHandlingUpgradeOffer)
        XCTAssertFalse(integration.canChangeBroadcastMode)
        XCTAssertFalse(integration.canEditLiveBroadcast(.youtube))
        let competing = await integration.chooseUpgradeOption(mode: "fhd_multi", restartConfirmed: true, accessToken: token)
        let declined = await integration.declineUpgradeOffer(accessToken: token)
        XCTAssertFalse(competing)
        XCTAssertFalse(declined)
        let selected = await task.value
        XCTAssertTrue(selected)
        XCTAssertEqual(UpgradeURLProtocol.requests.count, 1)
    }

    func testResetRejectsLateSelectionResponse() async throws {
        try seed(body(settings: true))
        enqueue(body(expires: Date().addingTimeInterval(180), selected: true, settings: true), delay: 0.1)
        let task = Task { await integration.chooseUpgradeOption(mode: "fhd_multi", restartConfirmed: true, accessToken: token) }
        await waitForRequest()
        integration.reset()
        let accepted = await task.value
        XCTAssertFalse(accepted)
        XCTAssertNil(integration.session)
        XCTAssertNil(integration.upgradeOffer)
        XCTAssertFalse(integration.isHandlingUpgradeOffer)
    }

    func testSettingsSaveThenSwitchSendsEachRequestOnce() async throws {
        try await seedSettings()
        enqueue(body(expires: Date().addingTimeInterval(180), selected: true, settings: true))
        enqueue(body(offer: false, settings: true, switching: true), status: 202)
        let switched = await integration.saveUpgradeOfferSettings(provider: .chzzk, accessToken: token)
        XCTAssertTrue(switched)
        XCTAssertEqual(UpgradeURLProtocol.requests.map { $0.url!.path },
                       ["/sessions/upgrade-session/broadcast", "/sessions/upgrade-session/broadcast-mode"])
        XCTAssertEqual(UpgradeURLProtocol.requests.first?.url?.query, "provider=chzzk")
        XCTAssertEqual(try payload(UpgradeURLProtocol.requests[1])["targets"] as? [String], ["chzzk", "youtube"])
        XCTAssertNil(integration.upgradeOffer)
    }

    func testSettingsFailureKeepsDraftAndRetriesBeforeSwitch() async throws {
        try await seedSettings()
        let draft = integration.settingsEditor.chzzk
        enqueue("{\"error\":{\"code\":\"bad_request\",\"details\":{\"field\":\"title\",\"reason\":\"retry\"}}}", status: 400)
        let failed = await integration.saveUpgradeOfferSettings(provider: .chzzk, accessToken: token)
        XCTAssertFalse(failed)
        XCTAssertEqual(integration.settingsEditor.chzzk, draft)
        XCTAssertNotNil(integration.upgradeOffer)
        XCTAssertNotNil(integration.broadcastModeError)
        XCTAssertNotNil(integration.settingsEditor.fieldErrors["title"])
        XCTAssertEqual(UpgradeURLProtocol.requests.count, 1)
        enqueue(body(expires: Date().addingTimeInterval(180), selected: true, settings: true))
        enqueue(body(offer: false, settings: true, switching: true), status: 202)
        let retried = await integration.saveUpgradeOfferSettings(provider: .chzzk, accessToken: token)
        XCTAssertTrue(retried)
        XCTAssertEqual(UpgradeURLProtocol.requests.filter { $0.url?.path.hasSuffix("broadcast-mode") == true }.count, 1)
    }

    func testOfferDisappearingDuringSettingsSavePreventsSwitch() async throws {
        try await seedSettings()
        enqueue(body(offer: false))
        let saved = await integration.saveUpgradeOfferSettings(provider: .chzzk, accessToken: token)
        XCTAssertFalse(saved)
        XCTAssertEqual(UpgradeURLProtocol.requests.count, 1)
        XCTAssertNil(integration.upgradeOffer)
        XCTAssertEqual(integration.settingsEditor.chzzk.title, "additional draft")
    }

    func testInvalidSettingsNeverSendsSaveOrModeRequest() async throws {
        try await seedSettings()
        integration.settingsEditor.editCHZZK { $0.title = "" }
        let saved = await integration.saveUpgradeOfferSettings(provider: .chzzk, accessToken: token)
        XCTAssertFalse(saved)
        XCTAssertTrue(UpgradeURLProtocol.requests.isEmpty)
    }

    func testUnknownOptionAndExpiredSelectionNeverSendRequest() async throws {
        try seed()
        let unknown = await integration.chooseUpgradeOption(mode: "future_mode", restartConfirmed: true, accessToken: token)
        XCTAssertFalse(unknown)
        apply(body(expires: Date().addingTimeInterval(-1)))
        let expired = await integration.chooseUpgradeOption(mode: "fhd_single", restartConfirmed: true, accessToken: token)
        XCTAssertFalse(expired)
        XCTAssertTrue(UpgradeURLProtocol.requests.isEmpty)
    }

    func testFractionalExpirationAndRestartEffectsKeepServerMeaning() throws {
        try seed(body().replacingOccurrences(of: "Z\"", with: ".123456789Z\""))
        let offer = try XCTUnwrap(integration.upgradeOffer)
        XCTAssertNotNil(offer.expirationDate)
        let option = try XCTUnwrap(offer.options?.first)
        XCTAssertFalse(option.restartEffects[0].sameLink)
        XCTAssertNil(option.restartEffects[0].gapSeconds)
        XCTAssertTrue(option.restartEffects[1].sameLink)
        XCTAssertEqual(option.restartEffects[1].gapSeconds, 15)
        XCTAssertTrue(option.restartMessage.contains("YouTube"))
        XCTAssertTrue(option.restartMessage.contains("15"))
    }

    func testNonRestartOfferDecodesNullEffectsAndUnlimitedTime() throws {
        var object = try JSONSerialization.jsonObject(with: Data(body(settings: true, restart: false).utf8)) as! [String: Any]
        var offer = object["upgrade_offer"] as! [String: Any]
        var options = offer["options"] as! [[String: Any]]
        options[0]["restart_effects"] = NSNull()
        options[0]["remaining_seconds_after"] = NSNull()
        options[0]["mode"] = "720p_multi"
        options[0]["resolution"] = "720p"
        offer["options"] = options
        object["upgrade_offer"] = offer
        let response = try JSONDecoder().decode(YouTubeSessionResponse.self, from: JSONSerialization.data(withJSONObject: object))
        let option = try XCTUnwrap(response.details.upgradeOffer?.options?.first)
        XCTAssertEqual(option.restartEffects, [])
        XCTAssertEqual(option.remainingSecondsAfter, .unlimitedOrInactive)
        XCTAssertEqual(option.supportedTargets, [.youtube, .chzzk])
    }

    func testSelectionExtensionSurvivesOriginalDeadline() async throws {
        try seed(body(expires: Date().addingTimeInterval(1.5), settings: true))
        enqueue(body(expires: Date().addingTimeInterval(180), selected: true, settings: true))
        let selected = await integration.chooseUpgradeOption(mode: "fhd_multi", restartConfirmed: true, accessToken: token)
        XCTAssertTrue(selected)
        try await Task.sleep(for: .seconds(1.6))
        XCTAssertNotNil(integration.selectedUpgradeOption)
        XCTAssertGreaterThan(try XCTUnwrap(integration.upgradeOffer?.expirationDate).timeIntervalSinceNow, 170)
    }

    func testExpiredOfferDuringSaveNeverSwitchesEvenWithOldResponse() async throws {
        try await seedSettings()
        apply(body(expires: Date().addingTimeInterval(1.5), selected: true, settings: true))
        let oldSnapshot = body(expires: Date().addingTimeInterval(180), selected: true, settings: true)
        enqueue(oldSnapshot, delay: 1.7)
        let saved = await integration.saveUpgradeOfferSettings(provider: .chzzk, accessToken: token)
        XCTAssertFalse(saved)
        XCTAssertEqual(UpgradeURLProtocol.requests.count, 1)
        XCTAssertNil(integration.upgradeOffer)
        XCTAssertEqual(integration.settingsEditor.chzzk.title, "additional draft")
    }

    func testLostSelectionResponseRefreshesServerHold() async throws {
        try seed(body(settings: true))
        enqueue("{\"error\":{\"code\":\"internal_error\"}}", status: 500)
        enqueue(body(expires: Date().addingTimeInterval(180), selected: true, settings: true))
        let selected = await integration.chooseUpgradeOption(mode: "fhd_multi", restartConfirmed: true, accessToken: token)
        XCTAssertFalse(selected)
        XCTAssertEqual(integration.selectedUpgradeOption?.mode, "fhd_multi")
        XCTAssertGreaterThan(try XCTUnwrap(integration.upgradeOffer?.expirationDate).timeIntervalSinceNow, 170)
        XCTAssertEqual(UpgradeURLProtocol.requests.map { $0.httpMethod }, ["POST", "GET"])
        XCTAssertFalse(integration.isHandlingUpgradeOffer)
    }

    func testRestoredRestartSelectionRequiresConfirmationBeforeSaving() async throws {
        try seed(body(expires: Date().addingTimeInterval(180), selected: true, settings: true))
        let selected = await integration.chooseUpgradeOption(mode: "fhd_multi", accessToken: token)
        let saved = await integration.saveUpgradeOfferSettings(provider: .chzzk, accessToken: token)
        XCTAssertFalse(selected)
        XCTAssertFalse(saved)
        XCTAssertTrue(UpgradeURLProtocol.requests.isEmpty)
    }

    func testCommittedOfferFixturesDecodeAllOptionsAndSelection() throws {
        let offered = try JSONDecoder().decode(YouTubeSessionResponse.self, from: ContractTestFixtures.data(named: "upgrade-offer-options.v1"))
        let options = try XCTUnwrap(offered.details.upgradeOffer?.options)
        XCTAssertEqual(options.map(\.mode), ["fhd_single", "720p_multi", "fhd_multi"])
        XCTAssertEqual(options[1].restartEffects, [])
        XCTAssertFalse(options[1].restartsBroadcast)
        XCTAssertTrue(options[1].needsSettings)
        XCTAssertEqual(options[2].unitsTo, 3)
        let held = try JSONDecoder().decode(YouTubeSessionResponse.self, from: ContractTestFixtures.data(named: "upgrade-offer-selected.v1"))
        XCTAssertEqual(held.details.upgradeOffer?.selectedOption?.mode, "720p_multi")
    }

    private func seedSettings() async throws {
        try seed(body(expires: Date().addingTimeInterval(180), selected: true, settings: true))
        enqueue("[{\"provider\":\"youtube\",\"channel_id\":\"channel\",\"channel_title\":\"YT\",\"reconnect_required\":false},{\"provider\":\"chzzk\",\"channel_id\":\"chzzk-channel\",\"channel_title\":\"CH\",\"reconnect_required\":false}]")
        await integration.refreshConnection(accessToken: token)
        integration.configureSettingsEditor(accessToken: token)
        integration.settingsEditor.editCHZZK { $0.title = "additional draft" }
        let confirmed = await integration.chooseUpgradeOption(mode: "fhd_multi", restartConfirmed: true, accessToken: token)
        XCTAssertTrue(confirmed)
        UpgradeURLProtocol.requests = []
    }

    private func waitForRequest() async {
        for _ in 0..<100 {
            if !UpgradeURLProtocol.requests.isEmpty { return }
            try? await Task.sleep(for: .milliseconds(5))
        }
        XCTFail("Request did not start")
    }

    private func payload(_ request: URLRequest) throws -> [String: Any] {
        if let data = request.httpBody { return try JSONSerialization.jsonObject(with: data) as! [String: Any] }
        let input = try XCTUnwrap(request.httpBodyStream)
        input.open()
        defer { input.close() }
        var data = Data(), buffer = [UInt8](repeating: 0, count: 2048)
        while input.hasBytesAvailable {
            let count = input.read(&buffer, maxLength: buffer.count)
            if count <= 0 { break }
            data.append(buffer, count: count)
        }
        return try JSONSerialization.jsonObject(with: data) as! [String: Any]
    }
}

private final class UpgradeURLProtocol: URLProtocol, @unchecked Sendable {
    struct Response { let body: Data; let status: Int; let delay: TimeInterval }
    nonisolated(unsafe) static var requests: [URLRequest] = []
    nonisolated(unsafe) static var responses: [Response] = []
    private var work: DispatchWorkItem?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests.append(request)
        let response = Self.responses.isEmpty ? Response(body: Data(), status: 500, delay: 0) : Self.responses.removeFirst()
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.client?.urlProtocol(self, didReceive: HTTPURLResponse(url: self.request.url!, statusCode: response.status,
                httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
            self.client?.urlProtocol(self, didLoad: response.body)
            self.client?.urlProtocolDidFinishLoading(self)
        }
        self.work = work
        DispatchQueue.main.asyncAfter(deadline: .now() + response.delay, execute: work)
    }
    override func stopLoading() { work?.cancel() }
}
