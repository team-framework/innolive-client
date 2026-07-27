//
//  PasswordResetView.swift
//  InnoLive
//

import SwiftUI

struct PasswordResetView: View {
    let email: String
    @Binding var isSignedIn: Bool

    @State private var password = ""
    @State private var passwordConfirmation = ""
    @State private var showsSuccess = false
    @State private var movesToSignIn = false
    @State private var showsPasswordMismatchError = false
    
    @FocusState private var isPasswordFocused: Bool

    private var canResetPassword: Bool {
        !password.isEmpty && !passwordConfirmation.isEmpty
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            VStack(alignment: .leading, spacing: 8) {
                Text("새 비밀번호 설정")
                    .font(.title.bold())
            }

            VStack(spacing: 12) {
                passwordField("새 비밀번호", text: $password)
                    .focused($isPasswordFocused)
                passwordField("새 비밀번호 확인", text: $passwordConfirmation)
            }

            if showsPasswordMismatchError {
                Text("비밀번호가 일치하지 않습니다.")
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            Button {
                guard password == passwordConfirmation else {
                    showsPasswordMismatchError = true
                    return
                }

                showsPasswordMismatchError = false
                showsSuccess = true
            } label: {
                Text("비밀번호 변경")
                    .font(.body.weight(.semibold))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.glassProminent)
            .tint(.blue)
            .frame(maxWidth: .infinity)
            .frame(height: 52)
            .disabled(!canResetPassword)

            Spacer()
        }
        .padding()
        .onAppear {
            isPasswordFocused = true
        }
        .alert("비밀번호를 변경했어요", isPresented: $showsSuccess) {
            Button("확인", role: .cancel) {
                movesToSignIn = true
            }
        } message: {
            Text("변경된 비밀번호로 로그인 해주세요.")
        }
        .navigationDestination(isPresented: $movesToSignIn) {
            EmailSignInView(email: .constant(email), isSignedIn: $isSignedIn)
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
        PasswordResetView(
            email: "chaeyn@dgsw.hs.kr",
            isSignedIn: .constant(false)
        )
    }
}
