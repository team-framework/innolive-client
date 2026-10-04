import AVFoundation
import XCTest
@preconcurrency import LiveKitWebRTC

@testable import InnoLive

final class WebRTCCameraFrameRelayTests: XCTestCase {
    override func setUp() {
        super.setUp()
        _ = LKRTCInitializeSSL()
    }

    func testUnlockedFramesKeepCapturerRotation() throws {
        let target = RecordingVideoTarget()
        let relay = WebRTCCameraFrameRelay(target: target, cameraPosition: .front)
        let frame = try makeFrame(rotation: 90)

        relay.capturer(LKRTCVideoCapturer(delegate: target), didCapture: frame)

        XCTAssertEqual(target.rotations, [90])
    }

    func testLockedLandscapeLeftOverridesFrontAndBackRotation() throws {
        let target = RecordingVideoTarget()
        let relay = WebRTCCameraFrameRelay(target: target, cameraPosition: .front)
        relay.setLockedInterfaceOrientation(.landscapeLeft)
        let frame = try makeFrame(rotation: 90)

        relay.capturer(LKRTCVideoCapturer(delegate: target), didCapture: frame)
        relay.updateCameraPosition(.back)
        relay.capturer(LKRTCVideoCapturer(delegate: target), didCapture: frame)

        XCTAssertEqual(target.rotations, [0, 180])
    }

    func testLockedLandscapeRightOverridesFrontAndBackRotation() throws {
        let target = RecordingVideoTarget()
        let relay = WebRTCCameraFrameRelay(target: target, cameraPosition: .back)
        relay.setLockedInterfaceOrientation(.landscapeRight)
        let frame = try makeFrame(rotation: 90)

        relay.capturer(LKRTCVideoCapturer(delegate: target), didCapture: frame)
        relay.updateCameraPosition(.front)
        relay.capturer(LKRTCVideoCapturer(delegate: target), didCapture: frame)

        XCTAssertEqual(target.rotations, [0, 180])
    }

    func testSourceChangeWhileHeldRecomputesRotationFromSameLock() throws {
        let target = RecordingVideoTarget()
        let relay = WebRTCCameraFrameRelay(target: target, cameraPosition: .front)
        relay.setLockedInterfaceOrientation(.portrait)
        let frame = try makeFrame(rotation: 0)

        relay.capturer(LKRTCVideoCapturer(delegate: target), didCapture: frame)
        relay.updateCameraPosition(.back)
        relay.capturer(LKRTCVideoCapturer(delegate: target), didCapture: frame)

        XCTAssertEqual(target.rotations, [90, 90])
    }

    func testReleaseRestoresPassThroughRotation() throws {
        let target = RecordingVideoTarget()
        let relay = WebRTCCameraFrameRelay(target: target, cameraPosition: .front)
        relay.setLockedInterfaceOrientation(.landscapeRight)
        let frame = try makeFrame(rotation: 90)
        relay.capturer(LKRTCVideoCapturer(delegate: target), didCapture: frame)

        relay.setLockedInterfaceOrientation(nil)
        relay.capturer(LKRTCVideoCapturer(delegate: target), didCapture: frame)

        XCTAssertEqual(target.rotations, [180, 90])
    }

    func testOutgoingFrameKeepsPixelBufferAndTimestamp() throws {
        let target = RecordingVideoTarget()
        let relay = WebRTCCameraFrameRelay(target: target, cameraPosition: .back)
        relay.setLockedInterfaceOrientation(.portrait)
        let frame = try makeFrame(rotation: 0, timeStampNs: 42)
        frame.timeStamp = 7

        relay.capturer(LKRTCVideoCapturer(delegate: target), didCapture: frame)

        XCTAssertEqual(target.timeStamps, [42])
        XCTAssertEqual(target.timeStamps90kHz, [7])
        XCTAssertEqual(target.widths, [8])
        XCTAssertEqual(target.rotations, [90])
    }

    func testCameraPositionUpdateAppliesToTheNextFrame() throws {
        let target = RecordingVideoTarget()
        let relay = WebRTCCameraFrameRelay(target: target, cameraPosition: .front)
        relay.setLockedInterfaceOrientation(.landscapeLeft)
        let frame = try makeFrame(rotation: 90)

        relay.updateCameraPosition(.back)
        relay.capturer(LKRTCVideoCapturer(delegate: target), didCapture: frame)

        XCTAssertEqual(target.rotations, [180])
    }

    func testRollbackCameraPositionAppliesBeforeNextFrame() throws {
        let target = RecordingVideoTarget()
        let relay = WebRTCCameraFrameRelay(target: target, cameraPosition: .front)
        relay.setLockedInterfaceOrientation(.landscapeLeft)
        let frame = try makeFrame(rotation: 90)
        let capturer = LKRTCVideoCapturer(delegate: target)

        relay.capturer(capturer, didCapture: frame)
        relay.updateCameraPosition(.back)
        relay.capturer(capturer, didCapture: frame)
        relay.updateCameraPosition(.front)
        relay.capturer(capturer, didCapture: frame)

        XCTAssertEqual(target.rotations, [0, 180, 0])
    }

    func testColorAdjustedPreviewMatchesServerUplink() throws {
        let sent = ColorRecordingVideoTarget()
        let preview = ColorRecordingVideoTarget()
        let sentFrame = expectation(description: "color-adjusted uplink")
        let previewFrame = expectation(description: "color-adjusted preview")
        sent.onFrame = { sentFrame.fulfill() }
        preview.onFrame = { previewFrame.fulfill() }
        let relay = WebRTCCameraFrameRelay(
            target: sent,
            cameraPosition: .back,
            previewTarget: preview
        )
        relay.setColor(warmth: 0, saturation: 0)
        let frame = try makeFrame(rotation: 90, timeStampNs: 42)
        frame.timeStamp = 7

        relay.capturer(LKRTCVideoCapturer(delegate: sent), didCapture: frame)
        wait(for: [sentFrame, previewFrame], timeout: 3)

        let uplink = try XCTUnwrap(sent.lastFrame)
        let local = try XCTUnwrap(preview.lastFrame)
        XCTAssertTrue(uplink === local)
        XCTAssertEqual(uplink.rotation, frame.rotation)
        XCTAssertEqual(uplink.timeStampNs, 42)
        XCTAssertEqual(uplink.timeStamp, 7)
        let pixelBuffer = try XCTUnwrap((uplink.buffer as? LKRTCCVPixelBuffer)?.pixelBuffer)
        XCTAssertEqual(CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly), kCVReturnSuccess)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
        let bytes = try XCTUnwrap(CVPixelBufferGetBaseAddress(pixelBuffer)).assumingMemoryBound(to: UInt8.self)
        XCTAssertLessThanOrEqual(abs(Int(bytes[0]) - Int(bytes[1])), 3)
        XCTAssertLessThanOrEqual(abs(Int(bytes[1]) - Int(bytes[2])), 3)
    }

    @MainActor
    func testUplinkKeepsLockForRecreatedRelay() {
        let uplink = WebRTCVideoUplink()
        uplink.applyBroadcastOrientationLock(.landscapeLeft)
        XCTAssertEqual(uplink.lockedBroadcastOrientation, .landscapeLeft)
        uplink.clearBroadcastOrientationLock()
        XCTAssertNil(uplink.lockedBroadcastOrientation)
    }

    func testServerAdjustmentsKeepPresetInputNeutralAndMatchPreview() throws {
        try assertAdjustmentsKeepPresetInputNeutral(mode: .server)
    }

    func testOnDeviceAdjustmentsKeepPresetInputNeutralWithAnonymizationOff() throws {
        try assertAdjustmentsKeepPresetInputNeutral(mode: .onDevice)
    }

    private func assertAdjustmentsKeepPresetInputNeutral(mode: AIProcessingMode) throws {
        let sent = ColorRecordingVideoTarget()
        let preview = ColorRecordingVideoTarget()
        let raw = UnprocessedPreviewRecording()
        let relay = WebRTCCameraFrameRelay(
            target: sent,
            cameraPosition: .back,
            processingMode: mode,
            previewTarget: preview
        )
        // Exercise the asynchronous local route without invoking the AI model.
        if mode == .onDevice { relay.setLocalAnonymizationEnabled(false) }
        relay.setUnprocessedPreviewHandler { buffer, rotation in
            raw.record(buffer, rotation: rotation)
        }
        defer { relay.stopProcessing() }

        let frame = try makeFrame(rotation: 90, timeStampNs: 42)
        frame.timeStamp = 7
        let source = try XCTUnwrap((frame.buffer as? LKRTCCVPixelBuffer)?.pixelBuffer)
        let originalPixel = try firstPixel(source)
        let renderer = VideoLookPreviewRenderer()
        let preset = [(warmth: Float(0.2), saturation: Float(1.2), exposureEV: Float(0.8))]
        let referencePreset = try XCTUnwrap(renderer.makePreviewSet(
            pixelBuffer: source, rotation: 90, adjustments: preset
        )[0])
        let expectedProcessor = VideoColorFrameProcessor()
        let adjustments: [(exposure: Float, warmth: Float, saturation: Float)] = [
            (-1, -0.5, 0), (1, 0.5, 2), (0.8, 0.2, 1.2)
        ]

        for adjustment in adjustments {
            let delivered = expectation(description: "adjusted uplink \(mode)")
            sent.onFrame = { delivered.fulfill() }
            relay.setExposureEV(adjustment.exposure)
            relay.setColor(warmth: adjustment.warmth, saturation: adjustment.saturation)
            relay.capturer(LKRTCVideoCapturer(delegate: sent), didCapture: frame)
            wait(for: [delivered], timeout: 3)

            let rawBuffer = try XCTUnwrap(raw.buffer)
            XCTAssertTrue(rawBuffer === source)
            XCTAssertEqual(raw.rotation, 90)
            XCTAssertEqual(try firstPixel(rawBuffer), originalPixel)
            let actualPreset = try XCTUnwrap(renderer.makePreviewSet(
                pixelBuffer: rawBuffer, rotation: raw.rotation, adjustments: preset
            )[0])
            XCTAssertEqual(
                try XCTUnwrap(actualPreset.dataProvider?.data) as Data,
                try XCTUnwrap(referencePreset.dataProvider?.data) as Data
            )

            let expected = try expectedProcessor.process(
                frame, exposureEV: adjustment.exposure,
                warmth: adjustment.warmth, saturation: adjustment.saturation
            )
            let expectedBuffer = try XCTUnwrap((expected.buffer as? LKRTCCVPixelBuffer)?.pixelBuffer)
            let expectedPixel = try firstPixel(expectedBuffer)
            XCTAssertNotEqual(expectedPixel, originalPixel)
            for target in [sent, preview] {
                let output = try XCTUnwrap(target.lastFrame)
                let outputBuffer = try XCTUnwrap((output.buffer as? LKRTCCVPixelBuffer)?.pixelBuffer)
                XCTAssertEqual(try firstPixel(outputBuffer), expectedPixel)
                XCTAssertEqual(output.rotation, frame.rotation)
                XCTAssertEqual(output.timeStampNs, 42)
                XCTAssertEqual(output.timeStamp, 7)
            }
        }
    }

    private func firstPixel(_ buffer: CVPixelBuffer) throws -> [UInt8] {
        XCTAssertEqual(CVPixelBufferLockBaseAddress(buffer, .readOnly), kCVReturnSuccess)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        let bytes = try XCTUnwrap(CVPixelBufferGetBaseAddress(buffer)).assumingMemoryBound(to: UInt8.self)
        return Array(UnsafeBufferPointer(start: bytes, count: 4))
    }

    private func makeFrame(
        rotation: Int,
        timeStampNs: Int64 = 1_000
    ) throws -> LKRTCVideoFrame {
        var pixelBuffer: CVPixelBuffer?
        let status = CVPixelBufferCreate(
            kCFAllocatorDefault,
            8,
            8,
            kCVPixelFormatType_32BGRA,
            nil,
            &pixelBuffer
        )
        XCTAssertEqual(status, kCVReturnSuccess)
        guard let pixelBuffer else {
            throw NSError(domain: "WebRTCCameraFrameRelayTests", code: 1)
        }
        XCTAssertEqual(CVPixelBufferLockBaseAddress(pixelBuffer, []), kCVReturnSuccess)
        if let base = CVPixelBufferGetBaseAddress(pixelBuffer) {
            let rowBytes = CVPixelBufferGetBytesPerRow(pixelBuffer)
            for row in 0..<8 {
                let bytes = base.advanced(by: row * rowBytes).assumingMemoryBound(to: UInt8.self)
                for column in 0..<8 {
                    let offset = column * 4
                    bytes[offset] = 40
                    bytes[offset + 1] = 120
                    bytes[offset + 2] = 220
                    bytes[offset + 3] = 255
                }
            }
        }
        CVPixelBufferUnlockBaseAddress(pixelBuffer, [])
        let buffer = LKRTCCVPixelBuffer(pixelBuffer: pixelBuffer)
        let liveKitRotation = try XCTUnwrap(LKRTCVideoRotation(rawValue: rotation))
        return LKRTCVideoFrame(buffer: buffer, rotation: liveKitRotation, timeStampNs: timeStampNs)
    }
}

nonisolated private final class UnprocessedPreviewRecording: @unchecked Sendable {
    private let lock = NSLock()
    private var recordedBuffer: CVPixelBuffer?
    private var recordedRotation = 0

    var buffer: CVPixelBuffer? { lock.withLock { recordedBuffer } }
    var rotation: Int { lock.withLock { recordedRotation } }

    func record(_ buffer: CVPixelBuffer, rotation: Int) {
        lock.withLock {
            recordedBuffer = buffer
            recordedRotation = rotation
        }
    }
}

nonisolated private final class ColorRecordingVideoTarget: NSObject, LKRTCVideoCapturerDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var recordedFrame: LKRTCVideoFrame?
    var onFrame: (@Sendable () -> Void)?

    var lastFrame: LKRTCVideoFrame? {
        lock.withLock { recordedFrame }
    }

    func capturer(_ capturer: LKRTCVideoCapturer, didCapture frame: LKRTCVideoFrame) {
        lock.withLock { recordedFrame = frame }
        onFrame?()
    }
}

nonisolated private final class RecordingVideoTarget: NSObject, LKRTCVideoCapturerDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var recordedRotations: [Int] = []
    private var recordedTimeStamps: [Int64] = []
    private var recordedTimeStamps90kHz: [Int32] = []
    private var recordedWidths: [Int] = []

    var rotations: [Int] {
        lock.lock()
        defer { lock.unlock() }
        return recordedRotations
    }

    var timeStamps: [Int64] {
        lock.lock()
        defer { lock.unlock() }
        return recordedTimeStamps
    }

    var timeStamps90kHz: [Int32] {
        lock.lock()
        defer { lock.unlock() }
        return recordedTimeStamps90kHz
    }

    var widths: [Int] {
        lock.lock()
        defer { lock.unlock() }
        return recordedWidths
    }

    func capturer(_ capturer: LKRTCVideoCapturer, didCapture frame: LKRTCVideoFrame) {
        lock.lock()
        recordedRotations.append(frame.rotation.rawValue)
        recordedTimeStamps.append(frame.timeStampNs)
        recordedTimeStamps90kHz.append(frame.timeStamp)
        recordedWidths.append(Int(frame.width))
        lock.unlock()
    }
}
