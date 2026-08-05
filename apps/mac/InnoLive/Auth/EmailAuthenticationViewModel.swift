import Combine
import Foundation
import Security

@MainActor
final class EmailAuthenticationViewModel: ObservableObject {
    @Published private(set) var isSignedIn = false
    @Published private(set) var isLoading = false
    @Published private(set) var errorMessage: String?

    private let session = URLSession.shared
    private let tokenStore = EmailAuthenticationTokenStore()
    private var signupToken = ""
    private var pendingEmail = ""
    private var pendingPassword = ""

    func restorePreviousSignIn() {
        isSignedIn = tokenStore.hasTokenPair
    }

    func signIn(email: String, password: String) async -> Bool {
        guard !isLoading else {
            return false
        }

        let normalizedEmail = Self.normalizedEmail(email)
        guard Self.isValidEmail(normalizedEmail), !password.isEmpty else {
            errorMessage = "이메일 주소와 비밀번호를 입력해 주세요."
            return false
        }

        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        do {
            let pair = try await authenticate(email: normalizedEmail, password: password)
            try tokenStore.save(pair)
            isSignedIn = true
            return true
        } catch let error as EmailAuthenticationError {
            errorMessage = error.message
            return false
        } catch {
            errorMessage = "인증 서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요."
            return false
        }
    }

    func startSignup(email: String, password: String) async -> Bool {
        guard !isLoading else {
            return false
        }

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

        guard let url = ServerEnvironment.current.serverURL(path: "/auth/native/sign-up") else {
            errorMessage = "인증 서버 주소가 올바르지 않습니다."
            return false
        }

        do {
            let response: EmailSignupResponse = try await request(
                url: url,
                body: EmailSignupRequest(email: normalizedEmail, password: password)
            )
            guard !response.signupToken.isEmpty else {
                errorMessage = "이메일 인증을 시작하지 못했습니다. 다시 시도해 주세요."
                return false
            }
            signupToken = response.signupToken
            pendingEmail = normalizedEmail
            pendingPassword = password
            return true
        } catch let error as EmailAuthenticationError {
            errorMessage = error.message
            return false
        } catch {
            errorMessage = "인증 메일을 보내지 못했습니다. 잠시 후 다시 시도해 주세요."
            return false
        }
    }

    func verify(code: String) async -> Bool {
        guard !isLoading else {
            return false
        }
        guard signupToken.isEmpty == false,
              code.count == 6,
              code.allSatisfy(\.isNumber) else {
            errorMessage = "6자리 인증 코드를 입력해 주세요."
            return false
        }

        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        guard let url = ServerEnvironment.current.serverURL(path: "/auth/native/verify-email") else {
            errorMessage = "인증 서버 주소가 올바르지 않습니다."
            return false
        }

        let email = pendingEmail
        let password = pendingPassword

        do {
            let _: EmailVerificationResponse = try await request(
                url: url,
                body: EmailVerificationRequest(
                    signupToken: signupToken,
                    verificationCode: code
                )
            )
            let pair = try await authenticate(email: email, password: password)
            try tokenStore.save(pair)
            isSignedIn = true
            clearPendingSignup()
            return true
        } catch let error as EmailAuthenticationError {
            errorMessage = error.message
            return false
        } catch {
            errorMessage = "이메일 인증은 완료됐지만 로그인하지 못했습니다. 다시 로그인해 주세요."
            clearPendingSignup()
            return false
        }
    }

    func resendSignup() async -> Bool {
        guard pendingEmail.isEmpty == false, pendingPassword.isEmpty == false else {
            errorMessage = "회원가입 인증 시간이 만료됐습니다. 다시 시작해 주세요."
            return false
        }
        return await startSignup(email: pendingEmail, password: pendingPassword)
    }

    func cancelPendingSignup() {
        clearPendingSignup()
        errorMessage = nil
    }

    func clearError() {
        errorMessage = nil
    }

    func signOut() {
        tokenStore.removeTokenPair()
        isSignedIn = false
        clearPendingSignup()
        errorMessage = nil
    }

    static func normalizedEmail(_ email: String) -> String {
        email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    static func isValidEmail(_ email: String) -> Bool {
        let pattern = #"^[^\s@]+@[^\s@]+\.[^\s@]+$"#
        return email.range(of: pattern, options: .regularExpression) != nil
    }

    static func isValidSignupPassword(_ password: String) -> Bool {
        (8...72).contains(password.utf8.count)
    }

    private func authenticate(email: String, password: String) async throws -> EmailAuthenticationTokenPair {
        guard let url = ServerEnvironment.current.serverURL(path: "/auth/sign-in") else {
            throw EmailAuthenticationError.serverAddress
        }
        return try await request(
            url: url,
            body: EmailLoginRequest(email: email, password: password)
        )
    }

    private func request<Response: Decodable, Body: Encodable>(url: URL, body: Body) async throws -> Response {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = try JSONEncoder().encode(body)

        do {
            let (data, response) = try await session.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse else {
                throw EmailAuthenticationError.serverResponse
            }
            guard (200..<300).contains(httpResponse.statusCode) else {
                let apiError = try? JSONDecoder().decode(EmailAuthenticationAPIError.self, from: data)
                throw EmailAuthenticationError.api(
                    code: apiError?.error.code,
                    fallback: httpResponse.statusCode == 409
                        ? "이미 가입된 이메일입니다."
                        : "요청을 처리하지 못했습니다. 다시 시도해 주세요."
                )
            }
            return try JSONDecoder().decode(Response.self, from: data)
        } catch let error as EmailAuthenticationError {
            throw error
        } catch is DecodingError {
            throw EmailAuthenticationError.serverResponse
        } catch {
            throw EmailAuthenticationError.connection
        }
    }

    private func clearPendingSignup() {
        signupToken = ""
        pendingEmail = ""
        pendingPassword = ""
    }
}

private enum EmailAuthenticationError: Error {
    case api(code: String?, fallback: String)
    case connection
    case serverAddress
    case serverResponse

    var message: String {
        switch self {
        case let .api(code, fallback):
            switch code {
            case "email_already_registered":
                return "이미 가입된 이메일입니다. 로그인해 주세요."
            case "invalid_email_credentials":
                return "이메일 또는 비밀번호가 올바르지 않습니다."
            case "invalid_verification_code":
                return "인증 코드가 올바르지 않거나 만료됐습니다."
            case "invalid_signup_token":
                return "회원가입 인증 시간이 만료됐습니다. 다시 시작해 주세요."
            case "email_delivery_unavailable", "email_delivery_failed", "email_auth_unavailable":
                return "인증 메일을 보낼 수 없습니다. 잠시 후 다시 시도해 주세요."
            default:
                return fallback
            }
        case .connection:
            return "인증 서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요."
        case .serverAddress:
            return "인증 서버 주소가 올바르지 않습니다."
        case .serverResponse:
            return "인증 서버 응답을 확인하지 못했습니다. 다시 시도해 주세요."
        }
    }
}

private struct EmailSignupRequest: Encodable {
    let email: String
    let password: String
}

private struct EmailSignupResponse: Decodable {
    let signupToken: String

    enum CodingKeys: String, CodingKey {
        case signupToken = "signup_token"
    }
}

private struct EmailVerificationRequest: Encodable {
    let signupToken: String
    let verificationCode: String

    enum CodingKeys: String, CodingKey {
        case signupToken = "signup_token"
        case verificationCode = "verification_code"
    }
}

private struct EmailVerificationResponse: Decodable {
    let status: String
}

private struct EmailLoginRequest: Encodable {
    let email: String
    let password: String
}

private struct EmailAuthenticationTokenPair: Codable {
    let accessToken: String
    let refreshToken: String

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
    }
}

private struct EmailAuthenticationAPIError: Decodable {
    struct Details: Decodable {
        let code: String?
        let message: String?
    }

    let error: Details
}

private final class EmailAuthenticationTokenStore {
    private let service = "com.framework.innolive.email-authentication"
    private let account = "token-pair"

    var hasTokenPair: Bool {
        loadTokenPair() != nil
    }

    func save(_ pair: EmailAuthenticationTokenPair) throws {
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
            throw EmailAuthenticationStorageError.saveFailed
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

    private func loadTokenPair() -> EmailAuthenticationTokenPair? {
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
        return try? JSONDecoder().decode(EmailAuthenticationTokenPair.self, from: data)
    }
}

private enum EmailAuthenticationStorageError: Error {
    case saveFailed
}
