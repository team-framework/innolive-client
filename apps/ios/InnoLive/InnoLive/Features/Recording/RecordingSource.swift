import Foundation

/// Which frames a recorder accepts. Fixed per recorder so one file never mixes raw and protected video.
nonisolated enum RecordingSource: String, Codable, Sendable {
    case raw
    case protected

    init(processingMode: AIProcessingMode) {
        self = processingMode == .onDevice ? .protected : .raw
    }
}
