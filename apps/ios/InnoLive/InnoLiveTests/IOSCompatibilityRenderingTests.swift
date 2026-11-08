import AuthenticationServices
import AVFoundation
import SwiftUI
import UIKit
import XCTest

@testable import InnoLive

/// 호스트 앱에서 도는 테스트로, 선택한 기기에서 실제 SwiftUI 뷰를 그리고 렌더링한 이미지를
/// xcresult에 남긴다. 계정 동작을 실행하거나 방송을 시작하지 않는다.
/// 네트워크를 쓰는 모델은 비어 있는 격리 세션을 쓴다. 얼굴 슬롯 테스트만 stub 호스트로 조회(GET)한다.
@MainActor
final class IOSCompatibilityRenderingTests: XCTestCase {
    func testSignInRendersInLightAndDarkAppearance() async throws {
        let fixture = RenderingFixture()
        defer { fixture.removePreferences() }

        for appearance in appearances {
            try await render("sign-in", appearance: appearance) {
                SignInView(authentication: fixture.authentication)
            } inspect: { window in
                let appleButton = try XCTUnwrap(
                    descendants(of: window).compactMap { $0 as? ASAuthorizationAppleIDButton }.first
                )
                assertVisible(appleButton, in: window)
                XCTAssertGreaterThanOrEqual(appleButton.bounds.height, 44)
                XCTAssertGreaterThanOrEqual(appleButton.bounds.width, 140)
                XCTAssertTrue(appleButton.isEnabled)
            }
        }
        XCTAssertNil(fixture.authentication.currentAccessToken())
    }

    func testEmailSignInRendersVisibleEditableFields() async throws {
        let fixture = RenderingFixture()
        defer { fixture.removePreferences() }

        for appearance in appearances {
            try await render("email-sign-in", appearance: appearance) {
                EmailAuthView(authentication: fixture.authentication)
            } inspect: { window in
                let fields = descendants(of: window).compactMap { $0 as? UITextField }
                let email = try XCTUnwrap(fields.first { $0.keyboardType == .emailAddress })
                let password = try XCTUnwrap(fields.first { $0.isSecureTextEntry })
                assertVisible(email, in: window)
                assertVisible(password, in: window)
                XCTAssertTrue(email.isEnabled)
                XCTAssertTrue(password.isEnabled)
                XCTAssertGreaterThanOrEqual(email.bounds.width, 120)
                XCTAssertLessThan(email.convert(email.bounds, to: window).maxY,
                                  password.convert(password.bounds, to: window).minY)
            }
        }
    }

    func testSettingsRendersInLightAndDarkAppearance() async throws {
        let fixture = RenderingFixture()
        defer { fixture.removePreferences() }

        for appearance in appearances {
            try await render("settings", appearance: appearance, title: String(localized: "앱 정보 및 계정")) {
                SettingsView(authentication: fixture.authentication, youtube: fixture.youtube)
                    .environment(fixture.camera)
            }
        }
    }

    func testCameraAndAudioSettingsRenderWithoutStartingCapture() async throws {
        let fixture = RenderingFixture()
        let preferenceKeys = ["selectedCameraID", "selectedAudioID", "selectedResolution"]
        let originalPreferences = preferenceKeys.map { ($0, UserDefaults.standard.object(forKey: $0)) }
        let audio = AVAudioSession.sharedInstance()
        let originalCategory = audio.category
        let originalMode = audio.mode
        let originalOptions = audio.categoryOptions
        defer {
            for (key, value) in originalPreferences {
                if let value { UserDefaults.standard.set(value, forKey: key) }
                else { UserDefaults.standard.removeObject(forKey: key) }
            }
            try? audio.setCategory(originalCategory, mode: originalMode, options: originalOptions)
            fixture.removePreferences()
        }

        for appearance in appearances {
            try await render("camera-audio", appearance: appearance,
                             title: String(localized: "카메라 및 오디오")) {
                CameraAudioSettingsView(youtube: fixture.youtube)
                    .environment(fixture.camera)
            }
            XCTAssertFalse(fixture.camera.session.isRunning)
            XCTAssertFalse(fixture.youtube.videoUplink.isCapturingMedia)
            XCTAssertFalse(fixture.youtube.videoUplink.isActive)
        }
        await fixture.camera.stopSession()
    }

    func testAISettingsRenderInLightAndDarkAppearance() async throws {
        let fixture = RenderingFixture()
        defer { fixture.removePreferences() }

        for appearance in appearances {
            try await render("ai-settings", appearance: appearance,
                             title: String(localized: "AI & Faces Setting")) {
                AISettingsView(authentication: fixture.authentication, youtube: fixture.youtube)
                    .environment(fixture.camera)
            }
        }
    }

    func testBroadcastSettingsRenderEditableTitleAndDescription() async throws {
        let fixture = RenderingFixture()
        defer { fixture.removePreferences() }

        for appearance in appearances {
            try await render("broadcast-settings", appearance: appearance,
                             title: String(localized: "방송 설정")) {
                BroadcastSettingsView(authentication: fixture.authentication, youtube: fixture.youtube)
            } inspect: { window in
                let resolution = try XCTUnwrap(descendants(of: window).compactMap { $0 as? UISegmentedControl }
                    .first { $0.numberOfSegments == 2 && $0.titleForSegment(at: 1) == "FHD" })
                assertVisible(resolution, in: window)
                XCTAssertEqual(resolution.titleForSegment(at: 0), "720p")
                let title = try XCTUnwrap(descendants(of: window).compactMap { $0 as? UITextField }.first)
                let description = try XCTUnwrap(descendants(of: window).compactMap { $0 as? UITextView }.first)
                assertVisible(title, in: window)
                // 화질 선택이 위에 있어 설명 입력란은 작은 화면에서 첫 화면 아래로 내려갈 수 있다.
                let scroll = try XCTUnwrap(descendants(of: window).compactMap { $0 as? UIScrollView }
                    .first { $0 !== description && $0.bounds.width > 200 && $0.bounds.height > 200 })
                scroll.scrollRectToVisible(description.convert(description.bounds, to: scroll), animated: false)
                window.layoutIfNeeded()
                assertVisible(description, in: window)
                XCTAssertTrue(title.isEnabled)
                XCTAssertTrue(description.isEditable)
                XCTAssertGreaterThan(description.bounds.height, 44)
            }
        }
        XCTAssertFalse(fixture.youtube.videoUplink.isActive)
    }

    func testAccountSettingsRenderWithoutChangingAuthentication() async throws {
        let fixture = RenderingFixture()
        defer { fixture.removePreferences() }

        for appearance in appearances {
            try await render("account-settings", appearance: appearance,
                             title: String(localized: "계정 설정")) {
                AccountSettingsView(authentication: fixture.authentication, youtube: fixture.youtube)
            }
        }
        XCTAssertFalse(fixture.authentication.isDeletingAccount)
        XCTAssertNil(fixture.authentication.currentAccessToken())
    }

    func testBroadcastControlsRenderOverPreviewInLightAndDarkAppearance() async throws {
        let fixture = RenderingFixture()
        defer { fixture.removePreferences() }

        for appearance in appearances {
            try await render("broadcast-controls", appearance: appearance) {
                ScrollView {
                    ZStack(alignment: .bottom) {
                        LinearGradient(colors: [.indigo, .teal, .black],
                                       startPoint: .topLeading, endPoint: .bottomTrailing)
                            .overlay(alignment: .top) {
                                Text(verbatim: "Preview placeholder")
                                    .font(.caption)
                                    .foregroundStyle(.white)
                                    .padding(.top, 24)
                            }
                        BroadcastControlsView(
                            isBroadcasting: .constant(false),
                            previewTransition: .constant(.none),
                            authentication: fixture.authentication,
                            youtube: fixture.youtube,
                            onPrepareBroadcast: { _ in XCTFail("Render tests must not connect media") },
                            onCancelPreparation: {}
                        )
                        .padding(.horizontal, 24)
                        .padding(.bottom, 12)
                    }
                    .frame(height: 600)
                }
                .environment(fixture.camera)
            }
        }
        XCTAssertFalse(fixture.camera.session.isRunning)
        XCTAssertFalse(fixture.youtube.videoUplink.isCapturingMedia)
        XCTAssertFalse(fixture.youtube.videoUplink.isActive)
    }

    func testChatControlsAtIPhone16Size() async throws {
        try await renderPhoneChatControls(name: "iphone-16-chat", size: CGSize(width: 393, height: 852))
    }

    func testChatControlsAtIPhone16ProSize() async throws {
        try await renderPhoneChatControls(name: "iphone-16-pro-chat", size: CGSize(width: 402, height: 874))
    }

    func testChatControlsAtIPhone16ProLandscapeSize() async throws {
        try await renderPhoneChatControls(name: "iphone-16-pro-chat-landscape", size: CGSize(width: 874, height: 402))
    }

    private func renderPhoneChatControls(name: String, size: CGSize) async throws {
        let fixture = RenderingFixture()
        defer { fixture.removePreferences() }
        for appearance in appearances {
            try await render(name, appearance: appearance, viewport: size) {
                GeometryReader { geometry in
                    ZStack(alignment: .bottom) {
                        LinearGradient(colors: [.indigo, .teal, .black], startPoint: .topLeading, endPoint: .bottomTrailing)
                        YouTubeChatMessageList(messages: [
                            YouTubeChatMessage(id: "sample-1", author: "시청자1", text: "안녕하세요! 😊"),
                            YouTubeChatMessage(id: "sample-2", author: "viewer", text: "화면과 소리 모두 잘 보여요"),
                            YouTubeChatMessage(id: "sample-3", author: "긴댓글", text: "댓글이 길어져도 이름과 본문이 화면 밖으로 잘리지 않고 여러 줄로 표시되어야 합니다.")
                        ])
                            .frame(maxWidth: 280)
                            .frame(height: size.width > size.height ? 150 : 180)
                            .padding(.horizontal, 24)
                            .padding(.bottom, size.width > size.height ? 24 : 160)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                        BroadcastControlsView(
                            isBroadcasting: .constant(false), previewTransition: .constant(.none),
                            authentication: fixture.authentication, youtube: fixture.youtube,
                            onPrepareBroadcast: { _ in XCTFail("Layout checks must not start broadcasts") },
                            onCancelPreparation: {}
                        )
                        .frame(maxWidth: size.width > size.height ? 400 : .infinity)
                        .padding(.horizontal, 24)
                        .padding(.bottom, 12)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                    }
                    .frame(height: geometry.size.height)
                }
                .environment(fixture.camera)
                .environment(\.horizontalSizeClass, .compact)
            } inspect: { window in
                XCTAssertEqual(window.bounds.size, size)
            }
        }
        XCTAssertFalse(fixture.camera.session.isRunning)
        XCTAssertFalse(fixture.youtube.videoUplink.isActive)
    }

    func testFaceManagementRendersUnauthenticatedStateWithoutStartingCapture() async throws {
        let fixture = RenderingFixture()
        defer { fixture.removePreferences() }

        for appearance in appearances {
            // 토큰 저장소가 비어 있어 ReferenceFaceAPI는 네트워크 요청 전에 멈춘다.
            // 등록 폼과 로그아웃 상태 오류만 확인하고, 저장된 얼굴은 확인하지 않는다.
            try await render("face-management-signed-out", appearance: appearance,
                             title: String(localized: "얼굴 관리")) {
                FaceManagementView(authentication: fixture.authentication,
                                   youtube: fixture.youtube, mode: .server)
                    .environment(fixture.camera)
            } inspect: { window in
                let name = try XCTUnwrap(descendants(of: window).compactMap { $0 as? UITextField }.first)
                assertVisible(name, in: window)
                XCTAssertTrue(name.isEnabled)
            }
        }
        XCTAssertFalse(fixture.camera.session.isRunning)
        XCTAssertFalse(fixture.youtube.videoUplink.isActive)
    }

    func testFaceManagementBlocksNewFaceOnlyWhenSparkFaceSlotsAreFull() async throws {
        let serverURLKey = "INNOLIVE_SERVER_URL"
        let originalServerURL = ProcessInfo.processInfo.environment[serverURLKey]
        setenv(serverURLKey, "https://\(FaceSlotURLProtocol.host)", 1)
        FaceSlotURLProtocol.reset()
        URLProtocol.registerClass(FaceSlotURLProtocol.self)
        defer {
            URLProtocol.unregisterClass(FaceSlotURLProtocol.self)
            FaceSlotURLProtocol.reset()
            if let originalServerURL { setenv(serverURLKey, originalServerURL, 1) } else { unsetenv(serverURLKey) }
        }
        XCTAssertEqual(AuthenticationConfiguration.serverURL(path: "/reference-face")?.host, FaceSlotURLProtocol.host,
                       "ReferenceFaceAPI must reach the stub, never a configured server")

        let spark = try sparkContractPlanAndUsage()
        let statusJSON = [1: referenceFaceStatusJSON(count: 1), 2: referenceFaceStatusJSON(count: 2)]
        var registeredFaceCount = 0
        FaceSlotURLProtocol.handler = { request in
            switch (request.httpMethod, request.url?.path) {
            case ("GET", "/users/me/plan"): return .init(status: 200, body: spark.plan)
            case ("GET", "/users/me/usage"): return .init(status: 200, body: spark.usage)
            case ("GET", "/reference-face"): return .init(status: 200, json: statusJSON[registeredFaceCount] ?? "")
            default: return .init(status: 404)
            }
        }

        let faceName = "진행자"
        let limitNotice = String(format: String(localized: "현재 요금제는 얼굴을 최대 %lld명까지 등록할 수 있습니다."), Int64(2))
        let otherDisableNotices = [String(localized: "얼굴을 등록하려면 카메라 권한이 필요합니다."),
                                   String(localized: "카메라 연결이 완료된 뒤 얼굴을 등록할 수 있어요.")]

        for appearance in appearances {
            // 1명은 같은 절차로 버튼이 켜지는 대조군이다. 이름 입력·카메라 권한·삭제 중 상태가 원인이 아님을 보인다.
            for faceCount in [1, 2] {
                registeredFaceCount = faceCount
                let fixture = RenderingFixture(serverHost: FaceSlotURLProtocol.host)
                defer { fixture.removePreferences() }
                await fixture.youtube.refreshPlan(accessToken: fixture.authentication.currentAccessToken())
                XCTAssertEqual(fixture.youtube.planStore.snapshot?.plan.plan, "spark")
                XCTAssertEqual(fixture.youtube.planStore.snapshot?.plan.faceSlots, 2)
                XCTAssertEqual(fixture.camera.authorizationStatus, .authorized,
                               "Grant the host app camera access with `xcrun simctl privacy <device> grant camera <bundle id>`")
                let uplink = fixture.youtube.videoUplink
                XCTAssertFalse(uplink.isSwitchingCamera || uplink.isReleasingCamera || uplink.isActive)

                try await render("face-management-spark-\(faceCount)-of-2-faces", appearance: appearance,
                                 title: String(localized: "얼굴 관리")) {
                    FaceManagementView(authentication: fixture.authentication,
                                       youtube: fixture.youtube, mode: .server)
                        .environment(fixture.camera)
                } settle: { window in
                    // 목록 조회(.task)가 끝나 상태 카드가 목록으로 바뀐 뒤에 입력해야 편집 중인 입력란 주변이 다시 배치되지 않는다.
                    try await waitUntil("\(faceCount) registered faces render", in: window) { items in
                        (1...faceCount + 1).map { index in
                            items.contains { $0.isButton && $0.label == String(localized: "등록 얼굴 \(index) 삭제") }
                        } == Array(repeating: true, count: faceCount) + [false]
                    }
                    let name = try XCTUnwrap(descendants(of: window).compactMap { $0 as? UITextField }.first)
                    let keyboard = KeyboardPresence()
                    defer { keyboard.stop() }
                    XCTAssertTrue(name.becomeFirstResponder())
                    name.insertText(faceName)
                    XCTAssertTrue(name.resignFirstResponder())
                    try await waitUntil("keyboard dismisses", in: window) { _ in !keyboard.isPresented }
                } inspect: { window in
                    let name = try XCTUnwrap(descendants(of: window).compactMap { $0 as? UITextField }.first)
                    XCTAssertEqual(name.text, faceName)
                    let items = accessibleItems(in: window)
                    XCTAssertFalse(items.contains { item in otherDisableNotices.contains { item.label.contains($0) } })
                    let deleteAll = items.filter { $0.isButton && $0.label == String(localized: "모두 삭제") }
                    XCTAssertEqual(deleteAll.count, 1)
                    XCTAssertTrue(try XCTUnwrap(deleteAll.first).isEnabled, "Face list must not be mid-deletion")
                    let register = items.filter { $0.isButton && $0.label == String(localized: "얼굴 등록") }
                    XCTAssertEqual(register.count, 1)
                    let registerButton = try XCTUnwrap(register.first)
                    if faceCount == 2 {
                        XCTAssertEqual(items.filter { $0.label == limitNotice }.count, 1)
                        XCTAssertFalse(registerButton.isEnabled)
                    } else {
                        XCTAssertFalse(items.contains { $0.label.contains(limitNotice) })
                        XCTAssertTrue(registerButton.isEnabled)
                    }
                }
                XCTAssertFalse(fixture.camera.session.isRunning)
                XCTAssertFalse(fixture.youtube.videoUplink.isActive)
            }
        }

        let requests = FaceSlotURLProtocol.requests
        XCTAssertEqual(Set(requests.map { "\($0.httpMethod ?? "") \($0.url?.path ?? "")" }),
                       ["GET /users/me/plan", "GET /users/me/usage", "GET /reference-face"],
                       "The fixture only reads the plan and face list; it never registers or deletes")
        XCTAssertTrue(requests.allSatisfy {
            $0.value(forHTTPHeaderField: "Authorization") == "Bearer \(RenderingFixture.accessToken)"
        })
    }

    func testQuickFaceRegistrationButtonRendersBelowVideoControls() async throws {
        let fixture = RenderingFixture()
        defer { fixture.removePreferences() }
        XCTAssertFalse(fixture.youtube.videoUplink.isFaceRegistrationCameraTransitioning)

        for appearance in appearances {
            // 카메라를 켜지 않고 홈 화면 오른쪽 위(top-trailing) 열을 그대로 재현한다.
            try await render("quick-face-registration-button", appearance: appearance) {
                ScrollView {
                    ZStack(alignment: .topTrailing) {
                        LinearGradient(colors: [.indigo, .teal, .black],
                                       startPoint: .topLeading, endPoint: .bottomTrailing)
                        VStack(alignment: .trailing, spacing: 12) {
                            Button {} label: {
                                Image(systemName: "camera.filters")
                                    .font(.title3.weight(.semibold))
                                    .frame(width: 44, height: 44)
                            }
                            .innoLiveGlassButtonStyle()
                            .buttonBorderShape(.circle)

                            QuickFaceRegistrationButton(videoUplink: fixture.youtube.videoUplink,
                                                        isSelecting: .constant(false),
                                                        isEnabled: true)
                        }
                        .padding(24)
                    }
                    .frame(height: 400)
                }
                .environment(fixture.camera)
            }
        }
        XCTAssertFalse(fixture.camera.session.isRunning)
        XCTAssertFalse(fixture.youtube.videoUplink.isActive)
    }

    func testFaceSelectionOutlinesRender() async throws {
        let imageSize = CGSize(width: 1080, height: 1920)
        let faces = [
            FaceSelectionCandidate(rect: CGRect(x: 0.12, y: 0.42, width: 0.26, height: 0.15), isSelectable: true,
                                   contour: (0...16).map { index in
                                       // 얼굴 박스 아래로 양쪽 귀를 잇는 턱 모양 호
                                       let angle = Double.pi * Double(index) / 16
                                       return CGPoint(x: 0.25 - 0.13 * cos(angle), y: 0.47 + 0.095 * sin(angle))
                                   }),
            FaceSelectionCandidate(rect: CGRect(x: 0.58, y: 0.44, width: 0.24, height: 0.14), isSelectable: true),
            FaceSelectionCandidate(rect: CGRect(x: 0.44, y: 0.66, width: 0.06, height: 0.035), isSelectable: false)
        ]
        for appearance in appearances {
            try await render("face-selection", appearance: appearance) {
                ScrollView {
                    VStack(spacing: 20) {
                        ZStack {
                            LinearGradient(colors: [.gray, .black], startPoint: .top, endPoint: .bottom)
                            FaceSelectionOverlay(faces: faces, imageSize: imageSize) { _ in }
                        }
                        .aspectRatio(FaceSelectionGeometry.previewAspectRatio, contentMode: .fit)
                        .clipShape(RoundedRectangle(cornerRadius: 32, style: .continuous))
                    }
                    .padding(24)
                }
                .background(Color.black)
            }
        }
    }

    func testSettingsRemainScrollableWithAccessibilityTextSize() async throws {
        let fixture = RenderingFixture()
        defer { fixture.removePreferences() }

        try await render("settings-accessibility-text", appearance: .light,
                         title: String(localized: "앱 정보 및 계정"), textSize: .accessibility3) {
            SettingsView(authentication: fixture.authentication, youtube: fixture.youtube)
                .environment(fixture.camera)
        } inspect: { window in
            let scroll = try XCTUnwrap(descendants(of: window).compactMap { $0 as? UIScrollView }
                .first { $0.bounds.width > 200 && $0.bounds.height > 200 })
            XCTAssertTrue(scroll.isScrollEnabled)
            XCTAssertLessThanOrEqual(scroll.contentSize.width, scroll.bounds.width + 1,
                                     "Larger text must not require horizontal scrolling")
        }
    }

    func testScreenModelsDeallocateInsideSynchronousTaskLocalScope() {
        weak var releasedAuthentication: AuthSession?
        weak var releasedController: BroadcastOrientationController?
        RenderingTaskContext.$isRendering.withValue(true) {
            let fixture = RenderingFixture()
            let controller = BroadcastOrientationController(appliesSceneUpdates: false)
            releasedAuthentication = fixture.authentication
            releasedController = controller
            fixture.removePreferences()
            withExtendedLifetime((fixture, controller)) {}
        }
        XCTAssertNil(releasedAuthentication)
        XCTAssertNil(releasedController)
    }

    private var appearances: [UIUserInterfaceStyle] { [.light, .dark] }

    private func render<Content: View>(
        _ name: String,
        appearance: UIUserInterfaceStyle,
        title: String? = nil,
        textSize: DynamicTypeSize = .large,
        viewport: CGSize? = nil,
        @ViewBuilder content: () -> Content,
        settle: (UIWindow) async throws -> Void = { _ in },
        inspect: (UIWindow) throws -> Void = { _ in }
    ) async throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive })
        let previousKeyWindow = scene.windows.first { $0.isKeyWindow }
        let controller = UIHostingController(rootView:
            NavigationStack { content() }
                .dynamicTypeSize(textSize)
                .environment(\.colorScheme, appearance == .dark ? .dark : .light)
        )
        let window = UIWindow(windowScene: scene)
        window.frame = viewport.map { CGRect(origin: .zero, size: $0) } ?? scene.coordinateSpace.bounds
        window.overrideUserInterfaceStyle = appearance
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer {
            window.endEditing(true)
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKeyAndVisible()
        }

        // SwiftUI의 첫 렌더링, 내비게이션 레이아웃, onAppear 작업이 돌도록 양보한다.
        try await Task.sleep(for: .milliseconds(350))
        let suffix = appearance == .dark ? "dark" : "light"
        do {
            try await settle(window)
        } catch {
            // 창을 내리기 전에 실패한 순간의 화면과 접근성 문구를 남긴다.
            _ = attachScreenshot(of: window, scale: scene.screen.scale, name: "\(name)-\(suffix)-settle-failure")
            let labels = XCTAttachment(string: accessibleItems(in: window).map(\.label).joined(separator: "\n"))
            labels.name = "\(name)-\(suffix)-settle-failure-accessibility"
            labels.lifetime = .keepAlways
            add(labels)
            throw error
        }
        controller.view.layoutIfNeeded()
        window.layoutIfNeeded()

        let views = descendants(of: window)
        let scrollView = try XCTUnwrap(views.compactMap { $0 as? UIScrollView }
            .first { $0.bounds.width > 200 && $0.bounds.height > 100 },
            "The primary scroll content must be laid out")
        assertVisible(scrollView, in: window, requireFullyVisible: false)
        XCTAssertGreaterThan(scrollView.contentSize.height, 100)
        if let title {
            let navigationBar = try XCTUnwrap(views.compactMap { $0 as? UINavigationBar }.first)
            XCTAssertEqual(navigationBar.topItem?.title, title)
            assertVisible(navigationBar, in: window)
        }

        let (screenshot, didDraw) = attachScreenshot(of: window, scale: scene.screen.scale, name: "\(name)-\(suffix)")
        XCTAssertTrue(didDraw, "UIKit must finish drawing the visible hierarchy")
        XCTAssertEqual(screenshot.size, window.bounds.size)
        let imageData = try XCTUnwrap(screenshot.pngData())
        XCTAssertGreaterThan(imageData.count, 10_000, "Screen capture must contain rendered UI")

        // 컨트롤 배치가 예상과 달라도 확인할 수 있도록 assert하기 전에 캡처한다.
        try inspect(window)
    }

    private func attachScreenshot(of window: UIWindow, scale: CGFloat, name: String) -> (image: UIImage, didDraw: Bool) {
        let format = UIGraphicsImageRendererFormat()
        format.scale = scale
        var didDraw = false
        let image = UIGraphicsImageRenderer(bounds: window.bounds, format: format).image { _ in
            didDraw = window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        let attachment = XCTAttachment(image: image)
        attachment.name = "\(name)-iOS-\(UIDevice.current.systemVersion)"
        attachment.lifetime = .keepAlways
        add(attachment)
        return (image, didDraw)
    }

    private func descendants(of view: UIView) -> [UIView] {
        [view] + view.subviews.flatMap { descendants(of: $0) }
    }

    /// SwiftUI 버튼과 문구는 UIKit 뷰가 아니라서 접근성 트리에서 문구와 비활성(notEnabled) 상태를 읽는다.
    private func accessibleItems(in object: NSObject) -> [AccessibleItem] {
        if let view = object as? UIView, view.isHidden || view.alpha < 0.01 { return [] }
        if object.isAccessibilityElement {
            return [AccessibleItem(label: object.accessibilityLabel ?? "", traits: object.accessibilityTraits)]
        }
        let count = object.accessibilityElementCount()
        let children: [NSObject]
        if let elements = object.accessibilityElements as? [NSObject] {
            children = elements
        } else if count != NSNotFound, count > 0 {
            children = (0..<count).compactMap { object.accessibilityElement(at: $0) as? NSObject }
        } else {
            children = (object as? UIView)?.subviews ?? []
        }
        return children.flatMap { accessibleItems(in: $0) }
    }

    private func waitUntil(_ description: String, in window: UIWindow, timeout: Duration = .seconds(5),
                           _ condition: ([AccessibleItem]) -> Bool) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: timeout)
        while true {
            window.layoutIfNeeded()
            if condition(accessibleItems(in: window)) { return }
            guard clock.now < deadline else { throw RenderingWaitTimeout(description: description) }
            try await Task.sleep(for: .milliseconds(50))
        }
    }

    private func sparkContractPlanAndUsage() throws -> (plan: Data, usage: Data) {
        let entries = try XCTUnwrap(JSONSerialization.jsonObject(
            with: ContractTestFixtures.data(named: "plan-usage-processing.v1")) as? [[String: Any]])
        let spark = try XCTUnwrap(entries.first { ($0["plan"] as? [String: Any])?["plan"] as? String == "spark" })
        return (try JSONSerialization.data(withJSONObject: XCTUnwrap(spark["plan"])),
                try JSONSerialization.data(withJSONObject: XCTUnwrap(spark["usage"])))
    }

    private func referenceFaceStatusJSON(count: Int) -> String {
        let faces = (1...count).map {
            #"{"face_id":"fixture-face-\#($0)","name":"슬롯 얼굴 \#($0)","registered_at":"2026-11-0\#($0)T00:00:00Z"}"#
        }
        return #"{"registered":true,"source":"api","registered_at":"2026-11-01T00:00:00Z","client_id":"fixture-user","count":\#(count),"faces":[\#(faces.joined(separator: ","))]}"#
    }

    private func assertVisible(_ view: UIView, in window: UIWindow, requireFullyVisible: Bool = true,
                               file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(view.window === window, file: file, line: line)
        var ancestor: UIView? = view
        while let current = ancestor {
            XCTAssertFalse(current.isHidden, file: file, line: line)
            XCTAssertGreaterThan(current.alpha, 0.01, file: file, line: line)
            ancestor = current.superview
        }
        let frame = view.convert(view.bounds, to: window)
        XCTAssertGreaterThan(frame.width, 0, file: file, line: line)
        XCTAssertGreaterThan(frame.height, 0, file: file, line: line)
        if requireFullyVisible {
            XCTAssertTrue(window.bounds.insetBy(dx: -1, dy: -1).contains(frame),
                          "Control must fit within the display: \(frame)", file: file, line: line)
        } else {
            XCTAssertTrue(window.bounds.intersects(frame), file: file, line: line)
        }
    }
}

@MainActor
private final class RenderingFixture {
    // iOS 18의 isolated deinit 런타임 오류를 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    let suiteName = "com.framework.innolive.tests.ios-rendering.\(UUID().uuidString)"
    let defaults: UserDefaults
    let authentication: AuthSession
    let youtube: YouTubeIntegration
    let camera = CameraManager()
    /// JWT가 아니어서 PlanStore가 계정 ID를 얻지 못하고 디스크 캐시를 쓰지 않는다.
    static let accessToken = "face-slot-fixture-access"

    /// serverHost가 있으면 로그인한 상태로 그 호스트의 stub 서버에 요청한다. 없으면 로그아웃 상태로 네트워크를 쓰지 않는다.
    init(serverHost: String? = nil) {
        defaults = UserDefaults(suiteName: suiteName)!
        let consentStore = ConsentAcknowledgementStore(userDefaults: defaults)
        authentication = AuthSession(
            api: AuthenticationAPI(serverURLProvider: { _ in nil }),
            tokenStore: RenderingTokenStore(tokens: serverHost.map { _ in
                AuthenticationTokenPair(accessToken: Self.accessToken, refreshToken: "face-slot-fixture-refresh")
            }),
            consentStore: consentStore
        )
        youtube = YouTubeIntegration(
            preferencesStore: YouTubePreferencesStore(userDefaults: defaults),
            api: YouTubeAPI(serverURLProvider: { path in serverHost.flatMap { URL(string: "https://\($0)\(path)") } }),
            consentStore: consentStore,
            sessionStore: RenderingBroadcastSessionStore(),
            persistAIProcessingMode: { _ in },
            planStore: serverHost == nil ? nil : PlanStore(cacheURL: nil)
        )
    }

    func removePreferences() { defaults.removePersistentDomain(forName: suiteName) }
}

private final class RenderingTokenStore: AuthenticationTokenStoring {
    // iOS 18의 isolated deinit 런타임 오류를 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    private let tokens: AuthenticationTokenPair?

    init(tokens: AuthenticationTokenPair? = nil) {
        self.tokens = tokens
    }

    func load() -> AuthenticationTokenPair? { tokens }
    func save(_ tokens: AuthenticationTokenPair) throws { XCTFail("Render tests must not sign in") }
    func remove() { XCTFail("Render tests must not sign out") }
}

private final class RenderingBroadcastSessionStore: BroadcastSessionStoring {
    // iOS 18의 isolated deinit 런타임 오류를 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    func load(scope: BroadcastSessionScope) throws -> StoredBroadcastSession? { nil }
    func save(_ session: StoredBroadcastSession, scope: BroadcastSessionScope) throws {
        XCTFail("Render tests must not prepare a broadcast")
    }
    func remove(scope: BroadcastSessionScope) throws {
        XCTFail("Render tests must not remove a broadcast")
    }
}

/// 키보드가 올라왔다가 완전히 내려갔는지 알린다. 하드웨어 키보드처럼 올라오지 않으면 처음부터 내려간 상태다.
@MainActor
private final class KeyboardPresence {
    // iOS 18의 isolated deinit 런타임 오류를 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    private(set) var isPresented = false
    private var observers: [NSObjectProtocol] = []

    init() {
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: UIResponder.keyboardWillShowNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.isPresented = true }
            },
            center.addObserver(forName: UIResponder.keyboardDidHideNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.isPresented = false }
            },
        ]
    }

    func stop() {
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers = []
    }
}

nonisolated private enum RenderingTaskContext {
    @TaskLocal static var isRendering = false
}

private struct AccessibleItem {
    let label: String
    let traits: UIAccessibilityTraits
    var isButton: Bool { traits.contains(.button) }
    var isEnabled: Bool { !traits.contains(.notEnabled) }
}

private struct RenderingWaitTimeout: LocalizedError {
    let description: String
    var errorDescription: String? { "Timed out waiting until \(description)" }
}

/// 이 호스트로 가는 요청만 가로챈다. 호스트 앱의 다른 요청은 건드리지 않는다.
nonisolated private final class FaceSlotURLProtocol: StubURLProtocol {
    static let host = "face-slots.invalid"
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == host }
    override class var fallbackResponse: Response { .failure(.badServerResponse) }
}
