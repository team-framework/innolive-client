#if DEBUG
import SwiftUI

/// Local UI inspection without signing in or starting capture and broadcast lifecycle.
struct StudioValidationView: View {
    @StateObject private var authentication = AuthSession()
    @StateObject private var youtube = YouTubeIntegration()

    private var initialPage: StudioPage {
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("--studio-page-preview") { return .preview }
        if arguments.contains("--studio-page-details") { return .details }
        return .basic
    }

    var body: some View {
        NavigationStack {
            HomeView(authentication: authentication, youtube: youtube,
                     initialPage: initialPage, startsLifecycle: false)
        }
        .preferredColorScheme(.dark)
    }
}
#endif
