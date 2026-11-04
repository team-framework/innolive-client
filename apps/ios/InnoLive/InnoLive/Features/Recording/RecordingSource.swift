import Foundation

/// recorder가 받는 프레임 종류다. recorder마다 고정해서 한 파일에 원본 영상과 보호 처리된 영상이 섞이지 않게 한다.
nonisolated enum RecordingSource: String, Codable, Sendable {
    case raw
    case protected

    init(processingMode: AIProcessingMode) {
        self = processingMode == .onDevice ? .protected : .raw
    }
}
