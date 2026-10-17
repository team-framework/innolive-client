import AVFoundation
import XCTest
@testable import InnoLive

@MainActor final class RecordingResilienceTests: XCTestCase {
    func testCompletedConfirmedPartialRecoveredOnlyAfterFullValidation() async throws {
        let directory = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let id = try await completedPartial(directory, status: .confirmed)
        let store = makeStore(directory)
        await store.reloadLibrary()
        XCTAssertEqual(store.recordings.map(\.id), [id])
        XCTAssertEqual(store.recordings.first?.sessionID, "recovered-session")
        XCTAssertTrue(store.preservedRecordings.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.appendingPathComponent("\(id).partial.mp4").path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.appendingPathComponent("\(id).pending.json").path))
    }

    func testCorruptUnknownAndProvisionalPartialsPreserved() async throws {
        let directory = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let unknown = try await completedPartial(directory, status: nil)
        let provisional = try await completedPartial(directory, status: .provisional)
        let corrupt = UUID()
        try Data("broken MP4".utf8).write(to: directory.appendingPathComponent("\(corrupt).partial.mp4"))
        try writeJournal(corrupt, directory: directory, status: .confirmed)
        let originals = try [unknown, provisional, corrupt].map { try Data(contentsOf: directory.appendingPathComponent("\($0).partial.mp4")) }
        let store = makeStore(directory)
        await store.reloadLibrary()
        XCTAssertTrue(store.recordings.isEmpty)
        XCTAssertEqual(Set(store.preservedRecordings.map(\.id)), Set([unknown, provisional, corrupt]))
        XCTAssertNotNil(store.libraryError)
        for (index, id) in [unknown, provisional, corrupt].enumerated() {
            XCTAssertEqual(try Data(contentsOf: directory.appendingPathComponent("\(id).partial.mp4")), originals[index])
        }
    }

    func testAnotherStoreDoesNotRecoverOrListActiveWriter() async throws {
        let directory = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let active = makeStore(directory)
        let recorder = try active.begin(sessionID: "active")
        try active.targetDidStart("target", sessionID: "active")
        try await feed(recorder)
        let other = makeStore(directory)
        await other.reloadLibrary()
        XCTAssertTrue(other.recordings.isEmpty)
        XCTAssertTrue(other.preservedRecordings.isEmpty)
        XCTAssertNil(other.libraryError)
        _ = try await active.targetDidEnd("target", sessionID: "active")
    }

    func testDirectoryAliasDoesNotExposeActiveWriterToRecovery() async throws {
        let directory = try temporaryDirectory()
        let alias = directory.deletingLastPathComponent().appendingPathComponent("recording-alias-\(UUID().uuidString)")
        defer {
            try? FileManager.default.removeItem(at: alias)
            try? FileManager.default.removeItem(at: directory)
        }
        try FileManager.default.createSymbolicLink(at: alias, withDestinationURL: directory)
        let active = makeStore(alias)
        let recorder = try active.begin(sessionID: "alias-active")
        try active.targetDidStart("target", sessionID: "alias-active")
        try await feed(recorder)
        let other = makeStore(directory.resolvingSymlinksInPath())
        await other.reloadLibrary()
        XCTAssertTrue(other.recordings.isEmpty)
        XCTAssertTrue(other.preservedRecordings.isEmpty)
        XCTAssertNil(other.libraryError)
        _ = try await active.targetDidEnd("target", sessionID: "alias-active")
        await other.reloadLibrary()
        XCTAssertEqual(other.recordings.count, 1, "Ownership must also be released through the same canonical key")
        XCTAssertTrue(other.preservedRecordings.isEmpty)
    }

    func testExpiryDisablesRetryUntilRealCompletionThenAllowsVerifiedSuccess() async throws {
        let directory = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        var leases: [FakeLease] = []
        let store = RecordingSessionStore(directory: directory, availableCapacity: { _ in 1_000_000_000 }, backgroundLeaseFactory: { _, callback in
            let lease = FakeLease(callback); leases.append(lease); return lease
        })
        let recorder = try store.begin(sessionID: "expiry")
        try await feed(recorder)
        let task = try XCTUnwrap(store.beginFinalization(sessionID: "expiry"))
        let lease = try XCTUnwrap(leases.first)
        lease.expire()
        guard case let .failed(_, canRetry) = store.state else { return XCTFail("Expected expiry state") }
        XCTAssertFalse(canRetry)
        do { _ = try await store.retryFinalization(); XCTFail("Writer still owns finalization") } catch {}
        let saved = try await task.value
        XCTAssertEqual(store.state, .saved(saved))
        XCTAssertTrue(lease.ended)
        XCTAssertEqual(store.recordings, [saved])
    }

    func testOldExpiryCannotChangeNewSessionState() async throws {
        let directory = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        var leases: [FakeLease] = []
        let store = RecordingSessionStore(directory: directory, availableCapacity: { _ in 1_000_000_000 }, backgroundLeaseFactory: { _, callback in
            let lease = FakeLease(callback); leases.append(lease); return lease
        })
        let first = try store.begin(sessionID: "old")
        try await feed(first)
        let task = try XCTUnwrap(store.beginFinalization(sessionID: "old"))
        _ = try store.begin(sessionID: "new")
        leases[0].expire()
        XCTAssertEqual(store.state, .recording)
        _ = try await task.value
        XCTAssertEqual(store.state, .recording)
        try await store.abortFailedStart(sessionID: "new")
    }

    func testRetryAcquiresAndEndsSeparateBackgroundLease() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        var leases: [FakeLease] = []
        let store = RecordingSessionStore(directory: directory, availableCapacity: { _ in 1_000_000_000 }, backgroundLeaseFactory: { _, callback in
            let lease = FakeLease(callback); leases.append(lease); return lease
        })
        let recorder = try store.begin(sessionID: "retry")
        try await feed(recorder)
        let partial = try XCTUnwrap(FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil).first { $0.lastPathComponent.hasSuffix(".partial.mp4") })
        let stem = partial.lastPathComponent.replacingOccurrences(of: ".partial.mp4", with: "")
        let blockedMetadata = directory.appendingPathComponent("\(stem).json")
        try FileManager.default.createDirectory(at: blockedMetadata, withIntermediateDirectories: true)
        do { _ = try await store.finish(sessionID: "retry"); XCTFail("Expected metadata write failure") } catch {}
        guard case let .failed(_, canRetry) = store.state else { return XCTFail("Missing failed state") }
        XCTAssertTrue(canRetry)
        XCTAssertEqual(leases.count, 1)
        XCTAssertTrue(leases[0].ended)
        let finalURL = directory.appendingPathComponent("\(stem).mp4")
        let bytesBefore = try Data(contentsOf: finalURL)
        try FileManager.default.removeItem(at: blockedMetadata)
        let saved = try await store.retryFinalization()
        XCTAssertEqual(try Data(contentsOf: finalURL), bytesBefore)
        XCTAssertEqual(store.state, .saved(saved))
        XCTAssertEqual(store.recordings, [saved])
        XCTAssertEqual(leases.count, 2)
        XCTAssertTrue(leases[1].ended)
    }

    func testCoordinatorShowsExpiryThenVerifiedLateSuccess() async throws {
        let directory = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        var leases: [FakeLease] = []
        let store = RecordingSessionStore(directory: directory, availableCapacity: { _ in 1_000_000_000 }, backgroundLeaseFactory: { _, callback in
            let lease = FakeLease(callback); leases.append(lease); return lease
        })
        let lifecycle = BroadcastLifecycle()
        var recorder: LocalBroadcastRecorder?
        let coordinator = BroadcastRecordingCoordinator(store: store, lifecycle: lifecycle) { if let sink = $0 { recorder = sink } }
        start(lifecycle, coordinator: coordinator, session: "expiry-ui")
        try await feed(try XCTUnwrap(recorder))
        lifecycle.reconcileTargets([], generation: lifecycle.generation)
        coordinator.reconcile(targets: [], sessionID: "expiry-ui")
        leases[0].expire()
        guard case .failed = lifecycle.recording else { return XCTFail("Overlay must receive expiry failure") }
        XCTAssertNotNil(coordinator.notice)
        XCTAssertFalse(coordinator.canRetry)
        await coordinator.waitForPendingWork()
        let saved = try XCTUnwrap(store.recordings.first)
        XCTAssertEqual(lifecycle.recording, .saved(store.fileURL(for: saved)))
        XCTAssertNil(coordinator.notice)
        XCTAssertFalse(coordinator.canRetry)
    }

    func testCoordinatorIgnoresOldExpiryAndCompletionAfterNewGeneration() async throws {
        let directory = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        var leases: [FakeLease] = []
        let store = RecordingSessionStore(directory: directory, availableCapacity: { _ in 1_000_000_000 }, backgroundLeaseFactory: { _, callback in
            let lease = FakeLease(callback); leases.append(lease); return lease
        })
        let lifecycle = BroadcastLifecycle()
        var recorder: LocalBroadcastRecorder?
        let coordinator = BroadcastRecordingCoordinator(store: store, lifecycle: lifecycle) { if let sink = $0 { recorder = sink } }
        start(lifecycle, coordinator: coordinator, session: "old-ui")
        try await feed(try XCTUnwrap(recorder))
        lifecycle.reconcileTargets([], generation: lifecycle.generation)
        coordinator.reconcile(targets: [], sessionID: "old-ui")
        // Change presentation generation before the new writer starts. The store's old
        // generation can still publish expiry, but the coordinator must reject it.
        let preparation = lifecycle.beginPreparation()
        leases[0].expire()
        XCTAssertEqual(lifecycle.recording, .idle)
        XCTAssertNil(coordinator.notice)
        lifecycle.finishPreparation(generation: preparation)
        let generation = try XCTUnwrap(lifecycle.beginCountdown())
        lifecycle.advanceCountdown(generation: generation)
        lifecycle.advanceCountdown(generation: generation)
        XCTAssertTrue(lifecycle.beginStarting(generation: generation))
        coordinator.arm(sessionID: "new-ui")
        await coordinator.waitForPendingWork()
        XCTAssertEqual(lifecycle.recording, .recording)
        XCTAssertNil(coordinator.notice)
        XCTAssertEqual(store.recordings.count, 1)
        coordinator.abortFailedStart(sessionID: "new-ui")
        await coordinator.waitForPendingWork()
    }

    private func start(_ lifecycle: BroadcastLifecycle, coordinator: BroadcastRecordingCoordinator, session: String) {
        let preparation = lifecycle.beginPreparation()
        lifecycle.finishPreparation(generation: preparation)
        guard let generation = lifecycle.beginCountdown() else { return XCTFail("Expected prepared state") }
        lifecycle.advanceCountdown(generation: generation)
        lifecycle.advanceCountdown(generation: generation)
        XCTAssertTrue(lifecycle.beginStarting(generation: generation))
        coordinator.arm(sessionID: session)
        lifecycle.reconcileTargets(["target"], generation: generation)
        coordinator.reconcile(targets: ["target"], sessionID: session)
    }

    private func temporaryDirectory() throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
    private func makeStore(_ directory: URL) -> RecordingSessionStore {
        RecordingSessionStore(directory: directory, availableCapacity: { _ in 1_000_000_000 })
    }
    private func writeJournal(_ id: UUID, directory: URL, status: RecordingRecoveryJournal.Status) throws {
        let journal = RecordingRecoveryJournal(id: id, sessionID: "recovered-session", createdAt: Date(), status: status)
        try JSONEncoder().encode(journal).write(to: directory.appendingPathComponent("\(id).pending.json"))
    }
    private func completedPartial(_ directory: URL, status: RecordingRecoveryJournal.Status?) async throws -> UUID {
        let id = UUID()
        let recorder = LocalBroadcastRecorder()
        try recorder.start(url: directory.appendingPathComponent("\(id).partial.mp4"))
        try await feed(recorder)
        _ = try await recorder.stop()
        if let status { try writeJournal(id, directory: directory, status: status) }
        return id
    }
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

@MainActor private final class FakeLease: RecordingBackgroundLease {
    nonisolated deinit {}
    var isExpired = false
    var ended = false
    let callback: @MainActor () -> Void
    init(_ callback: @escaping @MainActor () -> Void) { self.callback = callback }
    func expire() { isExpired = true; end(); callback() }
    func end() { ended = true }
}
