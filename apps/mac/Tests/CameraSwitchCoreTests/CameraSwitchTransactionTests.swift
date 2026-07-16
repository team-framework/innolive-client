import XCTest
@testable import CameraSwitchCore

final class CameraSwitchTransactionTests: XCTestCase {
    func testRunningSessionStopsBeforeReplacementAndRestartsAfterward() {
        var events: [String] = []
        var activeCameraID = "built-in"
        var isRunning = true

        let result = CameraSwitchTransaction.execute(
            wasRunning: true,
            stop: {
                events.append("stop")
                isRunning = false
            },
            resetBufferedFrames: {
                events.append("reset")
            },
            replaceInput: {
                events.append("replace")
                activeCameraID = "external"
            },
            start: {
                events.append("start")
                isRunning = true
            },
            activeCameraID: { activeCameraID },
            isRunning: { isRunning }
        )

        XCTAssertEqual(events, ["stop", "reset", "replace", "start"])
        XCTAssertEqual(
            result,
            CameraSwitchTransactionResult(activeCameraID: "external", isRunning: true)
        )
    }

    func testStoppedSessionReplacesInputWithoutStartingSession() {
        var events: [String] = []
        var activeCameraID = "built-in"

        let result = CameraSwitchTransaction.execute(
            wasRunning: false,
            stop: {
                XCTFail("A stopped session must not be stopped again.")
            },
            resetBufferedFrames: {
                events.append("reset")
            },
            replaceInput: {
                events.append("replace")
                activeCameraID = "external"
            },
            start: {
                XCTFail("A stopped session must remain stopped.")
            },
            activeCameraID: { activeCameraID },
            isRunning: { false }
        )

        XCTAssertEqual(events, ["reset", "replace"])
        XCTAssertEqual(
            result,
            CameraSwitchTransactionResult(activeCameraID: "external", isRunning: false)
        )
    }

    func testResultReportsRestartFailure() {
        let result = CameraSwitchTransaction.execute(
            wasRunning: true,
            stop: {},
            resetBufferedFrames: {},
            replaceInput: {},
            start: {},
            activeCameraID: { "external" },
            isRunning: { false }
        )

        XCTAssertEqual(
            result,
            CameraSwitchTransactionResult(activeCameraID: "external", isRunning: false)
        )
    }
}
