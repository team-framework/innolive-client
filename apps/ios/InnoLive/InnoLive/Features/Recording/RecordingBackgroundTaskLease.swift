import Foundation
#if canImport(UIKit)
import UIKit
#endif

@MainActor protocol RecordingBackgroundLease: AnyObject {
    var isExpired: Bool { get }
    func end()
}

typealias RecordingBackgroundLeaseFactory = @MainActor (String, @escaping @MainActor () -> Void) -> any RecordingBackgroundLease

/// A finite UIKit assertion, not a promise that iOS will permit arbitrary background recording.
@MainActor final class RecordingBackgroundTaskLease: RecordingBackgroundLease {
    private(set) var isExpired = false
    private var hasEnded = false
    private let onExpiration: @MainActor () -> Void
    #if canImport(UIKit)
    private var identifier: UIBackgroundTaskIdentifier = .invalid
    #endif

    init(name: String, onExpiration: @escaping @MainActor () -> Void) {
        self.onExpiration = onExpiration
        #if canImport(UIKit)
        identifier = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
            // UIKit delivers expiry on main; explicitly hop to the actor for Swift concurrency.
            Task { @MainActor [weak self] in self?.expire() }
        }
        if identifier == .invalid {
            // No background time was granted. Foreground work may still finish normally.
            // Expiry is not fabricated here; actual completion must still pass file validation.
        }
        #endif
    }

    private func expire() {
        guard !hasEnded else { return }
        isExpired = true
        end()
        onExpiration()
    }

    func end() {
        guard !hasEnded else { return }
        hasEnded = true
        #if canImport(UIKit)
        if identifier != .invalid {
            UIApplication.shared.endBackgroundTask(identifier)
            identifier = .invalid
        }
        #endif
    }
}
