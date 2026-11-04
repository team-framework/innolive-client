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
                .disabled(authentication.isDeletingAccount)
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
        .onChange(of: authentication.isAuthenticated) { _, isAuthenticated in
            // 만료 요청의 비동기 실패 처리가 재로그인 뒤 도착해도 홈 화면에
            // 이전 만료 오류 배너가 남지 않도록 한다.
            if isAuthenticated {
                youtube.dismissError()
                let accessToken = authentication.currentAccessToken()
                youtube.planStore.restore(accountID: OfflineGrant.accountID(fromAccessToken: accessToken))
                // 로컬 모드는 저장된 이용 권한으로 시작하므로 오프라인으로 실행해도 네트워크를 기다리지 않는다.
                if youtube.storedAdministrator(accessToken: accessToken) {
                    youtube.startLocalModeSupport { authentication.currentAccessToken() }
                }
                Task {
                    await youtube.refreshConnection(accessToken: accessToken, quietWhenUnreachable: true)
                    youtube.localTakeUploader.drain()
                    // 서버에 닿는 동안 오프라인 이용 권한을 갱신한다. 로컬 모드는 관리자 전용이다.
                    if await youtube.isAdministrator(accessToken: accessToken) {
                        youtube.startLocalModeSupport { authentication.currentAccessToken() }
                    }
                    await youtube.refreshPlan(accessToken: accessToken)
                }
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
