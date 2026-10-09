import SwiftUI
import UIKit
import XCTest

@testable import InnoLive

/// 방송 준비 안내 오버레이를 실제 SwiftUI 화면으로 그려 xcresult에 이미지로 남긴다.
/// 계정 연결, 방송 준비, 카메라 시작은 하지 않는다.
@MainActor
final class BroadcastGuideRenderingTests: XCTestCase {
    func testHomeGuideHighlightsThePrepareButton() async throws {
        let fixture = GuideRenderingFixture()
        defer { fixture.removePreferences() }

        for (appearance, textSize) in variants {
            let tutorial = fixture.makeTutorial()
            tutorial.startIfNeeded(.init(selectedAccountsConnected: false))
            try await render("guide-home-prepare", appearance: appearance, textSize: textSize) {
                ZStack(alignment: .bottom) {
                    GuidePreviewBackground()
                    BroadcastControllsView(
                        isBroadcasting: .constant(false),
                        previewTransition: .constant(.none),
                        authentication: fixture.authentication,
                        youtube: fixture.youtube,
                        onPrepareBroadcast: { _ in XCTFail("Render tests must not connect media") },
                        onCancelPreparation: {},
                        tutorial: tutorial
                    )
                    .padding(.horizontal, 24)
                    .padding(.bottom, 12)
                }
                .broadcastTutorialHost(tutorial, host: .home)
            }
            XCTAssertEqual(tutorial.stage, .init(step: .openPreparation, host: .home))
            XCTAssertEqual(tutorial.progress, .init(index: 1, total: 5))
        }
    }

    func testSettingsSheetGuideAsksToConnectAnAccount() async throws {
        let fixture = GuideRenderingFixture()
        defer { fixture.removePreferences() }

        for (appearance, textSize) in variants {
            let tutorial = fixture.makeTutorial()
            tutorial.startIfNeeded(.init(selectedAccountsConnected: false))
            tutorial.update(.init(isSettingsSheetPresented: true, selectedAccountsConnected: false))
            try await render("guide-sheet-connect-account", appearance: appearance, textSize: textSize) {
                BroadcastSettingsView(
                    authentication: fixture.authentication,
                    youtube: fixture.youtube,
                    onPrepare: { _ in XCTFail("Render tests must not prepare a broadcast") }
                )
                .broadcastTutorialHost(tutorial, host: .settingsSheet)
            }
            XCTAssertEqual(tutorial.stage, .init(step: .connectAccount, host: .settingsSheet))
        }
    }

    private var variants: [(UIUserInterfaceStyle, DynamicTypeSize)] {
        [(.light, .large), (.dark, .large), (.light, .accessibility2)]
    }

    private func render<Content: View>(
        _ name: String,
        appearance: UIUserInterfaceStyle,
        textSize: DynamicTypeSize,
        @ViewBuilder content: () -> Content
    ) async throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive })
        let previousKeyWindow = scene.windows.first { $0.isKeyWindow }
        let window = UIWindow(windowScene: scene)
        window.frame = scene.coordinateSpace.bounds
        window.overrideUserInterfaceStyle = appearance
        window.rootViewController = UIHostingController(rootView:
            NavigationStack { content() }
                .dynamicTypeSize(textSize)
        )
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKeyAndVisible()
        }

        try await Task.sleep(for: .milliseconds(450))
        window.layoutIfNeeded()

        let format = UIGraphicsImageRendererFormat()
        format.scale = scene.screen.scale
        let screenshot = UIGraphicsImageRenderer(bounds: window.bounds, format: format).image { _ in
            XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
        }
        XCTAssertGreaterThan(screenshot.pngData()?.count ?? 0, 10_000)
        let attachment = XCTAttachment(image: screenshot)
        let style = appearance == .dark ? "dark" : "light"
        attachment.name = "\(name)-\(style)-\(textSize == .large ? "default" : "large-text")"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}

/// 카메라 대신 쓰는 배경. 실제 미리보기처럼 밝고 어두운 영역이 섞여 있어 대비를 확인하기 좋다.
private struct GuidePreviewBackground: View {
    var body: some View {
        LinearGradient(colors: [.orange, .indigo, .black], startPoint: .topLeading, endPoint: .bottomTrailing)
            .ignoresSafeArea()
            .toolbar(.hidden, for: .navigationBar)
    }
}

@MainActor
private final class GuideRenderingFixture {
    // iOS 18의 isolated deinit 런타임 오류를 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    let suiteName = "com.framework.innolive.tests.guide-rendering.\(UUID().uuidString)"
    let defaults: UserDefaults
    let authentication: AuthSession
    let youtube: YouTubeIntegration

    init() {
        defaults = UserDefaults(suiteName: suiteName)!
        let consentStore = ConsentAcknowledgementStore(userDefaults: defaults)
        authentication = AuthSession(
            api: AuthenticationAPI(serverURLProvider: { _ in nil }),
            tokenStore: GuideRenderingTokenStore(),
            consentStore: consentStore
        )
        youtube = YouTubeIntegration(
            preferencesStore: YouTubePreferencesStore(userDefaults: defaults),
            api: YouTubeAPI(serverURLProvider: { _ in nil }),
            consentStore: consentStore,
            sessionStore: GuideRenderingSessionStore(),
            persistAIProcessingMode: { _ in }
        )
    }

    func makeTutorial() -> BroadcastTutorialCoordinator {
        defaults.removeObject(forKey: "com.framework.innolive.tutorial.broadcast-preparation.v1")
        defaults.removeObject(forKey: "com.framework.innolive.tutorial.live-status.v1")
        return BroadcastTutorialCoordinator(store: BroadcastTutorialStore(userDefaults: defaults))
    }

    func removePreferences() { defaults.removePersistentDomain(forName: suiteName) }
}

private final class GuideRenderingTokenStore: AuthenticationTokenStoring {
    // iOS 18의 isolated deinit 런타임 오류를 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    func load() -> AuthenticationTokenPair? { nil }
    func save(_ tokens: AuthenticationTokenPair) throws { XCTFail("Render tests must not sign in") }
    func remove() { XCTFail("Render tests must not sign out") }
}

private final class GuideRenderingSessionStore: BroadcastSessionStoring {
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
