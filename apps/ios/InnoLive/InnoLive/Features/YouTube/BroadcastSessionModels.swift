import Foundation

/// 서버 세션의 얼굴·번호판 블러 강도와 영역이다(server #484). nil 필드는 서버 AI
/// 기본값이며 0과 다르다. 요청에서도 nil 필드는 빠져 기본값으로 돌아간다.
struct BroadcastMosaicConfig: Codable, Equatable {
    static let defaultBlurRadius = 24
    static let defaultMaskMargin = 8
    // 서버는 blur_radius를 0 초과 64 이하로 받는다. 슬라이더는 정수 1~64만 쓴다.
    static let blurRadiusRange = 1...64
    static let maskMarginRange = 0...64

    var blurRadius: Double?
    var pixelSize: Int?
    var maskMargin: Int?

    enum CodingKeys: String, CodingKey {
        case blurRadius = "blur_radius"
        case pixelSize = "pixel_size"
        case maskMargin = "mask_margin"
    }

    var effectiveBlurRadius: Int { blurRadius.map { Int($0.rounded()) } ?? Self.defaultBlurRadius }
    var effectiveMaskMargin: Int { maskMargin ?? Self.defaultMaskMargin }

    /// 슬라이더 값을 서버 허용 범위로 맞춘다. pixel_size는 iOS에서 바꾸지 않고 유지한다.
    func adjusted(blurRadius radius: Int?, maskMargin margin: Int?) -> Self {
        var config = self
        config.blurRadius = radius.map { Double(min(max($0, Self.blurRadiusRange.lowerBound), Self.blurRadiusRange.upperBound)) }
        config.maskMargin = margin.map { min(max($0, Self.maskMarginRange.lowerBound), Self.maskMarginRange.upperBound) }
        return config
    }
}

enum BroadcastRemainingTime: Equatable {
    case missing
    case unlimitedOrInactive
    case seconds(Int)
}

struct BroadcastTargetState: Decodable, Equatable, Identifiable {
    let provider: String
    var stream: YouTubeStreamState
    var accountID: String? = nil
    var channelTitle: String? = nil
    var videoID: String? = nil

    enum CodingKeys: String, CodingKey {
        case provider, stream
        case accountID = "account_id"
        case channelTitle = "channel_title"
        case videoID = "video_id"
    }

    var id: String { provider }
    var title: String {
        switch provider {
        case "youtube": return channelTitle.map { "YouTube · " + $0 } ?? "YouTube"
        case "chzzk": return "CHZZK"
        default: return provider
        }
    }
}

struct BroadcastNotice: Decodable, Equatable {
    let code: String
    let at: String
}

struct BroadcastPrepareWarning: Decodable, Equatable {
    let code: String
    let message: String
}

struct BroadcastTargetFailure: Decodable, Equatable {
    let provider: String
    let code: String
    let message: String?

    /// 채널 단위 요청 한도로 실패했다(innolive-server#505). 재시도·채널 변경 안내가 필요하다.
    var isRateLimited: Bool { code == "streaming_rate_limited" }
}

struct BroadcastResolutionSwitch: Decodable, Equatable {
    let status: String
    let resolution: String
    let targets: [String]
    let failedTargets: [BroadcastTargetFailure]?
    let startedAt: String
    let finishedAt: String?

    enum CodingKeys: String, CodingKey {
        case status, resolution, targets
        case failedTargets = "failed_targets"
        case startedAt = "started_at"
        case finishedAt = "finished_at"
    }
}

struct BroadcastRestartEffect: Decodable, Equatable {
    let provider: String
    let sameLink: Bool
    let gapSeconds: Int?

    enum CodingKeys: String, CodingKey {
        case provider
        case sameLink = "same_link"
        case gapSeconds = "gap_seconds"
    }
}

struct BroadcastUpgradeOption: Decodable, Equatable {
    let mode: String
    let resolution: String
    let targets: [String]
    let unitsTo: Int
    let remainingSecondsAfter: BroadcastRemainingTime
    let needsSettings: Bool
    let restartsBroadcast: Bool
    let restartEffects: [BroadcastRestartEffect]

    enum CodingKeys: String, CodingKey {
        case mode, resolution, targets
        case unitsTo = "units_to"
        case remainingSecondsAfter = "remaining_seconds_after"
        case needsSettings = "needs_settings"
        case restartsBroadcast = "restarts_broadcast"
        case restartEffects = "restart_effects"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        mode = try container.decode(String.self, forKey: .mode)
        resolution = try container.decode(String.self, forKey: .resolution)
        targets = try container.decode([String].self, forKey: .targets)
        unitsTo = try container.decode(Int.self, forKey: .unitsTo)
        remainingSecondsAfter = try container.remainingTime(forKey: .remainingSecondsAfter)
        needsSettings = try container.decode(Bool.self, forKey: .needsSettings)
        restartsBroadcast = try container.decode(Bool.self, forKey: .restartsBroadcast)
        restartEffects = try container.decodeIfPresent([BroadcastRestartEffect].self, forKey: .restartEffects) ?? []
    }
}

struct BroadcastUpgradeOffer: Decodable, Equatable {
    let resolution: String
    let mode: String?
    let unitsFrom: Int
    let unitsTo: Int
    let remainingSecondsAfter: BroadcastRemainingTime
    let options: [BroadcastUpgradeOption]?
    let selected: String?
    let expiresAt: String

    enum CodingKeys: String, CodingKey {
        case resolution, mode, options, selected
        case unitsFrom = "units_from"
        case unitsTo = "units_to"
        case remainingSecondsAfter = "remaining_seconds_after"
        case expiresAt = "expires_at"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        resolution = try container.decode(String.self, forKey: .resolution)
        mode = try container.decodeIfPresent(String.self, forKey: .mode)
        unitsFrom = try container.decode(Int.self, forKey: .unitsFrom)
        unitsTo = try container.decode(Int.self, forKey: .unitsTo)
        remainingSecondsAfter = try container.remainingTime(forKey: .remainingSecondsAfter)
        options = try container.decodeIfPresent([BroadcastUpgradeOption].self, forKey: .options)
        selected = try container.decodeIfPresent(String.self, forKey: .selected)
        expiresAt = try container.decode(String.self, forKey: .expiresAt)
    }
}

private extension KeyedDecodingContainer {
    func remainingTime(forKey key: Key) throws -> BroadcastRemainingTime {
        guard contains(key) else { return .missing }
        return try decodeIfPresent(Int.self, forKey: key).map(BroadcastRemainingTime.seconds) ?? .unlimitedOrInactive
    }
}

struct BroadcastSessionDetails: Decodable, Equatable {
    var provider: String? = nil
    var broadcastResolution: String? = nil
    var targets: [BroadcastTargetState]? = nil
    var notices: [BroadcastNotice] = []
    var remainingTime: BroadcastRemainingTime = .missing
    var resolutionSwitch: BroadcastResolutionSwitch? = nil
    var upgradeOffer: BroadcastUpgradeOffer? = nil
    var warnings: [BroadcastPrepareWarning] = []
    var failedTargets: [BroadcastTargetFailure] = []

    /// 단일 대상 세션은 서버가 provider를 보내지 않을 수 있어 YouTube를 기본값으로 쓴다.
    var resolvedProvider: String { provider ?? "youtube" }

    enum CodingKeys: String, CodingKey {
        case provider, targets, notices, warnings
        case failedTargets = "failed_targets"
        case broadcastResolution = "broadcast_resolution"
        case remainingTime = "broadcast_remaining_seconds"
        case resolutionSwitch = "resolution_switch"
        case upgradeOffer = "upgrade_offer"
    }
}

extension BroadcastSessionDetails {
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        provider = try container.decodeIfPresent(String.self, forKey: .provider)
        broadcastResolution = try container.decodeIfPresent(String.self, forKey: .broadcastResolution)
        targets = try container.decodeIfPresent([BroadcastTargetState].self, forKey: .targets)
        notices = try container.decodeIfPresent([BroadcastNotice].self, forKey: .notices) ?? []
        remainingTime = try container.remainingTime(forKey: .remainingTime)
        resolutionSwitch = try container.decodeIfPresent(BroadcastResolutionSwitch.self, forKey: .resolutionSwitch)
        upgradeOffer = try container.decodeIfPresent(BroadcastUpgradeOffer.self, forKey: .upgradeOffer)
        warnings = try container.decodeIfPresent([BroadcastPrepareWarning].self, forKey: .warnings) ?? []
        failedTargets = try container.decodeIfPresent([BroadcastTargetFailure].self, forKey: .failedTargets) ?? []
    }
}

struct BroadcastSessionSnapshot: Equatable {
    var stream: YouTubeStreamState? = nil
    var media: YouTubeSessionMedia? = nil
    var details = BroadcastSessionDetails()

    mutating func apply(_ response: YouTubeSessionResponse, provider: String? = nil, clearsWarnings: Bool = false) {
        if response.isFullSnapshot {
            stream = response.stream
            let previousWarnings = details.warnings
            details = response.details
            if !clearsWarnings && details.warnings.isEmpty { details.warnings = previousWarnings }
        } else {
            if let targets = response.details.targets { details.targets = targets }
            details.failedTargets = response.details.failedTargets
            let targetProvider = provider ?? details.resolvedProvider
            if let index = details.targets?.firstIndex(where: { $0.provider == targetProvider }) {
                details.targets?[index].stream = response.stream
            }
            if details.targets == nil || targetProvider == details.resolvedProvider {
                stream = response.stream
            }
        }
        if response.hasMedia { media = response.media }
    }
}
