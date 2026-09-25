import CoreVideo
import XCTest

@testable import InnoLive

final class VideoLookPreviewRendererTests: XCTestCase {
    func testZeroSaturationMakesColorNeutral() throws {
        let renderer = VideoLookPreviewRenderer()
        let base = try XCTUnwrap(renderer.makeBaseImage(pixelBuffer: try makeBuffer(red: 220, green: 80, blue: 40), rotation: 0))

        let preview = try XCTUnwrap(renderer.makePreview(
            from: base,
            warmth: 0,
            saturation: 0,
            relativeExposureEV: 0
        ))
        let pixel = try centerPixel(preview)

        XCTAssertLessThanOrEqual(abs(Int(pixel.red) - Int(pixel.green)), 8)
        XCTAssertLessThanOrEqual(abs(Int(pixel.green) - Int(pixel.blue)), 8)
    }

    func testPositiveExposureIsBrighterThanNegative() throws {
        let renderer = VideoLookPreviewRenderer()
        let base = try XCTUnwrap(renderer.makeBaseImage(pixelBuffer: try makeBuffer(red: 90, green: 90, blue: 90), rotation: 0))
        let bright = try centerPixel(renderer.makePreview(from: base, warmth: 0, saturation: 1, relativeExposureEV: 1))
        let dark = try centerPixel(renderer.makePreview(from: base, warmth: 0, saturation: 1, relativeExposureEV: -1))

        XCTAssertGreaterThan(Int(bright.red) + Int(bright.green) + Int(bright.blue), Int(dark.red) + Int(dark.green) + Int(dark.blue))
    }

    func testWarmPreviewShiftsGrayTowardRed() throws {
        let renderer = VideoLookPreviewRenderer()
        let base = try XCTUnwrap(renderer.makeBaseImage(pixelBuffer: try makeBuffer(red: 128, green: 128, blue: 128), rotation: 0))
        let warm = try centerPixel(renderer.makePreview(from: base, warmth: 1, saturation: 1, relativeExposureEV: 0))
        let cool = try centerPixel(renderer.makePreview(from: base, warmth: -1, saturation: 1, relativeExposureEV: 0))

        XCTAssertGreaterThan(Int(warm.red) - Int(warm.blue), Int(cool.red) - Int(cool.blue))
    }

    private func makeBuffer(red: UInt8, green: UInt8, blue: UInt8) throws -> CVPixelBuffer {
        var pixelBuffer: CVPixelBuffer?
        let status = CVPixelBufferCreate(
            kCFAllocatorDefault,
            16,
            16,
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
        for row in 0..<16 {
            let bytes = base.advanced(by: row * rowBytes).assumingMemoryBound(to: UInt8.self)
            for column in 0..<16 {
                let offset = column * 4
                bytes[offset] = blue
                bytes[offset + 1] = green
                bytes[offset + 2] = red
                bytes[offset + 3] = 255
            }
        }
        return output
    }

    private func centerPixel(_ image: CGImage?) throws -> (red: UInt8, green: UInt8, blue: UInt8) {
        let image = try XCTUnwrap(image)
        var bytes = [UInt8](repeating: 0, count: image.width * image.height * 4)
        let context = CGContext(
            data: &bytes,
            width: image.width,
            height: image.height,
            bitsPerComponent: 8,
            bytesPerRow: image.width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )
        let output = try XCTUnwrap(context)
        output.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
        let offset = ((image.height / 2) * image.width + image.width / 2) * 4
        return (bytes[offset], bytes[offset + 1], bytes[offset + 2])
    }
}
