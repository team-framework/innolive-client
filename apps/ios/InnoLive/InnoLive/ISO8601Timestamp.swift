import Foundation

// 서버 시각은 소수점 초를 붙이거나 생략해 보낸다. 두 형식을 모두 받는다.
enum ISO8601Timestamp {
    private static let lock = NSLock()

    private static let fractionalSecondsFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    private static let standardFormatter = ISO8601DateFormatter()

    static func date(from value: String) -> Date? {
        lock.lock()
        defer { lock.unlock() }
        return fractionalSecondsFormatter.date(from: value)
            ?? standardFormatter.date(from: value)
    }
}
