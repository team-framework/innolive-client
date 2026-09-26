#if DEBUG
import CoreML
import CoreImage
import XCTest
@testable import InnoLive

final class PrivacySegmentationTests: XCTestCase {
    func testClassAwareSuppressionKeepsPlateOverlappingFace() throws {
        let count = 3
        var raw = [Float](repeating: 0, count: 38 * count)
        for i in 0..<count {
            raw[i] = 320; raw[count + i] = 320
            raw[2 * count + i] = 80; raw[3 * count + i] = 80
        }
        raw[4 * count] = 0.9
        raw[4 * count + 1] = 0.8
        raw[5 * count + 2] = 0.7
        let found = try PrivacySegmentation.detections(raw, count: count)
        XCTAssertEqual(found.map(\.classID), [0, 1])
        XCTAssertEqual(found[0].box, CGRect(x: 280, y: 280, width: 80, height: 80))
    }

    func testInvalidOutputFailsInsteadOfShowingOriginal() {
        XCTAssertThrowsError(try PrivacySegmentation.detections([0], count: 1))
        var raw = [Float](repeating: 0, count: 38)
        raw[4] = 0.9; raw[0] = .nan; raw[2] = 80; raw[3] = 80
        XCTAssertThrowsError(try PrivacySegmentation.detections(raw, count: 1))
    }

    func testEmptyInstanceMaskProtectsDetectionBox() throws {
        let detection = PrivacySegmentation.Detection(box: CGRect(x: 40, y: 80, width: 16, height: 16),
                                                      score: 0.9, classID: 0,
                                                      coefficients: [Float](repeating: 0, count: 32))
        let mask = try PrivacySegmentation.unionMask(detections: [detection],
                                                     prototypes: [Float](repeating: 0, count: 32 * 160 * 160))
        XCTAssertEqual(mask.filter { $0 == 255 }.count, 16)
        XCTAssertEqual(mask[20 * 160 + 10], 255)
        XCTAssertEqual(mask[10 * 160 + 20], 0, "Mask coordinates must not be transposed")
    }

    func testLetterboxPreservesPortraitAndLandscapeGeometry() {
        let portrait = PrivacySegmentation.Letterbox(size: CGSize(width: 1080, height: 1920))
        XCTAssertEqual(portrait.resized, CGSize(width: 360, height: 640))
        XCTAssertEqual(portrait.left, 140)
        XCTAssertEqual(portrait.bottom, 0)
        let landscape = PrivacySegmentation.Letterbox(size: CGSize(width: 1920, height: 1080))
        XCTAssertEqual(landscape.resized, CGSize(width: 640, height: 360))
        XCTAssertEqual(landscape.left, 0)
        XCTAssertEqual(landscape.bottom, 140)
    }

    func testSoftEdgeDoesNotReducePreviouslyProtectedArea() throws {
        var bytes = [UInt8](repeating: 0, count: 160 * 160)
        for y in 40..<80 { for x in 40..<80 { bytes[y * 160 + x] = 255 } }
        let hard = try maskPixels(PrivacyMask.modelImage(bytes: bytes, feathered: false))
        let soft = try maskPixels(PrivacyMask.modelImage(bytes: bytes))
        for i in hard.indices {
            XCTAssertGreaterThanOrEqual(Int(soft[i]) + 1, Int(hard[i]))
        }
        XCTAssertTrue(soft.indices.contains { hard[$0] == 0 && soft[$0] > 0 && soft[$0] < 255 })
        XCTAssertEqual(soft[0], 0)
    }

    func testMaskTopLeftCoordinatesSurviveCoreImageConversion() throws {
        var bytes = [UInt8](repeating: 0, count: 160 * 160)
        for y in 20..<30 { for x in 10..<20 { bytes[y * 160 + x] = 255 } }
        let image = try PrivacyMask.modelImage(bytes: bytes)
        let context = CIContext(options: [.useSoftwareRenderer: true])
        // iOS 18 Core Image는 한 픽셀을 읽을 때도 행 바이트 수가 4의 배수여야 한다.
        var sample = [UInt8](repeating: 0, count: 4)
        context.render(image, toBitmap: &sample, rowBytes: 4,
                       bounds: CGRect(x: 15, y: 135, width: 1, height: 1), format: .L8,
                       colorSpace: CGColorSpaceCreateDeviceGray())
        XCTAssertEqual(sample[0], 255)
        context.render(image, toBitmap: &sample, rowBytes: 4,
                       bounds: CGRect(x: 15, y: 25, width: 1, height: 1), format: .L8,
                       colorSpace: CGColorSpaceCreateDeviceGray())
        XCTAssertEqual(sample[0], 0)
    }

    private func maskPixels(_ image: CIImage) throws -> [UInt8] {
        let context = CIContext(options: [.useSoftwareRenderer: true])
        var pixels = [UInt8](repeating: 0, count: 160 * 160)
        context.render(image, toBitmap: &pixels, rowBytes: 160,
                       bounds: CGRect(x: 0, y: 0, width: 160, height: 160), format: .L8,
                       colorSpace: CGColorSpaceCreateDeviceGray())
        return pixels
    }
}
#endif
