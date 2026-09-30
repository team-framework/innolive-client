import Foundation

final class YouTubePreferencesStore {
    // iOS 18 소멸자 충돌을 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

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

    func removeAccountData() {
        for key in userDefaults.dictionaryRepresentation().keys where key.hasPrefix("com.framework.innolive.broadcast-settings.v2:") {
            userDefaults.removeObject(forKey: key)
        }
        removeConnection()
        userDefaults.removeObject(forKey: Key.broadcastSettings)
        userDefaults.removeObject(forKey: YouTubeBroadcastAudience.storageKey)
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

    func loadScopedYouTubeSettings(key: String) -> YouTubeBroadcastSettings {
        loadScoped(key: key) ?? .defaultValue
    }
    func loadScopedCHZZKSettings(key: String) -> CHZZKBroadcastSettings {
        loadScoped(key: key) ?? CHZZKBroadcastSettings()
    }
    func saveScopedYouTubeSettings(_ settings: YouTubeBroadcastSettings, key: String) {
        saveScoped(settings, key: key)
    }
    func saveScopedCHZZKSettings(_ settings: CHZZKBroadcastSettings, key: String) {
        saveScoped(settings, key: key)
    }
    private func loadScoped<T: Decodable>(key: String) -> T? {
        userDefaults.data(forKey: "com.framework.innolive.broadcast-settings.v2:" + key)
            .flatMap { try? JSONDecoder().decode(T.self, from: $0) }
    }
    private func saveScoped<T: Encodable>(_ value: T, key: String) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        userDefaults.set(data, forKey: "com.framework.innolive.broadcast-settings.v2:" + key)
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
