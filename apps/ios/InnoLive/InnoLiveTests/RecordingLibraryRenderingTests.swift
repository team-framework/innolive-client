import AVFoundation
import SwiftUI
import UIKit
import XCTest
@testable import InnoLive

@MainActor
final class RecordingLibraryRenderingTests: XCTestCase {
    func testLibraryEmptyAndVerifiedFileAtDefaultAndAccessibilitySizes() async throws {
        let fixture = RecordingRenderFixture()
        defer { fixture.removeFiles() }
        try await render("library-empty", textSize: .accessibility3) {
            NavigationStack { BroadcastRecordingLibraryView(store: fixture.store) }
        }
        let saved = try await fixture.createRecording()
        XCTAssertTrue(FileManager.default.fileExists(atPath: fixture.store.fileURL(for: saved).path))
        for size in [DynamicTypeSize.large, .accessibility3] {
            try await render("library-saved", textSize: size) {
                NavigationStack { BroadcastRecordingLibraryView(store: fixture.store) }
            }
        }
    }

    func testVerifiedResultAndPlayerLayout() async throws {
        let fixture = RecordingRenderFixture()
        defer { fixture.removeFiles() }
        let saved = try await fixture.createRecording()
        let url = fixture.store.fileURL(for: saved)
        var actions = 0
        for size in [DynamicTypeSize.large, .accessibility3] {
            try await render("result-verified", textSize: size, expectsFullHeightScroll: true) {
                BroadcastEndOverlay(phase: .result,
                    result: .init(startedAt: saved.createdAt, endedAt: saved.createdAt.addingTimeInterval(saved.duration)),
                    recording: .saved(url), isRecordingEnabled: true,
                    onClose: { actions += 1 }, onPlay: { _ in actions += 1 }, onSave: { _ in actions += 1 })
            }
        }
        XCTAssertEqual(actions, 0, "Rendering must not invoke user actions")
        try await render("player-file-layout", textSize: .accessibility3, expectsAnyScroll: false) {
            NavigationStack { BroadcastRecordingPlayerView(url: url) }
        }
        // This only checks the player layout, not playback progression or audible output.
    }

    func testSavingAndRealCommitFailureRetryAtLargestTextSize() async throws {
        let fixture = RecordingRenderFixture()
        defer { fixture.removeFiles() }
        try await render("result-saving", textSize: .accessibility5, highContrast: true, expectsFullHeightScroll: true) {
            BroadcastEndOverlay(phase: .result,
                result: .init(startedAt: Date(timeIntervalSince1970: 0), endedAt: Date(timeIntervalSince1970: 3609)),
                recording: .finalizing, isRecordingEnabled: true, onClose: {})
        }
        let recorder = try fixture.store.begin(sessionID: "retry-render")
        try await fixture.feed(recorder)
        // begin() writes the journal synchronously; AVAssetWriter creates its MP4 asynchronously.
        // Block the metadata destination using the session identity, without racing the writer.
        let journal = try XCTUnwrap(FileManager.default.contentsOfDirectory(at: fixture.directory, includingPropertiesForKeys: nil)
            .first { $0.lastPathComponent.hasSuffix(".pending.json") })
        let stem = journal.lastPathComponent.replacingOccurrences(of: ".pending.json", with: "")
        try FileManager.default.createDirectory(at: fixture.directory.appendingPathComponent(stem + ".json"), withIntermediateDirectories: true)
        do { _ = try await fixture.store.finish(sessionID: "retry-render"); XCTFail("Expected blocked metadata destination") } catch {}
        guard case let .failed(message, canRetry) = fixture.store.state else { return XCTFail("Expected actual commit failure") }
        XCTAssertTrue(canRetry)
        var retries = 0
        try await render("result-retry-real-failure", textSize: .accessibility5, highContrast: true, expectsFullHeightScroll: true) {
            BroadcastEndOverlay(phase: .result,
                result: .init(startedAt: Date(timeIntervalSince1970: 0), endedAt: Date(timeIntervalSince1970: 3609)),
                recording: .failed(message), isRecordingEnabled: true, onClose: {}, onRetrySaving: { retries += 1 })
        }
        XCTAssertEqual(retries, 0)
    }

    private func foregroundScene() async throws -> UIWindowScene {
        for _ in 0..<50 {
            if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene })
                .first(where: { $0.activationState == .foregroundActive }) { return scene }
            try await Task.sleep(for: .milliseconds(100))
        }
        return try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }, "Foreground the test app before running hosted UI checks")
    }

    private func render<Content: View>(_ name: String, textSize: DynamicTypeSize,
                                      highContrast: Bool = false, expectsFullHeightScroll: Bool = false,
                                      expectsAnyScroll: Bool = true,
                                      @ViewBuilder content: () -> Content) async throws {
        let scene = try await foregroundScene()
        let previousKeyWindow = scene.windows.first { $0.isKeyWindow }
        let window = UIWindow(windowScene: scene)
        window.frame = scene.coordinateSpace.bounds
        window.overrideUserInterfaceStyle = .dark
        let host = UIHostingController(rootView: content().dynamicTypeSize(textSize))
        host.traitOverrides.accessibilityContrast = highContrast ? .high : .normal
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKeyAndVisible()
        }
        try await Task.sleep(for: .milliseconds(650))
        host.view.layoutIfNeeded()
        window.layoutIfNeeded()
        let scrolls = descendants(window).compactMap { $0 as? UIScrollView }
        if expectsAnyScroll {
            let scroll = try XCTUnwrap(scrolls.first)
            let width = scroll.bounds.width - scroll.adjustedContentInset.left - scroll.adjustedContentInset.right
            let height = scroll.bounds.height - scroll.adjustedContentInset.top - scroll.adjustedContentInset.bottom
            XCTAssertGreaterThan(width, 0)
            XCTAssertGreaterThan(height, 0)
            XCTAssertLessThanOrEqual(scroll.contentSize.width, width + 1, "Large text must wrap inside the usable width")
            if expectsFullHeightScroll {
                XCTAssertGreaterThanOrEqual(scroll.contentSize.height, height - 1)
                let bottom = max(-scroll.adjustedContentInset.top,
                    scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom)
                scroll.setContentOffset(CGPoint(x: -scroll.adjustedContentInset.left, y: bottom), animated: false)
                scroll.layoutIfNeeded()
                XCTAssertEqual(scroll.contentOffset.y, bottom, accuracy: 1, "The last action must remain reachable")
                // Capture the action area for accessibility sizes; default captures the full result.
                if textSize == .large { scroll.setContentOffset(CGPoint(x: 0, y: -scroll.adjustedContentInset.top), animated: false) }
            }
        }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(bounds: window.bounds, format: format).image { _ in
            XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
        }
        XCTAssertGreaterThan(image.pngData()?.count ?? 0, 5_000)
        let attachment = XCTAttachment(image: image)
        attachment.name = "recording-\(name)-\(textSize)-iOS\(UIDevice.current.systemVersion)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func descendants(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(descendants) }
}

@MainActor private final class RecordingRenderFixture {
    nonisolated deinit {}
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("recording-ui-\(UUID().uuidString)")
    let store: RecordingSessionStore
    init() { store = RecordingSessionStore(directory: directory, availableCapacity: { _ in 1_000_000_000 }) }
    func removeFiles() { try? FileManager.default.removeItem(at: directory) }

    func createRecording() async throws -> SavedBroadcastRecording {
        let recorder = try store.begin(sessionID: "render")
        try await feed(recorder)
        let saved = try await store.finish(sessionID: "render")
        return try XCTUnwrap(saved)
    }

    /// Deterministic 0.3-second media, including real encoded silent audio samples.
    /// This fixture verifies saved-file UI and is not hardware capture evidence.
    func feed(_ recorder: LocalBroadcastRecorder) async throws {
        let base = CMClockGetTime(CMClockGetHostTimeClock())
        let format = try XCTUnwrap(AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 2, interleaved: false))
        for index in 0..<30 {
            let time = CMTimeAdd(base, CMTime(value: Int64(index * 480), timescale: 48_000))
            if index % 2 == 0 {
                var pixel: CVPixelBuffer?
                CVPixelBufferCreate(kCFAllocatorDefault, 160, 90, kCVPixelFormatType_32BGRA,
                    [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &pixel)
                let buffer = try XCTUnwrap(pixel)
                CVPixelBufferLockBaseAddress(buffer, [])
                let bytes = try XCTUnwrap(CVPixelBufferGetBaseAddress(buffer)).assumingMemoryBound(to: UInt8.self)
                let stride = CVPixelBufferGetBytesPerRow(buffer)
                for y in 0..<90 {
                    for x in 0..<160 {
                        bytes[y * stride + x * 4] = UInt8(80 + x)
                        bytes[y * stride + x * 4 + 1] = UInt8(80 + y)
                        bytes[y * stride + x * 4 + 2] = 180
                        bytes[y * stride + x * 4 + 3] = 255
                    }
                }
                CVPixelBufferUnlockBaseAddress(buffer, [])
                recorder.appendVideo(pixelBuffer: buffer, rotation: 0,
                    timestampNs: CMTimeConvertScale(time, timescale: 1_000_000_000, method: .default).value)
            }
            let audio = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 480))
            audio.frameLength = 480
            for channel in 0..<2 {
                for sample in 0..<480 { audio.floatChannelData![channel][sample] = 0 }
            }
            recorder.appendAudio(audio, when: AVAudioTime(hostTime: AVAudioTime.hostTime(forSeconds: CMTimeGetSeconds(time))))
            try await Task.sleep(for: .milliseconds(10))
        }
    }
}
