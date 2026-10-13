#if DEBUG
import SwiftUI

/// 개발 빌드에서만 송출 경로를 보여 준다. 실기기 테스트에서 직접 송출인지 바로 확인하기 위해서다.
/// ingest 주소와 스트림 키는 표시하지 않는다.
struct DirectPublishDebugBadge: View {
    @ObservedObject var youtube: YouTubeIntegration

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: "송출 경로: \(route)")
            ForEach(youtube.directPublishStates.keys.sorted(), id: \.self) { provider in
                let title = BroadcastSettingsProvider(rawValue: provider)?.title ?? provider
                Text(verbatim: "\(title): \(Self.label(youtube.directPublishStates[provider]))")
            }
            if youtube.isDirectPublishSession, !youtube.directPublishStates.isEmpty {
                Text(verbatim: youtube.isDirectMicrophoneUnavailable ? "음성: 무음(마이크 없음)" : "음성: 마이크")
            }
        }
        .font(.caption2.monospaced())
        .foregroundStyle(.secondary)
        .accessibilityIdentifier("direct-publish-debug-badge")
    }

    private var route: String {
        if youtube.isDirectPublishSession { return "앱 직접 송출" }
        if youtube.session?.processingMode == .onDevice { return "서버 중계 · 앱에서 가림" }
        return "서버 중계"
    }

    private static func label(_ state: DirectPublishState?) -> String {
        switch state {
        case .connecting: return "연결 중"
        case .publishing: return "송출 중"
        case .paused: return "일시 중지"
        case .reconnecting: return "재연결 중"
        case .failed: return "실패"
        case nil: return "-"
        }
    }
}
#endif
