import Foundation

enum BroadcastPlanMode: String, CaseIterable, Identifiable {
    case hdSingle = "720p_single"
    case fhdSingle = "fhd_single"
    case hdMulti = "720p_multi"
    case fhdMulti = "fhd_multi"

    var id: String { rawValue }
    var title: String {
        switch self {
        case .hdSingle: return String(localized: "720p 단독")
        case .fhdSingle: return String(localized: "FHD 단독")
        case .hdMulti: return String(localized: "720p 동시")
        case .fhdMulti: return String(localized: "FHD 동시")
        }
    }
    var referenceMultiplier: Int {
        switch self {
        case .hdSingle: return 1
        case .fhdSingle, .hdMulti: return 2
        case .fhdMulti: return 3
        }
    }
    static func current(resolution: String?, targetCount: Int) -> Self {
        if resolution == "fhd" { return targetCount > 1 ? .fhdMulti : .fhdSingle }
        return targetCount > 1 ? .hdMulti : .hdSingle
    }
}

struct UserPlan: Decodable, Equatable {
    let plan: String
    let allowedModes: [String]
    let monthlyBroadcastSeconds: Int
    let maxPerBroadcastSeconds: Int
    var monthlyDeviceSeconds: Int? = nil
    var deviceModes: [String]? = nil
    enum CodingKeys: String, CodingKey {
        case plan
        case allowedModes = "allowed_modes"
        case monthlyBroadcastSeconds = "monthly_broadcast_seconds"
        case maxPerBroadcastSeconds = "max_per_broadcast_seconds"
        case monthlyDeviceSeconds = "monthly_device_seconds"
        case deviceModes = "device_modes"
    }
}

struct UserUsage: Decodable, Equatable {
    let plan: String
    let usedSeconds: Int
    let remainingSeconds: Int?
    let availableByMode: [ModeAvailability]
    let deviceLimitSeconds: Int?
    let deviceUsedSeconds: Int?
    let deviceRemainingSeconds: Int?
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        plan = try c.decode(String.self, forKey: .plan)
        usedSeconds = try c.decode(Int.self, forKey: .usedSeconds)
        remainingSeconds = try c.decode(Int?.self, forKey: .remainingSeconds)
        availableByMode = try c.decode([ModeAvailability].self, forKey: .availableByMode)
        deviceLimitSeconds = try c.decodeIfPresent(Int.self, forKey: .deviceLimitSeconds)
        deviceUsedSeconds = try c.decodeIfPresent(Int.self, forKey: .deviceUsedSeconds)
        deviceRemainingSeconds = try c.decodeIfPresent(Int.self, forKey: .deviceRemainingSeconds)
        guard [deviceLimitSeconds, deviceUsedSeconds, deviceRemainingSeconds].allSatisfy({ $0.map { $0 >= 0 } ?? true }) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Invalid device usage"))
        }
    }
    enum CodingKeys: String, CodingKey {
        case plan
        case usedSeconds = "used_seconds"
        case remainingSeconds = "remaining_seconds"
        case availableByMode = "available_by_mode"
        case deviceLimitSeconds = "device_limit_seconds"
        case deviceUsedSeconds = "device_used_seconds"
        case deviceRemainingSeconds = "device_remaining_seconds"
    }
}

struct ModeAvailability: Decodable, Equatable {
    let mode: String
    let allowed: Bool
    let seconds: Int?
    let multiplier: Int
    init(mode: String, allowed: Bool, seconds: Int?, multiplier: Int) {
        self.mode = mode
        self.allowed = allowed
        self.seconds = seconds
        self.multiplier = multiplier
    }
    enum CodingKeys: String, CodingKey { case mode, allowed, seconds, multiplier }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        mode = try c.decode(String.self, forKey: .mode)
        allowed = try c.decode(Bool.self, forKey: .allowed)
        seconds = try c.decode(Int?.self, forKey: .seconds)
        multiplier = try c.decode(Int.self, forKey: .multiplier)
        guard multiplier > 0, seconds.map({ $0 >= 0 }) ?? true else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Invalid mode availability"))
        }
    }
}

struct PlanSnapshot: Equatable {
    let plan: UserPlan
    let usage: UserUsage
    func availability(for mode: BroadcastPlanMode, processing: AIProcessingMode = .server) -> ModeAvailability? {
        if processing == .server { return usage.availableByMode.first { $0.mode == mode.rawValue } }
        guard let modes = plan.deviceModes, let remaining = usage.deviceRemainingSeconds else { return nil }
        return ModeAvailability(mode: mode.rawValue, allowed: modes.contains(mode.rawValue),
                                seconds: remaining / mode.referenceMultiplier, multiplier: mode.referenceMultiplier)
    }
    func isAllowed(_ mode: BroadcastPlanMode, processing: AIProcessingMode = .server) -> Bool {
        if processing == .onDevice { return plan.deviceModes?.contains(mode.rawValue) == true }
        return plan.monthlyBroadcastSeconds > 0 && plan.allowedModes.contains(mode.rawValue)
            && availability(for: mode)?.allowed == true
    }
    func canPrepare(_ mode: BroadcastPlanMode, processing: AIProcessingMode = .server) -> Bool {
        isAllowed(mode, processing: processing)
            && availability(for: mode, processing: processing).map { $0.seconds == nil || $0.seconds! > 0 } == true
    }
    // Missing additive fields are unknown: let the server validate instead of reporting exhaustion.
    func preparationBlockMessage(_ mode: BroadcastPlanMode, processing: AIProcessingMode) -> String? {
        if processing == .server && (plan.monthlyBroadcastSeconds == 0 || plan.allowedModes.isEmpty) {
            return String(localized: "이 요금제는 서버 송출을 지원하지 않습니다. 방송 준비를 취소한 뒤 온디바이스 방식을 선택해 주세요.")
        }
        if processing == .onDevice && plan.deviceModes == nil { return nil }
        if !isAllowed(mode, processing: processing) {
            return String(localized: "현재 요금제에서 허용하지 않는 방송 방식입니다. 요금제 및 사용량을 확인해 주세요.")
        }
        guard let value = availability(for: mode, processing: processing), let seconds = value.seconds, seconds == 0 else { return nil }
        return processing == .server
            ? String(localized: "이번 달 서버 시간을 모두 사용했습니다. 온디바이스 잔여 시간을 확인해 주세요.")
            : String(localized: "선택한 방식으로 사용할 온디바이스 시간이 부족합니다. 요금제 및 사용량을 확인해 주세요.")
    }

}

enum PlanTimeText {
    static func duration(_ seconds: Int?) -> String {
        guard let seconds else { return String(localized: "무제한") }
        guard seconds > 0 else { return String(localized: "소진") }
        let hours = seconds / 3600
        let minutes = (seconds % 3600) / 60
        let remainder = seconds % 60
        return String(format: "%02d:%02d:%02d", hours, minutes, remainder)
    }
    static func remaining(_ time: BroadcastRemainingTime) -> String {
        switch time {
        case .missing: return String(localized: "조회 대기")
        case .unlimitedOrInactive: return String(localized: "무제한")
        case .seconds(let seconds): return duration(seconds)
        }
    }
}

// The same notices arrive through session polling and direct-publish heartbeat.
extension BroadcastNotice {
    var planMessage: String? {
        switch code {
        case "monthly_usage_80": String(localized: "현재 처리 위치의 월 시간을 80% 사용했습니다.")
        case "monthly_usage_100": String(localized: "현재 처리 위치의 월 시간을 모두 사용했습니다. 진행 중 방송은 서버 정책에 따라 유지됩니다.")
        case "monthly_limit_reached": String(localized: "월 방송 한도에 도달해 방송이 종료되었습니다.")
        case "broadcast_limit_30m": String(localized: "1회 방송 한도까지 30분 남았습니다.")
        case "broadcast_limit_10m": String(localized: "1회 방송 한도까지 10분 남았습니다.")
        case "broadcast_limit_reached": String(localized: "1회 방송 한도에 도달해 방송이 종료되었습니다.")
        case "no_input_stopped": String(localized: "영상 입력이 없어 방송이 종료되었습니다.")
        default: nil
        }
    }
}
