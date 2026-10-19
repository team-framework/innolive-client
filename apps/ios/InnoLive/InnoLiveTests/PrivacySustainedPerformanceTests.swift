#if DEBUG
import XCTest
import AVFoundation
import CoreImage
import VideoToolbox
import UIKit
@preconcurrency import LiveKitWebRTC
@testable import InnoLive

/// Physical-device load test: production frame gate, render pool, rotation and two H.264 encoders.
/// Input is a fixed public fixture; this does not measure camera capture or network delivery.
final class PrivacySustainedPerformanceTests: XCTestCase {
    @MainActor func testPhysicalCameraWithTwoEncoders() async throws {
        #if targetEnvironment(simulator)
        throw XCTSkip("Requires a physical iPhone camera")
        #endif
        guard Bundle.main.url(forResource: "PrivacyDetector", withExtension: "mlmodelc") != nil else {
            throw XCTSkip("Optional local Core ML model is required")
        }
        XCTAssertEqual(UIApplication.shared.applicationState, .active)
        guard await AVCaptureDevice.requestAccess(for: .video) else {
            return XCTFail("Camera permission is required")
        }
        try await Self.coolDown()
        let uplink = WebRTCVideoUplink()
        uplink.isStopping = false
        uplink.applyBroadcastOrientationLock(.landscapeLeft)
        let sink = SustainedCameraSink()
        uplink.setDirectMediaSink(sink)
        do {
            try await uplink.prepareNativeCamera(preferredCameraID: nil, preferredVideoQuality: .fullHD30,
                                                operationGeneration: uplink.cameraOperationGeneration)
            let relay = try XCTUnwrap(uplink.cameraFrameRelay)
            relay.setProcessingMode(.onDevice)
            try await relay.prepareLocalProcessing()
            try await Task.sleep(for: .seconds(3))
            let first = try SustainedH264Output()
            let second = try SustainedH264Output()
            sink.fanout.attach(first, provider: "benchmark-first")
            sink.fanout.attach(second, provider: "benchmark-second")
            let thermalBefore = ProcessInfo.processInfo.thermalState.rawValue
            let start = ProcessInfo.processInfo.systemUptime
            try await Task.sleep(for: .seconds(90))
            let elapsed = ProcessInfo.processInfo.systemUptime - start
            let diagnostics = relay.validationCaptureDiagnostics
            uplink.setDirectMediaSink(nil)
            await uplink.stopAndWait()
            first.finish(); second.finish()
            sink.fanout.detach(provider: "benchmark-first"); sink.fanout.detach(provider: "benchmark-second")
            let result = sink.stats.snapshot
            let media = sink.fanout.metrics.drain()
            print("PRIVACY_CAMERA seconds=\(elapsed) delivered=\(result.count) fps=\(Double(result.count)/elapsed) median=\(result.median) p95=\(result.p95) failures=\(result.failures) encoder1=\(first.completed) encoder2=\(second.completed) encoderErrors=\(first.errors+second.errors) dimensions=\(media.videoWidth ?? 0)x\(media.videoHeight ?? 0) thermalBefore=\(thermalBefore) thermalAfter=\(ProcessInfo.processInfo.thermalState.rawValue) lowPower=\(ProcessInfo.processInfo.isLowPowerModeEnabled) capture=\(diagnostics)")
            XCTAssertEqual(media.videoWidth, 1920)
            XCTAssertEqual(media.videoHeight, 1080)
            XCTAssertEqual(result.failures, 0)
            XCTAssertEqual(first.errors + second.errors, 0)
            XCTAssertEqual(first.completed, result.count)
            XCTAssertEqual(second.completed, result.count)
            XCTAssertGreaterThanOrEqual(Double(result.count) / elapsed, 29.5)
        } catch {
            uplink.setDirectMediaSink(nil)
            await uplink.stopAndWait()
            throw error
        }
    }

    func testFullHDThirtyFPSWithTwoEncoders() async throws {
        #if targetEnvironment(simulator)
        throw XCTSkip("Performance acceptance requires a physical iPhone")
        #endif
        guard Bundle.main.url(forResource: "PrivacyDetector", withExtension: "mlmodelc") != nil,
              let fixture = Bundle(for: Self.self).url(forResource: "privacy-performance-face", withExtension: "png") else {
            throw XCTSkip("Optional local Core ML model and public performance fixture are required")
        }
        try await Task.detached {
            try await Self.coolDown()
            for faces in [1, 5] {
                try await Self.run(fixture: fixture, faces: faces)
            }
        }.value
    }

    nonisolated private static func coolDown() async throws {
        let start = ProcessInfo.processInfo.systemUptime
        while ProcessInfo.processInfo.thermalState != .nominal,
              ProcessInfo.processInfo.systemUptime - start < 300 {
            print("PRIVACY_COOLING thermal=\(ProcessInfo.processInfo.thermalState.rawValue)")
            try await Task.sleep(for: .seconds(15))
        }
        guard ProcessInfo.processInfo.thermalState == .nominal else {
            throw XCTSkip("Device remains thermally throttled; no comparable performance result")
        }
    }

    nonisolated private static func run(fixture: URL, faces: Int) async throws {
        let face = try XCTUnwrap(CIImage(contentsOf: fixture))
        let bounds = CGRect(x: 0, y: 0, width: 1920, height: 1080)
        var image = CIImage(color: .gray).cropped(to: bounds)
        let side: CGFloat = faces == 1 ? 1080 : 540
        for index in 0..<faces {
            let tile = face.transformed(by: CGAffineTransform(scaleX: side / face.extent.width, y: side / face.extent.height))
                .transformed(by: CGAffineTransform(translationX: CGFloat(index % 3) * side, y: CGFloat(index / 3) * side))
            image = tile.composited(over: image)
        }
        var source: CVPixelBuffer?
        let status = CVPixelBufferCreate(kCFAllocatorDefault, 1920, 1080,
            kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
            [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &source)
        XCTAssertEqual(status, kCVReturnSuccess)
        let pixels = try XCTUnwrap(source)
        CIContext().render(image.cropped(to: bounds), to: pixels)
        let stats = SustainedPrivacyStats()
        let processor = PrivacyUplinkProcessor { _ in stats.failure() }
        processor.setTimingObserver { stats.stages($0) }
        processor.setMetricsHandler { metric in
            if case let .inference(milliseconds) = metric { stats.timing(milliseconds) }
        }
        try await processor.prepare()
        let fanout = DirectMediaFanout()
        let first = try SustainedH264Output()
        let second = try SustainedH264Output()
        fanout.attach(first, provider: "benchmark-first")
        fanout.attach(second, provider: "benchmark-second")
        let thermalBefore = ProcessInfo.processInfo.thermalState.rawValue
        let start = ProcessInfo.processInfo.systemUptime
        let frameCount = 2700
        for index in 0..<frameCount {
            let deadline = start + Double(index) / 30
            let wait = deadline - ProcessInfo.processInfo.systemUptime
            if wait > 0 { try await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000)) }
            autoreleasepool {
                let timestamp = Int64(ProcessInfo.processInfo.systemUptime * 1_000_000_000)
                let frame = LKRTCVideoFrame(buffer: LKRTCCVPixelBuffer(pixelBuffer: pixels), rotation: ._0, timeStampNs: timestamp)
                processor.submit(frame) { output in
                    guard let buffer = output.buffer as? LKRTCCVPixelBuffer else { stats.failure(); return }
                    fanout.appendVideo(pixelBuffer: buffer.pixelBuffer, rotation: output.rotation.rawValue, timestampNs: output.timeStampNs)
                    stats.delivered()
                }
            }
        }
        // Drain the single processing slot and asynchronous VideoToolbox callbacks.
        try await processor.prepare()
        first.finish(); second.finish()
        let elapsed = ProcessInfo.processInfo.systemUptime - start
        processor.stop()
        fanout.detach(provider: "benchmark-first"); fanout.detach(provider: "benchmark-second")
        let result = stats.snapshot
        print("PRIVACY_SUSTAINED faces=\(faces) submitted=\(frameCount) delivered=\(result.count) dropped=\(frameCount-result.count) failures=\(result.failures) seconds=\(elapsed) fps=\(Double(result.count)/elapsed) median=\(result.median) p95=\(result.p95) encoder1=\(first.completed) encoder2=\(second.completed) encoderErrors=\(first.errors+second.errors) thermalBefore=\(thermalBefore) thermalAfter=\(ProcessInfo.processInfo.thermalState.rawValue) stages=\(stats.stageMedians)")
        XCTAssertEqual(result.failures, 0)
        XCTAssertEqual(first.errors + second.errors, 0)
        XCTAssertEqual(first.completed, result.count)
        XCTAssertEqual(second.completed, result.count)
        XCTAssertGreaterThanOrEqual(Double(result.count) / elapsed, 29.5)
        XCTAssertLessThan(result.p95, 33.333)
    }
}

nonisolated private final class SustainedCameraSink: DirectMediaSink, @unchecked Sendable {
    let fanout = DirectMediaFanout()
    let stats = SustainedPrivacyStats()
    var acceptsMedia: Bool { fanout.acceptsMedia }
    func appendVideo(pixelBuffer: CVPixelBuffer, rotation: Int, timestampNs: Int64) {
        guard acceptsMedia else { return }
        fanout.appendVideo(pixelBuffer: pixelBuffer, rotation: rotation, timestampNs: timestampNs)
        stats.delivered()
    }
    func recordInference(milliseconds: Double) { if acceptsMedia { stats.timing(milliseconds) } }
    func recordAnonymizationFailure() { if acceptsMedia { stats.failure() } }
}

nonisolated private final class SustainedPrivacyStats: @unchecked Sendable {
    private let lock = NSLock()
    private var times: [Double] = []
    private var count = 0
    private var failures = 0
    private var stageValues: [PrivacyTimings] = []
    func stages(_ value: PrivacyTimings) { lock.withLock { stageValues.append(value) } }
    var stageMedians: String {
        lock.withLock {
            guard !stageValues.isEmpty else { return "none" }
            return [stageValues.map(\.prepare), stageValues.map(\.inference), stageValues.map(\.mask), stageValues.map(\.render)]
                .map { $0.sorted()[$0.count / 2] }.map(String.init(describing:)).joined(separator: ",")
        }
    }
    func failure() { lock.withLock { failures += 1 } }
    func timing(_ value: Double) { lock.withLock { times.append(value) } }
    func delivered() { lock.withLock { count += 1 } }
    var snapshot: (count: Int, failures: Int, median: Double, p95: Double) {
        lock.withLock {
            let sorted = times.sorted()
            return (count, failures, sorted.isEmpty ? .infinity : sorted[sorted.count / 2],
                    sorted.isEmpty ? .infinity : sorted[min(sorted.count-1, Int(Double(sorted.count) * 0.95))])
        }
    }
}

nonisolated private final class SustainedH264Output: DirectMediaOutput, @unchecked Sendable {
    private var session: VTCompressionSession?
    private let lock = NSLock()
    private var encoded = 0
    private var failed = 0
    var completed: Int { lock.withLock { encoded } }
    var errors: Int { lock.withLock { failed } }
    init() throws {
        let status = VTCompressionSessionCreate(allocator: kCFAllocatorDefault, width: 1920, height: 1080,
            codecType: kCMVideoCodecType_H264, encoderSpecification: nil, imageBufferAttributes: nil,
            compressedDataAllocator: nil, outputCallback: { ref, _, status, flags, sample in
                guard let ref else { return }
                let output = Unmanaged<SustainedH264Output>.fromOpaque(ref).takeUnretainedValue()
                output.lock.withLock {
                    if status == noErr, !flags.contains(.frameDropped), sample != nil { output.encoded += 1 }
                    else { output.failed += 1 }
                }
            }, refcon: Unmanaged.passUnretained(self).toOpaque(), compressionSessionOut: &session)
        guard status == noErr, let session else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
        for (key, value): (CFString, CFTypeRef) in [
            (kVTCompressionPropertyKey_RealTime, kCFBooleanTrue),
            (kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse),
            (kVTCompressionPropertyKey_ProfileLevel, kVTProfileLevel_H264_High_AutoLevel),
            (kVTCompressionPropertyKey_AverageBitRate, NSNumber(value: 6_000_000)),
            (kVTCompressionPropertyKey_ExpectedFrameRate, NSNumber(value: 30)),
            (kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, NSNumber(value: 2))
        ] {
            let result = VTSessionSetProperty(session, key: key, value: value)
            guard result == noErr else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(result)) }
        }
        let ready = VTCompressionSessionPrepareToEncodeFrames(session)
        guard ready == noErr else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(ready)) }
        if #available(iOS 17.4, *) {
            var hardware: Unmanaged<CFTypeRef>?
            let result = VTSessionCopyProperty(session, key: kVTCompressionPropertyKey_UsingHardwareAcceleratedVideoEncoder,
                                              allocator: kCFAllocatorDefault, valueOut: &hardware)
            print("PRIVACY_ENCODER hardwareQueryStatus=\(result) hardware=\(String(describing: hardware?.takeRetainedValue()))")
        }
    }
    func appendVideo(_ sampleBuffer: CMSampleBuffer) {
        guard let session, let image = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let status = VTCompressionSessionEncodeFrame(session, imageBuffer: image,
            presentationTimeStamp: CMSampleBufferGetPresentationTimeStamp(sampleBuffer),
            duration: CMTime(value: 1, timescale: 30), frameProperties: nil, sourceFrameRefcon: nil, infoFlagsOut: nil)
        if status != noErr { lock.withLock { failed += 1 } }
    }
    func appendAudio(_ buffer: AVAudioPCMBuffer, when: AVAudioTime) {}
    func finish() {
        if let session { VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid) }
    }
    deinit { if let session { VTCompressionSessionInvalidate(session) } }
}
#endif
