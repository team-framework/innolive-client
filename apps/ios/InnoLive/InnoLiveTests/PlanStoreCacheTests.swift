import Foundation
import XCTest
@testable import InnoLive

@MainActor
final class PlanStoreCacheTests: XCTestCase {
    private var directory: URL!

    override func setUp() {
        super.setUp()
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("plan-cache-\(UUID().uuidString)")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: directory)
        super.tearDown()
    }

    private var cacheURL: URL { directory.appendingPathComponent("PlanSnapshot.json") }

    private func token(for account: String) -> String {
        let payload = Data(#"{"sub":"\#(account)"}"#.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return "eyJhbGciOiJIUzI1NiJ9.\(payload).sig"
    }

    private func plan() throws -> UserPlan {
        try JSONDecoder().decode(UserPlan.self, from: Data("""
            {"plan":"beam","allowed_modes":["720p_single"],"monthly_broadcast_seconds":144000,"max_per_broadcast_seconds":43200,"monthly_device_seconds":432000,"device_modes":["720p_single"]}
            """.utf8))
    }

    private func usage(_ json: String = """
        {"plan":"beam","month":"2026-11","used_seconds":0,"remaining_seconds":144000,"limit_seconds":null,"available_by_mode":[{"mode":"720p_single","allowed":true,"seconds":3600,"multiplier":1}],"device_limit_seconds":432000,"device_used_seconds":100,"device_remaining_seconds":1000,"device_local_seconds":40,"broadcasts":[{"session_id":"s1","started_at":"2026-11-01T00:00:00Z","resolution":"720p","providers":["youtube"],"ai_processing":"on_device","broadcast_seconds":60,"charged_seconds":60}]}
        """) throws -> UserUsage {
        try JSONDecoder().decode(UserUsage.self, from: Data(json.utf8))
    }

    func testUsageRoundTripKeepsWhichKeysWerePresent() throws {
        let present = try usage()
        XCTAssertEqual(try JSONDecoder().decode(UserUsage.self, from: JSONEncoder().encode(present)), present)
        let absent = try usage(#"{"plan":"spark","used_seconds":0,"remaining_seconds":null,"available_by_mode":[]}"#)
        let decoded = try JSONDecoder().decode(UserUsage.self, from: JSONEncoder().encode(absent))
        XCTAssertEqual(decoded, absent)
        XCTAssertFalse(decoded.hasDeviceRemainingSeconds)
        XCTAssertFalse(decoded.hasLimitSeconds)
    }

    func testRelaunchShowsLastAnswerForSameAccountWithoutRequests() async throws {
        let online = PlanStore(cacheURL: cacheURL)
        await online.refresh(api: CountingPlanAPI(plan: try plan(), usage: try usage()), accessToken: token(for: "a"))
        let fetchedAt = try XCTUnwrap(online.lastUpdatedAt)

        let offline = CountingPlanAPI(plan: try plan(), usage: try usage(), fails: true)
        let relaunched = PlanStore(cacheURL: cacheURL)
        relaunched.restore(accountID: "a")
        XCTAssertEqual(relaunched.snapshot, online.snapshot)
        XCTAssertEqual(relaunched.lastUpdatedAt, fetchedAt)
        XCTAssertEqual(offline.requests, 0)
    }

    func testOtherAccountSeesNoCachedPlan() async throws {
        let online = PlanStore(cacheURL: cacheURL)
        await online.refresh(api: CountingPlanAPI(plan: try plan(), usage: try usage()), accessToken: token(for: "a"))
        let other = PlanStore(cacheURL: cacheURL)
        other.restore(accountID: "b")
        XCTAssertNil(other.snapshot)
        other.restore(accountID: nil)
        XCTAssertNil(other.snapshot)
    }

    func testOfflineRefreshKeepsCachedPlanWithoutError() async throws {
        let online = PlanStore(cacheURL: cacheURL)
        await online.refresh(api: CountingPlanAPI(plan: try plan(), usage: try usage()), accessToken: token(for: "a"))
        let relaunched = PlanStore(cacheURL: cacheURL)
        relaunched.restore(accountID: "a")
        await relaunched.refresh(api: CountingPlanAPI(plan: try plan(), usage: try usage(), fails: true), accessToken: token(for: "a"))
        XCTAssertNotNil(relaunched.snapshot)
        XCTAssertNil(relaunched.errorMessage)
        XCTAssertFalse(relaunched.isLoading)
    }

    func testOfflineRefreshWithoutCacheStillReportsError() async throws {
        let store = PlanStore(cacheURL: cacheURL)
        await store.refresh(api: CountingPlanAPI(plan: try plan(), usage: try usage(), fails: true), accessToken: token(for: "a"))
        XCTAssertNil(store.snapshot)
        XCTAssertNotNil(store.errorMessage)
    }

    func testEraseCacheForgetsPlan() async throws {
        let online = PlanStore(cacheURL: cacheURL)
        await online.refresh(api: CountingPlanAPI(plan: try plan(), usage: try usage()), accessToken: token(for: "a"))
        online.eraseCache()
        let relaunched = PlanStore(cacheURL: cacheURL)
        relaunched.restore(accountID: "a")
        XCTAssertNil(relaunched.snapshot)
    }
}

@MainActor
private final class CountingPlanAPI: PlanAPIClient {
    let plan: UserPlan
    let usage: UserUsage
    let fails: Bool
    private(set) var requests = 0
    init(plan: UserPlan, usage: UserUsage, fails: Bool = false) { self.plan = plan; self.usage = usage; self.fails = fails }
    func userPlan(accessToken: String) async throws -> UserPlan {
        requests += 1
        if fails { throw YouTubeAPIError.transport }
        return plan
    }
    func userUsage(accessToken: String) async throws -> UserUsage {
        requests += 1
        if fails { throw YouTubeAPIError.transport }
        return usage
    }
}
