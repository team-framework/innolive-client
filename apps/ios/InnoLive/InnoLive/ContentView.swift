//
//  ContentView.swift
//  InnoLive
//
//  Created by chaeyn on 7/25/26.
//

import SwiftUI

struct ContentView: View {
    @StateObject private var authentication = AuthSession()
    @StateObject private var youtube = YouTubeIntegration()

    var body: some View {
        Group {
            if authentication.isAuthenticated {
                NavigationStack {
                    HomeView(
                        authentication: authentication,
                        youtube: youtube
                    )
                }
            } else {
                NavigationStack {
                    SignInView(authentication: authentication)
                }
            }
        }
        .task {
            youtube.configureAuthentication(authentication)
            authentication.restore()
        }
        .onReceive(NotificationCenter.default.publisher(for: AuthenticationSessionExpiration.notification)) { _ in
            guard authentication.isAuthenticated else { return }
            Task {
                switch await authentication.refreshSession() {
                case .refreshed:
                    break
                case .invalid:
                    youtube.reset()
                    authentication.expireSession()
                case .unavailable:
                    authentication.showError("로그인 상태를 갱신하지 못했습니다. 잠시 후 다시 시도해 주세요.")
                }
            }
        }
        .onChange(of: authentication.isAuthenticated) { _, isAuthenticated in
            // 만료 요청의 비동기 실패 처리가 재로그인 뒤 도착해도 홈 화면에
            // 이전 만료 오류 배너가 남지 않도록 한다.
            if isAuthenticated {
                youtube.dismissError()
            }
        }
    }
}

#Preview("Dark") {
    ContentView()
        .environment(CameraManager())
        .preferredColorScheme(.dark)
}

#Preview("Light") {
    ContentView()
        .environment(CameraManager())
        .preferredColorScheme(.light)
}
