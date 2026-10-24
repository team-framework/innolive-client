import XCTest
import CoreImage
@testable import InnoLive

final class PrivacyAnimalSpriteTests: XCTestCase {
    private func atlas(_ name: String) throws -> PrivacyAnimalAtlas {
        let bundle = Bundle(for: PrivacyModel.self)
        let json = try Data(contentsOf: XCTUnwrap(bundle.url(forResource: name, withExtension: "json")))
        let image = try XCTUnwrap(CIImage(contentsOf: XCTUnwrap(bundle.url(forResource: name, withExtension: "png"))))
        return try XCTUnwrap(PrivacyAnimalAtlas(json: json, imageSize: image.extent.size))
    }

    func testBlurAndNoFacesKeepProtectedImage() {
        let compositor = PrivacyAnimalSprite()
        let image = CIImage(color: .black).cropped(to: CGRect(x: 0, y: 0, width: 128, height: 128))
        XCTAssertTrue(compositor.composite(image, faces: [(CGRect(x: 32, y: 32, width: 64, height: 64), 1)], animal: .blur) === image)
        XCTAssertTrue(compositor.composite(image, faces: [], animal: .rabbit) === image)
    }

    func testBundledAnimalsRenderOverProtectedImageWithinFrame() throws {
        let compositor = PrivacyAnimalSprite()
        let context = CIContext()
        let bounds = CGRect(x: 0, y: 0, width: 128, height: 128)
        let protected = CIImage(color: .black).cropped(to: bounds)
        for animal in [PrivacyAnimal.rabbit, .owl] {
            let output = compositor.composite(protected, faces: [(CGRect(x: 32, y: 32, width: 64, height: 64), 1)], animal: animal)
            XCTAssertFalse(output === protected, "Bundled sprite failed to load: \(animal)")
            XCTAssertEqual(output.extent, bounds)
            let rendered = try XCTUnwrap(context.createCGImage(output, from: bounds))
            XCTAssertEqual(rendered.width, 128)
            XCTAssertEqual(rendered.height, 128)
        }
    }

    func testBundledAtlasesCoverYawWithMirroring() throws {
        for name in ["rabbit", "owl"] {
            let atlas = try atlas(name)
            XCTAssertTrue(atlas.mirrorNegativeYaw)
            XCTAssertEqual(atlas.views.map(\.yaw), stride(from: 0.0, through: 90, by: 10).map { $0 })
        }
    }

    func testSideFacePlacementKeepsAspectAndMirrors() throws {
        let atlas = try atlas("rabbit")
        let side = try XCTUnwrap(atlas.place(box: CGRect(x: 0, y: 0, width: 120, height: 240), yaw: 90, roll: 0))
        let cell = atlas.views[0].cell.size
        XCTAssertEqual(side.canvas.width / side.canvas.height, cell.width / cell.height, accuracy: 1e-6)
        XCTAssertEqual(side.from.yaw, 90)
        let left = try XCTUnwrap(atlas.place(box: CGRect(x: 0, y: 0, width: 120, height: 240), yaw: -90, roll: 0))
        XCTAssertTrue(left.mirrored)
        XCTAssertEqual(left.anchor.x, 1 - side.anchor.x, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(atlas.place(box: CGRect(x: 0, y: 0, width: 100, height: 120), yaw: 25, roll: 0)).blend, 0.5, accuracy: 1e-9)
    }

    func testPoseRecoversYawSignAndRoll() throws {
        func landmarks(_ yaw: Double, roll: Double) -> [CGPoint] {
            let r = yaw * .pi / 180
            return [(-0.5, 0.0, 0.0), (0.5, 0, 0), (0, 0.55, 0.4), (-0.35, 0.3, 0.8), (0.35, 0.3, 0.8)].map { x, depth, y in
                let px = x * cos(r) + depth * sin(r)
                return CGPoint(x: 100 * (px * cos(roll) - y * sin(roll)), y: 100 * (px * sin(roll) + y * cos(roll)))
            }
        }
        for yaw in [-70.0, -20, 0, 35, 60] {
            let pose = try XCTUnwrap(PrivacyAnimalPose.estimate(landmarks(yaw, roll: 0.2)))
            XCTAssertEqual(pose.yaw, yaw, accuracy: 0.5)
            XCTAssertEqual(pose.roll, 0.2, accuracy: 1e-6)
        }
        XCTAssertNil(PrivacyAnimalPose.estimate(landmarks(0, roll: 1.3)))
    }
}
