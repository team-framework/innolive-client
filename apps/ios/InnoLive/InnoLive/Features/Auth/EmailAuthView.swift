import SwiftUI
import UIKit

private enum EmailAuthenticationStep { case email, signIn, signUp, verification }

struct EmailAuthView: View {
    @ObservedObject var authentication: AuthSession
    @State private var step: EmailAuthenticationStep = .email
    @State private var email = ""
    @State private var password = ""
    @State private var passwordConfirmation = ""
    @State private var verificationCode = ""
    @FocusState private var focusedField: Field?

    private enum Field: Hashable { case email, password, passwordConfirmation, verificationCode }
    private var normalizedEmail: String { AuthSession.normalizedEmail(email) }
    private var canContinue: Bool { AuthSession.isValidEmail(normalizedEmail) }
    private var canSignIn: Bool { canContinue && !password.isEmpty }
    private var canSignUp: Bool { canContinue && AuthSession.isValidSignupPassword(password) && password == passwordConfirmation }
    private var canVerify: Bool { verificationCode.count == 6 && verificationCode.allSatisfy(\.isNumber) }

    var body: some View {
        AuthenticationLayout {
            VStack(alignment: .leading, spacing: 24) {
                if step != .email {
                    Button(action: goBack) { Label("뒤로", systemImage: "chevron.left") }
                        .buttonStyle(.borderless)
                }
                switch step {
                case .email: emailStep
                case .signIn: signInStep
                case .signUp: signUpStep
                case .verification: verificationStep
                }
                if let errorMessage = authentication.errorMessage {
                    Text(errorMessage).font(.caption).foregroundStyle(.red)
                }
            }
        }
        .onAppear { focusedField = .email }
        .onChange(of: step) { _, _ in
            authentication.clearError()
            switch step {
            case .email: focusedField = .email
            case .signIn, .signUp: focusedField = .password
            case .verification: focusedField = .verificationCode
            }
        }
    }

    private var emailStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("이메일로 계속하기").font(.title.bold())
            Text("로그인하거나 새 계정을 만들 수 있어요.").foregroundStyle(.secondary)
            emailField
            Button("로그인") { step = .signIn }
                .buttonStyle(.glassProminent).tint(.blue)
                .frame(maxWidth: .infinity).frame(height: 52).disabled(!canContinue)
            Button("회원가입") { step = .signUp }
                .buttonStyle(.glass)
                .frame(maxWidth: .infinity).frame(height: 52).disabled(!canContinue)
        }
    }

    private var signInStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("로그인").font(.title.bold())
            emailField.disabled(true).foregroundStyle(.secondary)
            passwordField("비밀번호", text: $password, field: .password, contentType: .password)
            Button { Task { await authentication.signIn(email: normalizedEmail, password: password) } } label: { actionLabel("로그인") }
                .buttonStyle(.glassProminent).tint(.blue)
                .frame(maxWidth: .infinity).frame(height: 52).disabled(!canSignIn || authentication.isLoading)
            Button("회원가입") { password = ""; step = .signUp }.frame(maxWidth: .infinity)
        }
    }

    private var signUpStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("회원가입").font(.title.bold())
            emailField.disabled(true).foregroundStyle(.secondary)
            passwordField("비밀번호", text: $password, field: .password, contentType: .newPassword)
            passwordField("비밀번호 확인", text: $passwordConfirmation, field: .passwordConfirmation, contentType: .newPassword)
            if !passwordConfirmation.isEmpty && password != passwordConfirmation {
                Text("비밀번호가 일치하지 않습니다.").font(.caption).foregroundStyle(.red)
            }
            Button {
                Task {
                    if await authentication.startSignup(email: normalizedEmail, password: password) {
                        verificationCode = ""
                        step = .verification
                    }
                }
            } label: { actionLabel("인증 메일 보내기") }
                .buttonStyle(.glassProminent).tint(.blue)
                .frame(maxWidth: .infinity).frame(height: 52).disabled(!canSignUp || authentication.isLoading)
        }
    }

    private var verificationStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("이메일 인증").font(.title.bold())
            Text("받은 6자리 인증 코드를 입력하세요.").foregroundStyle(.secondary)
            TextField("000000", text: $verificationCode)
                .keyboardType(.numberPad).textContentType(.oneTimeCode)
                .multilineTextAlignment(.center).font(.title2.monospacedDigit())
                .focused($focusedField, equals: .verificationCode)
                .onChange(of: verificationCode) { _, value in verificationCode = String(value.filter(\.isNumber).prefix(6)) }
                .padding(.horizontal, 16).frame(height: 52)
                .glassEffect(.regular, in: .rect(cornerRadius: 12))
            Button { Task { await authentication.verifySignup(code: verificationCode) } } label: { actionLabel("인증하고 로그인") }
                .buttonStyle(.glassProminent).tint(.blue)
                .frame(maxWidth: .infinity).frame(height: 52).disabled(!canVerify || authentication.isLoading)
            Button("코드 재전송") { Task { _ = await authentication.resendSignup() } }
                .frame(maxWidth: .infinity).disabled(authentication.isLoading)
        }
    }

    private var emailField: some View {
        TextField("이메일", text: $email)
            .textContentType(.emailAddress).keyboardType(.emailAddress)
            .textInputAutocapitalization(.never).autocorrectionDisabled()
            .focused($focusedField, equals: .email)
            .padding(.horizontal, 16).frame(height: 52)
            .glassEffect(.regular, in: .rect(cornerRadius: 12))
    }

    private func passwordField(_ title: String, text: Binding<String>, field: Field, contentType: UITextContentType) -> some View {
        SecureField(title, text: text).textContentType(contentType).focused($focusedField, equals: field)
            .padding(.horizontal, 16).frame(height: 52)
            .glassEffect(.regular, in: .rect(cornerRadius: 12))
    }

    @ViewBuilder private func actionLabel(_ title: String) -> some View {
        if authentication.isLoading { ProgressView().tint(.white) } else { Text(title) }
    }

    private func goBack() {
        switch step {
        case .email: break
        case .verification: authentication.cancelSignup(); step = .signUp
        case .signIn, .signUp: password = ""; passwordConfirmation = ""; step = .email
        }
    }
}
