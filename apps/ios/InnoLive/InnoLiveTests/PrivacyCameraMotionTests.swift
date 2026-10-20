#if DEBUG
import XCTest
import CoreImage
@testable import InnoLive

final class PrivacyCameraMotionTests: XCTestCase {
    func testSparseOpticalFlowMeasuresTopLeftTranslation() {
        let image = texture()
        let layout = PrivacySegmentation.Letterbox(size: image.extent.size)
        let motion = PrivacyCameraMotion()
        XCTAssertEqual(motion.estimate(image, layout: layout), .identity)
        let shifted = image.transformed(by: CGAffineTransform(translationX: 16, y: -8))
            .composited(over: CIImage(color: .black)).cropped(to: image.extent)
        let warp = motion.estimate(shifted, layout: layout)
        XCTAssertEqual(warp.tx, 40, accuracy: 2)
        XCTAssertEqual(warp.ty, 20, accuracy: 2)
        XCTAssertEqual(warp.a, 1, accuracy: 0.01)
        XCTAssertEqual(warp.d, 1, accuracy: 0.01)
        motion.reset()
        XCTAssertEqual(motion.estimate(shifted, layout: layout), .identity)
    }

    func testTexturelessFramesUseIdentityFallback() {
        let image = CIImage(color: .black).cropped(to: CGRect(x: 0, y: 0, width: 256, height: 192))
        let layout = PrivacySegmentation.Letterbox(size: image.extent.size)
        let motion = PrivacyCameraMotion()
        XCTAssertEqual(motion.estimate(image, layout: layout), .identity)
        XCTAssertEqual(motion.estimate(image, layout: layout), .identity)
    }

    func testLetterboxConjugationMatchesPointCoordinatesForPortraitRotation() {
        let size = CGSize(width: 720, height: 1280)
        let layout = PrivacySegmentation.Letterbox(size: size)
        let warp = CGAffineTransform(a: 0.99, b: 0.1, c: -0.1, d: 0.99, tx: 12, ty: -8)
        let model = PrivacyCameraMotion.modelTransform(warp, imageSize: size, layout: layout)
        let letterbox = CGAffineTransform(a: 0.5, b: 0, c: 0, d: 0.5, tx: 140, ty: 0)
        let point = CGPoint(x: 150, y: 300)
        let expected = point.applying(warp).applying(letterbox)
        let actual = point.applying(letterbox).applying(model)
        XCTAssertEqual(actual.x, expected.x, accuracy: 0.0001)
        XCTAssertEqual(actual.y, expected.y, accuracy: 0.0001)
    }

    func testChangingFrameSizeResetsOpticalFlow() {
        let motion = PrivacyCameraMotion()
        let image = texture()
        _ = motion.estimate(image, layout: .init(size: image.extent.size))
        let resized = image.transformed(by: CGAffineTransform(scaleX: 2, y: 2))
        XCTAssertEqual(motion.estimate(resized, layout: .init(size: resized.extent.size)), .identity)
    }

    private func texture() -> CIImage {
        let width = 256, height = 192
        var bytes = [UInt8](repeating: 255, count: width * height * 4)
        for y in 0..<height {
            for x in 0..<width {
                let value = UInt8(((x / 8 * 73) ^ (y / 8 * 137) ^ (x / 8 * y / 8 * 19)) & 255)
                for channel in 0..<3 { bytes[(y * width + x) * 4 + channel] = value }
            }
        }
        return CIImage(bitmapData: Data(bytes), bytesPerRow: width * 4,
                       size: CGSize(width: width, height: height), format: .RGBA8,
                       colorSpace: CGColorSpaceCreateDeviceRGB())
    }
}
#endif
