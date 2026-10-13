import Foundation
import SwiftUI
import UIKit
import XCTest
@testable import InnoLive

@MainActor
final class BroadcastPreparationFlowTests: XCTestCase {
    private var store: MemoryPreparationSessionStore!
    private var defaults: UserDefaults!
    private var suite: String!
    private var token: String { Self.token("user-a") }
    private var allowed: BroadcastPreparationPermissions {
        BroadcastPreparationPermissions(hasMediaTransmissionConsent: true, cameraAuthorized: true, microphoneAuthorized: true)
    }

    override func setUp() {
        super.setUp()
        store = MemoryPreparationSessionStore()
        suite = "broadcast-preparation-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
        PreparationFlowURLProtocol.reset()
    }

    override func tearDown() {
        PreparationFlowURLProtocol.resumeHold()
        PreparationFlowURLProtocol.reset()
        defaults.removePersistentDomain(forName: suite)
        super.tearDown()
    }

    func testAccountRefreshAndNilSessionDefaultsDoNotCreateSession() async {
        let integration = makeIntegration()
        PreparationFlowURLProtocol.responses = [.json(Self.accounts)]
        await integration.refreshConnection(accessToken: token)
        integration.configureSettingsEditor(accessToken: token)
        await integration.settingsEditor.loadDefaults(session: nil, accessToken: token, provider: .youtube)
        XCTAssertEqual(PreparationFlowURLProtocol.requests.map(\.httpMethod), ["GET"])
        XCTAssertEqual(PreparationFlowURLProtocol.requests.first?.url?.path, "/auth/streaming/accounts")
        XCTAssertNil(integration.session)
    }

    func testRejectedConsentPermissionAndSettingsDoNotConnect() async {
        let integration = makeIntegration()
        let refusedConsent = BroadcastPreparationPermissions(
            hasMediaTransmissionConsent: false, cameraAuthorized: true, microphoneAuthorized: true
        )
        let refusedCamera = BroadcastPreparationPermissions(
            hasMediaTransmissionConsent: true, cameraAuthorized: false, microphoneAuthorized: true
        )
        let refusedConsentResult = await prepare(integration, permissions: refusedConsent)
        let refusedCameraResult = await prepare(integration, permissions: refusedCamera)
        XCTAssertFalse(refusedConsentResult)
        XCTAssertFalse(refusedCameraResult)
        integration.settingsEditor.editYouTube { $0.title = " " }
        let invalidTitle = await prepare(integration)
        XCTAssertFalse(invalidTitle)
        integration.settingsEditor.editYouTube { $0.title = "제목"; $0.audience = nil }
        let invalidAudience = await prepare(integration)
        XCTAssertFalse(invalidAudience)
        let disconnected = makeIntegration(connected: false)
        let missingAccount = await prepare(disconnected)
        XCTAssertFalse(missingAccount)
        XCTAssertTrue(PreparationFlowURLProtocol.requests.isEmpty)
        XCTAssertNil(integration.session)
        XCTAssertNil(integration.preparationStatus)
        XCTAssertNil(disconnected.preparationStatus)
    }

    func testPrepareCreatesSessionConnectsAndDoesNotGoLive() async throws {
        let integration = makeIntegration()
        integration.configureSettingsEditor(accessToken: token)
        integration.settingsEditor.setUsesDefaults(false, provider: .youtube)
        let probe = PreparationProbe()
        PreparationFlowURLProtocol.responses = [.created("new"), .snapshot(phase: "idle"), .snapshot(phase: "prepared")]
        let prepared = await prepare(integration, probe: probe)
        XCTAssertTrue(prepared)
        XCTAssertEqual(paths, ["/sessions", "/sessions/new/broadcast", "/sessions/new/stream/prepare"])
        XCTAssertEqual(methods, ["POST", "PUT", "POST"])
        XCTAssertEqual(probe.calls, 1)
        XCTAssertNil(integration.preparationStatus)
        XCTAssertEqual(integration.broadcastPhase, "prepared")
        XCTAssertTrue(integration.isVideoConnected)
        XCTAssertEqual(integration.session?.sessionID, "new")
        XCTAssertFalse(paths.contains { $0.contains("golive") })
    }

    func testDefaultsLoadAfterSessionAndBeforeVideo() async throws {
        let integration = makeIntegration()
        integration.configureSettingsEditor(accessToken: token)
        let probe = PreparationProbe()
        PreparationFlowURLProtocol.responses = [
            .created("new"),
            .json("{\"title\":\"서버 제목\",\"description\":\"\",\"privacy\":\"private\",\"made_for_kids\":false}"),
            .snapshot(phase: "idle"),
            .snapshot(phase: "prepared")
        ]
        let prepared = await prepare(integration, probe: probe)
        XCTAssertTrue(prepared)
        XCTAssertEqual(probe.pathsAtCall, ["/sessions", "/sessions/new/broadcast/defaults"])
        XCTAssertEqual(paths, [
            "/sessions",
            "/sessions/new/broadcast/defaults",
            "/sessions/new/broadcast",
            "/sessions/new/stream/prepare"
        ])
        let saved = try jsonBody(PreparationFlowURLProtocol.requests[2])
        XCTAssertEqual(saved["title"] as? String, "서버 제목")
        XCTAssertEqual(integration.settingsEditor.youtube.title, "서버 제목")
        XCTAssertFalse(paths.contains { $0.contains("golive") })
    }

    func testInvalidDefaultsDiscardSessionBeforeVideo() async throws {
        let integration = makeIntegration()
        integration.configureSettingsEditor(accessToken: token)
        let probe = PreparationProbe()
        let title = String(repeating: "a", count: 101)
        PreparationFlowURLProtocol.responses = [
            .created("new"),
            .json("{\"title\":\"\(title)\",\"description\":\"\",\"privacy\":\"private\",\"made_for_kids\":false}"),
            .empty(204)
        ]
        let prepared = await prepare(integration, probe: probe)
        XCTAssertFalse(prepared)
        XCTAssertEqual(probe.calls, 0)
        XCTAssertNil(integration.session)
        XCTAssertNil(try store.load(scope: try scope()))
        XCTAssertEqual(integration.preparationStatus?.failedPhase, .creatingSession)
        XCTAssertEqual(paths, ["/sessions", "/sessions/new/broadcast/defaults", "/sessions/new"])
        XCTAssertEqual(methods, ["POST", "GET", "DELETE"])
    }

    func testDuplicatePreparationCreatesOneSession() async {
        let integration = makeIntegration()
        integration.configureSettingsEditor(accessToken: token)
        integration.settingsEditor.setUsesDefaults(false, provider: .youtube)
        let probe = PreparationProbe()
        PreparationFlowURLProtocol.responses = [
            .created("new", hold: true), .snapshot(phase: "idle"), .snapshot(phase: "prepared")
        ]
        async let first = prepare(integration, probe: probe)
        async let second = prepare(integration, probe: probe)
        await waitUntil { PreparationFlowURLProtocol.isHolding }
        XCTAssertEqual(PreparationFlowURLProtocol.requests.filter { $0.url?.path == "/sessions" }.count, 1)
        PreparationFlowURLProtocol.resumeHold()
        let results = await [first, second]
        XCTAssertEqual(results, [true, true])
        XCTAssertEqual(PreparationFlowURLProtocol.requests.filter { $0.httpMethod == "POST" && $0.url?.path == "/sessions" }.count, 1)
        XCTAssertEqual(probe.calls, 1)
    }

    func testResetDuringCreateKeepsCleanupRecordAndSkipsVideo() async throws {
        let integration = makeIntegration()
        let probe = PreparationProbe()
        PreparationFlowURLProtocol.responses = [.created("new")]
        PreparationFlowURLProtocol.beforeResponse = { request in
            if request.httpMethod == "POST", request.url?.path == "/sessions" {
                integration.reset()
            }
        }
        let prepared = await prepare(integration, probe: probe)
        XCTAssertFalse(prepared)
        XCTAssertNil(integration.session)
        XCTAssertNil(integration.preparationStatus)
        XCTAssertEqual(probe.calls, 0)
        XCTAssertEqual(try store.load(scope: try scope())?.sessionID, "new")
    }

    func testCancelDuringCreateDeletesSessionAndSkipsVideo() async throws {
        let integration = makeIntegration()
        let probe = PreparationProbe()
        PreparationFlowURLProtocol.responses = [.created("new", hold: true), .empty(204)]
        async let prepared = prepare(integration, probe: probe)
        await waitUntil { PreparationFlowURLProtocol.isHolding }
        async let cancelled: Void = integration.cancelBroadcastPreparation(accessToken: token)
        await waitUntil { integration.preparationStatus?.phase == .cancelling }
        PreparationFlowURLProtocol.resumeHold()
        let result = await prepared
        await cancelled
        XCTAssertFalse(result)
        XCTAssertNil(integration.session)
        XCTAssertNil(integration.preparationStatus)
        XCTAssertNil(try store.load(scope: try scope()))
        XCTAssertEqual(probe.calls, 0)
        XCTAssertEqual(methods, ["POST", "DELETE"])
        XCTAssertFalse(paths.contains { $0.contains("golive") })
    }

    func testPrepareFailureKeepsSessionAndRetrySkipsNewConnection() async throws {
        let integration = makeIntegration()
        integration.configureSettingsEditor(accessToken: token)
        integration.settingsEditor.setUsesDefaults(false, provider: .youtube)
        let probe = PreparationProbe()
        PreparationFlowURLProtocol.responses = [
            .created("new"),
            .snapshot(phase: "idle"),
            .status(502, "{\"error\":{\"code\":\"streaming_prepare_failed\"}}"),
            .snapshot(phase: "idle"),
            .snapshot(phase: "prepared"),
            .snapshot(phase: "idle"),
            .empty(503)
        ]
        let failed = await prepare(integration, probe: probe)
        XCTAssertFalse(failed)
        XCTAssertEqual(integration.preparationStatus?.failedPhase, .preparingStream)
        XCTAssertEqual(integration.session?.sessionID, "new")
        XCTAssertEqual(probe.calls, 1)
        XCTAssertEqual(PreparationFlowURLProtocol.requests.filter { $0.httpMethod == "POST" && $0.url?.path == "/sessions" }.count, 1)

        let retried = await prepare(integration, probe: probe)
        XCTAssertTrue(retried)
        XCTAssertEqual(probe.calls, 1)
        XCTAssertEqual(PreparationFlowURLProtocol.requests.filter { $0.httpMethod == "POST" && $0.url?.path == "/sessions" }.count, 1)
        XCTAssertEqual(integration.broadcastPhase, "prepared")
        XCTAssertNil(integration.preparationStatus)

        await integration.cancelBroadcastPreparation(accessToken: token)
        XCTAssertNil(integration.session)
        XCTAssertNil(integration.preparationStatus)
        XCTAssertEqual(try store.load(scope: try scope())?.sessionID, "new")
        XCTAssertTrue(paths.contains { $0.hasSuffix("/stream/stop") })
        XCTAssertEqual(methods.last, "DELETE")
        XCTAssertFalse(paths.contains { $0.contains("golive") })
    }

    func testVideoConnectionFailureDiscardsSession() async throws {
        let integration = makeIntegration()
        integration.configureSettingsEditor(accessToken: token)
        integration.settingsEditor.setUsesDefaults(false, provider: .youtube)
        let probe = PreparationProbe()
        PreparationFlowURLProtocol.responses = [.created("new"), .empty(204)]
        let prepared = await prepare(integration, probe: probe) { _ in false }
        XCTAssertFalse(prepared)
        XCTAssertEqual(probe.calls, 1)
        XCTAssertNil(integration.session)
        XCTAssertNil(try store.load(scope: try scope()))
        XCTAssertEqual(integration.preparationStatus?.failedPhase, .connectingServer)
    }

    func testConnectorWithoutLiveVideoFailsConfirmationAndDiscardsSession() async throws {
        let integration = makeIntegration()
        integration.configureSettingsEditor(accessToken: token)
        integration.settingsEditor.setUsesDefaults(false, provider: .youtube)
        let probe = PreparationProbe()
        PreparationFlowURLProtocol.responses = [.created("new"), .empty(204)]
        let prepared = await prepare(integration, probe: probe, markReady: false) { _ in true }
        XCTAssertFalse(prepared)
        XCTAssertNil(integration.session)
        XCTAssertNil(try store.load(scope: try scope()))
        XCTAssertEqual(integration.preparationStatus?.failedPhase, .confirmingVideo)
    }

    private func configureDual(_ integration: YouTubeIntegration) async {
        PreparationFlowURLProtocol.responses = [.json(Self.accounts)]
        await integration.refreshConnection(accessToken: token)
        integration.configureSettingsEditor(accessToken: token)
        integration.selectedBroadcastProviders = [.youtube, .chzzk]
        integration.settingsEditor.setUsesDefaults(false, provider: .youtube)
        integration.settingsEditor.setUsesDefaults(false, provider: .chzzk)
        integration.settingsEditor.editCHZZK { $0.title = "치지직 제목" }
        PreparationFlowURLProtocol.requests = []
    }

    func testDualSelectionPreparesEachTargetButDoesNotGoLive() async throws {
        let integration = makeIntegration()
        await configureDual(integration)
        PreparationFlowURLProtocol.responses = [
            .created("new"), .targets("idle", "idle"), .targets("prepared", "idle"),
            .targets("prepared", "idle"), .readyTargets()
        ]
        let result = await prepare(integration)
        XCTAssertTrue(result)
        XCTAssertEqual(methods, ["POST", "PUT", "POST", "PUT", "POST"])
        let requests = PreparationFlowURLProtocol.requests
        XCTAssertEqual(requests.count, 5)
        if requests.count == 5 {
            XCTAssertEqual(requests[1].url?.query, "provider=youtube")
            XCTAssertEqual(try jsonBody(requests[2])["provider"] as? String, "youtube")
            XCTAssertEqual(requests[3].url?.query, "provider=chzzk")
            XCTAssertEqual(try jsonBody(requests[4])["provider"] as? String, "chzzk")
            XCTAssertEqual(try jsonBody(requests[3])["title"] as? String, "치지직 제목")
        }
        XCTAssertFalse(paths.contains { $0.contains("golive") })
        XCTAssertNil(integration.preparationStatus)
        integration.reset()
    }

    func testSaveFailureSkipsOnlyFailedTargetPrepareAndOffersContinue() async throws {
        let integration = makeIntegration()
        await configureDual(integration)
        PreparationFlowURLProtocol.responses = [
            .created("new"), .status(500, ""), .targets("idle", "idle"), .targets("idle", "prepared")
        ]
        let result = await prepare(integration)
        XCTAssertFalse(result)
        XCTAssertEqual(methods, ["POST", "PUT", "PUT", "POST"])
        let prepares = PreparationFlowURLProtocol.requests.filter { $0.url?.path.hasSuffix("/stream/prepare") == true }
        XCTAssertEqual(prepares.count, 1)
        if let request = prepares.first { XCTAssertEqual(try jsonBody(request)["provider"] as? String, "chzzk") }
        XCTAssertEqual(integration.preparationFailures[.youtube]?.failedPhase, .savingSettings)
        XCTAssertNil(integration.preparationFailures[.chzzk])
        XCTAssertTrue(integration.canContinuePreparedBroadcast)
        XCTAssertTrue(integration.continueWithPreparedTargets())
        XCTAssertNil(integration.preparationStatus)
        XCTAssertFalse(paths.contains { $0.contains("golive") })
        integration.reset()
    }

    func testPartialFailureRetriesOnlyTheFailedProvider() async throws {
        let integration = makeIntegration()
        await configureDual(integration)
        let probe = PreparationProbe()
        PreparationFlowURLProtocol.responses = [
            .created("new"), .targets("idle", "idle"), .targets("prepared", "idle"),
            .targets("prepared", "idle"), .status(502, "{\"error\":{\"code\":\"streaming_prepare_failed\"}}"),
            .targets("prepared", "idle"), .readyTargets()
        ]
        let failed = await prepare(integration, probe: probe)
        XCTAssertFalse(failed)
        XCTAssertEqual(integration.preparationFailures[.chzzk]?.failedPhase, .preparingStream)
        let retried = await prepare(integration, probe: probe)
        XCTAssertTrue(retried)
        XCTAssertEqual(probe.calls, 1)
        XCTAssertEqual(PreparationFlowURLProtocol.requests.filter { $0.url?.path == "/sessions" }.count, 1)
        let retryRequests = Array(PreparationFlowURLProtocol.requests.suffix(2))
        XCTAssertEqual(retryRequests[0].url?.query, "provider=chzzk")
        XCTAssertEqual(try jsonBody(retryRequests[1])["provider"] as? String, "chzzk")
        XCTAssertNil(integration.preparationStatus)
        XCTAssertFalse(paths.contains { $0.contains("golive") })
        integration.reset()
    }

    func testPartialCancelStopsPreparedTargetThenDeletesSession() async throws {
        let integration = makeIntegration()
        await configureDual(integration)
        PreparationFlowURLProtocol.responses = [
            .created("new"), .targets("idle", "idle"), .targets("prepared", "idle"),
            .targets("prepared", "idle"), .status(500, ""), .snapshot(phase: "idle"), .empty(204)
        ]
        let failed = await prepare(integration)
        XCTAssertFalse(failed)
        await integration.cancelBroadcastPreparation(accessToken: token)
        XCTAssertNil(integration.session)
        XCTAssertNil(integration.preparationStatus)
        XCTAssertNil(try store.load(scope: try scope()))
        let stop = try XCTUnwrap(PreparationFlowURLProtocol.requests.first { $0.url?.path.hasSuffix("/stream/stop") == true })
        XCTAssertEqual(stop.url?.query, "provider=youtube")
        XCTAssertEqual(methods.last, "DELETE")
        XCTAssertFalse(paths.contains { $0.contains("golive") })
    }

    func testInvalidUnconnectedOrEmptySelectionDoesNotCreateSession() async {
        let integration = makeIntegration()
        integration.selectedBroadcastProviders = []
        let empty = await prepare(integration)
        XCTAssertFalse(empty)
        integration.selectedBroadcastProviders = [.youtube, .chzzk]
        let unconnected = await prepare(integration)
        XCTAssertFalse(unconnected)
        await configureDual(integration)
        integration.settingsEditor.editCHZZK { $0.title = " " }
        let invalid = await prepare(integration)
        XCTAssertFalse(invalid)
        XCTAssertTrue(PreparationFlowURLProtocol.requests.isEmpty)
        XCTAssertNil(integration.session)
        integration.reset()
    }

    func testChzzkOnlySelectionPreparesOnceWithoutYouTubeSettings() async throws {
        let integration = makeIntegration()
        await configureDual(integration)
        integration.selectedBroadcastProviders = [.chzzk]
        PreparationFlowURLProtocol.responses = [.created("new"), .targets("idle", "idle"), .targets("idle", "prepared")]
        let result = await prepare(integration)
        XCTAssertTrue(result)
        XCTAssertEqual(methods, ["POST", "PUT", "POST"])
        XCTAssertEqual(PreparationFlowURLProtocol.requests[1].url?.query, "provider=chzzk")
        XCTAssertEqual(try jsonBody(PreparationFlowURLProtocol.requests[2])["provider"] as? String, "chzzk")
        XCTAssertEqual(integration.broadcastPhase, "prepared")
        XCTAssertEqual(integration.visibleBroadcastTargets.map(\.provider), ["chzzk"])
        integration.reset()
    }

    func testDualSelectionChecksMultiPlanBeforeCreatingSession() async throws {
        let integration = makeIntegration()
        await configureDual(integration)
        PreparationFlowURLProtocol.responses = [
            .json("{\"plan\":\"spark\",\"allowed_modes\":[\"720p_single\"],\"monthly_broadcast_seconds\":18000,\"max_per_broadcast_seconds\":7200}"),
            .json("{\"plan\":\"spark\",\"used_seconds\":0,\"remaining_seconds\":18000,\"available_by_mode\":[{\"mode\":\"720p_single\",\"allowed\":true,\"seconds\":18000,\"multiplier\":1},{\"mode\":\"720p_multi\",\"allowed\":false,\"seconds\":9000,\"multiplier\":2}]}")
        ]
        await integration.refreshPlan(accessToken: token)
        XCTAssertNotNil(integration.planStore.snapshot)
        PreparationFlowURLProtocol.requests = []
        let result = await prepare(integration)
        XCTAssertFalse(result)
        XCTAssertEqual(integration.currentPlanMode, .hdMulti)
        XCTAssertTrue(paths.isEmpty)
        XCTAssertNil(integration.session)
        integration.reset()
    }

    func testPartialPreparationDoesNotGoLiveUntilUserContinuesAndStarts() async throws {
        let integration = makeIntegration()
        await configureDual(integration)
        PreparationFlowURLProtocol.responses = [
            .created("new"), .status(500, ""), .targets("idle", "idle"), .targets("idle", "prepared")
        ]
        _ = await prepare(integration)
        await integration.goLiveYouTubeStream(accessToken: token)
        XCTAssertFalse(paths.contains { $0.contains("golive") })
        XCTAssertTrue(integration.continueWithPreparedTargets())
        XCTAssertFalse(paths.contains { $0.contains("golive") })
        PreparationFlowURLProtocol.responses = [.json("{\"status\":\"idle\",\"publisher_active\":false,\"reconnect_attempts\":0,\"broadcast_phase\":\"idle\",\"targets\":[{\"provider\":\"youtube\",\"stream\":{\"status\":\"idle\",\"publisher_active\":false,\"reconnect_attempts\":0,\"broadcast_phase\":\"idle\"}},{\"provider\":\"chzzk\",\"stream\":{\"status\":\"streaming\",\"publisher_active\":true,\"reconnect_attempts\":0,\"broadcast_phase\":\"live\"}}]}")]
        await integration.goLiveYouTubeStream(accessToken: token)
        let starts = PreparationFlowURLProtocol.requests.filter { $0.url?.path.hasSuffix("/stream/golive") == true }
        XCTAssertEqual(starts.count, 1)
        XCTAssertNil(starts.first?.url?.query)
        XCTAssertEqual(integration.liveEditingTargets, [.chzzk])
        integration.reset()
    }

    func testAllPreparationFailuresCannotContinue() async {
        let integration = makeIntegration()
        await configureDual(integration)
        PreparationFlowURLProtocol.responses = [.created("new"), .status(500, ""), .status(500, "")]
        _ = await prepare(integration)
        XCTAssertEqual(Set(integration.preparationFailures.keys), [.youtube, .chzzk])
        XCTAssertFalse(integration.canContinuePreparedBroadcast)
        XCTAssertFalse(integration.continueWithPreparedTargets())
        XCTAssertFalse(paths.contains { $0.contains("prepare") })
        integration.reset()
    }

    func testServerConfirmedReadyTargetsClearStalePreparationFailureWithoutReprepare() async throws {
        let integration = makeIntegration()
        await configureDual(integration)
        PreparationFlowURLProtocol.responses = [.created("new"), .status(500, ""), .targets("idle", "idle"), .targets("idle", "prepared")]
        _ = await prepare(integration)
        let ready = PreparationFlowURLProtocol.Response.readyTargets()
        integration.applyLiveEditingSnapshotForTesting(try JSONDecoder().decode(YouTubeSessionResponse.self, from: Data(ready.body.utf8)))
        PreparationFlowURLProtocol.requests = []
        let result = await prepare(integration)
        XCTAssertTrue(result)
        XCTAssertTrue(integration.preparationFailures.isEmpty)
        XCTAssertNil(integration.preparationStatus)
        XCTAssertTrue(paths.isEmpty)
        integration.reset()
    }

    func testSimulcastSettingsAndPartialPreparationRendering() async throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive })
        let previousWindow = scene.windows.first { $0.isKeyWindow }
        let authentication = AuthSession(api: AuthenticationAPI(serverURLProvider: { _ in nil }),
            tokenStore: SimulcastEmptyTokenStore(), consentStore: ConsentAcknowledgementStore(userDefaults: defaults))
        for partial in [false, true] {
            let integration = makeIntegration()
            await configureDual(integration)
            if partial {
                PreparationFlowURLProtocol.responses = [.created("new"), .status(500, ""), .targets("idle", "idle"), .targets("idle", "prepared")]
                _ = await prepare(integration)
            }
            let controller = UIHostingController(rootView: NavigationStack {
                BroadcastSettingsView(authentication: authentication, youtube: integration, onPrepare: { _ in }, onCancelPreparation: {})
            }.dynamicTypeSize(.large))
            let window = UIWindow(windowScene: scene)
            window.frame = scene.coordinateSpace.bounds
            window.overrideUserInterfaceStyle = partial ? .dark : .light
            window.rootViewController = controller
            window.makeKeyAndVisible()
            try await Task.sleep(for: .milliseconds(400))
            window.layoutIfNeeded()
            let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
                XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
            }
            let data = try XCTUnwrap(image.pngData())
            let name = partial ? "simulcast-partial-preparation" : "simulcast-dual-settings"
            let attachment = XCTAttachment(image: image)
            attachment.name = name
            attachment.lifetime = .keepAlways
            add(attachment)
            // Kept outside the repository for inspection; xcresult contains the same images.
            try data.write(to: URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(name + ".png"))
            window.isHidden = true
            window.rootViewController = nil
            previousWindow?.makeKeyAndVisible()
            XCTAssertEqual(integration.selectedBroadcastProviders, [.youtube, .chzzk])
            integration.reset()
        }
    }

    private var paths: [String] { PreparationFlowURLProtocol.requests.compactMap { $0.url?.path } }
    private var methods: [String] { PreparationFlowURLProtocol.requests.compactMap(\.httpMethod) }

    private func prepare(
        _ integration: YouTubeIntegration,
        probe: PreparationProbe? = nil,
        permissions: BroadcastPreparationPermissions? = nil,
        markReady: Bool = true,
        connector: ((YouTubeIntegration) async -> Bool)? = nil
    ) async -> Bool {
        let probe = probe ?? PreparationProbe()
        return await integration.startBroadcastPreparation(
            accessToken: token,
            providers: BroadcastSettingsProvider.allCases.filter(integration.selectedBroadcastProviders.contains),
            permissions: permissions ?? allowed,
            videoConnector: {
                probe.calls += 1
                probe.pathsAtCall = PreparationFlowURLProtocol.requests.compactMap { $0.url?.path }
                if let connector { return await connector(integration) }
                if markReady { integration.markServerVideoReadyForTesting() }
                return true
            }
        )
    }

    private func waitUntil(_ condition: () -> Bool) async {
        for _ in 0..<200 {
            if condition() { return }
            await Task.yield()
            try? await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("조건 대기 시간이 지났습니다.")
    }

    private func jsonBody(_ request: URLRequest) throws -> [String: Any] {
        let data = try bodyData(request)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func bodyData(_ request: URLRequest) throws -> Data {
        if let body = request.httpBody { return body }
        let stream = try XCTUnwrap(request.httpBodyStream)
        stream.open()
        defer { stream.close() }
        var collected = Data()
        var buffer = [UInt8](repeating: 0, count: 2048)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count <= 0 { break }
            collected.append(buffer, count: count)
        }
        return collected
    }

    private static func token(_ user: String) -> String {
        "header." + Data("{\"sub\":\"\(user)\"}".utf8).base64EncodedString() + ".signature"
    }

    private func scope() throws -> BroadcastSessionScope {
        try BroadcastSessionScope(server: XCTUnwrap(URL(string: "https://example.invalid/")), accessToken: token)
    }

    private func makeAPI() -> YouTubeAPI {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [PreparationFlowURLProtocol.self]
        return YouTubeAPI(urlSession: URLSession(configuration: configuration), serverURLProvider: {
            URL(string: "https://example.invalid\($0)")
        })
    }

    private func makeIntegration(connected: Bool = true) -> YouTubeIntegration {
        let preferences = YouTubePreferencesStore(userDefaults: defaults)
        if connected {
            preferences.saveConnection(YouTubeConnection(provider: "youtube", channel: YouTubeChannel(id: "yt", title: "YT")))
        } else {
            preferences.removeConnection()
        }
        return YouTubeIntegration(
            preferencesStore: preferences,
            api: makeAPI(),
            sessionStore: store,
            pollingInterval: .seconds(300),
            aiModeProvider: { .server },
            localModelsAvailable: { true },
            persistAIProcessingMode: { _ in }
        )
    }

    private static let accounts = """
    [{"provider":"youtube","channel_id":"yt","channel_title":"YT","reconnect_required":false},\
    {"provider":"chzzk","channel_id":"cz","channel_title":"CZ","reconnect_required":false}]
    """
}

private final class PreparationProbe {
    var calls = 0
    var pathsAtCall: [String] = []
}

@MainActor
private final class MemoryPreparationSessionStore: BroadcastSessionStoring {
    nonisolated deinit {}
    var records: [String: StoredBroadcastSession] = [:]
    func load(scope: BroadcastSessionScope) throws -> StoredBroadcastSession? { records[scope.storageKey] }
    func save(_ session: StoredBroadcastSession, scope: BroadcastSessionScope) throws { records[scope.storageKey] = session }
    func remove(scope: BroadcastSessionScope) throws { records.removeValue(forKey: scope.storageKey) }
}

private final class PreparationFlowURLProtocol: URLProtocol {
    nonisolated deinit {}

    struct Response {
        let status: Int
        let body: String
        var hold = false
        static func empty(_ status: Int) -> Self { .init(status: status, body: "") }
        static func json(_ body: String, status: Int = 200) -> Self { .init(status: status, body: body) }
        static func status(_ status: Int, _ body: String) -> Self { .init(status: status, body: body) }
        static func created(_ id: String, hold: Bool = false) -> Self {
            .init(
                status: 201,
                body: "{\"session_id\":\"\(id)\",\"owner_token\":\"owner\",\"stream\":{\"status\":\"idle\",\"publisher_active\":false,\"reconnect_attempts\":0}}",
                hold: hold
            )
        }
        static func snapshot(phase: String) -> Self {
            .json("{\"session_id\":\"new\",\"owner_token\":\"owner\",\"provider\":\"youtube\",\"stream\":{\"status\":\"idle\",\"publisher_active\":false,\"reconnect_attempts\":0,\"broadcast_phase\":\"\(phase)\"}}")
        }
        static func targets(_ youtube: String, _ chzzk: String) -> Self {
            func stream(_ phase: String) -> String {
                "{\"status\":\"idle\",\"publisher_active\":false,\"reconnect_attempts\":0,\"broadcast_phase\":\"\(phase)\"}"
            }
            return .json("{\"session_id\":\"new\",\"provider\":\"youtube\",\"stream\":\(stream(youtube)),\"targets\":[{\"provider\":\"youtube\",\"stream\":\(stream(youtube))},{\"provider\":\"chzzk\",\"stream\":\(stream(chzzk))}]}")
        }
        static func partial() -> Self {
            let stream = "{\"status\":\"idle\",\"publisher_active\":false,\"reconnect_attempts\":0,\"broadcast_phase\":\"prepared\"}"
            let idle = "{\"status\":\"idle\",\"publisher_active\":false,\"reconnect_attempts\":0,\"broadcast_phase\":\"idle\"}"
            return .json("{\"session_id\":\"new\",\"owner_token\":\"owner\",\"provider\":\"youtube\",\"stream\":\(stream),\"targets\":[{\"provider\":\"youtube\",\"stream\":\(stream)},{\"provider\":\"chzzk\",\"stream\":\(idle)}],\"failed_targets\":[{\"provider\":\"chzzk\",\"code\":\"not_prepared\",\"message\":\"fail\"}]}")
        }
        static func readyTargets() -> Self {
            let stream = "{\"status\":\"idle\",\"publisher_active\":false,\"reconnect_attempts\":0,\"broadcast_phase\":\"prepared\"}"
            return .json("{\"session_id\":\"new\",\"owner_token\":\"owner\",\"provider\":\"youtube\",\"stream\":\(stream),\"targets\":[{\"provider\":\"youtube\",\"stream\":\(stream)},{\"provider\":\"chzzk\",\"stream\":\(stream)}],\"failed_targets\":[]}")
        }
    }

    @MainActor static var responses: [Response] = []
    @MainActor static var requests: [URLRequest] = []
    @MainActor static var beforeResponse: ((URLRequest) -> Void)?
    @MainActor static var isHolding = false
    @MainActor private static var holdContinuation: CheckedContinuation<Void, Never>?

    @MainActor static func reset() {
        responses = []
        requests = []
        beforeResponse = nil
        isHolding = false
    }

    @MainActor static func resumeHold() {
        let continuation = holdContinuation
        holdContinuation = nil
        isHolding = false
        continuation?.resume()
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Task { @MainActor in
            Self.requests.append(self.request)
            guard !Self.responses.isEmpty else {
                self.client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
                return
            }
            let response = Self.responses.removeFirst()
            Self.beforeResponse?(self.request)
            if response.hold {
                Self.isHolding = true
                await withCheckedContinuation { continuation in
                    Self.holdContinuation = continuation
                }
                Self.isHolding = false
            }
            let http = HTTPURLResponse(url: self.request.url!, statusCode: response.status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!
            self.client?.urlProtocol(self, didReceive: http, cacheStoragePolicy: .notAllowed)
            self.client?.urlProtocol(self, didLoad: Data(response.body.utf8))
            self.client?.urlProtocolDidFinishLoading(self)
        }
    }
    override func stopLoading() {}
}

private final class SimulcastEmptyTokenStore: AuthenticationTokenStoring {
    nonisolated deinit {}
    func load() -> AuthenticationTokenPair? { nil }
    func save(_ tokens: AuthenticationTokenPair) throws { XCTFail("Rendering must not sign in") }
    func remove() { XCTFail("Rendering must not sign out") }
}
