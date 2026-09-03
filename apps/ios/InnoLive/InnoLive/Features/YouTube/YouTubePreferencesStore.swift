import Foundation

final class YouTubePreferencesStore {
    private enum Key {
        static let connection = "com.framework.innolive.youtube.connection"
        static let broadcastSettings = "com.framework.innolive.youtube.broadcast-settings.v1"
    }

    private let userDefaults: UserDefaults

    init(userDefaults: UserDefaults = .standard) {
        self.userDefaults = userDefaults
    }

    func loadConnection() -> YouTubeConnection? {
        guard let data = userDefaults.data(forKey: Key.connection) else { return nil }
        return try? JSONDecoder().decode(YouTubeConnection.self, from: data)
    }

    func saveConnection(_ connection: YouTubeConnection) {
        guard let data = try? JSONEncoder().encode(connection) else { return }
        userDefaults.set(data, forKey: Key.connection)
    }

    func removeConnection() {
        userDefaults.removeObject(forKey: Key.connection)
    }

    func loadBroadcastSettings() -> YouTubeBroadcastSettings {
        var settings = userDefaults
            .data(forKey: Key.broadcastSettings)
            .flatMap { try? JSONDecoder().decode(YouTubeBroadcastSettings.self, from: $0) }
            ?? .defaultValue

        if settings.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            settings.title = YouTubeBroadcastSettings.defaultTitle()
        }
        if settings.audience == nil,
           let savedAudience = loadSavedAudience() {
            settings.audience = savedAudience
        }
        return settings.normalized
    }

    func saveBroadcastSettings(_ settings: YouTubeBroadcastSettings) {
        guard let data = try? JSONEncoder().encode(settings.normalized) else { return }
        userDefaults.set(data, forKey: Key.broadcastSettings)

        if let audience = settings.audience {
            userDefaults.set(audience.rawValue, forKey: YouTubeBroadcastAudience.storageKey)
        } else {
            userDefaults.removeObject(forKey: YouTubeBroadcastAudience.storageKey)
        }
    }

    private func loadSavedAudience() -> YouTubeBroadcastAudience? {
        if let rawValue = userDefaults.string(forKey: YouTubeBroadcastAudience.storageKey),
           let audience = YouTubeBroadcastAudience(rawValue: rawValue) {
            return audience
        }
#if DEBUG
        return .notMadeForKids
#else
        return nil
#endif
    }
}
