import Foundation

private struct YouTubeLiveBroadcastRequest: Encodable {
    let title: String
    let description: String
    let categoryID: String?
    enum CodingKeys: String, CodingKey { case title, description; case categoryID = "category_id" }
}

private struct YouTubeBroadcastSettingsRequest: Encodable {
    let title: String
    let description: String
    let privacy: String
    let madeForKids: Bool?
    let categoryID: String?

    enum CodingKeys: String, CodingKey {
        case title
        case description
        case privacy
        case madeForKids = "made_for_kids"
        case categoryID = "category_id"
    }

    init(settings: YouTubeBroadcastSettings) {
        let settings = settings.normalized
        title = settings.title
        description = settings.description
        privacy = settings.privacy.rawValue
        categoryID = settings.categoryID
        madeForKids = settings.audience?.madeForKidsValue
    }
}

private struct YouTubePrepareStreamRequest: Encodable {
    var provider = "youtube"
    var accountID: String? = nil
    enum CodingKeys: String, CodingKey { case provider; case accountID = "account_id" }
}

private struct BroadcastModeRequest: Encodable {
    let resolution: String
    let targets: [String]
    var accountID: String? = nil
    enum CodingKeys: String, CodingKey { case resolution, targets; case accountID = "account_id" }
}

private struct UpgradeOfferSelectionRequest: Encodable {
    let mode: String
}

private struct AnonymizationRequest: Encodable {
    let enabled: Bool
}

extension YouTubeAPI {
    func saveBroadcastSettings(
        session: YouTubeBroadcastSession,
        accessToken: String,
        settings: YouTubeBroadcastSettings
    ) async throws -> YouTubeSessionResponse {
        try await request(
            path: "/sessions/\(session.sessionID)/broadcast",
            method: "PUT",
            accessToken: accessToken,
            ownerToken: session.ownerToken,
            body: YouTubeBroadcastSettingsRequest(settings: settings),
            queryItems: [URLQueryItem(name: "provider", value: "youtube")]
        )
    }

    func saveCHZZKBroadcastSettings(session: YouTubeBroadcastSession, accessToken: String,
                                    settings: CHZZKBroadcastSettings) async throws -> YouTubeSessionResponse {
        try await request(path: "/sessions/\(session.sessionID)/broadcast", method: "PUT",
                          accessToken: accessToken, ownerToken: session.ownerToken, body: settings,
                          queryItems: [URLQueryItem(name: "provider", value: "chzzk")])
    }

    func updateLiveBroadcast(session: YouTubeBroadcastSession, accessToken: String,
                             provider: BroadcastSettingsProvider, youtube: YouTubeBroadcastSettings,
                             chzzk: CHZZKBroadcastSettings) async throws -> YouTubeSessionResponse {
        if provider == .chzzk {
            var settings = chzzk
            settings.title = settings.title.trimmingCharacters(in: .whitespacesAndNewlines)
            return try await request(path: "/sessions/\(session.sessionID)/broadcast/live", method: "PATCH",
                                     accessToken: accessToken, ownerToken: session.ownerToken, body: settings,
                                     queryItems: [URLQueryItem(name: "provider", value: provider.rawValue)])
        }
        return try await request(path: "/sessions/\(session.sessionID)/broadcast/live", method: "PATCH",
                                 accessToken: accessToken, ownerToken: session.ownerToken,
                                 body: YouTubeLiveBroadcastRequest(title: youtube.title.trimmingCharacters(in: .whitespacesAndNewlines),
                                                                  description: youtube.description, categoryID: youtube.categoryID),
                                 queryItems: [URLQueryItem(name: "provider", value: provider.rawValue)])
    }

    func changeBroadcastMode(session: YouTubeBroadcastSession, accessToken: String,
                             resolution: String, targets: Set<BroadcastSettingsProvider>, accountID: String? = nil) async throws -> YouTubeSessionResponse {
        try await request(path: "/sessions/\(session.sessionID)/broadcast-mode", method: "PUT",
                          accessToken: accessToken, ownerToken: session.ownerToken,
                          body: BroadcastModeRequest(resolution: resolution, targets: targets.map(\.rawValue).sorted(), accountID: accountID))
    }

    func selectUpgradeOffer(session: YouTubeBroadcastSession, accessToken: String,
                            mode: String) async throws -> YouTubeSessionResponse {
        try await request(path: "/sessions/\(session.sessionID)/upgrade-offer/select", method: "POST",
                          accessToken: accessToken, ownerToken: session.ownerToken,
                          body: UpgradeOfferSelectionRequest(mode: mode))
    }

    func declineUpgradeOffer(session: YouTubeBroadcastSession, accessToken: String) async throws -> YouTubeSessionResponse {
        try await request(path: "/sessions/\(session.sessionID)/upgrade-offer", method: "DELETE",
                          accessToken: accessToken, ownerToken: session.ownerToken,
                          body: Optional<YouTubeEmptyRequest>.none)
    }

    func broadcastDefaults(session: YouTubeBroadcastSession, accessToken: String,
                           provider: BroadcastSettingsProvider, accountID: String? = nil) async throws -> BroadcastSettingsDefaults {
        try await request(path: "/sessions/\(session.sessionID)/broadcast/defaults", method: "GET",
                          accessToken: accessToken, ownerToken: session.ownerToken,
                          body: Optional<YouTubeEmptyRequest>.none,
                          queryItems: [URLQueryItem(name: "provider", value: provider.rawValue)] + (accountID.map { [URLQueryItem(name: "account_id", value: $0)] } ?? []))
    }

    func prepareStream(
        session: YouTubeBroadcastSession,
        accessToken: String,
        provider: BroadcastSettingsProvider = .youtube,
        accountID: String? = nil
    ) async throws -> YouTubeSessionResponse {
        try await request(
            path: "/sessions/\(session.sessionID)/stream/prepare",
            method: "POST",
            accessToken: accessToken,
            ownerToken: session.ownerToken,
            body: YouTubePrepareStreamRequest(provider: provider.rawValue, accountID: accountID)
        )
    }

    func goLive(
        session: YouTubeBroadcastSession,
        accessToken: String
    ) async throws -> YouTubeStreamState {
        try await streamAction(.goLive, session: session, accessToken: accessToken).stream
    }

    func stopStream(session: YouTubeBroadcastSession, accessToken: String) async throws -> YouTubeStreamState {
        try await streamAction(.stop, session: session, accessToken: accessToken).stream
    }

    func streamAction(
        _ action: StreamAction,
        session: YouTubeBroadcastSession,
        accessToken: String,
        provider: String? = nil
    ) async throws -> YouTubeSessionResponse {
        return try await request(
            path: "/sessions/\(session.sessionID)/stream/\(action.rawValue)",
            method: "POST",
            accessToken: accessToken,
            ownerToken: session.ownerToken,
            body: Optional<YouTubeEmptyRequest>.none,
            queryItems: provider.map { [URLQueryItem(name: "provider", value: $0)] } ?? []
        )
    }

    func toggleAnonymization(session: YouTubeBroadcastSession, accessToken: String, enabled: Bool) async throws -> YouTubeSessionResponse {
        try await request(
            path: "/sessions/\(session.sessionID)/anonymization",
            method: "PATCH",
            accessToken: accessToken,
            ownerToken: session.ownerToken,
            body: AnonymizationRequest(enabled: enabled)
        )
    }

    func toggleScreenBlur(session: YouTubeBroadcastSession, accessToken: String, enabled: Bool) async throws -> YouTubeSessionResponse {
        try await request(
            path: "/sessions/\(session.sessionID)/screen-blur",
            method: "PATCH",
            accessToken: accessToken,
            ownerToken: session.ownerToken,
            body: AnonymizationRequest(enabled: enabled)
        )
    }

    func updateMosaicConfig(session: YouTubeBroadcastSession, accessToken: String,
                            config: BroadcastMosaicConfig) async throws -> YouTubeSessionResponse {
        try await request(
            path: "/sessions/\(session.sessionID)/mosaic-config",
            method: "PUT",
            accessToken: accessToken,
            ownerToken: session.ownerToken,
            body: config
        )
    }
}
