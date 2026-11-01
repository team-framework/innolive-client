import Foundation
import Security

/// The last online confirmation of the account, used to keep admin-only local mode usable offline.
nonisolated struct OfflineGrant: Codable, Equatable, Sendable {
    static let length: TimeInterval = 14 * 24 * 60 * 60

    enum Status: Equatable, Sendable {
        case valid
        case expired
        /// The device clock is earlier than a time this grant already observed.
        case clockRolledBack
    }

    let isAdministrator: Bool
    let issuedAt: Date
    let validUntil: Date
    private(set) var lastSeenWallClock: Date

    init(isAdministrator: Bool, issuedAt: Date) {
        self.isAdministrator = isAdministrator
        self.issuedAt = issuedAt
        validUntil = issuedAt.addingTimeInterval(Self.length)
        lastSeenWallClock = issuedAt
    }

    func status(at now: Date) -> Status {
        if now < lastSeenWallClock { return .clockRolledBack }
        return now < validUntil ? .valid : .expired
    }

    /// The observed clock only moves forward, so setting it back later is detectable.
    func observing(_ now: Date) -> OfflineGrant {
        var next = self
        next.lastSeenWallClock = max(lastSeenWallClock, now)
        return next
    }

    static var expiredMessage: String {
        String(localized: "오프라인 사용 기간이 끝났습니다. 인터넷에 연결해 한 번 로그인 상태를 확인해 주세요.")
    }
}

protocol OfflineGrantStoring {
    func save(_ grant: OfflineGrant)
    func load() -> OfflineGrant?
    func remove()
}

final class OfflineGrantStore: OfflineGrantStoring {
    // iOS 18 소멸자 충돌을 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    private let identity: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "com.framework.innolive.offline-grant",
        kSecAttrAccount as String: "grant"
    ]

    nonisolated init() {}

    func save(_ grant: OfflineGrant) {
        guard let data = try? JSONEncoder().encode(grant) else { return }
        let status = SecItemUpdate(identity as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        guard status == errSecItemNotFound else { return }
        var add = identity
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    func load() -> OfflineGrant? {
        var query = identity
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return try? JSONDecoder().decode(OfflineGrant.self, from: data)
    }

    func remove() { SecItemDelete(identity as CFDictionary) }
}
