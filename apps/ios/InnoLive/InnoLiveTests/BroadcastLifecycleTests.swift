import XCTest
@testable import InnoLive

@MainActor
final class BroadcastLifecycleTests: XCTestCase {
    func testCancelledCountdownCannotStartOrAcceptDelayedTicks() throws {
        let lifecycle = preparedLifecycle()
        let generation = try XCTUnwrap(lifecycle.beginCountdown())
        XCTAssertEqual(lifecycle.recording, .idle)
        lifecycle.advanceCountdown(generation: generation)
        lifecycle.cancelCountdown()
        lifecycle.advanceCountdown(generation: generation)
        XCTAssertFalse(lifecycle.beginStarting(generation: generation))
        XCTAssertEqual(lifecycle.phase, .prepared)
        XCTAssertEqual(lifecycle.recording, .idle)
    }

    func testOnlyCompletedCountdownArmsRecording() throws {
        let lifecycle = preparedLifecycle()
        let generation = try XCTUnwrap(lifecycle.beginCountdown())
        XCTAssertFalse(lifecycle.beginStarting(generation: generation))
        lifecycle.advanceCountdown(generation: generation)
        XCTAssertFalse(lifecycle.beginStarting(generation: generation))
        lifecycle.advanceCountdown(generation: generation)
        XCTAssertTrue(lifecycle.beginStarting(generation: generation))
        XCTAssertFalse(lifecycle.beginStarting(generation: generation))
        XCTAssertEqual(lifecycle.phase, .starting)
        XCTAssertEqual(lifecycle.recording, .armed)
        lifecycle.reconcileTargets([], generation: generation)
        XCTAssertEqual(lifecycle.phase, .starting)
        lifecycle.reconcileTargets(["youtube"], generation: generation)
        XCTAssertEqual(lifecycle.phase, .live)
    }

    func testTotalStartFailureDiscardsProvisionalRecordingAndRejectsLateCallbacks() throws {
        let lifecycle = try startingLifecycle()
        let generation = lifecycle.generation
        lifecycle.recordingStarted(generation: generation)
        lifecycle.startFailed(generation: generation, message: "start failed")
        lifecycle.reconcileTargets(["youtube"], generation: generation)
        lifecycle.recordingFailed("late failure", generation: generation)
        XCTAssertEqual(lifecycle.phase, .prepared)
        XCTAssertEqual(lifecycle.recording, .idle)
        XCTAssertNil(lifecycle.startedAt)
    }

    func testPartialStopKeepsRecordingUntilLastLiveOrPausedTargetEnds() throws {
        let lifecycle = try startingLifecycle()
        let generation = lifecycle.generation
        lifecycle.reconcileTargets(["youtube", "chzzk"], generation: generation)
        lifecycle.recordingStarted(generation: generation)
        XCTAssertTrue(lifecycle.beginEnding())
        lifecycle.finishEnding(remainingTargets: ["chzzk"], generation: generation, error: "chzzk stop failed")
        XCTAssertEqual(lifecycle.phase, .live)
        XCTAssertEqual(lifecycle.recording, .recording)
        XCTAssertNil(lifecycle.result)
        // A paused target remains in the caller's active set.
        lifecycle.reconcileTargets(["chzzk"], generation: generation)
        XCTAssertEqual(lifecycle.phase, .live)
        XCTAssertTrue(lifecycle.beginEnding())
        lifecycle.finishEnding(remainingTargets: [], generation: generation, at: Date(timeIntervalSince1970: 110))
        XCTAssertEqual(lifecycle.phase, .result)
        XCTAssertEqual(lifecycle.result?.duration, 10)
        XCTAssertEqual(lifecycle.recording, .finalizing)
        let file = URL(fileURLWithPath: "/recording.mp4")
        lifecycle.recordingSaved(file, generation: generation)
        XCTAssertEqual(lifecycle.recording, .saved(file))
    }

    func testRecordingFailureDoesNotStopLiveOrReportSaved() throws {
        let lifecycle = try startingLifecycle()
        let generation = lifecycle.generation
        lifecycle.reconcileTargets(["youtube"], generation: generation)
        lifecycle.recordingFailed("disk full", generation: generation)
        XCTAssertEqual(lifecycle.phase, .live)
        lifecycle.reconcileTargets([], generation: generation)
        XCTAssertEqual(lifecycle.phase, .result)
        lifecycle.recordingSaved(URL(fileURLWithPath: "/invalid.mp4"), generation: generation)
        XCTAssertEqual(lifecycle.recording, .failed("disk full"))
    }

    func testNewSessionRejectsOldPreparationAndFinalization() throws {
        let lifecycle = try startingLifecycle()
        let old = lifecycle.generation
        lifecycle.reconcileTargets(["youtube"], generation: old)
        lifecycle.reconcileTargets([], generation: old)
        let current = lifecycle.beginPreparation()
        lifecycle.recordingSaved(URL(fileURLWithPath: "/old.mp4"), generation: old)
        lifecycle.finishPreparation(generation: old)
        XCTAssertEqual(lifecycle.phase, .preparing)
        XCTAssertEqual(lifecycle.recording, .idle)
        XCTAssertNil(lifecycle.result)
        lifecycle.finishPreparation(generation: current, error: "offline")
        XCTAssertEqual(lifecycle.phase, .idle)
        XCTAssertEqual(lifecycle.errorMessage, "offline")
    }

    func testDismissedResultKeepsGenerationForWriterCompletion() throws {
        let lifecycle = try startingLifecycle()
        let generation = lifecycle.generation
        lifecycle.reconcileTargets(["youtube"], generation: generation)
        lifecycle.reconcileTargets([], generation: generation)
        XCTAssertTrue(lifecycle.isPresentingEnd)
        lifecycle.dismissResult()
        XCTAssertFalse(lifecycle.isPresentingEnd)
        XCTAssertEqual(lifecycle.generation, generation)
        let url = URL(fileURLWithPath: "/finished.mp4")
        lifecycle.recordingSaved(url, generation: generation)
        XCTAssertEqual(lifecycle.recording, .saved(url))
        XCTAssertFalse(lifecycle.isPresentingEnd)
        _ = lifecycle.beginPreparation()
        XCTAssertFalse(lifecycle.resultDismissed)
    }

    func testLocalTerminationRecordsUnconfirmedRemoteStopWithoutReset() throws {
        let lifecycle = try startingLifecycle()
        let generation = lifecycle.generation
        lifecycle.reconcileTargets(["youtube"], generation: generation)
        lifecycle.finishLocalSession(warning: "remote stop unconfirmed", at: Date(timeIntervalSince1970: 130))
        XCTAssertEqual(lifecycle.result?.duration, 30)
        XCTAssertEqual(lifecycle.result?.terminationWarning, "remote stop unconfirmed")
        XCTAssertEqual(lifecycle.recording, .finalizing)
        XCTAssertEqual(lifecycle.generation, generation)
    }

    private func preparedLifecycle() -> BroadcastLifecycle {
        let lifecycle = BroadcastLifecycle()
        let generation = lifecycle.beginPreparation()
        lifecycle.finishPreparation(generation: generation)
        return lifecycle
    }

    private func startingLifecycle() throws -> BroadcastLifecycle {
        let lifecycle = preparedLifecycle()
        let generation = try XCTUnwrap(lifecycle.beginCountdown())
        lifecycle.advanceCountdown(generation: generation)
        lifecycle.advanceCountdown(generation: generation)
        XCTAssertTrue(lifecycle.beginStarting(generation: generation, at: Date(timeIntervalSince1970: 100)))
        return lifecycle
    }
}
