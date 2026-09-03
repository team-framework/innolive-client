import AuthenticationServices
import Combine
import Foundation
import Security

@MainActor
final class AuthSession: ObservableObject {
    @Published private(set) var isAuthenticated = false
    @Published private(set) var isLoading = false
    @Published private(set) var errorMessage: String?

    private let api: AuthenticationAPIClient
    private let tokenStore: AuthenticationTokenStoring
    private var pendingSignup: PendingSignup?
    private var tokenRefreshTask: Task<AuthenticationRefreshResult, Never>?
    private var tokenRefreshTaskGeneration: UInt?
    private var sessionGeneration: UInt = 0

    init(
        api: AuthenticationAPIClient = AuthenticationAPI(),
        tokenStore: AuthenticationTokenStoring = AuthenticationTokenStore()
    ) {
        self.api = api
        self.tokenStore = tokenStore
    }

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
            invalidateSessionGeneration()
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
        invalidateSessionGeneration()
        tokenStore.remove()
        pendingSignup = nil
        errorMessage = nil
        isAuthenticated = false
    }

    func expireSession() {
        invalidateSessionGeneration()
        tokenStore.remove()
        pendingSignup = nil
        errorMessage = "로그인이 만료되었습니다. 다시 로그인해 주세요."
        isAuthenticated = false
    }

    func refreshSession() async -> AuthenticationRefreshResult {
        if let tokenRefreshTask {
            return await tokenRefreshTask.value
        }
        guard let currentTokens = tokenStore.load() else { return .invalid }

        let generation = sessionGeneration
        let task: Task<AuthenticationRefreshResult, Never> = Task { @MainActor [weak self, api, tokenStore] in
            do {
                let refreshedTokens = try await api.refresh(refreshToken: currentTokens.refreshToken)
                guard !Task.isCancelled,
                      let self,
                      self.sessionGeneration == generation else {
                    return .unavailable
                }
                try tokenStore.save(refreshedTokens)
                return .refreshed
            } catch let AuthenticationError.api(code, _) where code == "invalid_refresh_token" {
                guard !Task.isCancelled,
                      let self,
                      self.sessionGeneration == generation else {
                    return .unavailable
                }
                return .invalid
            } catch {
                // 네트워크 오류나 서버 오류로 갱신하지 못해도 기존 refresh token은 보존한다.
                return .unavailable
            }
        }
        tokenRefreshTask = task
        tokenRefreshTaskGeneration = generation
        let result = await task.value
        if sessionGeneration == generation, tokenRefreshTaskGeneration == generation {
            tokenRefreshTask = nil
            tokenRefreshTaskGeneration = nil
        }
        return result
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
            let tokens = try await action()
            try tokenStore.save(tokens)
            invalidateSessionGeneration()
            isAuthenticated = true
        } catch {
            errorMessage = message(for: error)
        }
    }

    private func invalidateSessionGeneration() {
        sessionGeneration &+= 1
        tokenRefreshTask?.cancel()
        tokenRefreshTask = nil
        tokenRefreshTaskGeneration = nil
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

enum AuthenticationRefreshResult {
    case refreshed
    case invalid
    case unavailable
}

enum AuthenticationSessionExpiration {
    static let notification = Notification.Name("com.framework.innolive.authentication.expired")

    static func notify() {
        NotificationCenter.default.post(name: notification, object: nil)
    }
}
