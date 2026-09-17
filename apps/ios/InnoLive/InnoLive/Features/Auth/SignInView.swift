import AuthenticationServices
import GoogleSignIn
import SwiftUI
import UIKit

struct SignInView: View {
    @Environment(\.colorScheme) private var colorScheme
    @ObservedObject var authentication: AuthSession
    @StateObject private var appleAuthorization = AppleSignInAuthorization()
    @State private var consentProvider: Provider?
    @State private var approvedSignIn: (Provider, SignupConsent)?
    @State private var isAuthorizing = false

    private enum Provider: String, Identifiable {
        case google, apple
        var id: String { rawValue }
    }

    var body: some View {
        AuthenticationLayout {
            VStack(alignment: .leading, spacing: 24) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(String(localized: "라이브 방송을 안전하게"))
                    Text(String(localized: "만드는 쉬운 방법"))
                }
                .font(.system(size: 30, weight: .semibold))

                VStack(spacing: 8) {
                    googleButton
                    appleButton
                    NavigationLink { EmailAuthView(authentication: authentication) } label: {
                        Label(String(localized: "이메일로 계속하기"), systemImage: "envelope.fill")
                            .font(.callout.weight(.semibold)).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.glass)
                    .controlSize(.large)
                    .frame(maxWidth: .infinity).frame(height: 52).disabled(authentication.isLoading)
                }
                .disabled(isAuthorizing || consentProvider != nil)
                if let errorMessage = authentication.errorMessage { Text(errorMessage).font(.caption).foregroundStyle(.red) }
            }
        }
        .sheet(item: $consentProvider, onDismiss: {
            guard let (provider, consent) = approvedSignIn else { return }
            approvedSignIn = nil
            guard consent.isAccepted, !isAuthorizing else { return }
            switch provider {
            case .google: signInWithGoogle(consent: consent)
            case .apple: signInWithApple(consent: consent)
            }
        }) { provider in
            SignupConsentView { consent in
                approvedSignIn = (provider, consent)
            }
        }
    }

    private var googleButton: some View {
        Button { consentProvider = .google } label: {
            HStack(spacing: 12) {
                if let googleIcon = Self.googleIcon {
                    Image(uiImage: googleIcon)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 18, height: 18)
                }
                Text(String(localized: "Google 계정으로 로그인"))
                    .font(.callout.weight(.semibold))
            }
            .foregroundStyle(Color(red: 31.0 / 255.0, green: 31.0 / 255.0, blue: 31.0 / 255.0))
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(.white, in: RoundedRectangle(cornerRadius: 12))
            .overlay {
                RoundedRectangle(cornerRadius: 12)
                    .stroke(Color(red: 116.0 / 255.0, green: 119.0 / 255.0, blue: 119.0 / 255.0), lineWidth: 1)
            }
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity).frame(height: 48)
        .disabled(authentication.isLoading)
    }

    private static let googleIcon: UIImage? = {
        let resourceBundleURL = Bundle.main.bundleURL.appendingPathComponent("GoogleSignIn_GoogleSignIn.bundle")
        guard let resourceBundle = Bundle(url: resourceBundleURL) else { return nil }
        return UIImage(named: "google", in: resourceBundle, compatibleWith: nil)
    }()

    private func signInWithGoogle(consent: SignupConsent) {
        guard consent.isAccepted, !isAuthorizing else { return }
        authentication.clearError()
        guard let presentingViewController else { return }
        guard let clientID = Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as? String,
              let serverClientID = Bundle.main.object(forInfoDictionaryKey: "GIDServerClientID") as? String else {
            authentication.showError(String(localized: "Google 로그인 설정이 필요합니다."))
            return
        }
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(
            clientID: clientID,
            serverClientID: serverClientID
        )
        isAuthorizing = true
        Task {
            defer { isAuthorizing = false }
            do {
                let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presentingViewController)
                await authentication.signInWithGoogle(idToken: result.user.idToken?.tokenString ?? "", consent: consent)
            } catch {
                authentication.showError(String(localized: "Google 로그인을 완료하지 못했습니다. 다시 시도해 주세요."))
            }
        }
    }

    private var appleButton: some View {
        ConsentAppleSignInButton(style: colorScheme == .dark ? .white : .black) {
            consentProvider = .apple
        }
        .id(colorScheme)
        .frame(maxWidth: .infinity).frame(height: 48).disabled(authentication.isLoading)
    }

    private func signInWithApple(consent: SignupConsent) {
        guard consent.isAccepted, !isAuthorizing,
              let window = presentingViewController?.view.window else { return }
        authentication.clearError()
        let nonce = authentication.makeNonce()
        isAuthorizing = true
        appleAuthorization.start(nonce: nonce, window: window) { result in
            switch result {
            case .success(let credential):
                Task {
                    defer { isAuthorizing = false }
                    await authentication.signInWithApple(credential: credential, nonce: nonce, consent: consent)
                }
            case .failure(let error) where (error as? ASAuthorizationError)?.code == .canceled:
                isAuthorizing = false
            case .failure:
                isAuthorizing = false
                authentication.showError(String(localized: "Apple 로그인을 완료하지 못했습니다. 다시 시도해 주세요."))
            }
        }
    }

    private var presentingViewController: UIViewController? {
        UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first?.rootViewController
    }
}
