import Foundation

struct StreamingAccountSummary: Decodable, Equatable {
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

    var displayTitle: String { channelTitle.isEmpty ? channelID : channelTitle }

    var youtubeConnection: YouTubeConnection? {
        guard provider == "youtube" else { return nil }
        return YouTubeConnection(provider: provider, channel: YouTubeChannel(id: channelID, title: displayTitle),
                                 requiresReconnection: reconnectRequired)
    }
}

// 기존 YouTube 호출부의 소스 호환성을 유지한다.
typealias YouTubeStreamingAccountSummary = StreamingAccountSummary

struct CHZZKConfiguration: Decodable {
    let clientID: String
    let redirectURI: URL
    let authorizeURL: URL

    enum CodingKeys: String, CodingKey {
        case clientID = "client_id"
        case redirectURI = "redirect_uri"
        case authorizeURL = "authorize_url"
    }
}

struct CHZZKConnectionResponse: Decodable {
    struct Channel: Decodable {
        let id: String
        let name: String
        let nickname: String
        enum CodingKeys: String, CodingKey {
            case id = "channelId"
            case name = "channelName"
            case nickname
        }
    }
    let connected: Bool
    let provider: String
    let channel: Channel

    var account: StreamingAccountSummary {
        StreamingAccountSummary(provider: provider, channelID: channel.id,
                                channelTitle: channel.name.isEmpty ? channel.nickname : channel.name,
                                connectedAt: nil, reconnectRequired: false)
    }
}

enum StreamingAccountPolicy {
    static func isInUse(_ provider: String, snapshot: BroadcastSessionSnapshot) -> Bool {
        if snapshot.details.resolutionSwitch?.status == "switching" { return true }
        if let targets = snapshot.details.targets {
            return targets.contains { $0.provider == provider && $0.stream.broadcastPhaseValue != .idle }
        }
        // 종전 단일 대상 응답은 YouTube다. 알 수 없는 phase도 해제를 허용하지 않는다.
        return provider == "youtube" && snapshot.stream.map { $0.broadcastPhaseValue != .idle } == true
    }
}
