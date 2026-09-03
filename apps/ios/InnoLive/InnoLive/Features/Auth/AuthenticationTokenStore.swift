import Foundation
import Security

protocol AuthenticationTokenStoring {
    func save(_ tokens: AuthenticationTokenPair) throws
    func load() -> AuthenticationTokenPair?
    func remove()
}

final class AuthenticationTokenStore: AuthenticationTokenStoring {
    private let service = "com.framework.innolive.authentication"
    private let account = "token-pair"

    nonisolated init() {}

    func save(_ tokens: AuthenticationTokenPair) throws {
        let data = try JSONEncoder().encode(tokens)
        let identityQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        let updateStatus = SecItemUpdate(
            identityQuery as CFDictionary,
            [kSecValueData as String: data] as CFDictionary
        )

        switch updateStatus {
        case errSecSuccess:
            return
        case errSecItemNotFound:
            var addQuery = identityQuery
            addQuery[kSecValueData as String] = data
            addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            guard SecItemAdd(addQuery as CFDictionary, nil) == errSecSuccess else {
                throw AuthenticationError.storage
            }
        default:
            throw AuthenticationError.storage
        }
    }

    func load() -> AuthenticationTokenPair? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return try? JSONDecoder().decode(AuthenticationTokenPair.self, from: data)
    }

    func remove() {
        SecItemDelete([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ] as CFDictionary)
    }
}
