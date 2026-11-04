import Foundation
import Security

/// 계정을 마지막으로 온라인에서 확인한 결과다. 관리자 전용 로컬 모드를 오프라인에서도 쓸 수 있게 한다.
/// 시각은 추정 서버 시각으로 둔다. 기기 시계에 서버 `Date` 헤더로 잰 차이를 더한 값이라,
/// 로그인할 때 기기 시계가 틀려도 이용 기간이 늘어나지 않는다.
nonisolated struct OfflineGrant: Codable, Equatable, Sendable {
    static let length: TimeInterval = 14 * 24 * 60 * 60

    enum Status: Equatable, Sendable {
        case valid
        case expired
        /// 기기 시계가 이 이용 권한이 이미 확인한 시각보다 이르다.
        case clockRolledBack
        /// 로그인한 계정이 서버가 확인한 계정과 다르다.
        case otherAccount
    }

    let accountID: String
    let isAdministrator: Bool
    let issuedAt: Date
    let validUntil: Date
    let serverClockOffset: TimeInterval
    private(set) var lastSeenServerTime: Date

    /// `serverDate`는 응답의 `Date` 헤더다. 없으면 기기 시계를 그대로 쓴다.
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

    /// 확인한 시각은 앞으로만 움직이므로 나중에 시계를 되돌리면 알아챌 수 있다.
    func observing(_ deviceNow: Date) -> OfflineGrant {
        var next = self
        next.lastSeenServerTime = max(lastSeenServerTime, serverTime(at: deviceNow))
        return next
    }

    /// access token의 `sub` claim이다. 서버는 여기에 계정의 사용자 ID를 넣는다.
    /// 서명은 검증하지 않고 읽는다. 이 값은 이 기기의 이용 권한을 적용할지만 정한다.
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

    /// HTTP `Date` 헤더(IMF-fixdate)를 파싱한다.
    static func serverDate(fromHTTPDate value: String?) -> Date? {
        guard let value else { return nil }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "GMT")
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        return formatter.date(from: value)
    }

    /// 새 로컬 촬영에는 이 계정의 관리자 이용 권한만 있으면 된다. 기간이 지났거나 시계가 되돌려진
    /// 이용 권한이어도 로컬 녹화는 계속되고, 다음 온라인 확인 때 갱신된다.
    func allowsLocalTake(at deviceNow: Date, accountID: String?) -> Bool {
        isAdministrator && status(at: deviceNow, accountID: accountID) != .otherAccount
    }

    static var unconfirmedMessage: String {
        String(localized: "이 계정의 로컬 녹화 권한을 확인하지 못했습니다. 인터넷에 연결해 한 번 로그인 상태를 확인해 주세요.")
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
