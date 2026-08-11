import AuthenticationServices
import Combine
import Foundation
import Security

@MainActor
final class AuthSession: ObservableObject {
    @Published private(set) var isAuthenticated = false
    @Published private(set) var isLoading = false
    @Published private(set) var errorMessage: String?

    private let api = AuthenticationAPI()
    private let tokenStore = AuthenticationTokenStore()
    private var pendingSignup: PendingSignup?

    func restore() { isAuthenticated = tokenStore.load() != nil }

    func currentAccessToken() -> String? { tokenStore.load()?.accessToken }

    func clearError() { errorMessage = nil }

    func showError(_ message: String) { errorMessage = message }

    func signIn(email: String, password: String) async {
        clearError()
        let normalizedEmail = Self.normalizedEmail(email)
        guard Self.isValidEmail(normalizedEmail), !password.isEmpty else {
            errorMessage = "이메일 주소와 비밀번호를 입력해 주세요."
            return
        }
        await authenticate { try await self.api.emailSignIn(email: normalizedEmail, password: password) }
    }

    func startSignup(email: String, password: String) async -> Bool {
        clearError()
        let normalizedEmail = Self.normalizedEmail(email)
        guard Self.isValidEmail(normalizedEmail) else {
            errorMessage = "올바른 이메일 주소를 입력해 주세요."
            return false
        }
        guard Self.isValidSignupPassword(password) else {
            errorMessage = "비밀번호는 8자 이상, 72바이트 이하로 입력해 주세요."
            return false
        }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let signupToken = try await api.startEmailSignup(email: normalizedEmail, password: password)
            pendingSignup = PendingSignup(email: normalizedEmail, password: password, token: signupToken)
            return true
        } catch {
            errorMessage = message(for: error)
            return false
        }
    }

    func verifySignup(code: String) async {
        clearError()
        guard let pendingSignup else {
            errorMessage = "회원가입 인증 시간이 만료됐습니다. 다시 시작해 주세요."
            return
        }
        guard code.count == 6, code.allSatisfy(\.isNumber) else {
            errorMessage = "6자리 인증 코드를 입력해 주세요."
            return
        }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            try await api.verifyEmail(signupToken: pendingSignup.token, code: code)
            let tokens = try await api.emailSignIn(email: pendingSignup.email, password: pendingSignup.password)
            try tokenStore.save(tokens)
            self.pendingSignup = nil
            isAuthenticated = true
        } catch {
            errorMessage = message(for: error)
        }
    }

    func resendSignup() async -> Bool {
        guard let pendingSignup else {
            errorMessage = "회원가입 인증 시간이 만료됐습니다. 다시 시작해 주세요."
            return false
        }
        return await startSignup(email: pendingSignup.email, password: pendingSignup.password)
    }

    func cancelSignup() {
        pendingSignup = nil
        errorMessage = nil
    }

    func signInWithGoogle(idToken: String) async {
        clearError()
        guard !idToken.isEmpty else {
            errorMessage = "Google 로그인 정보를 받지 못했습니다."
            return
        }
        await authenticate { try await self.api.googleSignIn(idToken: idToken) }
    }

    func signInWithApple(credential: ASAuthorizationAppleIDCredential, nonce: String) async {
        clearError()
        guard let authorizationCode = credential.authorizationCode,
              let code = String(data: authorizationCode, encoding: .utf8),
              !code.isEmpty else {
            errorMessage = "Apple 로그인 정보를 받지 못했습니다."
            return
        }
        await authenticate {
            try await self.api.appleSignIn(
                authorizationCode: code,
                nonce: nonce,
                givenName: credential.fullName?.givenName,
                familyName: credential.fullName?.familyName
            )
        }
    }

    func signOut() {
        tokenStore.remove()
        pendingSignup = nil
        errorMessage = nil
        isAuthenticated = false
    }

    func expireSession() {
        tokenStore.remove()
        pendingSignup = nil
        errorMessage = "로그인이 만료되었습니다. 다시 로그인해 주세요."
        isAuthenticated = false
    }

    func makeNonce() -> String {
        let characters = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._")
        var nonce = ""
        while nonce.count < 32 {
            var random: UInt8 = 0
            guard SecRandomCopyBytes(kSecRandomDefault, 1, &random) == errSecSuccess else { return UUID().uuidString }
            if random < characters.count { nonce.append(characters[Int(random)]) }
        }
        return nonce
    }

    static func normalizedEmail(_ email: String) -> String {
        email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    static func isValidEmail(_ email: String) -> Bool {
        email.range(of: #"^[^\s@]+@[^\s@]+\.[^\s@]+$"#, options: .regularExpression) != nil
    }

    static func isValidSignupPassword(_ password: String) -> Bool { (8...72).contains(password.utf8.count) }

    private func authenticate(_ action: () async throws -> AuthenticationTokenPair) async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            try tokenStore.save(try await action())
            isAuthenticated = true
        } catch {
            errorMessage = message(for: error)
        }
    }

    private func message(for error: Error) -> String {
        guard let error = error as? AuthenticationError else {
            return "인증 서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요."
        }
        switch error {
        case let .api(code, fallback):
            switch code {
            case "email_already_registered": return "이미 가입된 이메일입니다. 로그인해 주세요."
            case "invalid_email_credentials": return "이메일 또는 비밀번호가 올바르지 않습니다."
            case "invalid_verification_code": return "인증 코드가 올바르지 않거나 만료됐습니다."
            case "invalid_signup_token": return "회원가입 인증 시간이 만료됐습니다. 다시 시작해 주세요."
            case "invalid_google_token": return "Google 로그인을 확인하지 못했습니다. 다시 시도해 주세요."
            case "invalid_apple_token": return "Apple 로그인을 확인하지 못했습니다. 다시 시도해 주세요."
            case "email_delivery_unavailable", "email_delivery_failed", "email_auth_unavailable": return "인증 메일을 보낼 수 없습니다. 잠시 후 다시 시도해 주세요."
            default: return fallback
            }
        case .configuration: return "인증 서버 설정이 필요합니다."
        case .storage: return "로그인 정보를 안전하게 저장하지 못했습니다. 다시 시도해 주세요."
        case .response: return "로그인 서버 응답을 확인하지 못했습니다. 다시 시도해 주세요."
        }
    }
}

private struct PendingSignup { let email: String; let password: String; let token: String }

enum AuthenticationSessionExpiration {
    static let notification = Notification.Name("com.framework.innolive.authentication.expired")

    static func notify() {
        NotificationCenter.default.post(name: notification, object: nil)
    }
}

private struct AuthenticationTokenPair: Codable {
    let accessToken: String
    let refreshToken: String
    enum CodingKeys: String, CodingKey { case accessToken = "access_token"; case refreshToken = "refresh_token" }
}

private final class AuthenticationTokenStore {
    private let service = "com.framework.innolive.authentication"
    private let account = "token-pair"

    func save(_ tokens: AuthenticationTokenPair) throws {
        remove()
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: try JSONEncoder().encode(tokens),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        guard SecItemAdd(query as CFDictionary, nil) == errSecSuccess else { throw AuthenticationError.storage }
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

private struct AuthenticationAPI {
    func emailSignIn(email: String, password: String) async throws -> AuthenticationTokenPair {
        try await request("/auth/sign-in", body: EmailCredentials(email: email, password: password))
    }
    func startEmailSignup(email: String, password: String) async throws -> String {
        let response: SignupResponse = try await request("/auth/native/sign-up", body: EmailCredentials(email: email, password: password))
        guard !response.signupToken.isEmpty else { throw AuthenticationError.response }
        return response.signupToken
    }
    func verifyEmail(signupToken: String, code: String) async throws {
        let _: StatusResponse = try await request("/auth/native/verify-email", body: EmailVerificationRequest(signupToken: signupToken, verificationCode: code))
    }
    func googleSignIn(idToken: String) async throws -> AuthenticationTokenPair {
        try await request("/auth/google", body: GoogleLoginRequest(idToken: idToken))
    }
    func appleSignIn(authorizationCode: String, nonce: String, givenName: String?, familyName: String?) async throws -> AuthenticationTokenPair {
        try await request("/auth/apple", body: AppleLoginRequest(authorizationCode: authorizationCode, nonce: nonce, givenName: givenName, familyName: familyName))
    }

    private func request<Body: Encodable, Response: Decodable>(_ path: String, body: Body) async throws -> Response {
        guard let url = AuthenticationConfiguration.serverURL(path: path) else { throw AuthenticationError.configuration }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = try JSONEncoder().encode(body)
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let response = response as? HTTPURLResponse else { throw AuthenticationError.response }
            guard (200..<300).contains(response.statusCode) else {
                let apiError = try? JSONDecoder().decode(APIErrorResponse.self, from: data)
                throw AuthenticationError.api(code: apiError?.error.code, fallback: apiError?.error.message ?? "요청을 처리하지 못했습니다. 다시 시도해 주세요.")
            }
            do { return try JSONDecoder().decode(Response.self, from: data) }
            catch { throw AuthenticationError.response }
        } catch let error as AuthenticationError { throw error }
        catch { throw AuthenticationError.response }
    }
}

enum AuthenticationConfiguration {
    static func serverURL(path: String) -> URL? {
        guard let value = configuredServerURL,
              var components = URLComponents(string: value),
              components.scheme == "https" || components.scheme == "http" else { return nil }
        components.path = path
        components.query = nil
        components.fragment = nil
        return components.url
    }

    private static var configuredServerURL: String? {
        nonEmptyValue(ProcessInfo.processInfo.environment["INNOLIVE_SERVER_URL"])
            ?? serverURLFromBundleEnvironmentFile()
            ?? nonEmptyValue(Bundle.main.object(forInfoDictionaryKey: "InnoLiveServerURL") as? String)
                .flatMap { $0.contains("$(") ? nil : $0 }
    }

    private static func serverURLFromBundleEnvironmentFile() -> String? {
        guard let url = Bundle.main.url(forResource: "Server", withExtension: "env")
            ?? Bundle.main.url(forResource: "Server", withExtension: "env", subdirectory: "Config"),
              let content = try? String(contentsOf: url, encoding: .utf8) else { return nil }

        return content
            .split(whereSeparator: \.isNewline)
            .compactMap { line -> String? in
                let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty,
                      !trimmed.hasPrefix("#"),
                      let separator = trimmed.firstIndex(of: "="),
                      trimmed[..<separator].trimmingCharacters(in: .whitespacesAndNewlines) == "INNOLIVE_SERVER_URL" else { return nil }
                return Self.nonEmptyValue(String(trimmed[trimmed.index(after: separator)...]))
            }
            .first
    }

    private static func nonEmptyValue(_ value: String?) -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if trimmed.count >= 2,
           let first = trimmed.first,
           let last = trimmed.last,
           (first == "\"" && last == "\"") || (first == "'" && last == "'") {
            return String(trimmed.dropFirst().dropLast())
        }
        return trimmed
    }
}

private enum AuthenticationError: Error { case api(code: String?, fallback: String); case configuration; case storage; case response }
private struct EmailCredentials: Encodable { let email: String; let password: String }
private struct EmailVerificationRequest: Encodable { let signupToken: String; let verificationCode: String; enum CodingKeys: String, CodingKey { case signupToken = "signup_token"; case verificationCode = "verification_code" } }
private struct GoogleLoginRequest: Encodable { let idToken: String; enum CodingKeys: String, CodingKey { case idToken = "id_token" } }
private struct AppleLoginRequest: Encodable { let authorizationCode: String; let nonce: String; let givenName: String?; let familyName: String?; enum CodingKeys: String, CodingKey { case authorizationCode = "authorization_code"; case nonce; case givenName = "given_name"; case familyName = "family_name" } }
private struct SignupResponse: Decodable { let signupToken: String; enum CodingKeys: String, CodingKey { case signupToken = "signup_token" } }
private struct StatusResponse: Decodable { let status: String }
private struct APIErrorResponse: Decodable { struct Details: Decodable { let code: String?; let message: String? }; let error: Details }
