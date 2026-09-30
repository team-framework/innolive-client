import Foundation

enum YouTubeBroadcastPrivacy: String, CaseIterable, Codable, Identifiable {
    case `public`
    case unlisted
    case `private`

    var id: String { rawValue }

    var title: String {
        switch self {
        case .public: return String(localized: "공개")
        case .unlisted: return String(localized: "일부 공개")
        case .private: return String(localized: "비공개")
        }
    }
}

enum YouTubeBroadcastAudience: String, CaseIterable, Codable, Identifiable {
    case notMadeForKids = "not_made_for_kids"
    case madeForKids = "made_for_kids"

    static let storageKey = "youtubeBroadcastAudience"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .notMadeForKids: return String(localized: "아동용 아님")
        case .madeForKids: return String(localized: "아동용")
        }
    }

    var madeForKidsValue: Bool {
        self == .madeForKids
    }

    static var defaultRawValue: String {
#if DEBUG
        Self.notMadeForKids.rawValue
#else
        ""
#endif
    }
}

enum YouTubeBroadcastPhase: RawRepresentable, Equatable, Hashable {
    typealias RawValue = String

    case idle
    case preparing
    case prepared
    case goingLive
    case live
    case unknown(String)

    init(rawValue: String) {
        switch rawValue {
        case "idle": self = .idle
        case "preparing": self = .preparing
        case "prepared": self = .prepared
        case "going_live": self = .goingLive
        case "live": self = .live
        default: self = .unknown(rawValue)
        }
    }

    var rawValue: String {
        switch self {
        case .idle: return "idle"
        case .preparing: return "preparing"
        case .prepared: return "prepared"
        case .goingLive: return "going_live"
        case .live: return "live"
        case let .unknown(rawValue): return rawValue
        }
    }
}

enum YouTubeStreamStatus: RawRepresentable, Equatable, Hashable {
    typealias RawValue = String

    case idle
    case streaming
    case reconnecting
    case reconfiguring
    case paused
    case pausedReconfiguring
    case pausedReconnecting
    case stopped
    case unknown(String)

    init(rawValue: String) {
        switch rawValue {
        case "idle": self = .idle
        case "streaming": self = .streaming
        case "reconnecting": self = .reconnecting
        case "reconfiguring": self = .reconfiguring
        case "paused": self = .paused
        case "paused_reconfiguring": self = .pausedReconfiguring
        case "paused_reconnecting": self = .pausedReconnecting
        case "stopped": self = .stopped
        default: self = .unknown(rawValue)
        }
    }

    var rawValue: String {
        switch self {
        case .idle: return "idle"
        case .streaming: return "streaming"
        case .reconnecting: return "reconnecting"
        case .reconfiguring: return "reconfiguring"
        case .paused: return "paused"
        case .pausedReconfiguring: return "paused_reconfiguring"
        case .pausedReconnecting: return "paused_reconnecting"
        case .stopped: return "stopped"
        case let .unknown(rawValue): return rawValue
        }
    }
}

enum YouTubeVideoTrackReadyState: RawRepresentable, Equatable, Hashable {
    typealias RawValue = String

    case new
    case live
    case ended
    case unknown(String)

    init(rawValue: String) {
        switch rawValue {
        case "new": self = .new
        case "live": self = .live
        case "ended": self = .ended
        default: self = .unknown(rawValue)
        }
    }

    var rawValue: String {
        switch self {
        case .new: return "new"
        case .live: return "live"
        case .ended: return "ended"
        case let .unknown(rawValue): return rawValue
        }
    }
}

struct YouTubeBroadcastSettings: Codable, Equatable {
    static let maxTitleLength = 100
    static let maxDescriptionLength = 5_000

    var title: String
    var description: String
    var privacy: YouTubeBroadcastPrivacy
    var audience: YouTubeBroadcastAudience?
    var categoryID: String? = nil

    static var defaultValue: Self {
        Self(
            title: defaultTitle(),
            description: "",
            privacy: .private,
            audience: YouTubeBroadcastAudience(rawValue: YouTubeBroadcastAudience.defaultRawValue)
        )
    }

    static func defaultTitle(for date: Date = Date()) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let components = calendar.dateComponents([.year, .month, .day], from: date)
        guard let year = components.year,
              let month = components.month,
              let day = components.day else {
            return String(localized: "InnoLive 방송")
        }
        return String(format: String(localized: "%04d%02d%02d InnoLive 방송"), year, month, day)
    }

    var normalized: Self {
        var value = self
        value.title = String(
            title
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(Self.maxTitleLength)
        )
        value.description = String(description.prefix(Self.maxDescriptionLength))
        return value
    }
}

struct YouTubeConnection: Codable, Equatable {
    let provider: String
    let channel: YouTubeChannel
    let requiresReconnection: Bool

    init(provider: String, channel: YouTubeChannel, requiresReconnection: Bool = false) {
        self.provider = provider
        self.channel = channel
        self.requiresReconnection = requiresReconnection
    }

    enum CodingKeys: String, CodingKey {
        case provider
        case channel
        case requiresReconnection = "requires_reconnection"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        provider = try container.decode(String.self, forKey: .provider)
        channel = try container.decode(YouTubeChannel.self, forKey: .channel)
        // Older installs persisted only provider and channel.
        requiresReconnection = try container.decodeIfPresent(Bool.self, forKey: .requiresReconnection) ?? false
    }
}

struct YouTubeChannel: Codable, Equatable {
    let id: String
    let title: String
}

struct YouTubeStreamingAccountSummary: Decodable, Equatable {
    let provider: String
    let channelID: String
    let channelTitle: String
    let connectedAt: String?
    let reconnectRequired: Bool

    enum CodingKeys: String, CodingKey {
        case provider
        case channelID = "channel_id"
        case channelTitle = "channel_title"
        case connectedAt = "connected_at"
        case reconnectRequired = "reconnect_required"
    }

    var youtubeConnection: YouTubeConnection? {
        guard provider == "youtube" else { return nil }
        return YouTubeConnection(
            provider: provider,
            channel: YouTubeChannel(
                id: channelID,
                title: channelTitle.isEmpty ? channelID : channelTitle
            ),
            requiresReconnection: reconnectRequired
        )
    }
}

struct YouTubeBroadcastSession: Decodable, Equatable {
    var aiProcessing: String? = nil
    // Server capability stays fixed; the client can move inference without replacing this session.
    var clientProcessingMode: AIProcessingMode? = nil
    var processingMode: AIProcessingMode { clientProcessingMode ?? AIProcessingMode(rawValue: aiProcessing ?? "server") ?? .server }
    let sessionID: String
    let ownerToken: String
    let stream: YouTubeStreamState
    var details = BroadcastSessionDetails()
    var media: YouTubeSessionMedia? = nil

    enum CodingKeys: String, CodingKey {
        case aiProcessing = "ai_processing"
        case sessionID = "session_id"
        case ownerToken = "owner_token"
        case stream
        case media
    }
}

extension YouTubeBroadcastSession {
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        aiProcessing = try container.decodeIfPresent(String.self, forKey: .aiProcessing)
        sessionID = try container.decode(String.self, forKey: .sessionID)
        ownerToken = try container.decode(String.self, forKey: .ownerToken)
        stream = try container.decode(YouTubeStreamState.self, forKey: .stream)
        media = try container.decodeIfPresent(YouTubeSessionMedia.self, forKey: .media)
        details = try BroadcastSessionDetails(from: decoder)
    }
}

struct YouTubeStreamState: Decodable, Equatable {
    let status: String
    let startedAt: String?
    let stoppedAt: String?
    let publisherActive: Bool
    let lastError: String?
    let reconnectAttempts: Int
    let stopReason: String?
    let pausedAt: String?
    let broadcastPhase: String?

    var statusValue: YouTubeStreamStatus {
        YouTubeStreamStatus(rawValue: status)
    }

    var broadcastPhaseValue: YouTubeBroadcastPhase {
        YouTubeBroadcastPhase(rawValue: broadcastPhase ?? "idle")
    }

    enum CodingKeys: String, CodingKey {
        case status
        case startedAt = "started_at"
        case stoppedAt = "stopped_at"
        case publisherActive = "publisher_active"
        case lastError = "last_error"
        case reconnectAttempts = "reconnect_attempts"
        case stopReason = "stop_reason"
        case pausedAt = "paused_at"
        case broadcastPhase = "broadcast_phase"
    }

    var startedAtDate: Date? {
        date(from: startedAt)
    }

    var pausedAtDate: Date? {
        date(from: pausedAt)
    }

    private func date(from value: String?) -> Date? {
        guard let value else { return nil }
        let fractionalFormatter = ISO8601DateFormatter()
        fractionalFormatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return fractionalFormatter.date(from: value)
            ?? ISO8601DateFormatter().date(from: value)
    }

    func markedStoppedByUser() -> Self {
        Self(
            status: "stopped",
            startedAt: nil,
            stoppedAt: stoppedAt,
            publisherActive: publisherActive,
            lastError: lastError,
            reconnectAttempts: reconnectAttempts,
            stopReason: stopReason ?? "user_requested",
            pausedAt: pausedAt,
            broadcastPhase: "idle"
        )
    }
}

struct YouTubeVideoTrackState: Decodable, Equatable {
    let id: String
    let kind: String
    let readyState: String

    var readyStateValue: YouTubeVideoTrackReadyState {
        YouTubeVideoTrackReadyState(rawValue: readyState)
    }

    enum CodingKeys: String, CodingKey {
        case id
        case kind
        case readyState = "ready_state"
    }
}

struct YouTubeConfiguration: Decodable {
    let webClientID: String
    let scope: String

    enum CodingKeys: String, CodingKey {
        case webClientID = "web_client_id"
        case scope
    }
}

struct YouTubeConnectionResponse: Decodable {
    let connected: Bool
    let provider: String
    let channel: YouTubeChannel
}

struct YouTubeBroadcastVisibility: Decodable {
    let privacy: String
}

struct YouTubeSessionResponse: Decodable {
    var broadcast: YouTubeBroadcastVisibility? = nil
    let stream: YouTubeStreamState
    let media: YouTubeSessionMedia
    var details = BroadcastSessionDetails()
    var isFullSnapshot = true
    var hasMedia = true
}

extension YouTubeSessionResponse {
    private enum CodingKeys: String, CodingKey { case broadcast, stream, media }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        isFullSnapshot = container.contains(.stream)
        stream = isFullSnapshot
            ? try container.decode(YouTubeStreamState.self, forKey: .stream)
            : try YouTubeStreamState(from: decoder)
        broadcast = try container.decodeIfPresent(YouTubeBroadcastVisibility.self, forKey: .broadcast)
        hasMedia = container.contains(.media)
        media = try container.decodeIfPresent(YouTubeSessionMedia.self, forKey: .media)
            ?? YouTubeSessionMedia(anonymizationEnabled: nil, rawVideoTrack: nil)
        details = try BroadcastSessionDetails(from: decoder)
    }
}

struct YouTubeSessionMedia: Decodable, Equatable {
    let anonymizationEnabled: Bool?
    let rawVideoTrack: YouTubeVideoTrackState?

    enum CodingKeys: String, CodingKey {
        case anonymizationEnabled = "anonymization_enabled"
        case rawVideoTrack = "raw_video_track"
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
    var id: String { provider }
    var title: String {
        switch provider {
        case "youtube": return "YouTube"
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
        restartEffects = try container.decode([BroadcastRestartEffect].self, forKey: .restartEffects)
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
            let targetProvider = provider ?? details.provider ?? "youtube"
            if let index = details.targets?.firstIndex(where: { $0.provider == targetProvider }) {
                details.targets?[index].stream = response.stream
            }
            if details.targets == nil || targetProvider == (details.provider ?? "youtube") {
                stream = response.stream
            }
        }
        if response.hasMedia { media = response.media }
    }
}
