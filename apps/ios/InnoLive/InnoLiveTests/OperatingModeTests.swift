import XCTest
@testable import InnoLive

final class OperatingModeTests: XCTestCase {
    private let start = ContinuousClock.now

    private func at(_ seconds: Double) -> ContinuousClock.Instant { start + .milliseconds(Int(seconds * 1_000)) }

    private func run(_ steps: [(Double, OperatingEvent)], from initial: OperatingState = OperatingState()) -> OperatingState {
        var state = initial
        for (time, event) in steps { state.apply(event, at: at(time)) }
        return state
    }

    func testTwoProbeFailuresReachLocal() {
        let state = run([(0, .probe(succeeded: false)), (2, .probe(succeeded: false))])
        XCTAssertEqual(state.mode, .local(.serverUnreachable))
    }

    func testOneFailureThenSuccessReturnsToServer() {
        let state = run([(0, .probe(succeeded: false)), (2, .probe(succeeded: true))])
        XCTAssertEqual(state.mode, .server)
    }

    func testPathLossReachesLocalAfterTwoSeconds() {
        XCTAssertEqual(run([(0, .path(satisfied: false)), (1.5, .tick)]).mode, .suspect(since: at(0), failures: 0))
        XCTAssertEqual(run([(0, .path(satisfied: false)), (2, .tick)]).mode, .local(.noPath))
    }

    func testRefreshUnavailableOnlyMakesSuspect() {
        XCTAssertEqual(run([(0, .refreshUnavailable)]).mode, .suspect(since: at(0), failures: 0))
    }

    func testRecoveryDuringCaptureOnlyShowsChip() {
        let state = run([
            (0, .probe(succeeded: false)), (2, .probe(succeeded: false)), (3, .capture(active: true)),
            (10, .probe(succeeded: true)), (25, .probe(succeeded: true)), (40, .probe(succeeded: true))
        ])
        XCTAssertEqual(state.mode, .local(.serverUnreachable))
        XCTAssertTrue(state.serverAvailable)
        XCTAssertEqual(run([(41, .capture(active: false))], from: state).mode, .server)
    }

    func testRecoveryNeedsThirtySeconds() {
        let state = run([
            (0, .probe(succeeded: false)), (2, .probe(succeeded: false)),
            (4, .probe(succeeded: true)), (8, .probe(succeeded: true)), (16, .probe(succeeded: true))
        ])
        XCTAssertEqual(state.mode, .local(.serverUnreachable))
        XCTAssertFalse(state.serverAvailable)
        XCTAssertTrue(state.canSwitchToServer)
        XCTAssertEqual(run([(17, .switchToServer)], from: state).mode, .server)
    }

    func testFailureResetsRecovery() {
        let state = run([
            (0, .probe(succeeded: false)), (2, .probe(succeeded: false)),
            (4, .probe(succeeded: true)), (8, .probe(succeeded: false)), (9, .switchToServer)
        ])
        XCTAssertEqual(state.mode, .local(.serverUnreachable))
        XCTAssertFalse(state.canSwitchToServer)
    }

    func testPathFlappingNeverEndsActiveLocalCapture() {
        var steps: [(Double, OperatingEvent)] = [(0, .path(satisfied: false)), (2, .tick), (2.5, .capture(active: true))]
        for index in 1...20 { steps.append((2.5 + Double(index) * 3, .path(satisfied: index % 2 == 0))) }
        XCTAssertEqual(run(steps).mode, .local(.noPath))
    }

    func testAlwaysLocalIsSticky() {
        let state = run([(0, .alwaysLocal(true)), (1, .probe(succeeded: true)), (40, .probe(succeeded: true)),
                         (80, .probe(succeeded: true)), (81, .switchToServer)])
        XCTAssertEqual(state.mode, .local(.userChoice))
        XCTAssertEqual(run([(82, .alwaysLocal(false))], from: state).mode, .server)
        XCTAssertEqual(OperatingState(alwaysLocal: true).mode, .local(.userChoice))
    }

    func testEachLocalReasonGivesADistinctBroadcastReason() {
        let reasons = [LocalReason.noPath, .serverUnreachable, .userChoice].map(\.broadcastUnavailableReason)
        XCTAssertEqual(Set(reasons).count, 3)
        XCTAssertFalse(reasons.contains(where: \.isEmpty))
    }

    @MainActor
    func testBroadcastTargetsStayAvailableWhileMonitorIsOff() throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "OperatingModeTests.\(UUID())"))
        defaults.set(true, forKey: OperatingModeMonitor.alwaysLocalKey)
        let monitor = OperatingModeMonitor(defaults: defaults)
        XCTAssertEqual(monitor.state.mode, .local(.userChoice))
        XCTAssertNil(monitor.broadcastUnavailableReason)
    }
}
