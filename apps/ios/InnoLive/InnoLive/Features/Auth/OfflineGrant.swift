import Foundation
import Security

/// The last online confirmation of the account, used to keep admin-only local mode usable offline.
/// Time is kept as estimated server time: the device clock plus the offset measured from the
/// server's `Date` header, so a wrong device clock at sign-in cannot stretch the grant.
nonisolated struct OfflineGrant: Codable, Equatable, Sendable {
    static let length: TimeInterval = 14 * 24 * 60 * 60

    enum Status: Equatable, Sendable {
        case valid
        case expired
        /// The device clock is earlier than a time this grant already observed.
        case clockRolledBack
        /// The signed-in account is not the one the server confirmed.
        case otherAccount
    }

    let accountID: String
    let isAdministrator: Bool
    let issuedAt: Date
    let validUntil: Date
    let serverClockOffset: TimeInterval
    private(set) var lastSeenServerTime: Date

    /// `serverDate` is the response's `Date` header. Without it the device clock is used as is.
    init(accountID: String, isAdministrator: Bool, serverDate: Date?, receivedAt: Date) {
        self.accountID = accountID
        self.isAdministrator = isAdministrator
        issuedAt = serverDate ?? receivedAt
        validUntil = issuedAt.addingTimeInterval(Self.length)
        serverClockOffset = issuedAt.timeIntervalSince(receivedAt)
        lastSeenServerTime = issuedAt
    }

    func serverTime(at deviceNow: Date) -> Date { deviceNow.addingTimeInterval(serverClockOffset) }

    func status(at deviceNow: Date, accountID: String?) -> Status {
        guard accountID == self.accountID else { return .otherAccount }
        let now = serverTime(at: deviceNow)
        if now < lastSeenServerTime { return .clockRolledBack }
        return now < validUntil ? .valid : .expired
    }

    /// The observed clock only moves forward, so setting it back later is detectable.
    func observing(_ deviceNow: Date) -> OfflineGrant {
        var next = self
        next.lastSeenServerTime = max(lastSeenServerTime, serverTime(at: deviceNow))
        return next
    }

    /// The access token's `sub` claim, which the server sets to the account's user ID.
    /// Read without verifying the signature: it only decides whether a grant on this device applies.
    static func accountID(fromAccessToken token: String?) -> String? {
        let parts = token?.split(separator: ".", omittingEmptySubsequences: false) ?? []
        guard parts.count == 3 else { return nil }
        var payload = parts[1].replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        payload += String(repeating: "=", count: (4 - payload.count % 4) % 4)
        guard let data = Data(base64Encoded: payload),
              let claims = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let subject = claims["sub"] as? String, !subject.isEmpty else { return nil }
        return subject
    }

    /// Parses an HTTP `Date` header (IMF-fixdate).
    static func serverDate(fromHTTPDate value: String?) -> Date? {
        guard let value else { return nil }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "GMT")
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        return formatter.date(from: value)
    }

    /// A new local take needs only an administrator grant for this account. An expired or
    /// clock-rolled-back grant keeps local recording working and is renewed by the next online check.
    func allowsLocalTake(at deviceNow: Date, accountID: String?) -> Bool {
        isAdministrator && status(at: deviceNow, accountID: accountID) != .otherAccount
    }

    static var unconfirmedMessage: String {
        String(localized: "이 계정의 로컬 녹화 권한을 확인하지 못했습니다. 인터넷에 연결해 한 번 로그인 상태를 확인해 주세요.")
    }

    static var staleNotice: String {
        String(localized: "오프라인 사용 기간이 지났어요. 녹화는 계속할 수 있고, 인터넷에 연결되면 자동으로 다시 확인해요.")
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
