import SwiftUI

struct AuthenticationView: View {
    @ObservedObject var authentication: GoogleAuthenticationViewModel
    @ObservedObject var appleAuthentication: AppleAuthenticationViewModel
    @ObservedObject var emailAuthentication: EmailAuthenticationViewModel

    @State private var showsEmailFlow = false

    private var isLoading: Bool {
        authentication.isLoading || appleAuthentication.isLoading || emailAuthentication.isLoading
    }

    private var errorMessage: String? {
        authentication.errorMessage
            ?? appleAuthentication.errorMessage
            ?? emailAuthentication.errorMessage
    }

    var body: some View {
        ZStack {
            AuthenticationBackground()

            if showsEmailFlow {
                EmailAuthenticationFlowView(
                    authentication: emailAuthentication,
                    onCancel: {
                        emailAuthentication.cancelPendingSignup()
                        showsEmailFlow = false
                    }
                )
                .transition(.move(edge: .trailing).combined(with: .opacity))
            } else {
                entryView
                    .transition(.move(edge: .leading).combined(with: .opacity))
            }
        }
        .frame(minWidth: 520, idealWidth: 560, minHeight: 620, idealHeight: 680)
        .animation(.easeInOut(duration: 0.22), value: showsEmailFlow)
    }

    private var entryView: some View {
        GeometryReader { proxy in
            ScrollView {
                VStack {
                    VStack(spacing: 20) {
                        AuthenticationBrand()

                        Text("로그인")
                            .font(.title2.weight(.semibold))
                            .foregroundStyle(.primary)

                        VStack(spacing: 10) {
                            GoogleOAuthButton(action: authentication.signIn)
                                .disabled(isLoading)

                            AppleSignInView(authentication: appleAuthentication)
                                .frame(maxWidth: .infinity)
                                .frame(height: 44)

                            AuthenticationDivider()

                            Button {
                                showsEmailFlow = true
                            } label: {
                                Label("이메일로 계속하기", systemImage: "envelope")
                            }
                            .buttonStyle(AuthenticationProviderButtonStyle())
                            .accessibilityHint("이메일 로그인 또는 회원가입")
                            .disabled(isLoading)
                        }

                        if let errorMessage {
                            AuthenticationErrorBanner(message: errorMessage)
                        }
                    }
                    .padding(.horizontal, 32)
                    .padding(.vertical, 32)
                    .frame(maxWidth: 460)
                    .authenticationGlassSurface()
                }
                .frame(maxWidth: .infinity, minHeight: max(proxy.size.height - 40, 0), alignment: .center)
                .padding(.horizontal, 24)
                .padding(.vertical, 20)
            }
        }
    }
}

private struct AuthenticationBackground: View {
    var body: some View {
        Color(nsColor: .windowBackgroundColor)
            .ignoresSafeArea()
    }
}
