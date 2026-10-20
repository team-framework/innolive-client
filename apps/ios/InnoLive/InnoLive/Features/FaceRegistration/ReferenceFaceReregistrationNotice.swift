import Combine
import SwiftUI

/// AI 워커가 재시작해 잃은 기준 얼굴이 있다는 안내다. 그대로 두면 등록한 사람도 블러된다.
struct ReferenceFaceReregistrationBanner: View {
    var showsDisclosure = false

    var body: some View {
        SettingsGlassRow {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.orange)
                VStack(alignment: .leading, spacing: 4) {
                    Text(String(localized: "다시 등록이 필요한 얼굴이 있어요"))
                        .font(.subheadline.weight(.semibold))
                    Text(ReferenceFaceReregistration.notice)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if showsDisclosure {
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("reference-face-reregistration-banner")
    }
}

/// 방송 준비 전에 서버 기준 얼굴의 재등록 필요 여부를 확인한다.
@MainActor
final class ReferenceFaceReregistrationCheck: ObservableObject {
    // iOS 18 소멸자 충돌을 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    @Published private(set) var isRequired = false

    /// 서버 AI를 쓸 때만 확인한다. 온디바이스 얼굴은 이 기기에 저장돼 워커 재시작과 무관하다.
    /// 확인하지 못하면 이전 값을 유지한다. 안내는 방송 준비를 막지 않는다.
    func refresh(authentication: AuthSession, mode: AIProcessingMode = .selected) async {
        guard mode == .server else {
            isRequired = false
            return
        }
        if let status = try? await ReferenceFaceAPI(authentication: authentication).status() {
            isRequired = status.reregistrationRequired
        }
    }
}
