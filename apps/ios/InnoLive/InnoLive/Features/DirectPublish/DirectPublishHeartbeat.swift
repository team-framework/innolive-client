import Foundation
import UIKit

/// `POST /sessions/{id}/heartbeat` 요청이다(server #433·#434, Notion `하트비트 계약`).
/// 누적 카운터는 송출 시작 이후 값을 보내 요청이 빠져도 서버가 차이를 계산한다.
struct DirectHeartbeatRequest: Encodable, Equatable {
    struct Target: Encodable, Equatable {
        let provider: String
        let connected: Bool
        let bitrateKbps: Int?
        let reconnects: Int

        enum CodingKeys: String, CodingKey {
            case provider, connected, reconnects
            case bitrateKbps = "bitrate_kbps"
        }
    }

    struct Video: Encodable, Equatable {
        let fps: Double
        let droppedFrames: Int64
        let width: Int?
        let height: Int?

        enum CodingKeys: String, CodingKey {
            case fps, width, height
            case droppedFrames = "dropped_frames"
        }
    }

    struct Anonymization: Encodable, Equatable {
        let enabled: Bool
        let inferenceMsP50: Double?
        let inferenceMsP95: Double?
        let failedFrames: Int64

        enum CodingKeys: String, CodingKey {
            case enabled
            case inferenceMsP50 = "inference_ms_p50"
            case inferenceMsP95 = "inference_ms_p95"
            case failedFrames = "failed_frames"
        }
    }

    struct Device: Encodable, Equatable {
        let thermal: String
        let batteryPct: Int?
        let charging: Bool?

        enum CodingKeys: String, CodingKey {
            case thermal, charging
            case batteryPct = "battery_pct"
        }
    }

    let sentAt: String
    let targets: [Target]
    let video: Video
    let anonymization: Anonymization
    let device: Device
    let platform = "ios"
    let model: String

    enum CodingKeys: String, CodingKey {
        case targets, video, anonymization, device, platform, model
        case sentAt = "sent_at"
    }
}

/// 하트비트 응답이다. `stop_targets`는 서버가 끝낸 대상이며 앱은 즉시 연결을 끊는다.
struct DirectHeartbeatResponse: Decodable, Equatable {
    let stopTargets: [String]
    let notices: [BroadcastNotice]
    let remainingTime: BroadcastRemainingTime

    enum CodingKeys: String, CodingKey {
        case notices
        case stopTargets = "stop_targets"
        case remainingTime = "broadcast_remaining_seconds"
    }

    init(stopTargets: [String] = [], notices: [BroadcastNotice] = [], remainingTime: BroadcastRemainingTime = .missing) {
        self.stopTargets = stopTargets
        self.notices = notices
        self.remainingTime = remainingTime
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        stopTargets = try container.decodeIfPresent([String].self, forKey: .stopTargets) ?? []
        notices = try container.decodeIfPresent([BroadcastNotice].self, forKey: .notices) ?? []
        if container.contains(.remainingTime) {
            remainingTime = try container.decodeIfPresent(Int.self, forKey: .remainingTime)
                .map(BroadcastRemainingTime.seconds) ?? .unlimitedOrInactive
        } else {
            remainingTime = .missing
        }
    }
}

/// 직전 보고와의 차이로 주기 fps·비트레이트를 계산한다.
struct DirectHeartbeatBuilder {
    private var lastSentAt: Date?
    private var lastFrames: Int64 = 0
    private var lastBytes: [String: Int] = [:]

    mutating func reset() {
        self = Self()
    }

    mutating func makeRequest(
        status: DirectPublishStatus,
        anonymizationEnabled: Bool,
        device: DirectHeartbeatRequest.Device,
        model: String,
        now: Date
    ) -> DirectHeartbeatRequest {
        let metrics = status.metrics
        let elapsed = (lastSentAt ?? metrics.startedAt).map { now.timeIntervalSince($0) }.flatMap { $0 > 0 ? $0 : nil }
        let fps = elapsed.map { Double(max(metrics.videoFrames - lastFrames, 0)) / $0 } ?? 0
        let targets = status.targets.map { target -> DirectHeartbeatRequest.Target in
            var bitrate: Int?
            if let bytes = target.sentBytes, let elapsed, lastSentAt != nil,
               let previous = lastBytes[target.provider], bytes >= previous {
                bitrate = Int((Double(bytes - previous) * 8 / 1_000 / elapsed).rounded())
            }
            return .init(provider: target.provider, connected: target.connected, bitrateKbps: bitrate, reconnects: target.reconnects)
        }
        lastSentAt = now
        lastFrames = metrics.videoFrames
        lastBytes = Dictionary(uniqueKeysWithValues: status.targets.compactMap { target in
            target.sentBytes.map { (target.provider, $0) }
        })
        return DirectHeartbeatRequest(
            sentAt: Self.timestamp(now),
            targets: targets,
            video: .init(fps: (fps * 10).rounded() / 10, droppedFrames: metrics.droppedFrames,
                         width: metrics.videoWidth, height: metrics.videoHeight),
            anonymization: .init(
                enabled: anonymizationEnabled,
                inferenceMsP50: DirectPublishMetrics.percentile(metrics.inferenceMilliseconds, 0.5).map(Self.rounded),
                inferenceMsP95: DirectPublishMetrics.percentile(metrics.inferenceMilliseconds, 0.95).map(Self.rounded),
                failedFrames: metrics.failedFrames
            ),
            device: device,
            model: model
        )
    }

    static func timestamp(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.string(from: date)
    }

    private static func rounded(_ value: Double) -> Double { (value * 10).rounded() / 10 }
}

enum DirectHeartbeatDevice {
    @MainActor
    static func current() -> DirectHeartbeatRequest.Device {
        let device = UIDevice.current
        if !device.isBatteryMonitoringEnabled { device.isBatteryMonitoringEnabled = true }
        let level = device.batteryLevel
        let state = device.batteryState
        return .init(
            thermal: thermal(ProcessInfo.processInfo.thermalState),
            batteryPct: level >= 0 ? Int((level * 100).rounded()) : nil,
            charging: state == .unknown ? nil : (state == .charging || state == .full)
        )
    }

    static func thermal(_ state: ProcessInfo.ThermalState) -> String {
        switch state {
        case .nominal: return "nominal"
        case .fair: return "fair"
        case .serious: return "serious"
        case .critical: return "critical"
        @unknown default: return "nominal"
        }
    }

    /// `iPhone15,4` 같은 하드웨어 식별자다. 시뮬레이터는 실행 중인 기기 식별자를 쓴다.
    static let model: String = {
        if let simulated = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] { return simulated }
        var info = utsname()
        uname(&info)
        let identifier = withUnsafeBytes(of: &info.machine) { buffer in
            String(decoding: buffer.prefix { $0 != 0 }, as: UTF8.self)
        }
        return identifier.isEmpty ? "unknown" : identifier
    }()
}
