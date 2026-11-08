import XCTest

/// 앱을 별도 프로세스로 띄워 Debug 전용 `--face-slot-preview` 경로의 실제 얼굴 관리 화면을 접근성으로 조회한다.
/// 요청은 모두 앱 안의 모의 응답으로 끝나 서버·계정·요금제·얼굴을 바꾸지 않는다.
/// 실행 전에 `xcrun simctl privacy <device> grant camera com.framework.innolive`로 카메라 권한을 준다.
final class FaceSlotRegistrationUITests: XCTestCase {
    private let registerLabel = "얼굴 등록"
    private let limitMessage = "현재 요금제는 얼굴을 최대 2명까지 등록할 수 있습니다."
    private let otherDisableNotices = ["얼굴을 등록하려면 카메라 권한이 필요합니다.", "카메라 연결이 완료된 뒤 얼굴을 등록할 수 있어요."]
    private let faceName = "진행자"

    func testOneOfTwoFaceSlotsWithNameEnablesRegistration() throws {
        try forEachAppearance(registeredFaces: 1) { app in
            XCTAssertEqual(texts(limitMessage, in: app).count, 0)
            XCTAssertTrue(try registerButton(in: app).isEnabled, "1 of 2 slots used, name entered, camera granted")
        }
    }

    func testTwoOfTwoFaceSlotsShowLimitAndDisableRegistration() throws {
        try forEachAppearance(registeredFaces: 2) { app in
            XCTAssertEqual(texts(limitMessage, in: app).count, 1)
            XCTAssertFalse(try registerButton(in: app).isEnabled, "Spark face_slots=2 is full")
        }
    }

    private func forEachAppearance(registeredFaces: Int, inspect: (XCUIApplication) throws -> Void) throws {
        continueAfterFailure = false
        let originalAppearance = XCUIDevice.shared.appearance
        addTeardownBlock { XCUIDevice.shared.appearance = originalAppearance }

        let appearances: [(XCUIDevice.Appearance, String)] = [(.light, "light"), (.dark, "dark")]
        for (appearance, suffix) in appearances {
            XCUIDevice.shared.appearance = appearance
            let app = XCUIApplication()
            app.launchArguments = ["--face-slot-preview=\(registeredFaces)", "-AppleLanguages", "(ko)", "-AppleLocale", "ko_KR"]
            app.launchEnvironment["INNOLIVE_SERVER_URL"] = "https://face-slots.invalid"
            app.launch()

            for index in 1...registeredFaces {
                XCTAssertTrue(app.buttons["등록 얼굴 \(index) 삭제"].waitForExistence(timeout: 15),
                              "Plan and face list fixtures must load")
            }
            XCTAssertFalse(app.buttons["등록 얼굴 \(registeredFaces + 1) 삭제"].exists)
            for notice in otherDisableNotices {
                XCTAssertEqual(texts(notice, in: app).count, 0,
                               "Only the slot limit may disable registration. Grant camera with simctl privacy.")
            }
            XCTAssertTrue(app.buttons["모두 삭제"].isEnabled, "Face list must not be mid-deletion")

            let name = app.textFields.element
            name.tap()
            name.typeText(faceName + "\n")
            XCTAssertEqual(name.value as? String, faceName)

            try inspect(app)

            let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
            screenshot.name = "face-management-spark-\(registeredFaces)-of-2-faces-\(suffix)"
            screenshot.lifetime = .keepAlways
            add(screenshot)
            app.terminate()
        }
    }

    private func registerButton(in app: XCUIApplication) throws -> XCUIElement {
        let buttons = app.buttons.matching(NSPredicate(format: "label == %@", registerLabel))
        XCTAssertEqual(buttons.count, 1)
        return try XCTUnwrap(buttons.allElementsBoundByIndex.first)
    }

    private func texts(_ label: String, in app: XCUIApplication) -> XCUIElementQuery {
        app.staticTexts.matching(NSPredicate(format: "label == %@", label))
    }
}
