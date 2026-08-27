import Foundation

enum YouTubeBroadcastPrivacy: String, CaseIterable, Codable, Identifiable {
    case `public`
    case unlisted
    case `private`

    var id: String { rawValue }

    var title: String {
        switch self {
        case .public: return "공개"
        case .unlisted: return "일부 공개"
        case .private: return "비공개"
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
        case .notMadeForKids: return "아동용 아님"
        case .madeForKids: return "아동용"
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

    static var saved: Self? {
        if let rawValue = UserDefaults.standard.string(forKey: storageKey),
           let audience = Self(rawValue: rawValue) {
            return audience
        }
#if DEBUG
        return .notMadeForKids
#else
        return nil
#endif
    }
}

struct YouTubeBroadcastSettings: Codable, Equatable {
    static let maxTitleLength = 100
    static let maxDescriptionLength = 5_000

    var title: String
    var description: String
    var privacy: YouTubeBroadcastPrivacy
    var audience: YouTubeBroadcastAudience?

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
            return "InnoLive 방송"
        }
        return String(format: "%04d%02d%02d InnoLive 방송", year, month, day)
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
}

struct YouTubeChannel: Codable, Equatable {
    let id: String
    let title: String
}

struct YouTubeBroadcastSession: Decodable, Equatable {
    let sessionID: String
    let ownerToken: String
    let stream: YouTubeStreamState

    enum CodingKeys: String, CodingKey {
        case sessionID = "session_id"
        case ownerToken = "owner_token"
        case stream
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

struct YouTubeSessionResponse: Decodable {
    let stream: YouTubeStreamState
    let media: YouTubeSessionMedia
}

struct YouTubeSessionMedia: Decodable {
    let anonymizationEnabled: Bool?
    let rawVideoTrack: YouTubeVideoTrackState?

    enum CodingKeys: String, CodingKey {
        case anonymizationEnabled = "anonymization_enabled"
        case rawVideoTrack = "raw_video_track"
    }
}
