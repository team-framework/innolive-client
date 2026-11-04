import AVFoundation
import CoreImage
import ImageIO
import UIKit
import XCTest

@testable import InnoLive

final class FaceSelectionTests: XCTestCase {
    private let portrait = CGSize(width: 1080, height: 1920)

    func testSquarePreviewShowsTheMiddleOfAPortraitFrame() {
        let visible = FaceSelectionGeometry.visibleRegion(imageSize: portrait, previewAspectRatio: 1)
        XCTAssertEqual(visible.minX, 0)
        XCTAssertEqual(visible.width, 1)
        XCTAssertEqual(visible.minY, 0.21875, accuracy: 0.0001)
        XCTAssertEqual(visible.height, 0.5625, accuracy: 0.0001)

        let landscape = FaceSelectionGeometry.visibleRegion(
            imageSize: CGSize(width: 1920, height: 1080),
            previewAspectRatio: 1
        )
        XCTAssertEqual(landscape.minX, 0.21875, accuracy: 0.0001)
        XCTAssertEqual(landscape.height, 1)
    }

    func testDisplayRectMatchesAspectFillPreview() {
        let rect = FaceSelectionGeometry.displayRect(
            for: CGRect(x: 0.25, y: 0.5, width: 0.5, height: 0.25),
            imageSize: portrait,
            in: CGSize(width: 300, height: 300)
        )
        XCTAssertEqual(rect.minX, 75, accuracy: 0.001)
        XCTAssertEqual(rect.minY, 150, accuracy: 0.001)
        XCTAssertEqual(rect.width, 150, accuracy: 0.001)
        XCTAssertEqual(rect.height, 133.333, accuracy: 0.001)
    }

    func testOnlyLargeVisibleFacesAreSelectable() {
        let candidates = FaceSelectionGeometry.candidates(for: [
            CGRect(x: 0.1, y: 0.4, width: 0.2, height: 0.12),    // 216 x 230px, 보임
            CGRect(x: 0.5, y: 0.4, width: 0.09, height: 0.06),   // 짧은 변 97px
            CGRect(x: 0.5, y: 0.5, width: 0.1, height: 0.06),    // 108 x 115px, 보임
            CGRect(x: 0.4, y: 0.02, width: 0.3, height: 0.17)    // 크지만 정사각형 미리보기보다 위에 있음
        ], imageSize: portrait)
        XCTAssertEqual(candidates.map(\.isSelectable), [true, false, true, false])
    }

    func testFullScreenPreviewChangesWhichFacesAreVisible() {
        let fullScreen: CGFloat = 393.0 / 852.0
        let edge = CGRect(x: 0, y: 0.45, width: 0.12, height: 0.07)   // 왼쪽 가장자리, 세로 가운데
        let top = CGRect(x: 0.45, y: 0.05, width: 0.12, height: 0.07)   // 위쪽, 가로 가운데
        let square = FaceSelectionGeometry.candidates(for: [edge, top], imageSize: portrait, previewAspectRatio: 1)
        let tall = FaceSelectionGeometry.candidates(for: [edge, top], imageSize: portrait, previewAspectRatio: fullScreen)
        XCTAssertEqual(square.map(\.isSelectable), [true, false])
        XCTAssertEqual(tall.map(\.isSelectable), [false, true])
    }

    func testGuestNameUsesMonthDayHourAndPassesNameRules() {
        let date = ISO8601DateFormatter().date(from: "2026-10-17T06:40:00Z")!
        let name = GuestFaceName.make(
            at: date,
            locale: Locale(identifier: "ko"),
            timeZone: TimeZone(identifier: "Asia/Seoul")!
        )
        XCTAssertEqual(name, "10월 17일 오후 3시 - \(String(localized: "게스트"))")
        XCTAssertTrue(ReferenceFaceName.isValid(name))
        for language in ["en", "ja"] {
            XCTAssertTrue(ReferenceFaceName.isValid(GuestFaceName.make(at: date, locale: Locale(identifier: language))))
        }
    }

    func testRegistrationCropDoublesTheLongerFaceSide() {
        let crop = FaceSelectionGeometry.registrationCropRect(around: CGRect(x: 100, y: 200, width: 80, height: 120))
        XCTAssertEqual(crop, CGRect(x: 20, y: 140, width: 240, height: 240))
    }

    func testRegistrationImageMasksOtherFacesButKeepsTheSelectedFace() throws {
        let frame = solidFrame(faces: [
            FaceSelectionCandidate(rect: CGRect(x: 0.45, y: 0.45, width: 0.1, height: 0.1), isSelectable: true),
            FaceSelectionCandidate(rect: CGRect(x: 0.56, y: 0.45, width: 0.05, height: 0.1), isSelectable: false),
            // 선택한 얼굴과 겹친다. 이 얼굴의 마스크가 선택한 얼굴을 가리면 안 된다.
            FaceSelectionCandidate(rect: CGRect(x: 0.38, y: 0.45, width: 0.08, height: 0.1), isSelectable: true)
        ])
        let image = try XCTUnwrap(FaceDetectionService().registrationImage(from: frame, faceIndex: 0))
        XCTAssertEqual(image.width, 500)
        XCTAssertEqual(image.height, 500)
        XCTAssertTrue(isRed(pixel(image, x: 250, y: 250)), "Selected face")
        XCTAssertTrue(isRed(pixel(image, x: 130, y: 250)), "Selected face edge under a neighbor mask")
        XCTAssertTrue(isBlack(pixel(image, x: 450, y: 250)), "Neighbor face")
        XCTAssertTrue(isBlack(pixel(image, x: 30, y: 250)), "Overlapping neighbor outside the selected face")
        XCTAssertTrue(isRed(pixel(image, x: 250, y: 20)), "Background above the face")
    }

    func testRegistrationImagePadsAreasOutsideTheFrameWithBlack() throws {
        let frame = solidFrame(faces: [
            FaceSelectionCandidate(rect: CGRect(x: 0, y: 0.45, width: 0.1, height: 0.1), isSelectable: true)
        ])
        let image = try XCTUnwrap(FaceDetectionService().registrationImage(from: frame, faceIndex: 0))
        XCTAssertTrue(isBlack(pixel(image, x: 40, y: 250)))
        XCTAssertTrue(isRed(pixel(image, x: 250, y: 250)))
    }

    func testRegistrationImageRejectsUnselectableFacesAndSmallFrames() {
        let service = FaceDetectionService()
        let frame = solidFrame(faces: [
            FaceSelectionCandidate(rect: CGRect(x: 0.45, y: 0.45, width: 0.05, height: 0.05), isSelectable: false)
        ])
        XCTAssertNil(service.registrationImage(from: frame, faceIndex: 0))
        XCTAssertNil(service.registrationImage(from: frame, faceIndex: 1))

        let small = FaceSelectionFrame(
            image: CIImage(color: .red).cropped(to: CGRect(x: 0, y: 0, width: 480, height: 640)),
            faces: [FaceSelectionCandidate(rect: CGRect(x: 0.3, y: 0.3, width: 0.4, height: 0.3), isSelectable: true)]
        )
        XCTAssertNil(service.registrationImage(from: small, faceIndex: 0))
    }

    /// git이 무시하는 PrivacyFaceFixtures 폴더에 선택적으로 넣는 공개 테스트 이미지를 쓴다.
    func testPublicFaceFixtureRegistersEachLargeFaceAsASingleFace() throws {
        let canvas = try fixtureCanvas()
        let service = FaceDetectionService()
        let frame = try XCTUnwrap(service.detectFaces(uprightImage: canvas))
        let selectable = frame.faces.indices.filter { frame.faces[$0].isSelectable }
        XCTAssertEqual(selectable.count, 2, "The two large faces are selectable; the small one is not")
        for face in frame.faces where face.isSelectable {
            XCTAssertGreaterThanOrEqual(face.rect.width * frame.pixelSize.width, FaceSelectionGeometry.minimumFaceSide)
        }

        let hasYuNet = Bundle.main.url(forResource: "PrivacyYuNet", withExtension: "mlmodelc") != nil
        for index in selectable {
            let data = try XCTUnwrap(service.registrationJPEG(from: frame, faceIndex: index), "face \(index)")
            let source = try XCTUnwrap(CGImageSourceCreateWithData(data as CFData, nil))
            let image = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
            XCTAssertEqual(image.width, 500)
            XCTAssertEqual(image.height, 500)
            if hasYuNet {
                // 서버 등록과 같은 YuNet 가중치, 0.9 점수, 얼굴 1개, 40px 규칙을 쓴다.
                XCTAssertNoThrow(try PrivacyYuNetDetector().face(in: image, enrollment: true), "face \(index)")
            }
        }
    }

    func testCameraOrientationsReportFacesInPreviewCoordinates() throws {
        let canvas = try fixtureCanvas()
        let service = FaceDetectionService()
        let upright = try XCTUnwrap(service.detectFaces(uprightImage: canvas)).faces.map(\.rect)
            .sorted { $0.minX < $1.minX }
        XCTAssertFalse(upright.isEmpty)

        // 카메라 버퍼는 센서 방향으로 들어온다. 전면 세로 프레임은 미리보기처럼 좌우 반전돼 있다.
        let cases: [(AVCaptureDevice.Position, CGImagePropertyOrientation)] = [(.front, .leftMirrored), (.back, .left)]
        for (position, sensorOrientation) in cases {
            let sensor = CIImage(cgImage: canvas).oriented(sensorOrientation)
            let buffer = try pixelBuffer(from: sensor)
            service.reset()
            let frame = try XCTUnwrap(service.detectFaces(
                pixelBuffer: buffer,
                cameraPosition: position,
                deviceOrientation: .portrait
            ))
            XCTAssertEqual(frame.pixelSize, CGSize(width: canvas.width, height: canvas.height))
            let rects = frame.faces.map(\.rect).sorted { $0.minX < $1.minX }
            XCTAssertEqual(rects.count, upright.count, "\(position.rawValue)")
            for (actual, expected) in zip(rects, upright) {
                XCTAssertEqual(actual.midX, expected.midX, accuracy: 0.02, "\(position.rawValue)")
                XCTAssertEqual(actual.midY, expected.midY, accuracy: 0.02, "\(position.rawValue)")
            }
        }
    }

    // MARK: - 도우미

    private func solidFrame(faces: [FaceSelectionCandidate]) -> FaceSelectionFrame {
        FaceSelectionFrame(
            image: CIImage(color: .red).cropped(to: CGRect(x: 0, y: 0, width: 1000, height: 1000)),
            faces: faces
        )
    }

    /// 세로 캔버스에 큰 얼굴 2개와 작은 얼굴 1개가 있다. 작은 얼굴은 왼쪽 얼굴의 등록용 crop 안에 있어서,
    /// 전체 프레임에서 놓치더라도 crop에서는 가려야 한다.
    private func fixtureCanvas() throws -> CGImage {
        let bundle = Bundle(for: FaceSelectionTests.self)
        guard let url = bundle.url(forResource: "face-selection-sample", withExtension: "jpg") else {
            throw XCTSkip("Optional public-image face fixture is not present")
        }
        let source = try XCTUnwrap(CGImageSourceCreateWithURL(url as CFURL, nil))
        let photo = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
        let detected = try XCTUnwrap(FaceDetectionService().detectFaces(uprightImage: photo)?.faces.first?.rect)
        let photoSize = CGSize(width: photo.width, height: photo.height)
        let faceBox = CGRect(
            x: detected.minX * photoSize.width, y: detected.minY * photoSize.height,
            width: detected.width * photoSize.width, height: detected.height * photoSize.height
        )
        let tile = try XCTUnwrap(photo.cropping(to: FaceSelectionGeometry.scaled(faceBox, by: 1.3).integral))
        let tileAspectRatio = CGFloat(tile.width) / CGFloat(tile.height)

        let width = Int(portrait.width), height = Int(portrait.height)
        let context = try XCTUnwrap(CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ))
        context.setFillColor(gray: 0.5, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let large = CGSize(width: 260 * tileAspectRatio, height: 260)
        context.draw(tile, in: CGRect(x: 150, y: 830, width: large.width, height: large.height))
        context.draw(tile, in: CGRect(x: 640, y: 830, width: large.width, height: large.height))
        context.draw(tile, in: CGRect(x: 380, y: 1065, width: 90 * tileAspectRatio, height: 90))
        return try XCTUnwrap(context.makeImage())
    }

    private func pixelBuffer(from image: CIImage) throws -> CVPixelBuffer {
        var buffer: CVPixelBuffer?
        let status = CVPixelBufferCreate(
            kCFAllocatorDefault, Int(image.extent.width), Int(image.extent.height),
            kCVPixelFormatType_32BGRA, [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &buffer
        )
        let result = try XCTUnwrap(buffer)
        XCTAssertEqual(status, kCVReturnSuccess)
        let origin = image.transformed(by: CGAffineTransform(
            translationX: -image.extent.minX,
            y: -image.extent.minY
        ))
        CIContext().render(origin, to: result)
        return result
    }

    private func pixel(_ image: CGImage, x: Int, y: Int) -> [UInt8] {
        var data = [UInt8](repeating: 0, count: 4)
        let context = CGContext(
            data: &data, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )!
        // 좌상단 기준 좌표로 요청한 픽셀을 1x1 context 위로 옮긴다.
        context.draw(image, in: CGRect(x: -x, y: -(image.height - 1 - y), width: image.width, height: image.height))
        return Array(data.prefix(3))
    }

    private func isRed(_ rgb: [UInt8]) -> Bool { rgb[0] > 200 && rgb[1] < 40 && rgb[2] < 40 }
    private func isBlack(_ rgb: [UInt8]) -> Bool { rgb.allSatisfy { $0 < 20 } }
}
