import AuthenticationServices
import Foundation
import Security
import UIKit

enum CHZZKAuthorizationError: Error, Equatable {
    case cancelled, expired, stateMismatch, invalidCallback, configuration, failed, alreadyRunning

    var userMessage: String {
        switch self {
        case .cancelled: return String(localized: "치지직 계정 연결을 취소했습니다.", table: "CHZZK")
        case .expired: return String(localized: "치지직 인증 시간이 만료되었습니다. 다시 연결해 주세요.", table: "CHZZK")
        case .stateMismatch: return String(localized: "치지직 인증 요청이 일치하지 않습니다. 다시 연결해 주세요.", table: "CHZZK")
        case .configuration: return String(localized: "치지직 인증 복귀 설정을 확인해 주세요.", table: "CHZZK")
        default: return String(localized: "치지직 인증을 완료하지 못했습니다. 다시 연결해 주세요.", table: "CHZZK")
        }
    }
}

struct CHZZKAuthorizationCode {
    let code: String
    let state: String
}

struct CHZZKAuthorizationAttempt {
    static let lifetime: Duration = .seconds(300)
    let state: String
    let startedAt: ContinuousClock.Instant

    init(state: String, startedAt: ContinuousClock.Instant = .now) {
        self.state = state
        self.startedAt = startedAt
    }

    static func random() throws -> Self {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw CHZZKAuthorizationError.failed
        }
        return Self(state: bytes.map { String(format: "%02x", $0) }.joined())
    }

    func remaining(at now: ContinuousClock.Instant = .now) throws -> Duration {
        let remaining = Self.lifetime - startedAt.duration(to: now)
        guard remaining > .zero else { throw CHZZKAuthorizationError.expired }
        return remaining
    }

    func validate(_ configuration: CHZZKConfiguration, callbackHost: String) throws {
        let callback = configuration.redirectURI
        guard !state.isEmpty, !configuration.clientID.isEmpty, !callbackHost.isEmpty,
              callback.scheme == "https", callback.host == callbackHost,
              callback.path == "/auth/chzzk/callback", callback.port == nil,
              callback.user == nil, callback.password == nil, callback.query == nil, callback.fragment == nil,
              let authorize = URLComponents(url: configuration.authorizeURL, resolvingAgainstBaseURL: false),
              authorize.scheme == "https", authorize.host == "chzzk.naver.com",
              authorize.path == "/account-interlock", authorize.port == nil,
              authorize.user == nil, authorize.password == nil, authorize.fragment == nil else {
            throw CHZZKAuthorizationError.configuration
        }
        func values(_ key: String) -> [String?] {
            (authorize.queryItems ?? []).filter { $0.name == key }.map(\.value)
        }
        guard values("state") == [state], values("clientId") == [configuration.clientID],
              values("redirectUri") == [callback.absoluteString] else {
            throw CHZZKAuthorizationError.configuration
        }
    }

    func complete(callback: URL, redirectURI: URL,
                  at now: ContinuousClock.Instant = .now) throws -> CHZZKAuthorizationCode {
        _ = try remaining(at: now)
        guard callback.scheme == redirectURI.scheme, callback.host == redirectURI.host,
              callback.port == redirectURI.port, callback.path == redirectURI.path,
              callback.user == nil, callback.password == nil, callback.fragment == nil,
              let components = URLComponents(url: callback, resolvingAgainstBaseURL: false) else {
            throw CHZZKAuthorizationError.invalidCallback
        }
        let items = components.queryItems ?? []
        let states = items.filter { $0.name == "state" }
        guard states.count == 1, states.first?.value == state else {
            throw CHZZKAuthorizationError.stateMismatch
        }
        let errors = items.filter { $0.name == "error" }
        if !errors.isEmpty {
            guard errors.count == 1 else { throw CHZZKAuthorizationError.invalidCallback }
            if errors.first?.value == "access_denied" { throw CHZZKAuthorizationError.cancelled }
            throw CHZZKAuthorizationError.failed
        }
        let codes = items.filter { $0.name == "code" }
        guard codes.count == 1, let code = codes.first?.value,
              !code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw CHZZKAuthorizationError.invalidCallback
        }
        return CHZZKAuthorizationCode(code: code, state: state)
    }
}

@MainActor
protocol CHZZKAuthorizing {
    func authorize(configuration: CHZZKConfiguration, attempt: CHZZKAuthorizationAttempt,
                   presenting: UIViewController) async throws -> CHZZKAuthorizationCode
    func cancel()
}

@MainActor
final class CHZZKAuthorization: NSObject, CHZZKAuthorizing, ASWebAuthenticationPresentationContextProviding {
    nonisolated deinit {}
    private var session: ASWebAuthenticationSession?
    private var continuation: CheckedContinuation<URL, Error>?
    private var timeoutTask: Task<Void, Never>?
    private var anchor: ASPresentationAnchor?
    private var requestID: UUID?

    func authorize(configuration: CHZZKConfiguration, attempt: CHZZKAuthorizationAttempt,
                   presenting: UIViewController) async throws -> CHZZKAuthorizationCode {
        guard session == nil else { throw CHZZKAuthorizationError.alreadyRunning }
        let host = Bundle.main.object(forInfoDictionaryKey: "CHZZKCallbackHost") as? String ?? ""
        try attempt.validate(configuration, callbackHost: host)
        let remaining = try attempt.remaining()
        guard let window = presenting.viewIfLoaded?.window,
              window.windowScene?.activationState == .foregroundActive else {
            throw CHZZKAuthorizationError.failed
        }
        try Task.checkCancellation()
        let id = UUID()
        requestID = id
        anchor = window
        let callback: URL = try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                self.continuation = continuation
                let session = ASWebAuthenticationSession(
                    url: configuration.authorizeURL,
                    callback: .https(host: host, path: configuration.redirectURI.path)
                ) { [weak self] url, error in
                    // URL·NSError의 원문에는 인가값이 포함될 수 있어 기록하지 않는다.
                    Task { @MainActor [weak self] in
                        guard let self, self.requestID == id else { return }
                        if let error = error as NSError? {
                            let cancelled = error.domain == ASWebAuthenticationSessionErrorDomain
                                && error.code == ASWebAuthenticationSessionError.canceledLogin.rawValue
                            self.finish(.failure(cancelled ? CHZZKAuthorizationError.cancelled : .failed))
                        } else if let url {
                            self.finish(.success(url))
                        } else {
                            self.finish(.failure(CHZZKAuthorizationError.invalidCallback))
                        }
                    }
                }
                self.session = session
                session.presentationContextProvider = self
                session.prefersEphemeralWebBrowserSession = true
                guard session.start() else {
                    finish(.failure(CHZZKAuthorizationError.failed))
                    return
                }
                timeoutTask = Task { @MainActor [weak self] in
                    do { try await Task.sleep(for: remaining) } catch { return }
                    guard let self, self.requestID == id else { return }
                    self.finish(.failure(CHZZKAuthorizationError.expired))
                }
            }
        } onCancel: { [weak self] in
            Task { @MainActor [weak self] in
                guard self?.requestID == id else { return }
                self?.cancel()
            }
        }
        return try attempt.complete(callback: callback, redirectURI: configuration.redirectURI)
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        anchor ?? ASPresentationAnchor()
    }

    func cancel() { finish(.failure(CHZZKAuthorizationError.cancelled)) }

    private func finish(_ result: Result<URL, Error>) {
        guard let continuation else { return }
        self.continuation = nil
        requestID = nil
        timeoutTask?.cancel()
        timeoutTask = nil
        let session = self.session
        self.session = nil
        anchor = nil
        session?.cancel()
        continuation.resume(with: result)
    }
}
