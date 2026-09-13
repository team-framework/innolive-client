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

    @MainActor
    func testUplinkKeepsLockForRecreatedRelay() {
        let uplink = WebRTCVideoUplink()
        uplink.applyBroadcastOrientationLock(.landscapeLeft)
        XCTAssertEqual(uplink.lockedBroadcastOrientation, .landscapeLeft)
        uplink.clearBroadcastOrientationLock()
        XCTAssertNil(uplink.lockedBroadcastOrientation)
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
        let buffer = LKRTCCVPixelBuffer(pixelBuffer: pixelBuffer)
        let liveKitRotation = try XCTUnwrap(LKRTCVideoRotation(rawValue: rotation))
        return LKRTCVideoFrame(buffer: buffer, rotation: liveKitRotation, timeStampNs: timeStampNs)
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
