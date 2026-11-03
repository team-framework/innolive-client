import Foundation

enum FaceRegistrationDateFormatting {
    static func displayDate(from value: String) -> String {
        guard let date = ISO8601Timestamp.date(from: value) else { return value }
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}
