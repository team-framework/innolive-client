import Foundation

/// The capture a recording belongs to. A local capture has no server session.
nonisolated enum CaptureSessionID: Hashable, Sendable {
    case server(String)
    case local(UUID)

    /// The device is the only output of a local capture, so it is its one live target.
    static let localTarget = "device"

    /// Stored in recording metadata and the recovery journal.
    var recordingKey: String {
        switch self {
        case .server(let id): id
        case .local(let id): "local-\(id.uuidString)"
        }
    }
}
