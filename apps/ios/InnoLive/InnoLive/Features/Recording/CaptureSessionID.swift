import Foundation

nonisolated enum CaptureSessionID: Hashable, Sendable {
    case server(String)
    case local(UUID)

    static let localTarget = "device"
    static let localKeyPrefix = "local-"

    var recordingKey: String {
        switch self {
        case .server(let id): id
        case .local(let id): "\(Self.localKeyPrefix)\(id.uuidString)"
        }
    }
}
