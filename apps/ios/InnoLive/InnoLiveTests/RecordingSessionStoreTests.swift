import AVFoundation
import XCTest
@testable import InnoLive

@MainActor final class RecordingSessionStoreTests: XCTestCase {
    func testRealFinalizeLastTargetAtomicMoveAndLibraryReload() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let recorder = try store.begin(sessionID: "session-one")
        try store.targetDidStart("youtube", sessionID: "session-one")
        try store.targetDidStart("chzzk", sessionID: "session-one")
        try await feed(recorder)
        let firstResult = try await store.targetDidEnd("youtube", sessionID: "session-one")
        XCTAssertNil(firstResult)
        XCTAssertEqual(store.state, .recording)
        let metadata = try await store.targetDidEnd("chzzk", sessionID: "session-one")
        let saved = try XCTUnwrap(metadata)
        XCTAssertEqual(store.state, .saved(saved))
        XCTAssertGreaterThan(saved.bytes, 0)
        XCTAssertTrue(FileManager.default.fileExists(atPath: store.fileURL(for: saved).path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.appendingPathComponent("\(saved.id.uuidString).partial.mp4").path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: directory.appendingPathComponent("\(saved.id.uuidString).json").path))
        let reloaded = makeStore(directory)
        await reloaded.reloadLibrary()
        XCTAssertEqual(reloaded.recordings, [saved])
        XCTAssertNil(reloaded.libraryError)
        // A failed subsequent start only removes its own partial file.
        let next = try store.begin(sessionID: "session-two")
        try await feed(next)
        try await store.abortFailedStart(sessionID: "session-two")
        XCTAssertTrue(FileManager.default.fileExists(atPath: store.fileURL(for: saved).path))
        XCTAssertEqual(store.recordings, [saved])
    }

    func testMissingMetadataAfterRenameRecoveredAndCorruptFileExcluded() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let recorder = try store.begin(sessionID: "recover")
        try await feed(recorder)
        let finished = try await store.finish(sessionID: "recover")
        let saved = try XCTUnwrap(finished)
        try FileManager.default.removeItem(at: directory.appendingPathComponent("\(saved.id.uuidString).json"))
        let corrupt = directory.appendingPathComponent("\(UUID().uuidString).mp4")
        try Data("invalid mp4".utf8).write(to: corrupt)
        let reload = makeStore(directory)
        await reload.reloadLibrary()
        XCTAssertEqual(reload.recordings.map(\.id), [saved.id])
        XCTAssertNotNil(reload.libraryError)
        XCTAssertTrue(FileManager.default.fileExists(atPath: directory.appendingPathComponent("\(saved.id.uuidString).json").path))
    }

    func testRetryMetadataCommitUsesExistingValidatedFile() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let recorder = try store.begin(sessionID: "retry")
        try await feed(recorder)
        let journal = try XCTUnwrap(FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil).first { $0.lastPathComponent.hasSuffix(".pending.json") })
        let stem = journal.lastPathComponent.replacingOccurrences(of: ".pending.json", with: "")
        let blockedMetadata = directory.appendingPathComponent("\(stem).json")
        try FileManager.default.createDirectory(at: blockedMetadata, withIntermediateDirectories: true)
        do { _ = try await store.finish(sessionID: "retry"); XCTFail("Expected metadata write failure") } catch {}
        guard case let .failed(_, canRetry) = store.state else { return XCTFail("Missing failed state") }
        XCTAssertTrue(canRetry)
        let finalURL = directory.appendingPathComponent("\(stem).mp4")
        let bytesBefore = try Data(contentsOf: finalURL)
        try FileManager.default.removeItem(at: blockedMetadata)
        let saved = try await store.retryFinalization()
        XCTAssertEqual(try Data(contentsOf: finalURL), bytesBefore)
        XCTAssertEqual(store.state, .saved(saved))
        XCTAssertEqual(store.recordings, [saved])
    }

    func testCapacityFailureDoesNotBeginRecorder() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = RecordingSessionStore(directory: directory, availableCapacity: { _ in 0 })
        XCTAssertThrowsError(try store.begin(sessionID: "no-space"))
        XCTAssertEqual(store.state, .idle)
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: directory.path).isEmpty)
    }

    func testWriterFailureDoesNotAdvertiseRetryOrSavedFile() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        _ = try store.begin(sessionID: "empty")
        do { _ = try await store.finish(sessionID: "empty"); XCTFail("Expected no-video error") } catch {}
        guard case let .failed(_, canRetry) = store.state else { return XCTFail("Missing failed state") }
        XCTAssertFalse(canRetry)
        XCTAssertTrue(store.recordings.isEmpty)
    }

    func testDeleteReleasesVideoAndMetadataAndSurvivesReload() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let recorder = try store.begin(sessionID: "delete")
        try await feed(recorder)
        let result = try await store.finish(sessionID: "delete")
        let saved = try XCTUnwrap(result)
        let exportedCopy = directory.appendingPathComponent("exported-copy.mp4")
        try FileManager.default.copyItem(at: store.fileURL(for: saved), to: exportedCopy)
        try store.delete(saved)
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.fileURL(for: saved).path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.appendingPathComponent("\(saved.id).json").path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: exportedCopy.path))
        XCTAssertEqual(store.state, .idle)
        XCTAssertTrue(store.recordings.isEmpty)
        let reloaded = makeStore(directory)
        await reloaded.reloadLibrary()
        XCTAssertTrue(reloaded.recordings.isEmpty)
    }

    func testDeleteDuringReloadDoesNotRestoreEntry() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let recorder = try store.begin(sessionID: "delete-reload")
        try await feed(recorder)
        let result = try await store.finish(sessionID: "delete-reload")
        let saved = try XCTUnwrap(result)
        let reload = Task { await store.reloadLibrary() }
        await Task.yield()
        try store.delete(saved)
        await reload.value
        XCTAssertTrue(store.recordings.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.fileURL(for: saved).path))
    }

    func testDeleteRejectsUnknownEntryAndPreservesActiveRecording() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let recorder = try store.begin(sessionID: "active")
        try await feed(recorder)
        let unknown = SavedBroadcastRecording(id: UUID(), sessionID: "active", createdAt: Date(),
            duration: 1, fileName: "../other.mp4", bytes: 1, videoFrames: nil, audioFrames: nil, droppedVideoFrames: nil)
        XCTAssertThrowsError(try store.delete(unknown))
        XCTAssertEqual(store.state, .recording)
        let result = try await store.finish(sessionID: "active")
        XCTAssertNotNil(result)
    }

    func testDeletionFailureKeepsFileAndLibraryEntry() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let manager = FileManager.default
        defer {
            try? manager.setAttributes([.posixPermissions: 0o700], ofItemAtPath: directory.path)
            try? manager.removeItem(at: directory)
        }
        let store = makeStore(directory)
        let recorder = try store.begin(sessionID: "delete-denied")
        try await feed(recorder)
        let result = try await store.finish(sessionID: "delete-denied")
        let saved = try XCTUnwrap(result)
        let original = try Data(contentsOf: store.fileURL(for: saved))
        try manager.setAttributes([.posixPermissions: 0o500], ofItemAtPath: directory.path)
        XCTAssertThrowsError(try store.delete(saved))
        XCTAssertEqual(store.recordings, [saved])
        XCTAssertEqual(store.state, .saved(saved))
        XCTAssertEqual(try Data(contentsOf: store.fileURL(for: saved)), original)
    }

    func testDeletingPreviousRecordingDoesNotStopActiveSession() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = makeStore(directory)
        let firstRecorder = try store.begin(sessionID: "previous")
        try await feed(firstRecorder)
        let firstResult = try await store.finish(sessionID: "previous")
        let previous = try XCTUnwrap(firstResult)
        let activeRecorder = try store.begin(sessionID: "active")
        try await feed(activeRecorder)
        try store.delete(previous)
        XCTAssertEqual(store.state, .recording)
        let activeResult = try await store.finish(sessionID: "active")
        let saved = try XCTUnwrap(activeResult)
        XCTAssertEqual(store.recordings, [saved])
        XCTAssertTrue(FileManager.default.fileExists(atPath: store.fileURL(for: saved).path))
    }

    private func makeStore(_ directory: URL) -> RecordingSessionStore {
        RecordingSessionStore(directory: directory, availableCapacity: { _ in 1_000_000_000 })
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
