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
                    youtube.operatingMode.send(.refreshUnavailable)
                    // Local mode keeps working without the server, so it is not an error there.
                    guard !youtube.operatingMode.state.mode.isLocal else { return }
                    authentication.showError(String(localized: "로그인 상태를 갱신하지 못했습니다. 잠시 후 다시 시도해 주세요."))
                }
            }
        }
        .onChange(of: authentication.isAuthenticated) { _, isAuthenticated in
            // 만료 요청의 비동기 실패 처리가 재로그인 뒤 도착해도 홈 화면에
            // 이전 만료 오류 배너가 남지 않도록 한다.
            if isAuthenticated {
                youtube.dismissError()
                let accessToken = authentication.currentAccessToken()
                youtube.planStore.restore(accountID: OfflineGrant.accountID(fromAccessToken: accessToken))
                // Local mode starts from the stored grant, so an offline launch never waits on the network.
                if youtube.storedAdministrator(accessToken: accessToken) {
                    youtube.startLocalModeSupport { authentication.currentAccessToken() }
                }
                Task {
                    await youtube.refreshConnection(accessToken: accessToken, quietWhenUnreachable: true)
                    youtube.localTakeUploader.drain()
                    // Renews the offline grant while the server is reachable. Local mode is admin-only.
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
