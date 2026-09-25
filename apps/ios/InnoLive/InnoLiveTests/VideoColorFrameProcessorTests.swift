import CoreVideo
import XCTest
@preconcurrency import LiveKitWebRTC

@testable import InnoLive

final class VideoColorFrameProcessorTests: XCTestCase {
    override func setUp() {
        super.setUp()
        _ = LKRTCInitializeSSL()
    }

    func testNeutralSettingsKeepOriginalFrame() throws {
        let frame = try makeFrame(red: 220, green: 120, blue: 40)

        let result = try VideoColorFrameProcessor().process(frame, warmth: 0, saturation: 1)

        XCTAssertTrue(result === frame)
    }

    func testZeroSaturationProducesGrayscale() throws {
        let frame = try makeFrame(red: 220, green: 120, blue: 40)

        let result = try VideoColorFrameProcessor().process(frame, warmth: 0, saturation: 0)

        let pixel = try readPixel(result)
        XCTAssertLessThanOrEqual(abs(Int(pixel.red) - Int(pixel.green)), 3)
        XCTAssertLessThanOrEqual(abs(Int(pixel.green) - Int(pixel.blue)), 3)
        XCTAssertEqual(pixel.alpha, 255)
    }

    func testPositiveWarmthWarmsNeutralGrayAndNegativeCoolsIt() throws {
        let frame = try makeFrame(red: 128, green: 128, blue: 128)
        let processor = VideoColorFrameProcessor()

        let warm = try readPixel(processor.process(frame, warmth: 1, saturation: 1))
        let cool = try readPixel(processor.process(frame, warmth: -1, saturation: 1))

        XCTAssertGreaterThan(Int(warm.red) - Int(warm.blue), 5)
        XCTAssertGreaterThan(Int(cool.blue) - Int(cool.red), 5)
    }

    func testAdjustmentValuesAreClampedToSupportedRange() throws {
        let frame = try makeFrame(red: 180, green: 120, blue: 80)
        let processor = VideoColorFrameProcessor()

        let atLimit = try readPixel(processor.process(frame, warmth: 1, saturation: 2))
        let aboveLimit = try readPixel(processor.process(frame, warmth: 10, saturation: 10))
        XCTAssertEqual(
            [aboveLimit.red, aboveLimit.green, aboveLimit.blue],
            [atLimit.red, atLimit.green, atLimit.blue]
        )
    }

    func testNonFiniteAdjustmentIsRejected() throws {
        let frame = try makeFrame(red: 180, green: 120, blue: 80)
        let processor = VideoColorFrameProcessor()

        XCTAssertThrowsError(try processor.process(frame, warmth: .nan, saturation: 1)) { error in
            guard case VideoColorFrameProcessor.ProcessingError.invalidAdjustment = error else {
                return XCTFail("Expected invalid adjustment for NaN warmth")
            }
        }
        XCTAssertThrowsError(try processor.process(frame, warmth: 0, saturation: .infinity)) { error in
            guard case VideoColorFrameProcessor.ProcessingError.invalidAdjustment = error else {
                return XCTFail("Expected invalid adjustment for infinite saturation")
            }
        }
    }

    func testProcessedFramePreservesGeometryAndTiming() throws {
        let frame = try makeFrame(red: 200, green: 100, blue: 50, rotation: 90)
        frame.timeStamp = 987

        let result = try VideoColorFrameProcessor().process(frame, warmth: -0.5, saturation: 1.2)

        XCTAssertFalse(result === frame)
        XCTAssertEqual(result.rotation, frame.rotation)
        XCTAssertEqual(result.timeStampNs, frame.timeStampNs)
        XCTAssertEqual(result.timeStamp, frame.timeStamp)
        XCTAssertEqual(result.width, frame.width)
        XCTAssertEqual(result.height, frame.height)
        XCTAssertEqual(
            CVPixelBufferGetPixelFormatType(try XCTUnwrap((result.buffer as? LKRTCCVPixelBuffer)?.pixelBuffer)),
            kCVPixelFormatType_32BGRA
        )
    }

    private func makeFrame(
        red: UInt8,
        green: UInt8,
        blue: UInt8,
        rotation: Int = 0
    ) throws -> LKRTCVideoFrame {
        var pixelBuffer: CVPixelBuffer?
        let status = CVPixelBufferCreate(
            kCFAllocatorDefault,
            8,
            8,
            kCVPixelFormatType_32BGRA,
            [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary,
            &pixelBuffer
        )
        XCTAssertEqual(status, kCVReturnSuccess)
        let output = try XCTUnwrap(pixelBuffer)
        XCTAssertEqual(CVPixelBufferLockBaseAddress(output, []), kCVReturnSuccess)
        defer { CVPixelBufferUnlockBaseAddress(output, []) }
        let base = try XCTUnwrap(CVPixelBufferGetBaseAddress(output))
        let rowBytes = CVPixelBufferGetBytesPerRow(output)
        for row in 0..<8 {
            let bytes = base.advanced(by: row * rowBytes).assumingMemoryBound(to: UInt8.self)
            for column in 0..<8 {
                let offset = column * 4
                bytes[offset] = blue
                bytes[offset + 1] = green
                bytes[offset + 2] = red
                bytes[offset + 3] = 255
            }
        }
        return LKRTCVideoFrame(
            buffer: LKRTCCVPixelBuffer(pixelBuffer: output),
            rotation: try XCTUnwrap(LKRTCVideoRotation(rawValue: rotation)),
            timeStampNs: 123_456_789
        )
    }

    private func readPixel(_ frame: LKRTCVideoFrame) throws -> (red: UInt8, green: UInt8, blue: UInt8, alpha: UInt8) {
        let pixelBuffer = try XCTUnwrap((frame.buffer as? LKRTCCVPixelBuffer)?.pixelBuffer)
        XCTAssertEqual(CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly), kCVReturnSuccess)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
        let bytes = try XCTUnwrap(CVPixelBufferGetBaseAddress(pixelBuffer)).assumingMemoryBound(to: UInt8.self)
        return (bytes[2], bytes[1], bytes[0], bytes[3])
    }
}
