//
//  PasswordResetRequestView.swift
//  InnoLive
//

import SwiftUI

struct PasswordResetRequestView: View {
    @Binding var isSignedIn: Bool
    @State private var email = ""
    @State private var showsEmailError = false
    @State private var showsVerification = false
    @FocusState private var isEmailFocused: Bool

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
        VStack(alignment: .leading, spacing: 24) {
            VStack(alignment: .leading, spacing: 8) {
                Text("비밀번호 찾기")
                    .font(.title.bold())
                Text("가입한 이메일을 입력해주세요.")
                    .foregroundStyle(.secondary)
            }

            TextField("이메일", text: $email)
                .textContentType(.emailAddress)
                .keyboardType(.emailAddress)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($isEmailFocused)
                .onChange(of: email) {
                    showsEmailError = false
                }
                .padding(.horizontal, 16)
                .frame(height: 52)
                .glassEffect(.regular, in: .rect(cornerRadius: 12))
                // .background는 요소 뒤, .overlay는 요소 앞
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
            }

            Button {
                guard isEmailValid else {
                    showsEmailError = true
                    return
                }

                email = normalizedEmail
                showsVerification = true
            } label: {
                Text("인증 코드 보내기")
                    .font(.body.weight(.semibold))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.glassProminent)
            .tint(.blue)
            .frame(maxWidth: .infinity)
            .frame(height: 52)
            .disabled(email.isEmpty)

            Spacer()
        }
        .padding()
        .onAppear {
            isEmailFocused = true
        }
        .navigationDestination(isPresented: $showsVerification) {
            PasswordVerificationView(email: email, isSignedIn: $isSignedIn)
        }
    }
}

#Preview {
    NavigationStack {
        PasswordResetRequestView(isSignedIn: .constant(false))
    }
}
