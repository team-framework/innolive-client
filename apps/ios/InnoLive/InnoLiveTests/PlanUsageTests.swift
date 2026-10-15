import XCTest
import SwiftUI
@testable import InnoLive

@MainActor
final class PlanUsageTests: XCTestCase {
    private let planJSON = """
    {"plan":"beam","allowed_modes":["720p_single","fhd_single","720p_multi"],"monthly_broadcast_seconds":432000,"max_per_broadcast_seconds":28800}
    """
    private func usageJSON(seconds: String = "3600", multiplier: Int = 7, allowed: Bool = true) -> String {
        """
        {"plan":"beam","used_seconds":12,"remaining_seconds":\(seconds),"available_by_mode":[{"mode":"720p_single","allowed":\(allowed),"seconds":\(seconds),"multiplier":\(multiplier)}]}
        """
    }
    private func plan() throws -> UserPlan { try JSONDecoder().decode(UserPlan.self, from: Data(planJSON.utf8)) }
    private func usage(_ json: String) throws -> UserUsage { try JSONDecoder().decode(UserUsage.self, from: Data(json.utf8)) }

    private struct ProcessingFixture: Decodable { let plan: UserPlan; let usage: UserUsage }
    private func processingSnapshots() throws -> [PlanSnapshot] {
        try JSONDecoder().decode([ProcessingFixture].self,
            from: ContractTestFixtures.data(named: "plan-usage-processing.v1"))
            .map { PlanSnapshot(plan: $0.plan, usage: $0.usage) }
    }

    func testNewPlansSeparateServerAndDeviceBudgetsAndModes() throws {
        let snapshots = try processingSnapshots()
        XCTAssertEqual(snapshots.map { $0.plan.monthlyDeviceSeconds }, [18000, 216000, 432000, 1080000])
        for snapshot in snapshots {
            XCTAssertFalse(snapshot.canPrepare(.hdSingle, processing: .server))
            XCTAssertTrue(snapshot.canPrepare(.hdSingle, processing: .onDevice))
            XCTAssertNotNil(snapshot.preparationBlockMessage(.hdSingle, processing: .server))
            XCTAssertNil(snapshot.preparationBlockMessage(.hdSingle, processing: .onDevice))
            for mode in BroadcastPlanMode.allCases {
                XCTAssertEqual(snapshot.availability(for: mode, processing: .onDevice)?.seconds,
                               snapshot.usage.deviceRemainingSeconds! / mode.referenceMultiplier)
            }
        }
        XCTAssertFalse(snapshots[0].isAllowed(.fhdSingle, processing: .onDevice))
        XCTAssertTrue(snapshots[1].isAllowed(.fhdSingle, processing: .onDevice))
        XCTAssertFalse(snapshots[1].isAllowed(.hdMulti, processing: .onDevice))
        XCTAssertFalse(snapshots[2].isAllowed(.fhdMulti, processing: .onDevice))
        XCTAssertTrue(snapshots[3].isAllowed(.fhdMulti, processing: .onDevice))
    }

    func testDeviceExhaustionDoesNotBlockServerAndMissingFieldsStayUnknown() throws {
        var policy = try plan()
        policy.deviceModes = ["720p_single", "fhd_single"]
        let exhaustedJSON = usageJSON().replacingOccurrences(of: "\"used_seconds\":12",
            with: "\"used_seconds\":12,\"device_remaining_seconds\":0")
        let exhausted = PlanSnapshot(plan: policy, usage: try usage(exhaustedJSON))
        XCTAssertTrue(exhausted.canPrepare(.hdSingle, processing: .server))
        XCTAssertFalse(exhausted.canPrepare(.hdSingle, processing: .onDevice))
        XCTAssertNotNil(exhausted.preparationBlockMessage(.hdSingle, processing: .onDevice))
        for raw in [usageJSON(), exhaustedJSON.replacingOccurrences(of: "\"device_remaining_seconds\":0", with: "\"device_remaining_seconds\":null")] {
            let unknown = PlanSnapshot(plan: policy, usage: try usage(raw))
            XCTAssertNil(unknown.availability(for: .hdSingle, processing: .onDevice))
            XCTAssertNil(unknown.preparationBlockMessage(.hdSingle, processing: .onDevice))
        }
        let fractional = PlanSnapshot(plan: policy, usage: try usage(exhaustedJSON.replacingOccurrences(of: "\"device_remaining_seconds\":0", with: "\"device_remaining_seconds\":1")))
        XCTAssertTrue(fractional.canPrepare(.hdSingle, processing: .onDevice))
        XCTAssertFalse(fractional.canPrepare(.fhdSingle, processing: .onDevice))
        XCTAssertThrowsError(try usage(exhaustedJSON.replacingOccurrences(of: "\"device_remaining_seconds\":0", with: "\"device_remaining_seconds\":-1")))
    }

    func testBillingUsesSessionProcessingInsteadOfLocalInferenceOverride() throws {
        let suite = "plan-processing-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let integration = YouTubeIntegration(preferencesStore: YouTubePreferencesStore(userDefaults: defaults), aiModeProvider: { .onDevice })
        XCTAssertEqual(integration.planProcessingMode, .onDevice)
        var session = try JSONDecoder().decode(YouTubeBroadcastSession.self, from: Data("""
        {"session_id":"test", "owner_token":"test", "ai_processing":"server", "stream":{"status":"idle","publisher_active":false,"reconnect_attempts":0}}
        """.utf8))
        session.clientProcessingMode = .onDevice
        integration.seedPreparedVideoSessionForTesting(
            connection: YouTubeConnection(provider: "youtube", channel: YouTubeChannel(id: "test", title: "Test")),
            session: session, videoTrack: YouTubeVideoTrackState(id: "test", kind: "video", readyState: "live"))
        XCTAssertEqual(integration.planProcessingMode, .server)
        session.aiProcessing = "on_device"
        integration.seedPreparedVideoSessionForTesting(
            connection: YouTubeConnection(provider: "youtube", channel: YouTubeChannel(id: "test", title: "Test")),
            session: session, videoTrack: YouTubeVideoTrackState(id: "test", kind: "video", readyState: "live"))
        XCTAssertEqual(integration.planProcessingMode, .onDevice)
    }

    func testPlanErrorsAndNoticesHaveActionableMessages() {
        for code in ["plan_server_streaming_not_allowed", "plan_resolution_not_allowed", "plan_simulcast_not_allowed", "monthly_limit_exhausted", "broadcast_limit_reached"] {
            XCTAssertNotEqual(YouTubeAPIError.api(code: code, fallback: "", helpURL: nil).userMessage,
                              String(localized: "YouTube 요청을 처리하지 못했습니다."))
        }
        for code in ["monthly_usage_80", "monthly_usage_100", "monthly_limit_reached", "broadcast_limit_30m", "broadcast_limit_10m", "broadcast_limit_reached", "no_input_stopped"] {
            XCTAssertNotNil(BroadcastNotice(code: code, at: "test").planMessage)
        }
        XCTAssertNil(BroadcastNotice(code: "future", at: "test").planMessage)
    }

    func testRenderDeviceAndUnsupportedServerUsage() async throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive })
        let previousWindow = scene.windows.first { $0.isKeyWindow }
        for snapshot in try processingSnapshots() {
            for processing in AIProcessingMode.allCases {
                let window = UIWindow(windowScene: scene)
                window.frame = CGRect(x: 0, y: 0, width: 360, height: 800)
                window.overrideUserInterfaceStyle = .light
                window.rootViewController = UIHostingController(rootView: ScrollView {
                    PlanUsageContent(snapshot: snapshot, isLoading: false, errorMessage: nil,
                        lastUpdatedAt: nil, currentMode: .hdSingle, currentProcessing: processing, onRefresh: {})
                        .padding(16)
                })
                window.makeKeyAndVisible()
                try await Task.sleep(for: .milliseconds(200))
                window.layoutIfNeeded()
                let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
                    XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
                }
                let attachment = XCTAttachment(image: image)
                attachment.name = "bm-\(snapshot.plan.plan)-\(processing.rawValue)"
                attachment.lifetime = .keepAlways
                add(attachment)
                window.isHidden = true
                window.rootViewController = nil
                previousWindow?.makeKeyAndVisible()
            }
        }
    }

    func testServerValuesOverrideReferenceMultiplier() throws {
        let snapshot = PlanSnapshot(plan: try plan(), usage: try usage(usageJSON()))
        XCTAssertTrue(snapshot.canPrepare(.hdSingle))
        XCTAssertEqual(snapshot.availability(for: .hdSingle)?.multiplier, 7)
        XCTAssertEqual(snapshot.availability(for: .hdSingle)?.seconds, 3600)
    }
    func testDisallowedAndAbsentModesStayLocked() throws {
        let snapshot = PlanSnapshot(plan: try plan(), usage: try usage(usageJSON(allowed: false)))
        XCTAssertFalse(snapshot.isAllowed(.hdSingle))
        XCTAssertFalse(snapshot.canPrepare(.hdSingle))
        XCTAssertFalse(snapshot.isAllowed(.fhdMulti))
        XCTAssertFalse(snapshot.isAllowed(.fhdSingle))
    }
    func testNullUnlimitedAndZeroExhaustedAreDistinct() throws {
        let unlimited = PlanSnapshot(plan: try plan(), usage: try usage(usageJSON(seconds: "null")))
        let exhausted = PlanSnapshot(plan: try plan(), usage: try usage(usageJSON(seconds: "0")))
        XCTAssertTrue(unlimited.canPrepare(.hdSingle))
        XCTAssertFalse(exhausted.canPrepare(.hdSingle))
        XCTAssertEqual(PlanTimeText.duration(nil), String(localized: "무제한"))
        XCTAssertEqual(PlanTimeText.duration(0), String(localized: "소진"))
        XCTAssertEqual(PlanTimeText.remaining(.missing), String(localized: "조회 대기"))
    }
    func testMissingSecondsDoNotDecodeAsUnlimited() {
        XCTAssertThrowsError(try usage(usageJSON().replacingOccurrences(of: "\"seconds\":3600,", with: "")))
        XCTAssertThrowsError(try usage(usageJSON().replacingOccurrences(of: "\"remaining_seconds\":3600,", with: "")))
    }
    func testInvalidMultiplierAndNegativeSecondsFail() {
        XCTAssertThrowsError(try usage(usageJSON(multiplier: 0)))
        XCTAssertThrowsError(try usage(usageJSON(seconds: "-1")))
    }
    func testModeTracksServerResolutionAndTargets() {
        XCTAssertEqual(BroadcastPlanMode.current(resolution: "720p", targetCount: 1), .hdSingle)
        XCTAssertEqual(BroadcastPlanMode.current(resolution: "fhd", targetCount: 1), .fhdSingle)
        XCTAssertEqual(BroadcastPlanMode.current(resolution: "720p", targetCount: 2), .hdMulti)
        XCTAssertEqual(BroadcastPlanMode.current(resolution: "fhd", targetCount: 2), .fhdMulti)
    }
    func testRefreshFailureKeepsLastValueThenRetryRecovers() async throws {
        let api = PlanStub(plan: try plan(), usage: try usage(usageJSON()))
        let store = PlanStore()
        await store.refresh(api: api, accessToken: "test")
        let previous = store.snapshot
        let date = store.lastUpdatedAt
        api.fails = true
        await store.refresh(api: api, accessToken: "test")
        XCTAssertEqual(store.snapshot, previous)
        XCTAssertEqual(store.lastUpdatedAt, date)
        XCTAssertNotNil(store.errorMessage)
        XCTAssertFalse(store.isLoading)
        api.fails = false
        await store.refresh(api: api, accessToken: "test")
        XCTAssertNil(store.errorMessage)
    }
    func testFirstFailureDoesNotBecomeExhausted() async throws {
        let api = PlanStub(plan: try plan(), usage: try usage(usageJSON()))
        api.fails = true
        let store = PlanStore()
        await store.refresh(api: api, accessToken: "test")
        XCTAssertNil(store.snapshot)
        XCTAssertNil(store.lastUpdatedAt)
        XCTAssertNotNil(store.errorMessage)
    }
    func testResetRejectsLateResponseFromPreviousAccount() async throws {
        let api = PlanStub(plan: try plan(), usage: try usage(usageJSON()))
        let store = PlanStore()
        api.onUsage = { store.reset() }
        await store.refresh(api: api, accessToken: "test")
        XCTAssertNil(store.snapshot)
        XCTAssertNil(store.lastUpdatedAt)
        XCTAssertFalse(store.isLoading)
    }
    func testRenderPlanAndLastKnownFailureStates() async throws {
        let integration = YouTubeIntegration()
        let renderedUsage = try usage("""
        {"plan":"beam","used_seconds":3600,"remaining_seconds":7200,"available_by_mode":[
        {"mode":"720p_single","allowed":true,"seconds":7200,"multiplier":1},
        {"mode":"fhd_single","allowed":true,"seconds":3600,"multiplier":2},
        {"mode":"720p_multi","allowed":true,"seconds":3600,"multiplier":2},
        {"mode":"fhd_multi","allowed":false,"seconds":2400,"multiplier":3}]}
        """)
        let api = PlanStub(plan: try plan(), usage: renderedUsage)
        await integration.planStore.refresh(api: api, accessToken: "test")
        for stale in [false, true] {
            if stale {
                api.fails = true
                await integration.planStore.refresh(api: api, accessToken: "test")
            }
            let renderer = ImageRenderer(content:
                PlanUsageContent(snapshot: integration.planStore.snapshot, isLoading: false,
                                 errorMessage: integration.planStore.errorMessage,
                                 lastUpdatedAt: integration.planStore.lastUpdatedAt,
                                 currentMode: .hdSingle, onRefresh: {})
                    .frame(width: 360)
                    .padding(16)
                    .background(Color.white)
                    .environment(\.colorScheme, .light)
            )
            renderer.scale = 2
            let image = try XCTUnwrap(renderer.uiImage)
            XCTAssertGreaterThan(image.size.height, 300)
            XCTAssertGreaterThan(try XCTUnwrap(image.pngData()).count, 10_000)
            let attachment = XCTAttachment(image: image)
            attachment.name = stale ? "plan-stale" : "plan-usage"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
    }

    func testMismatchedPlanAndUsageDoesNotReplaceSnapshot() async throws {
        let api = PlanStub(plan: try plan(), usage: try usage(usageJSON().replacingOccurrences(of: "beam", with: "spark")))
        let store = PlanStore()
        await store.refresh(api: api, accessToken: "test")
        XCTAssertNil(store.snapshot)
        XCTAssertNotNil(store.errorMessage)
    }
}

@MainActor
private final class PlanStub: PlanAPIClient {
    let plan: UserPlan
    let usage: UserUsage
    var fails = false
    var onUsage: (() -> Void)?
    init(plan: UserPlan, usage: UserUsage) { self.plan = plan; self.usage = usage }
    func userPlan(accessToken: String) async throws -> UserPlan {
        if fails { throw URLError(.notConnectedToInternet) }
        return plan
    }
    func userUsage(accessToken: String) async throws -> UserUsage {
        onUsage?()
        return usage
    }
}
