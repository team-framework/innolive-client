import AuthenticationServices
import SwiftUI

struct AppleSignInView: View {
    @ObservedObject var authentication: AppleAuthenticationViewModel
    @Environment(\.colorScheme) private var colorScheme
    @State private var nonce = ""

    var body: some View {
        SignInWithAppleButton(.signIn) { request in
            request.requestedScopes = [.fullName, .email]
            nonce = authentication.makeNonce()
            request.nonce = nonce
        } onCompletion: { result in
            switch result {
            case .success(let authorization):
                guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential else {
                    return
                }
                Task {
                    await authentication.signIn(credential: credential, nonce: nonce)
                }
            case .failure:
                authentication.errorMessage = "Apple 로그인을 완료하지 못했습니다. 다시 시도해 주세요."
            }
        }
        .signInWithAppleButtonStyle(colorScheme == .dark ? .black : .white)
        .font(.body.weight(.medium))
        .frame(maxWidth: .infinity)
        .frame(height: 44)
        .background(
            colorScheme == .dark ? Color.black : Color.white,
            in: RoundedRectangle(cornerRadius: 10, style: .continuous)
        )
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(.separator, lineWidth: 1)
        }
        .accessibilityLabel("Apple로 계속하기")
        .disabled(authentication.isLoading)
    }
}
