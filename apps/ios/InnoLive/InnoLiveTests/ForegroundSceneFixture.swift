import Darwin
import UIKit
import XCTest

// 호스트 앱에서 도는 테스트는 UIKit이 앱 scene 활성화를 마치기 전에 시작될 수 있다.
func foregroundActiveScene(file: StaticString = #filePath, line: UInt = #line,
                           function: StaticString = #function) async throws -> UIWindowScene {
    SceneActivationLog.install()
    for _ in 0..<50 {
        if let scene = activeWindowScene() {
            SceneActivationLog.record("ok \(function)")
            return scene
        }
        try await Task.sleep(for: .milliseconds(100))
    }
    SceneActivationLog.record("fail \(function)")
    // 병렬 실행에서는 호스트 stdout이 xcodebuild 로그에 오지 않는다. 실패 메시지는 로그에 남으므로 표식을 여기에 둔다.
    return try XCTUnwrap(activeWindowScene(),
                         "SCENE_DIAG Foreground the test app before running hosted UI checks; \(SceneActivationLog.report())",
                         file: file, line: line)
}

private func activeWindowScene() -> UIWindowScene? {
    UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        .first { $0.activationState == .foregroundActive }
}

// 실패 시점에 scene이 늦게 오는 중인지, 이 프로세스에서 이미 비활성으로 남았는지 구분한다.
private enum SceneActivationLog {
    private static var observers: [NSObjectProtocol] = []
    private static var events: [String] = []

    static func install() {
        guard observers.isEmpty else { return }
        record("install")
        let names: [Notification.Name] = [UIScene.didActivateNotification, UIScene.willDeactivateNotification,
                                          UIScene.didEnterBackgroundNotification, UIScene.willEnterForegroundNotification,
                                          UIScene.didDisconnectNotification]
        observers = names.map { name in
            NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated { record(name.rawValue) }
            }
        }
    }

    static func record(_ event: String) {
        events.append("\(String(format: "%.1f", ProcessInfo.processInfo.systemUptime)) \(event)")
        if events.count > 16 { events.removeFirst() }
    }

    static func report() -> String {
        let scenes = UIApplication.shared.connectedScenes.map { scene in
            let windows = (scene as? UIWindowScene)?.windows ?? []
            return "\(name(scene.activationState)) windows=\(windows.count) visible=\(windows.filter { !$0.isHidden }.count)"
        }
        let environment = ProcessInfo.processInfo.environment
        return "pid=\(getpid()) sim=\(environment["SIMULATOR_UDID"] ?? "?") ageSeconds=\(processAge().map { String(format: "%.1f", $0) } ?? "?") "
            + "now=\(String(format: "%.1f", ProcessInfo.processInfo.systemUptime)) appState=\(UIApplication.shared.applicationState.rawValue) "
            + "scenes=\(scenes) events=\(events)"
    }

    private static func name(_ state: UIScene.ActivationState) -> String {
        switch state {
        case .unattached: "unattached"
        case .foregroundActive: "foregroundActive"
        case .foregroundInactive: "foregroundInactive"
        case .background: "background"
        @unknown default: "unknown"
        }
    }

    private static func processAge() -> Double? {
        var info = kinfo_proc()
        var size = MemoryLayout<kinfo_proc>.stride
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        guard sysctl(&mib, u_int(mib.count), &info, &size, nil, 0) == 0 else { return nil }
        let start = info.kp_proc.p_un.__p_starttime
        return Date().timeIntervalSince1970 - (Double(start.tv_sec) + Double(start.tv_usec) / 1_000_000)
    }
}
