import UIKit
import XCTest

// 호스트 앱에서 도는 테스트는 UIKit이 앱 scene 활성화를 마치기 전에 시작될 수 있다.
func foregroundActiveScene(file: StaticString = #filePath, line: UInt = #line) async throws -> UIWindowScene {
    for _ in 0..<50 {
        if let scene = activeWindowScene() { return scene }
        try await Task.sleep(for: .milliseconds(100))
    }
    return try XCTUnwrap(activeWindowScene(), "Foreground the test app before running hosted UI checks",
                         file: file, line: line)
}

private func activeWindowScene() -> UIWindowScene? {
    UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        .first { $0.activationState == .foregroundActive }
}
