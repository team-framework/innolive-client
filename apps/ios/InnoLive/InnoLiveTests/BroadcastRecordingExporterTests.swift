import AVFoundation
import XCTest
@testable import InnoLive

@MainActor
final class BroadcastRecordingExporterTests: XCTestCase {
    func testDefaultKeepsOriginalBytesAndSplitPreservesTracksAndTiming() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("original.mp4")
        try await ExportToneFixture.write(to: source)
        let originalBytes = try Data(contentsOf: source)
        let exporter = BroadcastRecordingExporter(root: root.appendingPathComponent("exports"))
        let full = try await exporter.original(source)
        XCTAssertEqual(full.urls, [source])
        XCTAssertEqual(try Data(contentsOf: full.urls[0]), originalBytes)
        let split = try await exporter.split(source)
        XCTAssertEqual(split.urls.map(\.pathExtension), ["mp4", "m4a"])
        let sourceAsset = AVURLAsset(url: source)
        let video = AVURLAsset(url: split.urls[0])
        let audio = AVURLAsset(url: split.urls[1])
        let sourceVideo = try await sourceAsset.loadTracks(withMediaType: .video)
        let sourceAudio = try await sourceAsset.loadTracks(withMediaType: .audio)
        let videoTracks = try await video.loadTracks(withMediaType: .video)
        let silentTracks = try await video.loadTracks(withMediaType: .audio)
        let audioTracks = try await audio.loadTracks(withMediaType: .audio)
        let imageTracks = try await audio.loadTracks(withMediaType: .video)
        XCTAssertEqual(videoTracks.count, 1)
        XCTAssertTrue(silentTracks.isEmpty)
        XCTAssertEqual(audioTracks.count, 1)
        XCTAssertTrue(imageTracks.isEmpty)
        try await compare(try XCTUnwrap(sourceVideo.first), try XCTUnwrap(videoTracks.first))
        try await compare(try XCTUnwrap(sourceAudio.first), try XCTUnwrap(audioTracks.first))
        let sourceTransform = try await XCTUnwrap(sourceVideo.first).load(.preferredTransform)
        let exportedTransform = try await XCTUnwrap(videoTracks.first).load(.preferredTransform)
        XCTAssertEqual(sourceTransform, exportedTransform)
        XCTAssertGreaterThan(try audioPeak(audio, track: XCTUnwrap(audioTracks.first)), 0.1)
        exporter.release(split)
        XCTAssertFalse(FileManager.default.fileExists(atPath: split.urls[0].path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: split.urls[1].path))
        exporter.release(full)
        XCTAssertEqual(try Data(contentsOf: source), originalBytes, "Cleanup must never remove the source")
    }

    func testCancelledExportAndMissingSourceDoNotDeleteUnrelatedFiles() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let sentinel = root.appendingPathComponent("existing.txt")
        try Data("preserve".utf8).write(to: sentinel)
        let exporter = BroadcastRecordingExporter(root: root)
        let operation = Task { @MainActor in try await exporter.split(root.appendingPathComponent("missing.mp4")) }
        operation.cancel()
        do { _ = try await operation.value; XCTFail("Cancelled work cannot succeed") }
        catch is CancellationError { }
        do { _ = try await exporter.original(root.appendingPathComponent("missing.mp4")); XCTFail("Missing file cannot succeed") }
        catch let error as BroadcastRecordingExportError {
            guard case .missingFile = error else { return XCTFail("Unexpected error: \(error)") }
        }
        XCTAssertEqual(try Data(contentsOf: sentinel), Data("preserve".utf8))
    }

    func testSplitUsesEachTracksTimelineWhenVideoEndsEarlier() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("different-ends.mp4")
        try await ExportToneFixture.write(to: source, videoUntil: 0.21)
        let exporter = BroadcastRecordingExporter(root: root.appendingPathComponent("exports"))
        let split = try await exporter.split(source)
        let original = AVURLAsset(url: source)
        let sourceVideo = try await original.loadTracks(withMediaType: .video)
        let sourceAudio = try await original.loadTracks(withMediaType: .audio)
        let expectedVideo = CMTimeGetSeconds(try await XCTUnwrap(sourceVideo.first).load(.timeRange).end)
        let expectedAudio = CMTimeGetSeconds(try await XCTUnwrap(sourceAudio.first).load(.timeRange).end)
        XCTAssertGreaterThan(abs(expectedAudio - expectedVideo), 0.2)
        let videoDuration = CMTimeGetSeconds(try await AVURLAsset(url: split.urls[0]).load(.duration))
        let audioDuration = CMTimeGetSeconds(try await AVURLAsset(url: split.urls[1]).load(.duration))
        XCTAssertEqual(videoDuration, expectedVideo, accuracy: 0.05)
        XCTAssertEqual(audioDuration, expectedAudio, accuracy: 0.05)
    }

    func testPartialWriteCancellationAndFailureCleanOnlyOwnedDirectory() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("source.mp4")
        try await ExportToneFixture.write(to: source)
        let sourceBytes = try Data(contentsOf: source)
        for cancelled in [false, true] {
            let output = root.appendingPathComponent(cancelled ? "cancel" : "failure")
            try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
            let sentinel = output.appendingPathComponent("keep.txt")
            try Data("keep".utf8).write(to: sentinel)
            var wrotePartial = false
            let exporter = BroadcastRecordingExporter(root: output, exportOperation: { _, url, _ in
                try Data([0, 1, 2, 3]).write(to: url)
                wrotePartial = true
                if cancelled { try await Task.sleep(for: .seconds(30)) }
                throw NSError(domain: "InjectedExportFailure", code: 1)
            })
            let operation = Task { @MainActor in try await exporter.split(source) }
            for _ in 0..<500 {
                if wrotePartial { break }
                try await Task.sleep(for: .milliseconds(10))
            }
            XCTAssertTrue(wrotePartial, "Exporter must reach the injected partial-write stage")
            if !wrotePartial { operation.cancel() }
            if cancelled { operation.cancel() }
            do { _ = try await operation.value; XCTFail("Injected export must not succeed") }
            catch is CancellationError { XCTAssertTrue(cancelled) }
            catch let error as NSError { XCTAssertEqual(error.domain, "InjectedExportFailure") }
            XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: output.path), ["keep.txt"])
            XCTAssertEqual(try Data(contentsOf: source), sourceBytes)
        }
    }

    func testColdStartupCleanupPreservesRecentExportsAndUnrelatedContent() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("source.mp4")
        try await ExportToneFixture.write(to: source)
        let output = root.appendingPathComponent("exports")
        let exporter = BroadcastRecordingExporter(root: output)
        let old = try await exporter.split(source)
        let recent = try await exporter.split(source)
        let marker = old.urls[0].deletingLastPathComponent().appendingPathComponent(".innolive-export-v1")
        try FileManager.default.setAttributes([.modificationDate: Date().addingTimeInterval(-25 * 3600)], ofItemAtPath: marker.path)
        let unmarked = output.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: unmarked, withIntermediateDirectories: true)
        XCTAssertEqual(try BroadcastRecordingExporter.cleanupStaleExportsOnColdStart(root: output), 1)
        XCTAssertFalse(FileManager.default.fileExists(atPath: old.urls[0].path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: recent.urls[0].path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: unmarked.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: source.path))
        XCTAssertEqual(try BroadcastRecordingExporter.cleanupStaleExportsOnColdStart(root: output, retention: 0), 0)
    }

    private func compare(_ source: AVAssetTrack, _ output: AVAssetTrack) async throws {
        let a = try await source.load(.timeRange)
        let b = try await output.load(.timeRange)
        XCTAssertEqual(CMTimeGetSeconds(a.start), CMTimeGetSeconds(b.start), accuracy: 0.05)
        XCTAssertEqual(CMTimeGetSeconds(a.duration), CMTimeGetSeconds(b.duration), accuracy: 0.05)
        let originalFormats = try await source.load(.formatDescriptions)
        let outputFormats = try await output.load(.formatDescriptions)
        XCTAssertEqual(CMFormatDescriptionGetMediaSubType(try XCTUnwrap(originalFormats.first)),
                       CMFormatDescriptionGetMediaSubType(try XCTUnwrap(outputFormats.first)))
    }

    private func audioPeak(_ asset: AVAsset, track: AVAssetTrack) throws -> Float {
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
            AVFormatIDKey: kAudioFormatLinearPCM, AVLinearPCMIsFloatKey: true,
            AVLinearPCMBitDepthKey: 32, AVLinearPCMIsNonInterleaved: false,
        ])
        reader.add(output)
        XCTAssertTrue(reader.startReading())
        var peak: Float = 0
        while let sample = output.copyNextSampleBuffer(), let block = CMSampleBufferGetDataBuffer(sample) {
            var length = 0
            var pointer: UnsafeMutablePointer<Int8>?
            XCTAssertEqual(CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: nil,
                totalLengthOut: &length, dataPointerOut: &pointer), noErr)
            if let pointer {
                pointer.withMemoryRebound(to: Float.self, capacity: length / 4) { floats in
                    for index in 0..<(length / 4) { peak = max(peak, abs(floats[index])) }
                }
            }
        }
        XCTAssertEqual(reader.status, .completed)
        return peak
    }
}

/// Generates a real H.264/AAC file through the recorder, without account, network or mic access.
/// SimulatorFixture.mp4 has video only and cannot cover the combined export contract.
@MainActor
enum ExportToneFixture {
    static func write(to url: URL, videoUntil: Double = .infinity) async throws {
        let recorder = LocalBroadcastRecorder()
        let epoch = CMClockGetTime(CMClockGetHostTimeClock())
        try recorder.start(url: url, epoch: epoch)
        for index in 0..<60 {
            let timestamp = CMTimeAdd(epoch, CMTime(value: Int64(index), timescale: 100))
            if index % 3 == 0, Double(index) / 100 < videoUntil {
                var pixel: CVPixelBuffer?
                XCTAssertEqual(CVPixelBufferCreate(kCFAllocatorDefault, 64, 48, kCVPixelFormatType_32BGRA,
                    [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &pixel), kCVReturnSuccess)
                let buffer = try XCTUnwrap(pixel)
                CVPixelBufferLockBaseAddress(buffer, [])
                memset(CVPixelBufferGetBaseAddress(buffer), Int32(index + 40), CVPixelBufferGetDataSize(buffer))
                CVPixelBufferUnlockBaseAddress(buffer, [])
                recorder.appendVideo(pixelBuffer: buffer, rotation: 90,
                    timestampNs: CMTimeConvertScale(timestamp, timescale: 1_000_000_000, method: .default).value)
            }
            let format = try XCTUnwrap(AVAudioFormat(commonFormat: .pcmFormatFloat32,
                                                   sampleRate: 48_000, channels: 2, interleaved: false))
            let audio = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 480))
            audio.frameLength = 480
            let channels = try XCTUnwrap(audio.floatChannelData)
            for sample in 0..<480 {
                let value = Float(sin(Double(index * 480 + sample) * 2 * .pi * 440 / 48_000) * 0.25)
                channels[0][sample] = value
                channels[1][sample] = value
            }
            recorder.appendAudio(audio, when: AVAudioTime(hostTime: AVAudioTime.hostTime(forSeconds: CMTimeGetSeconds(timestamp))))
            try await Task.sleep(for: .milliseconds(10))
        }
        _ = try await recorder.stop()
    }
}
