import Foundation

enum BroadcastUplinkLimitation: Equatable {
    case network
    case device
}

struct BroadcastUplinkQuality: Equatable {
    var shortEdge: Int?
    var framesPerSecond: Int?
    var limitation: BroadcastUplinkLimitation?

    static let measuring = BroadcastUplinkQuality()

    /// 앱이 서버로 실제 올리고 있는 영상의 해상도와 프레임 수
    var summary: String? {
        guard let shortEdge else { return nil }
        guard let framesPerSecond else { return "\(shortEdge)p" }
        return "\(shortEdge)p · \(framesPerSecond)fps"
    }
}

/// WebRTC는 연결 직후 대역폭을 추정하는 몇 초 동안에도 제한 사유를 보고한다.
/// 매 방송 시작마다 경고가 깜빡이지 않도록 연속으로 제한될 때만 경고를 띄운다.
struct BroadcastUplinkQualityTracker {
    static let samplesToWarn = 3
    static let samplesToClear = 2

    private(set) var quality = BroadcastUplinkQuality.measuring
    private var limitedStreak = 0
    private var clearStreak = 0

    mutating func record(_ sample: UplinkVideoOutboundStats) -> BroadcastUplinkQuality {
        if let limitation = Self.limitation(for: sample.reason) {
            limitedStreak += 1
            clearStreak = 0
            if quality.limitation != nil || limitedStreak >= Self.samplesToWarn {
                quality.limitation = limitation
            }
        } else {
            limitedStreak = 0
            clearStreak += 1
            if clearStreak >= Self.samplesToClear {
                quality.limitation = nil
            }
        }
        if let width = sample.width, let height = sample.height {
            quality.shortEdge = min(width, height)
        }
        quality.framesPerSecond = sample.framesPerSecond.map { Int($0.rounded()) }
        return quality
    }

    mutating func reset() {
        self = Self()
    }

    private static func limitation(for reason: String) -> BroadcastUplinkLimitation? {
        switch reason {
        case "bandwidth": return .network
        case "cpu": return .device
        default: return nil
        }
    }
}
