import Foundation

protocol AuthenticationAPIClient {
    func emailSignIn(email: String, password: String) async throws -> AuthenticationTokenPair
    func startEmailSignup(email: String, password: String) async throws -> String
    func verifyEmail(signupToken: String, code: String) async throws
    func googleSignIn(idToken: String) async throws -> AuthenticationTokenPair
    func appleSignIn(authorizationCode: String, nonce: String, givenName: String?, familyName: String?) async throws -> AuthenticationTokenPair
    func refresh(refreshToken: String) async throws -> AuthenticationTokenPair
}

struct AuthenticationAPI: AuthenticationAPIClient {
    nonisolated init() {}

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

    func refresh(refreshToken: String) async throws -> AuthenticationTokenPair {
        try await request("/auth/refresh", body: RefreshTokenRequest(refreshToken: refreshToken))
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

struct AuthenticationTokenPair: Codable {
    let accessToken: String
    let refreshToken: String

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
    }
}

enum AuthenticationError: Error {
    case api(code: String?, fallback: String)
    case configuration
    case storage
    case response
}

private struct EmailCredentials: Encodable { let email: String; let password: String }

private struct EmailVerificationRequest: Encodable {
    let signupToken: String
    let verificationCode: String

    enum CodingKeys: String, CodingKey {
        case signupToken = "signup_token"
        case verificationCode = "verification_code"
    }
}

private struct GoogleLoginRequest: Encodable {
    let idToken: String

    enum CodingKeys: String, CodingKey { case idToken = "id_token" }
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

private struct RefreshTokenRequest: Encodable {
    let refreshToken: String

    enum CodingKeys: String, CodingKey { case refreshToken = "refresh_token" }
}

private struct SignupResponse: Decodable {
    let signupToken: String

    enum CodingKeys: String, CodingKey { case signupToken = "signup_token" }
}

private struct StatusResponse: Decodable { let status: String }

private struct APIErrorResponse: Decodable {
    struct Details: Decodable {
        let code: String?
        let message: String?
    }

    let error: Details
}
