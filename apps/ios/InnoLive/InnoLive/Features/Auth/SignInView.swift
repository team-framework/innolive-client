import AuthenticationServices
import GoogleSignIn
import SwiftUI
import UIKit

struct SignInView: View {
    @Environment(\.colorScheme) private var colorScheme
    @ObservedObject var authentication: AuthSession
    @State private var appleNonce = ""

    var body: some View {
        AuthenticationLayout {
            VStack(alignment: .leading, spacing: 24) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("라이브 방송을 안전하게")
                    Text("만드는 쉬운 방법")
                }
                .font(.system(size: 30, weight: .semibold))

                VStack(spacing: 8) {
                    googleButton
                    appleButton
                    NavigationLink { EmailAuthView(authentication: authentication) } label: {
                        Label("이메일로 계속하기", systemImage: "envelope.fill")
                            .font(.callout.weight(.semibold)).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.glass)
                    .controlSize(.large)
                    .frame(maxWidth: .infinity).frame(height: 52).disabled(authentication.isLoading)
                }
                if let errorMessage = authentication.errorMessage { Text(errorMessage).font(.caption).foregroundStyle(.red) }
            }
        }
    }

    private var googleButton: some View {
        Button(action: signInWithGoogle) {
            HStack(spacing: 12) {
                if let googleIcon = Self.googleIcon {
                    Image(uiImage: googleIcon)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 18, height: 18)
                }
                Text("Google 계정으로 로그인")
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

    private func signInWithGoogle() {
        authentication.clearError()
        guard let presentingViewController else { return }
        Task {
            do {
                let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presentingViewController)
                await authentication.signInWithGoogle(idToken: result.user.idToken?.tokenString ?? "")
            } catch {
                authentication.showError("Google 로그인을 완료하지 못했습니다. 다시 시도해 주세요.")
            }
        }
    }

    private var appleButton: some View {
        SignInWithAppleButton(.signIn) { request in
            authentication.clearError()
            appleNonce = authentication.makeNonce()
            request.nonce = appleNonce
            request.requestedScopes = [.fullName, .email]
        } onCompletion: { result in
            switch result {
            case .success(let authorization):
                guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential else { return }
                Task { await authentication.signInWithApple(credential: credential, nonce: appleNonce) }
            case .failure(let error) where (error as? ASAuthorizationError)?.code == .canceled:
                break
            case .failure:
                authentication.showError("Apple 로그인을 완료하지 못했습니다. 다시 시도해 주세요.")
            }
        }
        .signInWithAppleButtonStyle(colorScheme == .dark ? .white : .black)
        .frame(maxWidth: .infinity).frame(height: 48).disabled(authentication.isLoading)
    }

    private var presentingViewController: UIViewController? {
        UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first?.rootViewController
    }
}
