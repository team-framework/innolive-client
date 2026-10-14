import Foundation

/// 하트비트에 싣는 송출 지표다. 누적값은 이번 세션의 첫 직접 송출 이후로 센다.
/// 캡처·추론·인코더 입력 스레드에서 기록한다.
nonisolated final class DirectPublishMetrics: @unchecked Sendable {
    struct Snapshot: Equatable {
        var videoFrames: Int64 = 0
        var droppedFrames: Int64 = 0
        var failedFrames: Int64 = 0
        var inferenceMilliseconds: [Double] = []
        var videoWidth: Int?
        var videoHeight: Int?
        /// 누적을 시작한 시각. 첫 하트비트의 fps 계산 기준이다.
        var startedAt: Date?
    }

    /// 주기 하나에 보관하는 추론 시간 표본 수 상한이다(30fps × 15초보다 넉넉하게).
    static let maximumInferenceSamples = 1_024

    private let lock = NSLock()
    private var state = Snapshot()

    func reset() {
        lock.withLock { state = Snapshot(startedAt: Date()) }
    }

    func recordVideoFrame(width: Int, height: Int) {
        lock.withLock {
            state.videoFrames += 1
            state.videoWidth = width
            state.videoHeight = height
        }
    }

    func recordDroppedFrame() {
        lock.withLock { state.droppedFrames += 1 }
    }

    func recordInference(milliseconds: Double) {
        guard milliseconds.isFinite, milliseconds >= 0 else { return }
        lock.withLock {
            if state.inferenceMilliseconds.count >= Self.maximumInferenceSamples {
                state.inferenceMilliseconds.removeFirst()
            }
            state.inferenceMilliseconds.append(milliseconds)
        }
    }

    func recordAnonymizationFailure() {
        lock.withLock { state.failedFrames += 1 }
    }

    /// 누적값과 함께 지난 호출 이후의 추론 시간 표본을 돌려주고 표본은 비운다.
    func drain() -> Snapshot {
        lock.withLock {
            let snapshot = state
            state.inferenceMilliseconds = []
            return snapshot
        }
    }

    static func percentile(_ values: [Double], _ fraction: Double) -> Double? {
        guard !values.isEmpty else { return nil }
        let sorted = values.sorted()
        let index = Int((Double(sorted.count - 1) * fraction).rounded())
        return sorted[min(max(index, 0), sorted.count - 1)]
    }
}
