//
//  PasswordVerificationView.swift
//  InnoLive
//

import SwiftUI

struct PasswordVerificationView: View {
    let email: String
    @Binding var isSignedIn: Bool
    @State private var verificationCode = ""
    @State private var showsPasswordReset = false
    @FocusState private var isVerificationCodeFocused: Bool

    private var isVerificationCodeValid: Bool {
        verificationCode.count == 6 && verificationCode.allSatisfy(\.isNumber)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            VStack(alignment: .leading, spacing: 8) {
                Text("이메일 인증")
                    .font(.title.bold())
                Text("\(email)로 보낸 인증 코드 6자리를 입력하세요.")
                    .foregroundStyle(.secondary)
            }

            TextField("인증 코드", text: $verificationCode)
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .focused($isVerificationCodeFocused)
                .onChange(of: verificationCode) {
                    verificationCode = String(verificationCode.filter(\.isNumber).prefix(6))
                }
                .padding(.horizontal, 16)
                .frame(height: 52)
                .glassEffect(.regular, in: .rect(cornerRadius: 12))

            Button {
                showsPasswordReset = true
            } label: {
                Text("인증하고 계속하기")
                    .font(.body.weight(.semibold))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.glassProminent)
            .tint(.blue)
            .frame(maxWidth: .infinity)
            .frame(height: 52)
            .disabled(!isVerificationCodeValid)

            Spacer()
        }
        .padding()
        .onAppear {
            isVerificationCodeFocused = true
        }
        .navigationDestination(isPresented: $showsPasswordReset) {
            PasswordResetView(email: email, isSignedIn: $isSignedIn)
        }
    }
}

#Preview {
    NavigationStack {
        PasswordVerificationView(
            email: "chaeyn@dgsw.hs.kr",
            isSignedIn: .constant(false)
        )
    }
}
