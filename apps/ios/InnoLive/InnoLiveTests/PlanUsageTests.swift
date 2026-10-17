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

    func testMaximumUsesServerSecondsAndPerBroadcastCap() throws {
        let capped = PlanSnapshot(plan: try plan(), usage: try usage(usageJSON(seconds: "80000")))
        XCTAssertEqual(capped.maximumBroadcastAvailability(for: .hdSingle, processing: .server)?.seconds, 28800)
        let shorter = PlanSnapshot(plan: try plan(), usage: try usage(usageJSON(seconds: "123")))
        XCTAssertEqual(shorter.maximumBroadcastAvailability(for: .hdSingle, processing: .server)?.seconds, 123)
        let apiCap = usageJSON(seconds: "80000").replacingOccurrences(of: "\"used_seconds\":12", with: "\"used_seconds\":12,\"max_per_broadcast_seconds\":600")
        XCTAssertEqual(PlanSnapshot(plan: try plan(), usage: try usage(apiCap)).maximumBroadcastAvailability(for: .hdSingle, processing: .server)?.seconds, 600)
    }

    func testDeviceNullMeansUnlimitedButAbsentStaysUnknown() throws {
        var policy = try plan()
        policy.deviceModes = ["720p_single"]
        let raw = usageJSON().replacingOccurrences(of: "\"used_seconds\":12", with: "\"used_seconds\":12,\"device_limit_seconds\":null,\"device_remaining_seconds\":null")
        let snapshot = PlanSnapshot(plan: policy, usage: try usage(raw))
        XCTAssertTrue(snapshot.usage.hasDeviceLimitSeconds)
        XCTAssertTrue(snapshot.canPrepare(.hdSingle, processing: .onDevice))
        XCTAssertNil(snapshot.availability(for: .hdSingle, processing: .onDevice)?.seconds)
        XCTAssertEqual(snapshot.maximumBroadcastAvailability(for: .hdSingle, processing: .onDevice)?.seconds, 28800)
        XCTAssertNil(PlanSnapshot(plan: policy, usage: try usage(usageJSON())).maximumBroadcastAvailability(for: .hdSingle, processing: .onDevice))
    }

    func testBroadcastHistoryDecodesBothProcessingLocations() throws {
        let raw = usageJSON().replacingOccurrences(of: "\"used_seconds\":12", with: """
        "used_seconds":12,"month":"2026-10","broadcasts":[
        {"session_id":"one","started_at":"2026-10-15T01:00:00Z","resolution":"fhd","providers":["youtube"],"ai_processing":"server","broadcast_seconds":3600,"charged_seconds":7200},
        {"session_id":"two","started_at":"2026-10-15T03:00:00Z","resolution":"720p","providers":["chzzk","youtube"],"ai_processing":"on_device","broadcast_seconds":10800,"charged_seconds":21600}]
        """)
        let result = try usage(raw)
        XCTAssertEqual(result.month, "2026-10")
        XCTAssertEqual(result.broadcasts.map(\.aiProcessing), ["server", "on_device"])
        XCTAssertEqual(result.broadcasts.map(\.chargedSeconds), [7200, 21600])
        XCTAssertNotNil(result.broadcasts.first?.startDate)
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
        for raw in [usageJSON()] {
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
        {"mode":"fhd_multi","allowed":false,"seconds":2400,"multiplier":3}],
        "month":"2026-10","broadcasts":[
        {"session_id":"server-history","started_at":"2026-10-15T01:00:00Z","resolution":"fhd","providers":["youtube"],"ai_processing":"server","broadcast_seconds":3600,"charged_seconds":7200},
        {"session_id":"device-history","started_at":"2026-10-15T03:00:00Z","resolution":"720p","providers":["chzzk","youtube"],"ai_processing":"on_device","broadcast_seconds":10800,"charged_seconds":21600}]}
        """)
        let api = PlanStub(plan: try plan(), usage: renderedUsage)
        await integration.planStore.refresh(api: api, accessToken: "test")
        for stale in [false, true] {
            if stale {
                api.fails = true
                await integration.planStore.refresh(api: api, accessToken: "test")
            }
            let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
                .first { $0.activationState == .foregroundActive })
            let previousWindow = scene.windows.first { $0.isKeyWindow }
            let window = UIWindow(windowScene: scene)
            window.frame = CGRect(x: 0, y: 0, width: 360, height: 1400)
            window.overrideUserInterfaceStyle = .light
            window.rootViewController = UIHostingController(rootView: ScrollView {
                PlanUsageContent(snapshot: integration.planStore.snapshot, isLoading: false,
                                 errorMessage: integration.planStore.errorMessage,
                                 lastUpdatedAt: integration.planStore.lastUpdatedAt,
                                 currentMode: .hdSingle, onRefresh: {})
                    .padding(16)
            })
            window.makeKeyAndVisible()
            try await Task.sleep(for: .milliseconds(200))
            window.layoutIfNeeded()
            let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
                XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
            }
            XCTAssertGreaterThan(try XCTUnwrap(image.pngData()).count, 10_000)
            window.isHidden = true
            window.rootViewController = nil
            previousWindow?.makeKeyAndVisible()
            let attachment = XCTAttachment(image: image)
            attachment.name = stale ? "plan-stale" : "plan-usage"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
    }

    func testBroadcastHistoryStartsCollapsedAndExpandsOnDemand() throws {
        let snapshot = PlanSnapshot(plan: try plan(), usage: try usage("""
        {"plan":"beam","used_seconds":3600,"remaining_seconds":7200,"available_by_mode":[],
        "month":"2026-10","broadcasts":[
        {"session_id":"a","started_at":"2026-10-15T01:00:00Z","resolution":"fhd","providers":["youtube"],"ai_processing":"server","broadcast_seconds":3600,"charged_seconds":7200},
        {"session_id":"b","started_at":"2026-10-15T03:00:00Z","resolution":"720p","providers":["chzzk"],"ai_processing":"on_device","broadcast_seconds":10800,"charged_seconds":21600}]}
        """))
        func height(expanded: Bool?) -> CGFloat {
            let content = expanded.map {
                PlanUsageContent(snapshot: snapshot, isLoading: false, errorMessage: nil, lastUpdatedAt: nil,
                                 currentMode: .hdSingle, showsBroadcastHistory: $0, onRefresh: {})
            } ?? PlanUsageContent(snapshot: snapshot, isLoading: false, errorMessage: nil, lastUpdatedAt: nil,
                                  currentMode: .hdSingle, onRefresh: {})
            return UIHostingController(rootView: content)
                .sizeThatFits(in: CGSize(width: 360, height: CGFloat.greatestFiniteMagnitude)).height
        }
        let collapsed = height(expanded: false)
        XCTAssertEqual(height(expanded: nil), collapsed)
        XCTAssertGreaterThan(height(expanded: true), collapsed + 100)
        for expanded in [false, true] {
            let controller = UIHostingController(rootView: PlanUsageContent(snapshot: snapshot, isLoading: false,
                errorMessage: nil, lastUpdatedAt: nil, currentMode: .hdSingle, showsBroadcastHistory: expanded, onRefresh: {}))
            controller.overrideUserInterfaceStyle = .light
            controller.view.frame = CGRect(origin: .zero, size: CGSize(width: 360, height: height(expanded: expanded)))
            controller.view.layoutIfNeeded()
            let attachment = XCTAttachment(image: UIGraphicsImageRenderer(bounds: controller.view.bounds).image { _ in
                controller.view.drawHierarchy(in: controller.view.bounds, afterScreenUpdates: true)
            })
            attachment.name = expanded ? "history-expanded" : "history-collapsed"
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
