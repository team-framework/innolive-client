//
//  LoginView.swift
//  InnoLive
//
//  Created by chaeyn on 7/25/26.
//

import SwiftUI
import AuthenticationServices

struct LoginView: View {
    @Environment(\.colorScheme) private var colorScheme
    @Binding var isLoggedIn: Bool

    var body: some View {
        VStack (alignment: .leading, spacing: 24) {
            VStack (alignment: .leading, spacing: 4) {
                Text("라이브 방송을 안전하게")
                Text("만드는 쉬운 방법")
            }
            .font(.system(size: 30, weight: .semibold))

            VStack (spacing: 8) {
                SignInWithAppleButton(.signIn) { request in
                    request.requestedScopes = [.fullName, .email]
                } onCompletion: { result in
                    switch result {
                    case .success(let authorization):
                        guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential else {
                            return
                        }

                        let userID = credential.user
                        let email = credential.email
                        let fullName = credential.fullName

                        print(userID, email ?? "", fullName?.description ?? "")
                        
                        isLoggedIn = true

                    case .failure(let error):
                        print("Apple 로그인 실패: \(error.localizedDescription)")
                    }
                }
                .signInWithAppleButtonStyle(
                    colorScheme == .dark ? .white : .black
                )
                .id(colorScheme)
                .frame(
                    maxWidth: .infinity,
                    alignment: .center
                )
                .frame(height: 44)

                EmailLoginView()
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 32)
    }
}
