import Foundation

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
    let publisherActive: Bool
    let reconnectAttempts: Int
    let stopReason: String?

    enum CodingKeys: String, CodingKey {
        case status
        case startedAt = "started_at"
        case publisherActive = "publisher_active"
        case reconnectAttempts = "reconnect_attempts"
        case stopReason = "stop_reason"
    }

    var startedAtDate: Date? {
        guard let startedAt else { return nil }
        let fractionalFormatter = ISO8601DateFormatter()
        fractionalFormatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return fractionalFormatter.date(from: startedAt)
            ?? ISO8601DateFormatter().date(from: startedAt)
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
    let rawVideoTrack: YouTubeVideoTrackState?

    enum CodingKeys: String, CodingKey {
        case rawVideoTrack = "raw_video_track"
    }
}
