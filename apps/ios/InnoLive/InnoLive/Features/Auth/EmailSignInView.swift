//
//  EmailSignInView.swift
//  InnoLive
//
//  Created by chaeyn on 7/25/26.
//

import SwiftUI

struct EmailSignInView: View {
    @Binding var email: String
    @Binding var isSignedIn: Bool
    @State private var password = ""
    @FocusState private var isPasswordFocused: Bool

    private var canSignIn: Bool {
        !email.isEmpty && !password.isEmpty
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            VStack(alignment: .leading, spacing: 8) {
                Text("로그인")
                    .font(.title.bold())
            }

            VStack(spacing: 12) {
                TextField("이메일", text: $email)
                    .textContentType(.emailAddress)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .disabled(true)
                    .foregroundStyle(.secondary)
                    .padding(.leading, 16)
                    .padding(.trailing, 48)
                    .frame(height: 52)
                    .glassEffect(.regular, in: .rect(cornerRadius: 12))

                SecureField("비밀번호", text: $password)
                    .textContentType(.password)
                    .focused($isPasswordFocused)
                    .padding(.horizontal, 16)
                    .frame(height: 52)
                    .glassEffect(.regular, in: .rect(cornerRadius: 12))

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
            }

            Button {
                isSignedIn = true
            } label: {
                Text("로그인")
                    .font(.body.weight(.semibold))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.glassProminent)
            .tint(.blue)
            .frame(maxWidth: .infinity)
            .frame(height: 52)
            .disabled(!canSignIn)

            Spacer()
        }
        .padding()
        .onAppear {
            isPasswordFocused = true
        }
    }
}

#Preview {
    NavigationStack {
        EmailSignInView(
            email: .constant("chaeyn@dgsw.hs.kr"),
            isSignedIn: .constant(false)
        )
    }
}
