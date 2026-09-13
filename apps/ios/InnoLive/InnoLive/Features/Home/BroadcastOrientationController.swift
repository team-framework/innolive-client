import Combine
import SwiftUI
import UIKit

extension Notification.Name {
    static let broadcastOrientationDidChange = Notification.Name(
        "InnoLiveBroadcastOrientationDidChange"
    )
}

@MainActor
protocol BroadcastOrientationLocking: AnyObject {
    var isLocked: Bool { get }
    var lockedOrientation: BroadcastInterfaceOrientation? { get }
    @discardableResult
    func lockToCurrentInterfaceOrientation() -> UInt
    func unlock(generation: UInt)
}

final class InnoLiveAppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        BroadcastOrientationController.shared.supportedInterfaceOrientations
    }
}

@MainActor
final class BroadcastOrientationController: ObservableObject, BroadcastOrientationLocking {
    static let shared = BroadcastOrientationController()

    @Published private(set) var lockedOrientation: BroadcastInterfaceOrientation?

    private let currentOrientationProvider: @MainActor () -> BroadcastInterfaceOrientation
    private let appliesSceneUpdates: Bool
    private var lockGeneration: UInt = 0
    private weak var trackedWindowScene: UIWindowScene?

    var isLocked: Bool { lockedOrientation != nil }

    var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        BroadcastOrientationPolicy.supportedMask(
            lockedOrientation: lockedOrientation,
            idiom: UIDevice.current.userInterfaceIdiom
        )
    }

    var prefersInterfaceOrientationLocked: Bool {
        BroadcastOrientationPolicy.prefersInterfaceOrientationLocked(lockedOrientation)
    }

    init(
        currentOrientationProvider: @escaping @MainActor () -> BroadcastInterfaceOrientation = {
            BroadcastOrientationController.resolveCurrentInterfaceOrientation()
        },
        appliesSceneUpdates: Bool = true
    ) {
        self.currentOrientationProvider = currentOrientationProvider
        self.appliesSceneUpdates = appliesSceneUpdates
    }

    @discardableResult
    func lockToCurrentInterfaceOrientation() -> UInt {
        if lockedOrientation != nil {
            return lockGeneration
        }
        lockGeneration &+= 1
        lockedOrientation = currentOrientationProvider()
        applyToScenes()
        notifyLockDidChange()
        return lockGeneration
    }

    func unlock(generation: UInt) {
        guard generation == lockGeneration else { return }
        clearLock()
    }

    func unlock() {
        clearLock()
    }

    func trackScene(from window: UIWindow?) {
        if let scene = window?.windowScene {
            trackedWindowScene = scene
        }
    }

    private func clearLock() {
        lockGeneration &+= 1
        guard lockedOrientation != nil else { return }
        lockedOrientation = nil
        applyToScenes()
        notifyLockDidChange()
    }

    private func notifyLockDidChange() {
        NotificationCenter.default.post(name: .broadcastOrientationDidChange, object: self)
    }

    private func applyToScenes() {
        guard appliesSceneUpdates else { return }
        let mask = supportedInterfaceOrientations
        for scene in foregroundActiveScenes() {
            for window in scene.windows {
                invalidateRotationSupport(from: window.rootViewController)
            }
            scene.requestGeometryUpdate(
                .iOS(interfaceOrientations: mask)
            ) { _ in }
        }
    }

    private func foregroundActiveScenes() -> [UIWindowScene] {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .filter { $0.activationState == .foregroundActive }
    }

    private func invalidateRotationSupport(from controller: UIViewController?) {
        guard let controller else { return }
        controller.setNeedsUpdateOfSupportedInterfaceOrientations()
        controller.setNeedsUpdateOfPrefersInterfaceOrientationLocked()
        invalidateRotationSupport(from: controller.presentedViewController)
        for child in controller.children {
            invalidateRotationSupport(from: child)
        }
    }

    static func resolveCurrentInterfaceOrientation() -> BroadcastInterfaceOrientation {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let activeScenes = scenes.filter { $0.activationState == .foregroundActive }
        let tracked = shared.trackedWindowScene
        let scene: UIWindowScene?
        if let tracked, tracked.activationState == .foregroundActive {
            scene = tracked
        } else {
            scene = activeScenes.first(where: { scene in
                scene.windows.contains(where: \.isKeyWindow)
            }) ?? activeScenes.first
        }
        let interfaceOrientation = scene?.effectiveGeometry.interfaceOrientation ?? .portrait
        return BroadcastInterfaceOrientation(interfaceOrientation: interfaceOrientation)
            ?? .portrait
    }
}

struct BroadcastOrientationSceneBridge: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> BroadcastOrientationBridgeController {
        BroadcastOrientationBridgeController()
    }

    func updateUIViewController(
        _ uiViewController: BroadcastOrientationBridgeController,
        context: Context
    ) {
        uiViewController.refreshOrientationSupport()
    }
}

final class BroadcastOrientationBridgeController: UIViewController {
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        BroadcastOrientationController.shared.supportedInterfaceOrientations
    }

    override var prefersInterfaceOrientationLocked: Bool {
        BroadcastOrientationController.shared.prefersInterfaceOrientationLocked
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        view.isAccessibilityElement = false
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        BroadcastOrientationController.shared.trackScene(from: view.window)
        refreshOrientationSupport()
    }

    override func viewWillTransition(
        to size: CGSize,
        with coordinator: UIViewControllerTransitionCoordinator
    ) {
        super.viewWillTransition(to: size, with: coordinator)
        coordinator.animate(alongsideTransition: nil) { [weak self] _ in
            guard let self else { return }
            BroadcastOrientationController.shared.trackScene(from: self.view.window)
        }
    }

    func refreshOrientationSupport() {
        setNeedsUpdateOfSupportedInterfaceOrientations()
        setNeedsUpdateOfPrefersInterfaceOrientationLocked()
        var controller: UIViewController? = parent ?? presentingViewController
        while let current = controller {
            current.setNeedsUpdateOfSupportedInterfaceOrientations()
            current.setNeedsUpdateOfPrefersInterfaceOrientationLocked()
            controller = current.parent ?? current.presentingViewController
        }
        if let presented = presentedViewController {
            presented.setNeedsUpdateOfSupportedInterfaceOrientations()
            presented.setNeedsUpdateOfPrefersInterfaceOrientationLocked()
        }
    }
}
