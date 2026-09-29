import AuthenticationServices
import AVFoundation
import SwiftUI
import UIKit
import XCTest

@testable import InnoLive

/// Hosted tests exercise the real SwiftUI views on the selected device and keep
/// their rendered images in the xcresult. They never activate account actions or
/// start a broadcast. Network-backed models use an empty, isolated session.
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
            try await render("settings", appearance: appearance, title: String(localized: "설정")) {
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
                let title = try XCTUnwrap(descendants(of: window).compactMap { $0 as? UITextField }.first)
                let description = try XCTUnwrap(descendants(of: window).compactMap { $0 as? UITextView }.first)
                assertVisible(title, in: window)
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
                        BroadcastControllsView(
                            isBroadcasting: .constant(false),
                            previewTransition: .constant(.none),
                            authentication: fixture.authentication,
                            youtube: fixture.youtube,
                            isStartingServerConnection: false,
                            onRetryConnection: { XCTFail("Render tests must not connect media") }
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

    func testFaceManagementRendersUnauthenticatedStateWithoutStartingCapture() async throws {
        let fixture = RenderingFixture()
        defer { fixture.removePreferences() }

        for appearance in appearances {
            // The empty token store stops ReferenceFaceAPI before any network request.
            // This covers the registration form and signed-out error, not stored faces.
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

    func testSettingsRemainScrollableWithAccessibilityTextSize() async throws {
        let fixture = RenderingFixture()
        defer { fixture.removePreferences() }

        try await render("settings-accessibility-text", appearance: .light,
                         title: String(localized: "설정"), textSize: .accessibility3) {
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
        @ViewBuilder content: () -> Content,
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
        window.frame = scene.coordinateSpace.bounds
        window.overrideUserInterfaceStyle = appearance
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKeyAndVisible()
        }

        // Yield to SwiftUI's first render, navigation layout, and onAppear tasks.
        try await Task.sleep(for: .milliseconds(350))
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

        let format = UIGraphicsImageRendererFormat()
        format.scale = scene.screen.scale
        let renderer = UIGraphicsImageRenderer(bounds: window.bounds, format: format)
        var didDraw = false
        let screenshot = renderer.image { _ in
            didDraw = window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        XCTAssertTrue(didDraw, "UIKit must finish drawing the visible hierarchy")
        XCTAssertEqual(screenshot.size, window.bounds.size)
        let imageData = try XCTUnwrap(screenshot.pngData())
        XCTAssertGreaterThan(imageData.count, 10_000, "Screen capture must contain rendered UI")
        let suffix = appearance == .dark ? "dark" : "light"
        let attachment = XCTAttachment(image: screenshot)
        attachment.name = "\(name)-\(suffix)-iOS-\(UIDevice.current.systemVersion)"
        attachment.lifetime = .keepAlways
        add(attachment)

        // Capture before assertions so an unexpected control layout is reviewable.
        try inspect(window)
    }

    private func descendants(of view: UIView) -> [UIView] {
        [view] + view.subviews.flatMap { descendants(of: $0) }
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

    init() {
        defaults = UserDefaults(suiteName: suiteName)!
        let consentStore = ConsentAcknowledgementStore(userDefaults: defaults)
        authentication = AuthSession(
            api: AuthenticationAPI(serverURLProvider: { _ in nil }),
            tokenStore: RenderingTokenStore(),
            consentStore: consentStore
        )
        youtube = YouTubeIntegration(
            preferencesStore: YouTubePreferencesStore(userDefaults: defaults),
            api: YouTubeAPI(serverURLProvider: { _ in nil }),
            consentStore: consentStore,
            sessionStore: RenderingBroadcastSessionStore(),
            persistAIProcessingMode: { _ in }
        )
    }

    func removePreferences() { defaults.removePersistentDomain(forName: suiteName) }
}

private final class RenderingTokenStore: AuthenticationTokenStoring {
    // iOS 18의 isolated deinit 런타임 오류를 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    func load() -> AuthenticationTokenPair? { nil }
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

nonisolated private enum RenderingTaskContext {
    @TaskLocal static var isRendering = false
}
