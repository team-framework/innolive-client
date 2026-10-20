import SwiftUI

/// 방송 설정의 송출 화질 선택이다. 세션 생성 `broadcast_resolution`과 카메라 캡처 해상도를 함께 정하고,
/// 프레임 레이트는 카메라 및 오디오 설정에서 고른 값을 유지한다.
struct BroadcastResolutionSelectionView: View {
    @ObservedObject var youtube: YouTubeIntegration
    @ObservedObject private var planStore: PlanStore
    @AppStorage(CameraQualityPreset.storageKey) private var selectedQualityRaw = CameraQualityPreset.defaultValue.rawValue

    init(youtube: YouTubeIntegration) {
        self.youtube = youtube
        self.planStore = youtube.planStore
    }

    private var storedQuality: CameraQualityPreset { CameraQualityPreset(rawValue: selectedQualityRaw) ?? .defaultValue }
    /// 세션을 만든 뒤에는 서버가 확정한 해상도가 송출 화질이다. 방송 중 변경은 방송 방식 화면이 맡는다.
    private var sessionResolution: VideoUplinkResolution? {
        youtube.session == nil ? nil : VideoUplinkResolution(serverValue: youtube.broadcastResolution) ?? .hd720
    }
    private var isLocked: Bool { youtube.isBroadcastSettingsLocked || youtube.session != nil }

    private var isFHDBlocked: Bool {
        planStore.snapshot?.blocksFHDOnly(targetCount: max(youtube.selectedBroadcastProviders.count, 1),
                                          processing: youtube.planProcessingMode) == true
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(String(localized: "방송 화질", table: "BroadcastMode")).font(.subheadline.weight(.semibold))
            Picker(String(localized: "방송 화질", table: "BroadcastMode"), selection: Binding(
                get: { sessionResolution ?? storedQuality.resolution },
                set: { resolution in
                    guard !isLocked, resolution != .fhd || !isFHDBlocked else { return }
                    selectedQualityRaw = storedQuality.with(resolution: resolution).rawValue
                }
            )) {
                ForEach(VideoUplinkResolution.allCases, id: \.self) { Text(verbatim: $0.title).tag($0) }
            }
            .pickerStyle(.segmented)
            .disabled(isLocked)
            .accessibilityIdentifier("broadcast-resolution-picker")
            if isLocked {
                Text(String(localized: "방송을 준비하거나 송출하는 동안에는 화질을 변경할 수 없습니다.", table: "BroadcastMode"))
                    .font(.caption).foregroundStyle(.secondary)
            } else if isFHDBlocked {
                Text(String(localized: "현재 요금제에서는 FHD로 방송할 수 없습니다. 요금제 및 사용량을 확인해 주세요.", table: "BroadcastMode"))
                    .font(.caption).foregroundStyle(.orange)
            }
        }
    }
}
