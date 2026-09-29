import Foundation
import Security

struct BroadcastSessionScope: Codable, Equatable {
    let server: String
    let user: String

    // 로컬 기록을 구분하는 용도이며, 인증과 소유권 검증은 서버가 수행한다.
    init(server: URL, accessToken: String) throws {
        let parts = accessToken.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3 else { throw YouTubeAPIError.unauthorized }
        var payload = String(parts[1]).replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        payload += String(repeating: "=", count: (4 - payload.count % 4) % 4)
        struct Claims: Decodable { let sub: String }
        guard let data = Data(base64Encoded: payload),
              let claims = try? JSONDecoder().decode(Claims.self, from: data),
              !claims.sub.isEmpty else { throw YouTubeAPIError.unauthorized }
        self.server = server.absoluteString
        user = claims.sub
    }

    var storageKey: String {
        // 구분 문자가 사용자 식별자에 포함되어도 충돌하지 않게 구성한다.
        Data(server.utf8).base64EncodedString() + ":" + Data(user.utf8).base64EncodedString()
    }
}

struct StoredBroadcastSession: Codable, Equatable {
    let sessionID: String
    let ownerToken: String
}

protocol BroadcastSessionStoring {
    func load(scope: BroadcastSessionScope) throws -> StoredBroadcastSession?
    func save(_ session: StoredBroadcastSession, scope: BroadcastSessionScope) throws
    func remove(scope: BroadcastSessionScope) throws
}

final class BroadcastSessionStore: BroadcastSessionStoring {
    // iOS 18 소멸자 충돌을 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    private let service: String

    init(service: String = "com.framework.innolive.broadcast-session") {
        self.service = service
    }

    private func query(_ scope: BroadcastSessionScope) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: scope.storageKey]
    }

    func load(scope: BroadcastSessionScope) throws -> StoredBroadcastSession? {
        var query = query(scope)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw AuthenticationError.storage
        }
        return try JSONDecoder().decode(StoredBroadcastSession.self, from: data)
    }

    func save(_ session: StoredBroadcastSession, scope: BroadcastSessionScope) throws {
        let data = try JSONEncoder().encode(session)
        let query = query(scope)
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecSuccess { return }
        guard status == errSecItemNotFound else { throw AuthenticationError.storage }
        var addition = query
        addition[kSecValueData as String] = data
        addition[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        guard SecItemAdd(addition as CFDictionary, nil) == errSecSuccess else {
            throw AuthenticationError.storage
        }
    }

    func remove(scope: BroadcastSessionScope) throws {
        let status = SecItemDelete(query(scope) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw AuthenticationError.storage
        }
    }
}
