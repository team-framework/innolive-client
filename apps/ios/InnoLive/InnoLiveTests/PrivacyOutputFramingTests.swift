#if DEBUG
import CoreImage
import XCTest
@testable import InnoLive

final class PrivacyOutputFramingTests: XCTestCase {
    private let context = CIContext()
    private let colorSpace = CGColorSpaceCreateDeviceRGB()

    private func pixel(_ image: CIImage, _ x: CGFloat, _ y: CGFloat) -> [UInt8] {
        var bytes = [UInt8](repeating: 0, count: 4)
        context.render(image, toBitmap: &bytes, rowBytes: 4,
                       bounds: CGRect(x: x, y: y, width: 1, height: 1), format: .RGBA8, colorSpace: colorSpace)
        return bytes
    }

    func testEdgesAreRemovedWithoutChangingResolutionOrOriginalPixels() {
        for size in [CGSize(width: 1280, height: 720), CGSize(width: 1920, height: 1080),
                     CGSize(width: 720, height: 1280), CGSize(width: 1080, height: 1920)] {
            let extent = CGRect(origin: .zero, size: size)
            let border = CIImage(color: .red).cropped(to: extent)
            let center = CIImage(color: .green).cropped(to: extent.insetBy(dx: size.width * 0.02, dy: size.height * 0.02))
            let original = center.composited(over: border)
            let output = PrivacyOutputFraming.apply(to: original)
            XCTAssertEqual(output.extent, extent)
            XCTAssertEqual(pixel(original, 0, 0), [255, 0, 0, 255])
            for point in [CGPoint(x: 0, y: 0), CGPoint(x: size.width - 1, y: 0),
                          CGPoint(x: 0, y: size.height - 1), CGPoint(x: size.width - 1, y: size.height - 1)] {
                XCTAssertEqual(pixel(output, point.x, point.y), [0, 255, 0, 255])
            }
        }
    }

    func testMaskAndPictureMoveTogetherAtExactlyOnePointZeroFiveScale() {
        let extent = CGRect(x: 0, y: 0, width: 1000, height: 1000)
        let original = CIImage(color: .green).cropped(to: extent)
        // Stand-in for an already composited anonymization region.
        let protected = CIImage(color: .blue).cropped(to: CGRect(x: 600, y: 400, width: 100, height: 200))
            .composited(over: original)
        let output = PrivacyOutputFraming.apply(to: protected)
        // x=600...700 maps to 605...710 about the frame center, with no second mask transform.
        XCTAssertEqual(pixel(output, 602, 500), [0, 255, 0, 255])
        XCTAssertEqual(pixel(output, 608, 500), [0, 0, 255, 255])
        XCTAssertEqual(pixel(output, 707, 500), [0, 0, 255, 255])
        XCTAssertEqual(pixel(output, 713, 500), [0, 255, 0, 255])
    }

    func testRotationsAndNonzeroOriginsPreserveCenteredCrop() {
        let extent = CGRect(x: 20, y: 40, width: 320, height: 180)
        let original = CIImage(color: .red).cropped(to: extent)
        let marker = CIImage(color: .blue).cropped(to: CGRect(x: 210, y: 100, width: 20, height: 20))
            .composited(over: original)
        XCTAssertEqual(PrivacyOutputFraming.apply(to: marker).extent, extent)
        for rotation in [0, 90, 180, 270] {
            let orientation = PrivacyUplinkProcessor.orientation(rotation: rotation)
            let beforeRotation = PrivacyOutputFraming.apply(to: marker).oriented(orientation.reverse)
            let afterRotation = PrivacyOutputFraming.apply(to: marker.oriented(orientation.reverse))
            XCTAssertEqual(beforeRotation.extent, afterRotation.extent)
            let difference = beforeRotation.applyingFilter("CIDifferenceBlendMode", parameters: [kCIInputBackgroundImageKey: afterRotation])
                .applyingFilter("CIAreaMaximum", parameters: [kCIInputExtentKey: CIVector(cgRect: afterRotation.extent)])
            XCTAssertLessThanOrEqual(pixel(difference, 0, 0).prefix(3).max()!, 1)
        }
    }
}
#endif
