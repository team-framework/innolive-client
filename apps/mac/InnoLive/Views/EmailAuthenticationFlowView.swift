import SwiftUI

enum EmailAuthenticationStep {
    case email
    case signIn
    case signUp
    case verification
}

struct EmailAuthenticationFlowView: View {
    @ObservedObject var authentication: EmailAuthenticationViewModel
    let onCancel: () -> Void

    @State private var step: EmailAuthenticationStep = .email
    @State private var email = ""
    @State private var password = ""
    @State private var passwordConfirmation = ""
    @State private var verificationCode = ""
    @FocusState private var focusedField: Field?

    private enum Field: Hashable {
        case email
        case password
        case passwordConfirmation
        case verificationCode
    }

    private var normalizedEmail: String {
        EmailAuthenticationViewModel.normalizedEmail(email)
    }

    private var canContinueWithEmail: Bool {
        EmailAuthenticationViewModel.isValidEmail(normalizedEmail)
    }

    private var canSignIn: Bool {
        canContinueWithEmail && password.isEmpty == false
    }

    private var canSignUp: Bool {
        canContinueWithEmail
            && EmailAuthenticationViewModel.isValidSignupPassword(password)
            && password == passwordConfirmation
    }

    private var canVerify: Bool {
        verificationCode.count == 6 && verificationCode.allSatisfy(\.isNumber)
    }

    var body: some View {
        GeometryReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    HStack {
                        Button(action: goBack) {
                            Image(systemName: "chevron.left")
                                .font(.body.weight(.semibold))
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("뒤로")
                        .keyboardShortcut(.cancelAction)

                        Spacer()
                    }

                    stepContent
                }
                .padding(.horizontal, 32)
                .padding(.vertical, 30)
                .frame(maxWidth: 410, alignment: .leading)
                .authenticationGlassSurface()
                .frame(maxWidth: .infinity, minHeight: max(proxy.size.height - 40, 0), alignment: .center)
                .padding(.horizontal, 24)
                .padding(.vertical, 20)
            }
        }
        .onAppear {
            focusedField = .email
        }
        .onChange(of: step) { _, _ in
            focusCurrentField()
        }
    }

    @ViewBuilder
    private var stepContent: some View {
        switch step {
        case .email:
            emailStep
        case .signIn:
            signInStep
        case .signUp:
            signUpStep
        case .verification:
            verificationStep
        }
    }

    private var emailStep: some View {
        VStack(alignment: .leading, spacing: 18) {
            AuthenticationStepHeader(
                title: "이메일",
                message: nil
            )

            emailField

            Button("계속") {
                step = .signIn
            }
            .buttonStyle(AuthenticationPrimaryButtonStyle())
            .disabled(!canContinueWithEmail)
            .keyboardShortcut(.defaultAction)

            Button("회원가입") {
                step = .signUp
                password = ""
                passwordConfirmation = ""
            }
            .buttonStyle(.link)
            .font(.callout)
            .frame(maxWidth: .infinity, alignment: .center)

            errorBanner
        }
    }

    private var signInStep: some View {
        VStack(alignment: .leading, spacing: 18) {
            AuthenticationStepHeader(
                title: "로그인",
                message: nil
            )

            emailField
            passwordField(title: "비밀번호", placeholder: "비밀번호 입력", text: $password, field: .password)

            Button {
                Task {
                    _ = await authentication.signIn(email: normalizedEmail, password: password)
                }
            } label: {
                if authentication.isLoading {
                    ProgressView()
                        .controlSize(.small)
                        .tint(.white)
                } else {
                    Text("로그인")
                }
            }
            .buttonStyle(AuthenticationPrimaryButtonStyle())
            .disabled(!canSignIn || authentication.isLoading)
            .keyboardShortcut(.defaultAction)

            Button("회원가입") {
                step = .signUp
                password = ""
            }
            .buttonStyle(.link)
            .font(.callout)
            .frame(maxWidth: .infinity, alignment: .center)

            errorBanner
        }
    }

    private var signUpStep: some View {
        VStack(alignment: .leading, spacing: 18) {
            AuthenticationStepHeader(
                title: "회원가입",
                message: nil
            )

            emailField
            passwordField(title: "비밀번호", placeholder: "비밀번호 입력", text: $password, field: .password)
            passwordField(title: "비밀번호 확인", placeholder: "다시 입력", text: $passwordConfirmation, field: .passwordConfirmation)

            if passwordConfirmation.isEmpty == false && password != passwordConfirmation {
                Text("비밀번호 불일치")
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            Button {
                Task {
                    if await authentication.startSignup(email: normalizedEmail, password: password) {
                        verificationCode = ""
                        step = .verification
                    }
                }
            } label: {
                if authentication.isLoading {
                    ProgressView()
                        .controlSize(.small)
                        .tint(.white)
                } else {
                    Text("인증 메일 보내기")
                }
            }
            .buttonStyle(AuthenticationPrimaryButtonStyle())
            .disabled(!canSignUp || authentication.isLoading)
            .keyboardShortcut(.defaultAction)

            errorBanner
        }
    }

    private var verificationStep: some View {
        VStack(alignment: .leading, spacing: 18) {
            AuthenticationStepHeader(
                title: "인증 코드",
                message: nil
            )

            TextField("000000", text: $verificationCode)
                .textContentType(.oneTimeCode)
                .multilineTextAlignment(.center)
                .font(.title3.monospacedDigit())
                .textFieldStyle(.roundedBorder)
                .controlSize(.large)
                .focused($focusedField, equals: .verificationCode)
                .onChange(of: verificationCode) { _, value in
                    verificationCode = String(value.filter(\.isNumber).prefix(6))
                }

            Button {
                Task {
                    _ = await authentication.verify(code: verificationCode)
                }
            } label: {
                if authentication.isLoading {
                    ProgressView()
                        .controlSize(.small)
                        .tint(.white)
                } else {
                    Text("인증하기")
                }
            }
            .buttonStyle(AuthenticationPrimaryButtonStyle())
            .disabled(!canVerify || authentication.isLoading)
            .keyboardShortcut(.defaultAction)

            Button {
                Task {
                    _ = await authentication.resendSignup()
                }
            } label: {
                Text("코드 재전송")
            }
            .buttonStyle(.link)
            .disabled(authentication.isLoading)
            .frame(maxWidth: .infinity, alignment: .center)

            errorBanner
        }
    }

    private var emailField: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("이메일")
                .font(.callout.weight(.medium))
                .foregroundStyle(.secondary)

            TextField("example@innolive.com", text: $email)
                .textContentType(.emailAddress)
                .autocorrectionDisabled()
                .font(.body)
                .textFieldStyle(.roundedBorder)
                .controlSize(.large)
                .focused($focusedField, equals: .email)
        }
    }

    private func passwordField(title: String, placeholder: String, text: Binding<String>, field: Field) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.callout.weight(.medium))
                .foregroundStyle(.secondary)

            SecureField(placeholder, text: text)
                .textContentType(field == .password ? .password : .newPassword)
                .font(.body)
                .textFieldStyle(.roundedBorder)
                .controlSize(.large)
                .focused($focusedField, equals: field)
        }
    }

    @ViewBuilder
    private var errorBanner: some View {
        if let errorMessage = authentication.errorMessage {
            AuthenticationErrorBanner(message: errorMessage)
        }
    }

    private func goBack() {
        authentication.clearError()

        switch step {
        case .email:
            onCancel()
        case .verification:
            step = .signUp
            verificationCode = ""
        case .signIn, .signUp:
            step = .email
            password = ""
            passwordConfirmation = ""
        }
    }

    private func focusCurrentField() {
        switch step {
        case .email:
            focusedField = .email
        case .signIn:
            focusedField = .password
        case .signUp:
            focusedField = .password
        case .verification:
            focusedField = .verificationCode
        }
    }
}
