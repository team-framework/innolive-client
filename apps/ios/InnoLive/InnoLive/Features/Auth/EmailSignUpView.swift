//
//  EmailSignUpView.swift
//  InnoLive
//
//  Created by chaeyn on 7/25/26.
//

import SwiftUI

struct EmailSignUpView: View {
    @Binding var email: String
    @Binding var isSignedIn: Bool
    @State private var password = ""
    @State private var passwordConfirmation = ""
    @FocusState private var isPasswordFocused: Bool

    private var canSignUp: Bool {
        !email.isEmpty && !password.isEmpty && password == passwordConfirmation
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            VStack(alignment: .leading, spacing: 8) {
                Text("회원가입")
                    .font(.title.bold())
            }

            VStack(spacing: 12) {
                TextField("이메일", text: $email)
                    .textContentType(.emailAddress)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(.horizontal, 16)
                    .frame(height: 52)
                    .glassEffect(.regular, in: .rect(cornerRadius: 12))

                passwordField("비밀번호", text: $password)
                    .focused($isPasswordFocused)
                passwordField("비밀번호 확인", text: $passwordConfirmation)
            }

            if !passwordConfirmation.isEmpty && password != passwordConfirmation {
                Text("비밀번호가 일치하지 않습니다.")
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            Button {
                isSignedIn = true
            } label: {
                Text("회원가입")
                    .font(.body.weight(.semibold))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.glassProminent)
            .tint(.blue)
            .frame(maxWidth: .infinity)
            .frame(height: 52)
            .disabled(!canSignUp)

            Spacer()
        }
        .padding()
        .onAppear {
            isPasswordFocused = true
        }
    }

    private func passwordField(_ placeholder: String, text: Binding<String>) -> some View {
        SecureField(placeholder, text: text)
            .textContentType(.newPassword)
            .padding(.horizontal, 16)
            .frame(height: 52)
            .glassEffect(.regular, in: .rect(cornerRadius: 12))
    }
}

#Preview {
    NavigationStack {
        EmailSignUpView(
            email: .constant("new@dgsw.hs.kr"),
            isSignedIn: .constant(false)
        )
    }
}
