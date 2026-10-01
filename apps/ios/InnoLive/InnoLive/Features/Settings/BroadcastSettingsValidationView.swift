#if DEBUG
import SwiftUI

// 로그인·서버 요청 없이 폼의 두 플랫폼과 입력 검증을 확인하는 실행 경로.
struct BroadcastSettingsValidationView: View {
    @StateObject private var authentication = AuthSession()
    @StateObject private var integration = YouTubeIntegration(
        preferencesStore: YouTubePreferencesStore(userDefaults: UserDefaults(suiteName: "broadcast-settings-preview")!))
    var body: some View {
        NavigationStack {
            BroadcastSettingsView(authentication: authentication, youtube: integration)
        }
    }
}
#endif
