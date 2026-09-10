import SwiftUI
import UIKit

private enum EmailAuthenticationStep { case signIn, signUp, verification }

struct EmailAuthView: View {
    @ObservedObject var authentication: AuthSession
    @State private var step: EmailAuthenticationStep = .signIn
    @State private var email = ""
    @State private var password = ""
    @State private var passwordConfirmation = ""
    @State private var verificationCode = ""
    @State private var editedFields: Set<Field> = []
    @State private var visiblePasswords: Set<Field> = []
    @State private var didResendCode = false
    @FocusState private var focusedField: Field?

    private enum Field: Hashable {
        case email, password, passwordConfirmation, verificationCode, visiblePassword, visiblePasswordConfirmation

        var concealed: Field {
            switch self {
            case .visiblePassword: .password
            case .visiblePasswordConfirmation: .passwordConfirmation
            default: self
            }
        }

        var revealed: Field {
            switch self {
            case .password: .visiblePassword
            case .passwordConfirmation: .visiblePasswordConfirmation
            default: self
            }
        }
    }
    private var normalizedEmail: String { AuthSession.normalizedEmail(email) }
    private var hasValidEmail: Bool { AuthSession.isValidEmail(normalizedEmail) }
    private var hasValidPassword: Bool { AuthSession.isValidSignupPassword(password) }
    private var canSubmit: Bool {
        switch step {
        case .signIn: hasValidEmail && !password.isEmpty
        case .signUp: hasValidEmail && hasValidPassword && password == passwordConfirmation
        case .verification: verificationCode.count == 6 && verificationCode.allSatisfy(\.isNumber)
        }
    }
    private var title: String {
        switch step {
        case .signIn: "이메일로 로그인"
        case .signUp: "계정 만들기"
        case .verification: "이메일을 확인해 주세요"
        }
    }
    private var actionTitle: String {
        switch step {
        case .signIn: "로그인"
        case .signUp: "인증 메일 보내기"
        case .verification: "인증하고 시작하기"
        }
    }

    var body: some View {
        AuthenticationLayout(alignment: .top) {
            VStack(alignment: .leading, spacing: 32) {
                header
                VStack(alignment: .leading, spacing: 24) {
                    if step == .verification {
                        verificationField
                    } else {
                        credentials
                    }
                    if let message = authentication.errorMessage {
                        feedback(message, symbol: "exclamationmark.circle.fill", color: .red)
                    }
                    Button(action: submit) {
                        HStack(spacing: 10) {
                            if authentication.isLoading { ProgressView().tint(.white) }
                            Text(actionTitle)
                        }
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity, minHeight: 32)
                    }
                    .buttonStyle(.glassProminent)
                    .controlSize(.large)
                    .tint(.blue)
                    .disabled(!canSubmit || authentication.isLoading)
                    .accessibilityIdentifier("emailAuth.submit")
                }
                footer
            }
        }
        .clipped()
        .background(Color(uiColor: .systemBackground))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        .navigationBarBackButtonHidden(step != .signIn || authentication.isLoading)
        .toolbar {
            if step != .signIn {
                ToolbarItem(placement: .topBarLeading) {
                    Button("뒤로", systemImage: "chevron.left", action: goBack)
                        .disabled(authentication.isLoading)
                }
            }
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("완료") { focusedField = nil }
            }
        }
        .onAppear { authentication.clearError() }
        .onDisappear { authentication.cancelSignup() }
        .onChange(of: focusedField) { previous, next in
            if let previous, previous.concealed != next?.concealed {
                editedFields.insert(previous.concealed)
            }
        }
        .onChange(of: authentication.errorMessage) { _, message in
            if let message { UIAccessibility.post(notification: .announcement, argument: message) }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.largeTitle.bold())
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            if step == .verification {
                Text(normalizedEmail).font(.body.weight(.semibold))
                    .textSelection(.enabled)
                Text("위 주소로 보낸 6자리 인증 코드를 입력해 주세요.")
                    .foregroundStyle(.secondary)
            } else {
                Text(step == .signIn ? "InnoLive에서 라이브를 이어가세요." : "이메일 인증 후 라이브를 시작할 수 있어요.")
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var credentials: some View {
        VStack(alignment: .leading, spacing: 20) {
            VStack(alignment: .leading, spacing: 8) {
                inputField("이메일", field: .email) {
                    TextField("이메일", text: $email, prompt: Text(verbatim: "name@example.com"))
                        .textContentType(.username)
                        .keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .focused($focusedField, equals: .email)
                        .submitLabel(.next)
                        .onSubmit { focusedField = passwordFocus(.password) }
                        .onChange(of: email) { _, _ in authentication.clearError() }
                        .accessibilityLabel("이메일")
                }
                if editedFields.contains(.email), !email.isEmpty, !hasValidEmail {
                    feedback("이메일 주소를 확인해 주세요.", symbol: "exclamationmark.circle", color: .red)
                }
            }
            VStack(alignment: .leading, spacing: 8) {
                passwordField("비밀번호", text: $password, field: .password)
                if step == .signUp {
                    feedback(
                        password.isEmpty ? "영문·숫자 기준 8~72자" : hasValidPassword ? "사용할 수 있는 길이입니다." : "영문·숫자 기준 8~72자로 입력해 주세요.",
                        symbol: hasValidPassword ? "checkmark.circle.fill" : "info.circle",
                        color: hasValidPassword ? .green : .secondary
                    )
                }
            }
            if step == .signUp {
                VStack(alignment: .leading, spacing: 8) {
                    passwordField("비밀번호 확인", text: $passwordConfirmation, field: .passwordConfirmation)
                    if !passwordConfirmation.isEmpty {
                        if password == passwordConfirmation {
                            feedback("비밀번호가 일치합니다.", symbol: "checkmark.circle.fill", color: .green)
                        } else if editedFields.contains(.passwordConfirmation) {
                            feedback("비밀번호가 일치하지 않습니다.", symbol: "exclamationmark.circle", color: .red)
                        }
                    }
                }
            }
        }
        .disabled(authentication.isLoading)
    }

    private var verificationField: some View {
        inputField("인증 코드", field: .verificationCode) {
            TextField("6자리 숫자", text: $verificationCode)
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .font(.title2.monospacedDigit())
                .focused($focusedField, equals: .verificationCode)
                .onChange(of: verificationCode) { _, value in
                    authentication.clearError()
                    verificationCode = String(value.filter(\.isNumber).prefix(6))
                }
                .accessibilityLabel("인증 코드")
        }
        .disabled(authentication.isLoading)
    }

    @ViewBuilder private var footer: some View {
        VStack(spacing: 4) {
            if step == .verification {
                Text(didResendCode ? "인증 코드를 다시 보냈어요." : "메일이 오지 않았다면 스팸함을 확인해 주세요.")
                    .font(.subheadline).foregroundStyle(.secondary)
                Button {
                    Task {
                        didResendCode = await authentication.resendSignup()
                        if didResendCode {
                            verificationCode = ""
                            UIAccessibility.post(notification: .announcement, argument: "인증 코드를 다시 보냈어요.")
                        }
                    }
                } label: {
                    Text("인증 코드 다시 보내기").frame(minHeight: 44)
                }
            } else {
                Text(step == .signIn ? "InnoLive가 처음이신가요?" : "이미 계정이 있으신가요?")
                    .font(.subheadline).foregroundStyle(.secondary)
                Button {
                    changeStep(to: step == .signIn ? .signUp : .signIn)
                } label: {
                    Text(step == .signIn ? "회원가입" : "로그인").frame(minWidth: 44, minHeight: 44)
                }
            }
        }
        .buttonStyle(.borderless)
        .controlSize(.large)
        .font(.body.weight(.semibold))
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .disabled(authentication.isLoading)
    }

    private func inputField<Content: View>(_ title: String, field: Field, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.subheadline.weight(.medium))
            content()
                .font(.body)
                .frame(minHeight: 44)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(Color(uiColor: .secondarySystemBackground), in: .rect(cornerRadius: 16))
                .overlay {
                    RoundedRectangle(cornerRadius: 16)
                        .strokeBorder(focusedField?.concealed == field ? Color.accentColor : Color.clear, lineWidth: 1.5)
                }
        }
    }

    private func passwordField(_ title: String, text: Binding<String>, field: Field) -> some View {
        inputField(title, field: field) {
            HStack(spacing: 8) {
                ZStack {
                    SecureField("비밀번호 입력", text: text)
                        .focused($focusedField, equals: field)
                        .opacity(visiblePasswords.contains(field) ? 0 : 1)
                        .allowsHitTesting(!visiblePasswords.contains(field))
                        .accessibilityHidden(visiblePasswords.contains(field))
                    TextField("비밀번호 입력", text: text)
                        .focused($focusedField, equals: field.revealed)
                        .opacity(visiblePasswords.contains(field) ? 1 : 0)
                        .allowsHitTesting(visiblePasswords.contains(field))
                        .accessibilityHidden(!visiblePasswords.contains(field))
                }
                .textContentType(step == .signIn ? .password : .newPassword)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(step == .signUp && field == .password ? .next : .go)
                .onSubmit {
                    if step == .signUp && field == .password { focusedField = passwordFocus(.passwordConfirmation) }
                    else { submit() }
                }
                .onChange(of: text.wrappedValue) { _, _ in authentication.clearError() }
                .accessibilityLabel(title)
                Button {
                    if visiblePasswords.contains(field) { visiblePasswords.remove(field) }
                    else { visiblePasswords.insert(field) }
                    focusedField = passwordFocus(field)
                } label: {
                    Image(systemName: visiblePasswords.contains(field) ? "eye.slash" : "eye")
                        .foregroundStyle(.secondary)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(title) \(visiblePasswords.contains(field) ? "가리기" : "보기")")
            }
        }
    }

    private func feedback(_ message: String, symbol: String, color: Color) -> some View {
        Label(message, systemImage: symbol)
            .font(.footnote)
            .foregroundStyle(color)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func passwordFocus(_ field: Field) -> Field {
        visiblePasswords.contains(field) ? field.revealed : field
    }

    private func changeStep(to nextStep: EmailAuthenticationStep) {
        focusedField = nil
        authentication.clearError()
        password = ""
        passwordConfirmation = ""
        verificationCode = ""
        visiblePasswords = []
        editedFields = []
        didResendCode = false
        step = nextStep
    }

    private func submit() {
        guard canSubmit, !authentication.isLoading else { return }
        focusedField = nil
        Task {
            switch step {
            case .signIn:
                await authentication.signIn(email: normalizedEmail, password: password)
            case .signUp:
                if await authentication.startSignup(email: normalizedEmail, password: password) {
                    verificationCode = ""
                    visiblePasswords = []
                    step = .verification
                }
            case .verification:
                await authentication.verifySignup(code: verificationCode)
            }
        }
    }

    private func goBack() {
        if step == .verification {
            authentication.cancelSignup()
            changeStep(to: .signUp)
        } else {
            changeStep(to: .signIn)
        }
    }
}
