//
//  EmailAuthView.swift
//  InnoLive
//
//  Created by chaeyn on 7/25/26.
//

import SwiftUI

private enum EmailAuthDestination: Hashable {
    case signIn
    case signUp
}

struct EmailAuthView: View {
    @Binding var isSignedIn: Bool
    @State private var email = ""
    @State private var destination: EmailAuthDestination?
    @State private var showsEmailError = false
    @FocusState private var isEmailFocused: Bool

    // 서버 연동 전 화면 분기를 확인하기 위한 임시 계정
    private let existingEmails = ["chaeyn@dgsw.hs.kr"]

    private var normalizedEmail: String {
        email
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
    }

    private var isEmailValid: Bool {
        let pattern = #"^[^\s@]+@[^\s@]+\.[^\s@]+$"#
        return normalizedEmail.range(of: pattern, options: .regularExpression) != nil
    }

    var body: some View {
        VStack (spacing: 24) {
            Text("로그인 또는 회원가입")
                .font(.title.bold())

            VStack (spacing: 12) {
                TextField("이메일을 입력하세요.", text: $email)
                    .font(.body)
                    .focused($isEmailFocused)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onChange(of: email) {
                        showsEmailError = false
                    }
                    .padding(.horizontal, 16)
                    .frame(height: 52)
                    .glassEffect(.regular, in: .rect(cornerRadius: 12))
                    .overlay {
                        if showsEmailError {
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .stroke(.red, lineWidth: 1)
                        }
                    }

                if showsEmailError {
                    Text("올바른 이메일 주소를 입력하세요.")
                        .font(.caption)
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                HStack {
                    Text("비밀번호를 잊어버리셨나요?")
                    NavigationLink {
                        PasswordResetRequestView(isSignedIn: $isSignedIn)
                    } label: {
                        Text("여기를 클릭")
                    }
                    Spacer()
                }
                .font(.caption)


                Button {
                    // 이메일 형식이 올바르지 않으면 오류를 표시하고 화면 이동을 중단
                    guard isEmailValid else {
                        showsEmailError = true
                        return
                    }

                    email = normalizedEmail

                    if existingEmails.contains(normalizedEmail) {
                        destination = .signIn
                    } else {
                        destination = .signUp
                    }
                } label: {
                    Text("계속하기")
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.glassProminent)
                .tint(.blue)
                .frame(maxWidth: .infinity)
                .frame(height: 52)
                .disabled(email.isEmpty)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, 24)
        .onAppear {
            isEmailFocused = true
        }
        .navigationDestination(item: $destination) { destination in
            switch destination {
            case .signIn:
                EmailSignInView(email: $email, isSignedIn: $isSignedIn)
            case .signUp:
                EmailSignUpView(email: $email, isSignedIn: $isSignedIn)
            }
        }
    }
}


#Preview("Dark") {
    EmailAuthView(isSignedIn: .constant(false))
        .preferredColorScheme(.dark)
}
