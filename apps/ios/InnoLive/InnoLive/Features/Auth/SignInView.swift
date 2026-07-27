//
//  SignInView.swift
//  InnoLive
//
//  Created by chaeyn on 7/25/26.
//

import SwiftUI
import AuthenticationServices

struct SignInView: View {
    @Environment(\.colorScheme) private var colorScheme
    @Binding var isSignedIn: Bool

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
                        
                        isSignedIn = true

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

                NavigationLink {
                    EmailAuthView(isSignedIn: $isSignedIn)
                } label: {
                    Label("이메일로 로그인", systemImage: "envelope.fill")
                        .font(.callout.weight(.semibold))
                        .imageScale(.small)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .contentShape(Rectangle()) // 버튼 전체를 인식하도록 지정
                }
                .buttonStyle(.glass)
                .frame(maxWidth: .infinity)
                .frame(height: 44)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, 24)
    }
}
