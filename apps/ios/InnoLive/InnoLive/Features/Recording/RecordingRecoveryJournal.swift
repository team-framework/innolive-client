import Foundation

nonisolated struct RecordingRecoveryJournal: Codable, Sendable {
    enum Status: String, Codable, Sendable {
        case provisional, confirmed, finalizing, writerCompleted, failed, aborted
        var mayRecover: Bool { self == .confirmed || self == .finalizing || self == .writerCompleted }
    }
    let id: UUID
    let sessionID: String
    let createdAt: Date
    let status: Status
}

nonisolated struct PreservedBroadcastRecording: Identifiable, Sendable, Equatable {
    let id: UUID
    let fileName: String
    let bytes: Int64
    let modifiedAt: Date?
    let reason: String
}
