import Foundation

enum FaceRegistrationDateFormatting {
    private static let lock = NSLock()

    private static let fractionalSecondsFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    private static let standardFormatter = ISO8601DateFormatter()

    static func displayDate(from value: String) -> String {
        guard let date = date(from: value) else { return value }
        return date.formatted(date: .abbreviated, time: .shortened)
    }

    private static func date(from value: String) -> Date? {
        lock.lock()
        defer { lock.unlock() }
        return fractionalSecondsFormatter.date(from: value)
            ?? standardFormatter.date(from: value)
    }
}
