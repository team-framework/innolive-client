import AppKit
import Combine
import Foundation
import GoogleSignIn
import Security

@MainActor
final class GoogleAuthenticationViewModel: ObservableObject {
    @Published private(set) var isSignedIn = false
    @Published private(set) var isLoading = false
    @Published private(set) var errorMessage: String?

    private let session = URLSession.shared
    private let tokenStore = AuthenticationTokenStore()

    func restorePreviousSignIn() {
        guard tokenStore.hasTokenPair else {
            return
        }

        GIDSignIn.sharedInstance.restorePreviousSignIn { [weak self] user, error in
            Task { @MainActor in
                guard let self, error == nil, user != nil else {
                    return
                }
                self.isSignedIn = true
            }
        }
    }

    func signIn() {
        guard !isLoading else {
            return
        }
        guard let serverClientID = ServerEnvironment.current.googleServerClientID else {
            errorMessage = "Google 로그인 설정이 필요합니다."
            return
        }
        guard let window = NSApplication.shared.keyWindow ?? NSApplication.shared.windows.first(where: { $0.isVisible }) else {
            errorMessage = "로그인 창을 찾을 수 없습니다."
            return
        }

        isLoading = true
        errorMessage = nil
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(
            clientID: GoogleAuthenticationConfiguration.clientID,
            serverClientID: serverClientID
        )
        GIDSignIn.sharedInstance.signIn(withPresenting: window) { [weak self] result, error in
            Task { @MainActor in
                guard let self else {
                    return
                }
                if error != nil {
                    self.isLoading = false
                    self.errorMessage = "Google 로그인을 완료하지 못했습니다. 다시 시도해 주세요."
                    return
                }
                guard let idToken = result?.user.idToken?.tokenString else {
                    self.isLoading = false
                    self.errorMessage = "Google 로그인 정보를 받지 못했습니다."
                    return
                }
                await self.authenticateWithServer(idToken: idToken)
            }
        }
    }

    func signOut() {
        GIDSignIn.sharedInstance.signOut()
        tokenStore.removeTokenPair()
        isSignedIn = false
        errorMessage = nil
    }

    private func authenticateWithServer(idToken: String) async {
        defer { isLoading = false }

        guard let url = ServerEnvironment.current.serverURL(path: "/auth/google") else {
            errorMessage = "로그인 서버 주소가 올바르지 않습니다."
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONEncoder().encode(GoogleLoginRequest(idToken: idToken))

        do {
            let (data, response) = try await session.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse else {
                errorMessage = "로그인 서버 응답을 확인할 수 없습니다."
                return
            }
            guard (200..<300).contains(httpResponse.statusCode) else {
                let error = try? JSONDecoder().decode(AuthenticationAPIError.self, from: data)
                if error?.error.code == "invalid_google_token" {
                    errorMessage = "Google 로그인 설정이 올바르지 않습니다. 잠시 후 다시 시도해 주세요."
                } else {
                    errorMessage = error?.error.message ?? "Google 로그인 요청이 거부되었습니다."
                }
                return
            }

            let pair = try JSONDecoder().decode(AuthenticationTokenPair.self, from: data)
            try tokenStore.save(pair)
            isSignedIn = true
        } catch {
            errorMessage = "로그인 서버에 연결하지 못했습니다."
        }
    }
}

private enum GoogleAuthenticationConfiguration {
    static let clientID = "683016419596-ofaq580fpv1qqglcgsd9t7tm4a6nrf4d.apps.googleusercontent.com"
}

private struct GoogleLoginRequest: Encodable {
    let idToken: String

    enum CodingKeys: String, CodingKey {
        case idToken = "id_token"
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
        let code: String?
        let message: String
    }

    let error: Details
}

private final class AuthenticationTokenStore {
    private let service = "com.framework.InnoLive.authentication"
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
            throw AuthenticationStorageError.saveFailed
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

private enum AuthenticationStorageError: Error {
    case saveFailed
}
