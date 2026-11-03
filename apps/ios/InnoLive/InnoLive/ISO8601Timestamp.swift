import Foundation

// 서버 시각은 소수점 초를 붙이거나 생략해 보낸다. 두 형식을 모두 받는다.
enum ISO8601Timestamp {
    private static let lock = NSLock()

    private static let fractionalSecondsFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    private static let standardFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    static func date(from value: String) -> Date? {
        lock.lock()
        defer { lock.unlock() }
        return fractionalSecondsFormatter.date(from: value)
            ?? standardFormatter.date(from: value)
    }

    /// 소수점 없는 인터넷 시각. 직접 송출 하트비트 `sentAt`이 이 형식을 쓴다.
    static func string(from date: Date) -> String {
        lock.lock()
        defer { lock.unlock() }
        return standardFormatter.string(from: date)
    }

    /// 소수점 초를 붙인 인터넷 시각. 업링크 진단 로그가 이 형식을 쓴다.
    static func stringWithFractionalSeconds(from date: Date) -> String {
        lock.lock()
        defer { lock.unlock() }
        return fractionalSecondsFormatter.string(from: date)
    }
}
