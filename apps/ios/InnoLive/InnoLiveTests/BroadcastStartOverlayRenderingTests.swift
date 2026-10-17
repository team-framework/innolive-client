import SwiftUI
import UIKit
import XCTest
@testable import InnoLive

@MainActor
final class BroadcastStartOverlayRenderingTests: XCTestCase {
    func testCountdownAndStartingPortraitAndLandscape() async throws {
        for style in [UIUserInterfaceStyle.light, .dark] {
            for landscape in [false, true] {
                for value in [3, 2, 1, 0] {
                    try await render(value: value, style: style, landscape: landscape,
                                     textSize: .large, highContrast: false)
                }
            }
        }
    }

    func testAccessibilityTextAndHighContrastRemainScrollable() async throws {
        for landscape in [false, true] {
            for value in [3, 0] {
                try await render(value: value, style: .dark, landscape: landscape,
                                 textSize: .accessibility5, highContrast: true)
            }
        }
    }

    private func render(value: Int, style: UIUserInterfaceStyle, landscape: Bool,
                        textSize: DynamicTypeSize, highContrast: Bool) async throws {
        let lifecycle = BroadcastLifecycle()
        let preparation = lifecycle.beginPreparation()
        lifecycle.finishPreparation(generation: preparation)
        let generation = try XCTUnwrap(lifecycle.beginCountdown())
        if value <= 2 { lifecycle.advanceCountdown(generation: generation) }
        if value <= 1 { lifecycle.advanceCountdown(generation: generation) }
        if value == 0 { XCTAssertTrue(lifecycle.beginStarting(generation: generation)) }
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive })
        let previousKeyWindow = scene.windows.first { $0.isKeyWindow }
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(origin: .zero, size: landscape ? CGSize(width: 844, height: 390) : CGSize(width: 390, height: 844))
        window.overrideUserInterfaceStyle = style
        let host = UIHostingController(rootView:
            ZStack {
                LinearGradient(colors: [.orange, .indigo, .black], startPoint: .topLeading, endPoint: .bottomTrailing)
                    .ignoresSafeArea()
                BroadcastStartOverlay(lifecycle: lifecycle, orientation: landscape ? .landscapeLeft : .portrait,
                                      onCancel: lifecycle.cancelCountdown)
            }.dynamicTypeSize(textSize)
        )
        if #available(iOS 17, *) {
            host.traitOverrides.accessibilityContrast = highContrast ? .high : .normal
        }
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKeyAndVisible()
        }
        try await Task.sleep(for: .milliseconds(350))
        window.layoutIfNeeded()
        host.view.layoutIfNeeded()
        let scrolls = descendants(host.view).compactMap { $0 as? UIScrollView }
        let scroll = try XCTUnwrap(scrolls.first, "Overlay must expose scroll access to large text")
        let viewportWidth = scroll.bounds.width - scroll.adjustedContentInset.left - scroll.adjustedContentInset.right
        let viewportHeight = scroll.bounds.height - scroll.adjustedContentInset.top - scroll.adjustedContentInset.bottom
        XCTAssertGreaterThan(viewportWidth, 0)
        XCTAssertGreaterThan(viewportHeight, 0)
        XCTAssertLessThanOrEqual(scroll.contentSize.width, viewportWidth + 1, "Text must wrap without horizontal clipping")
        // Reach the end of large content and ensure the last region stays in the viewport.
        let maximumY = max(-scroll.adjustedContentInset.top,
                           scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom)
        scroll.setContentOffset(CGPoint(x: -scroll.adjustedContentInset.left, y: maximumY), animated: false)
        scroll.layoutIfNeeded()
        XCTAssertEqual(scroll.contentOffset.y, maximumY, accuracy: 1)
        if textSize == .large { scroll.setContentOffset(CGPoint(x: 0, y: -scroll.adjustedContentInset.top), animated: false) }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(bounds: window.bounds, format: format).image { _ in
            XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
        }
        XCTAssertGreaterThan(image.pngData()?.count ?? 0, 5_000)
        let attachment = XCTAttachment(image: image)
        attachment.name = "start-\(value)-\(landscape ? "landscape" : "portrait")-\(style == .dark ? "dark" : "light")-\(highContrast ? "AX-high-contrast" : "default")"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func descendants(_ view: UIView) -> [UIView] {
        view.subviews.flatMap { [$0] + descendants($0) }
    }
}
