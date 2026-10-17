import AVFoundation
import XCTest
@testable import InnoLive

@MainActor
final class BroadcastRecordingCoordinatorTests: XCTestCase {
    func testConfirmedLastTargetDetachesAndSavesAfterResultWasDismissed() async throws {
        let fixture = Fixture()
        defer { fixture.removeFiles() }
        fixture.start()
        let recorder = try XCTUnwrap(fixture.sink.recorder)
        fixture.update(["youtube", "chzzk"])
        try await feed(recorder)
        fixture.update(["chzzk"])
        XCTAssertTrue(fixture.sink.recorder === recorder)
        XCTAssertEqual(fixture.lifecycle.phase, .live)
        fixture.update([])
        XCTAssertNil(fixture.sink.recorder)
        XCTAssertEqual(fixture.lifecycle.phase, .result)
        fixture.lifecycle.dismissResult()
        await fixture.coordinator.waitForPendingWork()
        let recording = try XCTUnwrap(fixture.store.recordings.first)
        XCTAssertEqual(fixture.store.recordings.count, 1)
        XCTAssertEqual(fixture.lifecycle.recording, .saved(fixture.store.fileURL(for: recording)))
        XCTAssertFalse(fixture.lifecycle.isPresentingEnd)
        XCTAssertTrue(FileManager.default.fileExists(atPath: fixture.store.fileURL(for: recording).path))
    }

    func testPendingStartAndAdditionalTargetsShareOneWriter() async throws {
        let fixture = Fixture()
        defer { fixture.removeFiles() }
        fixture.start()
        let recorder = try XCTUnwrap(fixture.sink.recorder)
        fixture.update([]) // prepared/going_live response before any live confirmation
        XCTAssertTrue(fixture.sink.recorder === recorder)
        XCTAssertEqual(fixture.lifecycle.phase, .starting)
        fixture.update(["youtube"])
        fixture.coordinator.arm(sessionID: "session")
        fixture.update(["youtube", "chzzk"])
        XCTAssertEqual(fixture.sink.attachments, 1)
        fixture.update([])
        await fixture.coordinator.waitForPendingWork()
        // No synthetic media is inserted to make a mocked session look saved.
        XCTAssertTrue(fixture.store.recordings.isEmpty)
        guard case .failed = fixture.lifecycle.recording else { return XCTFail("No-media writer must fail") }
        XCTAssertEqual(fixture.lifecycle.phase, .result)
    }

    func testFailedStartDiscardsProvisionalFileAndCanStartAgain() async throws {
        let fixture = Fixture()
        defer { fixture.removeFiles() }
        fixture.start()
        let recorder = try XCTUnwrap(fixture.sink.recorder)
        try await feed(recorder)
        let generation = fixture.lifecycle.generation
        fixture.coordinator.abortFailedStart(sessionID: "session")
        fixture.lifecycle.startFailed(generation: generation, message: "all targets rejected")
        XCTAssertNil(fixture.sink.recorder)
        await fixture.coordinator.waitForPendingWork()
        XCTAssertTrue(fixture.store.recordings.isEmpty)
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: fixture.directory.path).isEmpty)
        fixture.start()
        XCTAssertNotNil(fixture.sink.recorder)
        fixture.coordinator.abortFailedStart(sessionID: "session")
        await fixture.coordinator.waitForPendingWork()
    }

    func testCapacityFailureDoesNotPreventBroadcastOrShowSaved() async {
        let fixture = Fixture(capacity: 0)
        defer { fixture.removeFiles() }
        fixture.start()
        XCTAssertNil(fixture.sink.recorder)
        XCTAssertNotNil(fixture.coordinator.notice)
        fixture.update(["youtube"])
        XCTAssertEqual(fixture.lifecycle.phase, .live)
        guard case .failed = fixture.lifecycle.recording else { return XCTFail("Expected recording failure") }
        fixture.update([])
        XCTAssertEqual(fixture.lifecycle.phase, .result)
        XCTAssertTrue(fixture.store.recordings.isEmpty)
    }

    func testOldFinalizationDoesNotOverwriteNewBroadcastGeneration() async throws {
        let fixture = Fixture()
        defer { fixture.removeFiles() }
        fixture.start()
        try await feed(try XCTUnwrap(fixture.sink.recorder))
        fixture.update(["youtube"])
        fixture.update([])
        // Store releases active ownership synchronously before awaiting file I/O.
        fixture.start()
        let newRecorder = try XCTUnwrap(fixture.sink.recorder)
        let generation = fixture.lifecycle.generation
        await fixture.coordinator.waitForPendingWork()
        XCTAssertEqual(fixture.store.recordings.count, 1)
        XCTAssertEqual(fixture.lifecycle.generation, generation)
        XCTAssertEqual(fixture.lifecycle.recording, .recording)
        XCTAssertTrue(fixture.sink.recorder === newRecorder)
        fixture.coordinator.abortFailedStart(sessionID: "session")
        await fixture.coordinator.waitForPendingWork()
    }

    func testLocalTeardownSavesWithRemoteUncertaintyPreserved() async throws {
        let fixture = Fixture()
        defer { fixture.removeFiles() }
        fixture.start()
        fixture.update(["youtube"])
        try await feed(try XCTUnwrap(fixture.sink.recorder))
        fixture.lifecycle.finishLocalSession(warning: "remote end unconfirmed")
        fixture.coordinator.endLocally()
        await fixture.coordinator.waitForPendingWork()
        XCTAssertEqual(fixture.lifecycle.result?.terminationWarning, "remote end unconfirmed")
        XCTAssertEqual(fixture.lifecycle.phase, .result)
        XCTAssertEqual(fixture.store.recordings.count, 1)
        XCTAssertNil(fixture.sink.recorder)
    }

    func testResetDuringUnconfirmedStartDiscardsProvisionalMedia() async throws {
        let fixture = Fixture()
        defer { fixture.removeFiles() }
        fixture.start()
        try await feed(try XCTUnwrap(fixture.sink.recorder))
        XCTAssertEqual(fixture.lifecycle.phase, .starting)
        fixture.coordinator.reset()
        fixture.lifecycle.reset()
        XCTAssertNil(fixture.sink.recorder)
        await fixture.coordinator.waitForPendingWork()
        XCTAssertTrue(fixture.store.recordings.isEmpty)
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: fixture.directory.path).isEmpty)
        XCTAssertNil(fixture.coordinator.notice)
        XCTAssertEqual(fixture.lifecycle.recording, .idle)
    }

    func testResetClearsPreviousRecordingFailureNotice() async {
        let fixture = Fixture(capacity: 0)
        defer { fixture.removeFiles() }
        fixture.start()
        XCTAssertNotNil(fixture.coordinator.notice)
        fixture.coordinator.reset()
        fixture.lifecycle.reset()
        await fixture.coordinator.waitForPendingWork()
        XCTAssertNil(fixture.coordinator.notice)
        XCTAssertFalse(fixture.coordinator.canRetry)
        XCTAssertEqual(fixture.lifecycle.recording, .idle)
    }

    /// Deterministic media fixtures prove file/store coordination, not microphone hardware capture.
    private func feed(_ recorder: LocalBroadcastRecorder) async throws {
        let base = CMClockGetTime(CMClockGetHostTimeClock())
        let format = try XCTUnwrap(AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 2, interleaved: false))
        for index in 0..<30 {
            let time = CMTimeAdd(base, CMTime(value: Int64(index * 480), timescale: 48_000))
            if index % 2 == 0 {
                var pixel: CVPixelBuffer?
                CVPixelBufferCreate(kCFAllocatorDefault, 64, 48, kCVPixelFormatType_32BGRA,
                    [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &pixel)
                let buffer = try XCTUnwrap(pixel)
                CVPixelBufferLockBaseAddress(buffer, [])
                memset(CVPixelBufferGetBaseAddress(buffer), 100, CVPixelBufferGetDataSize(buffer))
                CVPixelBufferUnlockBaseAddress(buffer, [])
                recorder.appendVideo(pixelBuffer: buffer, rotation: 0,
                    timestampNs: CMTimeConvertScale(time, timescale: 1_000_000_000, method: .default).value)
            }
            let audio = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 480))
            audio.frameLength = 480
            for sample in 0..<480 {
                let value = Float(sin(Double(index * 480 + sample) * 2 * .pi * 440 / 48_000) * 0.25)
                audio.floatChannelData![0][sample] = value
                audio.floatChannelData![1][sample] = value
            }
            recorder.appendAudio(audio, when: AVAudioTime(hostTime: AVAudioTime.hostTime(forSeconds: CMTimeGetSeconds(time))))
            try await Task.sleep(for: .milliseconds(10))
        }
    }
}

@MainActor private final class Fixture {
    nonisolated deinit {}
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("recording-coordinator-\(UUID().uuidString)")
    let lifecycle = BroadcastLifecycle()
    let sink = Sink()
    let store: RecordingSessionStore
    let coordinator: BroadcastRecordingCoordinator

    init(capacity: Int64 = 1_000_000_000) {
        store = RecordingSessionStore(directory: directory, availableCapacity: { _ in capacity })
        coordinator = BroadcastRecordingCoordinator(store: store, lifecycle: lifecycle, attachSink: { [sink] in sink.attach($0) })
    }

    func start() {
        let preparation = lifecycle.beginPreparation()
        lifecycle.finishPreparation(generation: preparation)
        guard let generation = lifecycle.beginCountdown() else { return XCTFail("Expected prepared phase") }
        lifecycle.advanceCountdown(generation: generation)
        lifecycle.advanceCountdown(generation: generation)
        XCTAssertTrue(lifecycle.beginStarting(generation: generation))
        coordinator.arm(sessionID: "session")
    }

    func update(_ targets: Set<String>) {
        lifecycle.reconcileTargets(targets, generation: lifecycle.generation)
        coordinator.reconcile(targets: targets, sessionID: "session")
    }

    func removeFiles() { try? FileManager.default.removeItem(at: directory) }
}

@MainActor private final class Sink {
    nonisolated deinit {}
    var recorder: LocalBroadcastRecorder?
    var attachments = 0
    func attach(_ recorder: LocalBroadcastRecorder?) {
        if recorder != nil { attachments += 1 }
        self.recorder = recorder
    }
}
