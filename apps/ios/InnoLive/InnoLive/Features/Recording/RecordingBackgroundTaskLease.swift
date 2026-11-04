import Foundation
#if canImport(UIKit)
import UIKit
#endif

@MainActor protocol RecordingBackgroundLease: AnyObject {
    var isExpired: Bool { get }
    func end()
}

typealias RecordingBackgroundLeaseFactory = @MainActor (String, @escaping @MainActor () -> Void) -> any RecordingBackgroundLease

/// 시간이 정해진 UIKit 백그라운드 작업 요청일 뿐, iOS가 백그라운드 녹화를 제한 없이 허용한다는 보장은 아니다.
@MainActor final class RecordingBackgroundTaskLease: RecordingBackgroundLease {
    nonisolated deinit {}
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
            // UIKit은 만료를 main에서 알려 주지만 Swift 동시성을 위해 actor로 명시적으로 넘어간다.
            Task { @MainActor [weak self] in self?.expire() }
        }
        if identifier == .invalid {
            // 백그라운드 시간을 받지 못했다. 포그라운드 작업은 그래도 정상적으로 끝날 수 있다.
            // 여기서 만료를 꾸며 내지 않는다. 실제로 끝난 작업도 파일 검증은 통과해야 한다.
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
