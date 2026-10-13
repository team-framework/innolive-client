import SwiftUI
import UIKit
import XCTest
@testable import InnoLive

@MainActor
final class UpgradeOfferRenderingTests: XCTestCase {
    func testOptionsAndSelectedSettingsRenderWithLargeText() async throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive })
        let previousWindow = scene.windows.first { $0.isKeyWindow }
        let suite = "upgrade-render-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let token = "h." + Data("{\"sub\":\"render-user\"}".utf8).base64EncodedString() + ".s"
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [UpgradeRenderingURLProtocol.self]
        let api = YouTubeAPI(urlSession: URLSession(configuration: configuration),
                             serverURLProvider: { URL(string: "https://example.invalid\($0)") })
        let authentication = AuthSession(api: AuthenticationAPI(serverURLProvider: { _ in nil }),
            tokenStore: UpgradeRenderingTokenStore(token: token), consentStore: ConsentAcknowledgementStore(userDefaults: defaults))
        let integration = YouTubeIntegration(preferencesStore: YouTubePreferencesStore(userDefaults: defaults), api: api)
        defer { integration.reset() }
        for settings in [false, true] {
            for large in [false, true] {
                let data = try ContractTestFixtures.data(named: settings ? "upgrade-offer-selected.v1" : "upgrade-offer-options.v1")
                var object = try JSONSerialization.jsonObject(with: data) as! [String: Any]
                var offer = object["upgrade_offer"] as! [String: Any]
                offer["expires_at"] = ISO8601DateFormatter().string(from: Date().addingTimeInterval(180))
                object["upgrade_offer"] = offer
                let snapshot = try JSONSerialization.data(withJSONObject: object)
                let session = try JSONDecoder().decode(YouTubeBroadcastSession.self, from: snapshot)
                integration.seedPreparedVideoSessionForTesting(connection: .init(provider: "youtube", channel: .init(id: "fake-channel", title: "YT")),
                    session: session, videoTrack: .init(id: "video", kind: "video", readyState: "live"))
                integration.applyLiveEditingSnapshotForTesting(try JSONDecoder().decode(YouTubeSessionResponse.self, from: snapshot))
                await integration.refreshConnection(accessToken: token)
                integration.configureSettingsEditor(accessToken: token)
                integration.settingsEditor.editCHZZK { $0.title = "저장 실패 후 다시 시도할 방송 초안" }
                if settings {
                    let saved = await integration.saveUpgradeOfferSettings(provider: .chzzk, accessToken: token)
                    XCTAssertFalse(saved)
                    XCTAssertNotNil(integration.broadcastModeError)
                    XCTAssertEqual(integration.settingsEditor.chzzk.title, "저장 실패 후 다시 시도할 방송 초안")
                    XCTAssertTrue(integration.canChangeBroadcastMode)
                }
                let window = UIWindow(windowScene: scene)
                window.frame = scene.coordinateSpace.bounds
                window.overrideUserInterfaceStyle = large ? .dark : .light
                window.rootViewController = UIHostingController(rootView: NavigationStack {
                    if settings {
                        BroadcastSettingsView(authentication: authentication, youtube: integration, modeProvider: .chzzk,
                                              upgradeSettings: true)
                    } else {
                        UpgradeOfferView(authentication: authentication, youtube: integration)
                    }
                }.dynamicTypeSize(large ? .accessibility3 : .large))
                window.makeKeyAndVisible()
                try await Task.sleep(for: .milliseconds(400))
                window.layoutIfNeeded()
                let name = "upgrade-" + (settings ? "settings" : "options") + (large ? "-large-dark" : "-light")
                capture(window, name: name)
                let scroll = try XCTUnwrap(findScrollView(window))
                if scroll.contentSize.height > scroll.bounds.height {
                    scroll.setContentOffset(CGPoint(x: 0, y: max(0, scroll.contentSize.height - scroll.bounds.height)), animated: false)
                    try await Task.sleep(for: .milliseconds(150))
                    capture(window, name: name + "-bottom")
                }
                window.isHidden = true
                window.rootViewController = nil
                previousWindow?.makeKeyAndVisible()
            }
        }
    }

    private func findScrollView(_ view: UIView) -> UIScrollView? {
        if let scroll = view as? UIScrollView { return scroll }
        return view.subviews.lazy.compactMap(findScrollView).first
    }

    private func capture(_ window: UIWindow, name: String) {
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
        }
        XCTAssertGreaterThan(image.pngData()?.count ?? 0, 10_000)
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}

private final class UpgradeRenderingTokenStore: AuthenticationTokenStoring {
    nonisolated deinit {}
    private let token: String
    init(token: String) { self.token = token }
    func load() -> AuthenticationTokenPair? { .init(accessToken: token, refreshToken: "fake-refresh") }
    func save(_ tokens: AuthenticationTokenPair) throws { XCTFail("Rendering must not sign in") }
    func remove() { XCTFail("Rendering must not sign out") }
}

private final class UpgradeRenderingURLProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let path = request.url!.path
        let status: Int
        let body: String
        if path == "/auth/streaming/accounts" {
            status = 200
            body = "[{\"provider\":\"youtube\",\"channel_id\":\"fake-channel\",\"channel_title\":\"YT\",\"reconnect_required\":false},{\"provider\":\"chzzk\",\"channel_id\":\"fake-chzzk\",\"channel_title\":\"CH\",\"reconnect_required\":false}]"
        } else if path.hasSuffix("/broadcast") {
            status = 400
            body = "{\"error\":{\"code\":\"bad_request\",\"details\":{\"field\":\"title\",\"reason\":\"retry\"}}}"
        } else if path.hasSuffix("/defaults") {
            status = 200
            body = "{}"
        } else {
            status = 500
            body = "{}"
        }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil,
            headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
