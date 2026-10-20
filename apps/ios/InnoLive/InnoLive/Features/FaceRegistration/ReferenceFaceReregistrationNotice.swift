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
