import XCTest
import CoreImage
@testable import InnoLive

final class PrivacyAnimalSpriteTests: XCTestCase {
    func testBlurAndNoFacesKeepProtectedImage() {
        let compositor = PrivacyAnimalSprite()
        let image = CIImage(color: .black).cropped(to: CGRect(x: 0, y: 0, width: 128, height: 128))
        XCTAssertTrue(compositor.composite(image, boxes: [CGRect(x: 32, y: 32, width: 64, height: 64)], animal: .blur) === image)
        XCTAssertTrue(compositor.composite(image, boxes: [], animal: .rabbit) === image)
    }

    func testBundledAnimalsRenderOverProtectedImageWithinFrame() throws {
        let compositor = PrivacyAnimalSprite()
        let context = CIContext()
        let bounds = CGRect(x: 0, y: 0, width: 128, height: 128)
        let protected = CIImage(color: .black).cropped(to: bounds)
        for animal in [PrivacyAnimal.rabbit, .owl] {
            let output = compositor.composite(protected, boxes: [CGRect(x: 32, y: 32, width: 64, height: 64)], animal: animal)
            XCTAssertFalse(output === protected, "Bundled sprite failed to load: \(animal)")
            XCTAssertEqual(output.extent, bounds)
            let rendered = try XCTUnwrap(context.createCGImage(output, from: bounds))
            XCTAssertEqual(rendered.width, 128)
            XCTAssertEqual(rendered.height, 128)
        }
    }
}
