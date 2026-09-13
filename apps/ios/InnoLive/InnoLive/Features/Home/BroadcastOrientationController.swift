import Combine
import ObjectiveC
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
    func unlock()
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

    private let currentOrientationProvider: () -> BroadcastInterfaceOrientation
    private let appliesSceneUpdates: Bool
    private var lockGeneration: UInt = 0

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
        currentOrientationProvider: @escaping () -> BroadcastInterfaceOrientation
            = BroadcastOrientationController.resolveCurrentInterfaceOrientation,
        appliesSceneUpdates: Bool = true
    ) {
        self.currentOrientationProvider = currentOrientationProvider
        self.appliesSceneUpdates = appliesSceneUpdates
        Self.installPrefersInterfaceOrientationLockedHook()
    }

    @discardableResult
    func lockToCurrentInterfaceOrientation() -> UInt {
        if lockedOrientation != nil {
            return lockGeneration
        }
        lockGeneration &+= 1
        lockedOrientation = currentOrientationProvider()
        applyToScenes()
        notify()
        return lockGeneration
    }

    func unlock() {
        lockGeneration &+= 1
        guard lockedOrientation != nil else { return }
        lockedOrientation = nil
        applyToScenes()
        notify()
    }

    func unlock(generation: UInt) {
        guard generation == lockGeneration else { return }
        unlock()
    }

    func captureActiveScene(from window: UIWindow?) {
        guard lockedOrientation == nil else { return }
        _ = window?.windowScene
        notifyIfNeededForUnlockedRotation()
    }

    private func notifyIfNeededForUnlockedRotation() {
        guard lockedOrientation == nil else { return }
        NotificationCenter.default.post(name: .broadcastOrientationDidChange, object: self)
    }

    private func notify() {
        objectWillChange.send()
        NotificationCenter.default.post(name: .broadcastOrientationDidChange, object: self)
    }

    private func applyToScenes() {
        guard appliesSceneUpdates else { return }
        let mask = supportedInterfaceOrientations
        for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
            scene.requestGeometryUpdate(
                .iOS(interfaceOrientations: mask)
            ) { _ in }
            for window in scene.windows {
                invalidateRotationSupport(from: window.rootViewController)
            }
        }
    }

    private func invalidateRotationSupport(from controller: UIViewController?) {
        guard let controller else { return }
        controller.setNeedsUpdateOfSupportedInterfaceOrientations()
        invalidateRotationSupport(from: controller.presentedViewController)
        for child in controller.children {
            invalidateRotationSupport(from: child)
        }
    }

    static func resolveCurrentInterfaceOrientation() -> BroadcastInterfaceOrientation {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let activeScenes = scenes.filter { $0.activationState == .foregroundActive }
        let scene = activeScenes.first(where: { scene in
            scene.windows.contains(where: \.isKeyWindow)
        }) ?? activeScenes.first ?? scenes.first
        let interfaceOrientation = scene?.effectiveGeometry.interfaceOrientation
            ?? scene?.interfaceOrientation
            ?? .portrait
        return BroadcastInterfaceOrientation(interfaceOrientation: interfaceOrientation)
            ?? .portrait
    }

    private static func installPrefersInterfaceOrientationLockedHook() {
        _ = prefersInterfaceOrientationLockedHook
    }

    private static let prefersInterfaceOrientationLockedHook: Void = {
        let originalSelector = #selector(getter: UIViewController.prefersInterfaceOrientationLocked)
        let swizzledSelector = #selector(UIViewController.innolive_prefersInterfaceOrientationLocked)
        guard let originalMethod = class_getInstanceMethod(UIViewController.self, originalSelector),
              let swizzledMethod = class_getInstanceMethod(UIViewController.self, swizzledSelector)
        else {
            return
        }
        method_exchangeImplementations(originalMethod, swizzledMethod)
    }()
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

    override var shouldAutorotate: Bool {
        !BroadcastOrientationController.shared.isLocked
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        view.isAccessibilityElement = false
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        BroadcastOrientationController.shared.captureActiveScene(from: view.window)
        refreshOrientationSupport()
    }

    override func viewWillTransition(
        to size: CGSize,
        with coordinator: UIViewControllerTransitionCoordinator
    ) {
        super.viewWillTransition(to: size, with: coordinator)
        coordinator.animate(alongsideTransition: nil) { [weak self] _ in
            guard let self else { return }
            BroadcastOrientationController.shared.captureActiveScene(from: self.view.window)
        }
    }

    func refreshOrientationSupport() {
        setNeedsUpdateOfSupportedInterfaceOrientations()
        var controller: UIViewController? = self
        while let current = controller {
            current.setNeedsUpdateOfSupportedInterfaceOrientations()
            controller = current.parent ?? current.presentingViewController
        }
        if let presented = presentedViewController {
            presented.setNeedsUpdateOfSupportedInterfaceOrientations()
        }
    }
}

private extension UIViewController {
    @objc func innolive_prefersInterfaceOrientationLocked() -> Bool {
        if BroadcastOrientationController.shared.prefersInterfaceOrientationLocked {
            return true
        }
        return innolive_prefersInterfaceOrientationLocked()
    }
}
