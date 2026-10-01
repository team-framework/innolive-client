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
    enum CodingKeys: String, CodingKey {
        case plan
        case allowedModes = "allowed_modes"
        case monthlyBroadcastSeconds = "monthly_broadcast_seconds"
        case maxPerBroadcastSeconds = "max_per_broadcast_seconds"
    }
}

struct UserUsage: Decodable, Equatable {
    let plan: String
    let usedSeconds: Int
    let remainingSeconds: Int?
    let availableByMode: [ModeAvailability]
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        plan = try c.decode(String.self, forKey: .plan)
        usedSeconds = try c.decode(Int.self, forKey: .usedSeconds)
        remainingSeconds = try c.decode(Int?.self, forKey: .remainingSeconds)
        availableByMode = try c.decode([ModeAvailability].self, forKey: .availableByMode)
    }
    enum CodingKeys: String, CodingKey {
        case plan
        case usedSeconds = "used_seconds"
        case remainingSeconds = "remaining_seconds"
        case availableByMode = "available_by_mode"
    }
}

struct ModeAvailability: Decodable, Equatable {
    let mode: String
    let allowed: Bool
    let seconds: Int?
    let multiplier: Int
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
    func availability(for mode: BroadcastPlanMode) -> ModeAvailability? {
        usage.availableByMode.first { $0.mode == mode.rawValue }
    }
    func isAllowed(_ mode: BroadcastPlanMode) -> Bool {
        plan.allowedModes.contains(mode.rawValue) && availability(for: mode)?.allowed == true
    }
    func canPrepare(_ mode: BroadcastPlanMode) -> Bool {
        isAllowed(mode) && availability(for: mode).map { $0.seconds == nil || $0.seconds! > 0 } == true
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
