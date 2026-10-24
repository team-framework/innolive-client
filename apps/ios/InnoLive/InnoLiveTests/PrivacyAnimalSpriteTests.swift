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

    /// ArcFace-like landmarks, eyes 100 px apart, rotated by `roll`.
    private func face(_ center: CGPoint, roll: Double = 0) -> [CGPoint] {
        let template: [(Double, Double)] = [(-0.5, 0), (0.5, 0), (0, 0.57), (-0.41, 1.16), (0.41, 1.16)]
        return template.map { (x: Double, y: Double) -> CGPoint in
            let dx: Double = 100 * (x * cos(roll) - y * sin(roll))
            let dy: Double = 100 * (x * sin(roll) + y * cos(roll))
            return CGPoint(x: center.x + CGFloat(dx), y: center.y + CGFloat(dy))
        }
    }

    func testBlurAndNoFacesKeepProtectedImage() {
        let compositor = PrivacyAnimalSprite()
        let image = CIImage(color: .black).cropped(to: CGRect(x: 0, y: 0, width: 128, height: 128))
        XCTAssertTrue(compositor.composite(image, faces: [(CGRect(x: 32, y: 32, width: 64, height: 64), 1)],
                                           source: image, mask: image, animal: .blur) === image)
        XCTAssertTrue(compositor.composite(image, faces: [], source: image, mask: image, animal: .rabbit) === image)
        // Untracked faces keep the blur, as face swap does.
        XCTAssertTrue(compositor.composite(image, faces: [(CGRect(x: 32, y: 32, width: 64, height: 64), nil)],
                                           source: image, mask: image, animal: .rabbit) === image)
    }

    func testBundledAtlasesCarryYawViewsWithTemplateLandmarks() throws {
        for name in ["rabbit", "owl"] {
            let atlas = try atlas(name)
            XCTAssertTrue(atlas.mirrorNegativeYaw)
            XCTAssertEqual(atlas.views.map(\.yaw), stride(from: 0.0, through: 90, by: 10).map { $0 })
            let front = atlas.views[0].landmarks
            XCTAssertEqual(front[0].y, front[1].y, accuracy: 1e-3)
            XCTAssertGreaterThan(front[3].y, front[2].y)
        }
    }

    func testLandmarksAlignTheAnimalTemplateWithRoll() throws {
        let atlas = try atlas("rabbit")
        let landmarks = face(CGPoint(x: 400, y: 300), roll: 0.2)
        let place = try XCTUnwrap(atlas.place(landmarks: landmarks, yaw: 0))
        let cell = place.from.cell.size, eye = place.from.landmarks[0]
        let x = eye.x * cell.width, y = eye.y * cell.height
        XCTAssertEqual(place.a * x - place.b * y + place.tx, landmarks[0].x, accuracy: 6)
        XCTAssertEqual(place.b * x + place.a * y + place.ty, landmarks[0].y, accuracy: 6)
        XCTAssertEqual(atan2(place.b, place.a), 0.2, accuracy: 0.03)
        let mirrored = try XCTUnwrap(atlas.place(landmarks: landmarks, yaw: -50))
        XCTAssertTrue(mirrored.mirrored)
        XCTAssertEqual(try XCTUnwrap(atlas.place(landmarks: landmarks, yaw: 25)).blend, 0.5, accuracy: 1e-9)
    }

    func testFaceSwapTargetRules() {
        let box = CGRect(x: 100, y: 100, width: 100, height: 120)
        XCTAssertEqual(PrivacyAnimalTargets.roi(box, frame: CGSize(width: 1280, height: 250)),
                       CGRect(x: 64, y: 64, width: 172, height: 186))
        XCTAssertNil(PrivacyAnimalTargets.roi(CGRect(x: 0, y: 0, width: 15, height: 40), frame: CGSize(width: 100, height: 100)))
        let near = [CGPoint](repeating: CGPoint(x: 1, y: 1), count: 5)
        XCTAssertEqual(PrivacyAnimalTargets.match(box, candidates: [(CGRect(x: 300, y: 100, width: 100, height: 120), []),
                                                                   (CGRect(x: 105, y: 110, width: 100, height: 115), near)]), near)
        XCTAssertNil(PrivacyAnimalTargets.match(box, candidates: [(CGRect(x: 170, y: 100, width: 100, height: 120), near)]))
    }

    func testLandmarkCacheRefreshesEveryThreeFramesAndFollowsTheBox() {
        let cache = PrivacyAnimalLandmarkCache()
        var runs = 0
        for frame in 0..<7 {
            cache.beginFrame(liveTracks: [1])
            let box = CGRect(x: 5 * CGFloat(frame), y: 0, width: 40, height: 40)
            let points = cache.landmarks(track: 1, box: box) { runs += 1; return [CGPoint](repeating: CGPoint(x: 10, y: 20), count: 5) }
            if frame < 3 { XCTAssertEqual(points?[0].x ?? 0, 10 + 5 * CGFloat(frame), accuracy: 1e-6) }
        }
        XCTAssertEqual(runs, 3)
        cache.beginFrame(liveTracks: [])
        cache.beginFrame(liveTracks: [1])
        _ = cache.landmarks(track: 1, box: CGRect(x: 0, y: 0, width: 40, height: 40)) { runs += 1; return nil }
        XCTAssertEqual(runs, 4)
        cache.beginFrame(liveTracks: [1])
        XCTAssertNil(cache.landmarks(track: 1, box: CGRect(x: 0, y: 0, width: 40, height: 40)) { runs += 1; return [] })
        XCTAssertEqual(runs, 4, "A failed refresh keeps the blur until the next refresh")
    }

    func testPoseRecoversYawSign() throws {
        for yaw in [-70.0, -20, 0, 35, 60] {
            let r = yaw * .pi / 180
            let template: [(Double, Double, Double)] = [(-0.5, 0, 0), (0.5, 0, 0), (0, 0.55, 0.4), (-0.35, 0.3, 0.8), (0.35, 0.3, 0.8)]
            let points = template.map { (x: Double, depth: Double, y: Double) -> CGPoint in
                let px: Double = 100 * (x * cos(r) + depth * sin(r))
                return CGPoint(x: px, y: 100 * y)
            }
            XCTAssertEqual(try XCTUnwrap(PrivacyAnimalPose.yaw(points)), yaw, accuracy: 0.5)
        }
    }

    func testAnimalsAreLabMethodsForOnDeviceOnly() {
        XCTAssertEqual(FaceAnonymizationMode(rawValue: "rabbit"), .rabbit)
        XCTAssertEqual(FaceAnonymizationMode.allCases.map(\.rawValue), ["blur", "face_swap", "rabbit", "owl"])
        XCTAssertNil(YouTubeSessionCreateRequest(mode: .server, faceAnonymization: .rabbit).faceAnonymization)
        XCTAssertNil(YouTubeSessionCreateRequest(mode: .onDevice, faceAnonymization: .owl).faceAnonymization)
    }
}
