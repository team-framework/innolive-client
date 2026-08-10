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
        Button {
            guard let presentingViewController else { return }
            Task {
                do {
                    let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presentingViewController)
                    await authentication.signInWithGoogle(idToken: result.user.idToken?.tokenString ?? "")
                } catch {
                    authentication.showError("Google 로그인을 완료하지 못했습니다. 다시 시도해 주세요.")
                }
            }
        } label: {
            Label("Google로 계속하기", systemImage: "g.circle.fill")
                .font(.callout.weight(.semibold)).frame(maxWidth: .infinity)
        }
        .buttonStyle(.glass)
        .controlSize(.large)
        .frame(maxWidth: .infinity).frame(height: 52).disabled(authentication.isLoading)
    }

    private var appleButton: some View {
        SignInWithAppleButton(.signIn) { request in
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
