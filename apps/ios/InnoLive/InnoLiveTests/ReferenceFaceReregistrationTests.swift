import Foundation
import XCTest

@testable import InnoLive

@MainActor
final class ReferenceFaceReregistrationTests: XCTestCase {
    func testDecodesReregistrationMarksFromServerStatus() throws {
        let json = #"""
        {
          "registered": true,
          "source": "api",
          "registered_at": "2026-10-20T00:00:00Z",
          "client_id": "user-1",
          "count": 2,
          "reregistration_required": true,
          "faces": [
            {"face_id": "lost", "name": "진행자", "registered_at": "2026-10-19T00:00:00Z", "reregistration_required": true},
            {"face_id": "kept", "registered_at": "2026-10-20T00:00:00Z", "reregistration_required": false}
          ]
        }
        """#
        let status = try JSONDecoder().decode(ReferenceFaceStatus.self, from: Data(json.utf8))

        XCTAssertTrue(status.reregistrationRequired)
        XCTAssertEqual(status.faces.map(\.reregistrationRequired), [true, false])
        XCTAssertEqual(status.faces.first?.name, "진행자")
    }

    func testOlderServerWithoutMarksDecodesAsNotRequired() throws {
        let json = #"""
        {"registered": true, "source": null, "registered_at": null, "client_id": "user-1", "count": 1,
         "faces": [{"face_id": "server-id", "registered_at": "2026-09-21"}]}
        """#
        let status = try JSONDecoder().decode(ReferenceFaceStatus.self, from: Data(json.utf8))

        XCTAssertFalse(status.reregistrationRequired)
        XCTAssertEqual(status.faces.map(\.reregistrationRequired), [false])
        XCTAssertNil(status.source)
    }

    func testOnDeviceStatusNeverRequiresReregistration() {
        let status = ReferenceFaceStatus(registered: true, source: "on_device", registeredAt: nil,
                                         clientID: "local-device", count: 1,
                                         faces: [ReferenceFace(id: "local", name: "나", registeredAt: "")])
        XCTAssertFalse(status.reregistrationRequired)
        XCTAssertFalse(status.faces[0].reregistrationRequired)
    }

    func testReregistrationBodySendsFaceIDAndOneImagesFileWithoutName() throws {
        let jpeg = Data([0xFF, 0xD8, 0x01, 0x02, 0xFF, 0xD9])
        let body = ReferenceFaceReregistration.multipartBody(faceID: "face-1", jpegData: jpeg, boundary: "B")
        let text = String(decoding: body, as: UTF8.self)

        XCTAssertTrue(text.hasPrefix("--B\r\nContent-Disposition: form-data; name=\"face_id\"\r\n\r\nface-1\r\n"))
        XCTAssertEqual(text.components(separatedBy: "name=\"images\"; filename=\"reference-face.jpg\"").count, 2)
        XCTAssertFalse(text.contains("name=\"image\";"))
        XCTAssertFalse(text.contains("name=\"name\""))
        XCTAssertTrue(text.hasSuffix("\r\n--B--\r\n"))
        XCTAssertNotNil(body.range(of: jpeg))
    }

    func testMissingFaceErrorAsksToRefreshTheList() {
        let error = ReferenceFaceAPIError.api(code: "not_found", fallback: "기준 얼굴을 찾을 수 없습니다.")
        XCTAssertEqual(
            error.userMessage,
            Bundle.main.localizedString(forKey: "등록된 얼굴을 찾지 못했습니다. 목록을 새로고침해 주세요.", value: nil, table: nil)
        )
    }

    func testReregistrationTextsAreTranslatedInEachLanguage() throws {
        let keys = [
            "AI 서버 재시작으로 등록이 초기화되어, 다시 등록해야 블러에서 제외됩니다.",
            "다시 등록이 필요한 얼굴이 있어요",
            "다시 등록 필요",
            "다시 등록",
            "등록 얼굴 %lld 다시 등록",
            "얼굴 다시 등록",
        ]
        for language in ["ko", "en", "ja"] {
            let path = try XCTUnwrap(Bundle.main.path(forResource: language, ofType: "lproj"))
            let bundle = try XCTUnwrap(Bundle(path: path))
            for key in keys {
                let value = bundle.localizedString(forKey: key, value: "MISSING", table: nil)
                XCTAssertNotEqual(value, "MISSING", "\(language): \(key)")
                if language != "ko" {
                    XCTAssertNil(value.range(of: "[가-힣]", options: .regularExpression), "\(language): \(key)")
                }
            }
        }
    }
}
