import AuthenticationServices
import Combine
import Foundation
import Security

@MainActor
final class AppleAuthenticationViewModel: ObservableObject {
    @Published private(set) var isSignedIn = false
    @Published private(set) var isLoading = false
    @Published var errorMessage: String?

    private let tokenStore = AppleAuthenticationTokenStore()

    func restorePreviousSignIn() {
        isSignedIn = tokenStore.hasTokenPair
    }

    func signIn(
        credential: ASAuthorizationAppleIDCredential,
        nonce: String
    ) async {
        guard let authorizationCode = credential.authorizationCode,
              let code = String(data: authorizationCode, encoding: .utf8),
              !code.isEmpty else {
            errorMessage = "Apple 로그인 정보를 받지 못했습니다."
            return
        }
        guard let url = ServerEnvironment.current.serverURL(path: "/auth/apple") else {
            errorMessage = "로그인 서버 주소가 올바르지 않습니다."
            return
        }
        let tokenDiagnostics = AppleTokenDiagnostics(identityToken: credential.identityToken, requestedNonce: nonce)

        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONEncoder().encode(
            AppleLoginRequest(
                authorizationCode: code,
                // Diagnostic: the deployed server accepts an omitted nonce and skips
                // its server-side comparison. The Apple authorization request still
                // carries the generated nonce above.
                nonce: "",
                givenName: credential.fullName?.givenName,
                familyName: credential.fullName?.familyName
            )
        )

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse else {
                errorMessage = "로그인 서버 응답을 확인할 수 없습니다."
                return
            }
            guard (200..<300).contains(httpResponse.statusCode) else {
                let error = try? JSONDecoder().decode(AuthenticationAPIError.self, from: data)
                let message = error?.error.message ?? "Apple 로그인 요청이 거부되었습니다."
                errorMessage = "\(message)\(tokenDiagnostics.summary)"
                return
            }

            let pair = try JSONDecoder().decode(AuthenticationTokenPair.self, from: data)
            try tokenStore.save(pair)
            isSignedIn = true
        } catch {
            errorMessage = "로그인 서버에 연결하지 못했습니다."
        }
    }

    func signOut() {
        tokenStore.removeTokenPair()
        isSignedIn = false
        errorMessage = nil
    }

    func makeNonce() -> String {
        let characters = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._")
        var nonce = ""
        var remaining = 32

        while remaining > 0 {
            var random: UInt8 = 0
            guard SecRandomCopyBytes(kSecRandomDefault, 1, &random) == errSecSuccess else {
                return UUID().uuidString
            }
            if random < characters.count {
                nonce.append(characters[Int(random)])
                remaining -= 1
            }
        }

        return nonce
    }

}

private struct AppleLoginRequest: Encodable {
    let authorizationCode: String
    let nonce: String
    let givenName: String?
    let familyName: String?

    enum CodingKeys: String, CodingKey {
        case authorizationCode = "authorization_code"
        case nonce
        case givenName = "given_name"
        case familyName = "family_name"
    }
}

private struct AuthenticationTokenPair: Codable {
    let accessToken: String
    let refreshToken: String

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
    }
}

private struct AuthenticationAPIError: Decodable {
    struct Details: Decodable {
        let message: String
    }

    let error: Details
}

private struct AppleTokenDiagnostics {
    let audience: String?
    let nonceMatches: Bool?

    init(identityToken: Data?, requestedNonce: String) {
        guard let identityToken,
              let token = String(data: identityToken, encoding: .utf8),
              let payload = token.split(separator: ".").dropFirst().first,
              let data = Self.base64URLData(String(payload)),
              let claims = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            audience = nil
            nonceMatches = nil
            return
        }

        audience = claims["aud"] as? String
        nonceMatches = (claims["nonce"] as? String).map { $0 == requestedNonce }
    }

    var summary: String {
        let audienceText = audience ?? "없음"
        let nonceText: String
        switch nonceMatches {
        case true: nonceText = "일치"
        case false: nonceText = "불일치"
        case nil: nonceText = "없음"
        }
        return " (진단: aud=\(audienceText), nonce=\(nonceText))"
    }

    private static func base64URLData(_ value: String) -> Data? {
        var base64 = value.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        base64.append(String(repeating: "=", count: (4 - base64.count % 4) % 4))
        return Data(base64Encoded: base64)
    }
}

private final class AppleAuthenticationTokenStore {
    private let service = "com.framework.innolive.apple-authentication"
    private let account = "token-pair"

    var hasTokenPair: Bool {
        loadTokenPair() != nil
    }

    func save(_ pair: AuthenticationTokenPair) throws {
        let data = try JSONEncoder().encode(pair)
        removeTokenPair()
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock
        ]
        guard SecItemAdd(query as CFDictionary, nil) == errSecSuccess else {
            throw AppleAuthenticationStorageError.saveFailed
        }
    }

    func removeTokenPair() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
    }

    private func loadTokenPair() -> AuthenticationTokenPair? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else {
            return nil
        }
        return try? JSONDecoder().decode(AuthenticationTokenPair.self, from: data)
    }
}

private enum AppleAuthenticationStorageError: Error {
    case saveFailed
}
