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
