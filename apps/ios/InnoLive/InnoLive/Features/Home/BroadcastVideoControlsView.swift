import SwiftUI

struct BroadcastVideoControlsView: View {
    @ObservedObject var uplink: WebRTCVideoUplink
    @Environment(CameraManager.self) private var cameraManager
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
               VStack(alignment: .leading, spacing: 20) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(String(localized: "프리셋"))
                            .font(.body.weight(.semibold))
                        HStack(spacing: 8) {
                            ForEach(BroadcastVideoLook.allCases) { look in
                                presetButton(look)
                            }
                        }
                    }

                    control(
                        title: String(localized: "노출"),
                        value: String(format: "%+.1f EV", uplink.videoQualitySettings.exposureEV),
                        binding: exposureBinding,
                        range: -2...2,
                        step: 0.1,
                        leading: String(localized: "어둡게"),
                        trailing: String(localized: "밝게")
                    )

                    control(
                        title: String(localized: "색온도"),
                        value: String(format: "%+.2f", uplink.videoQualitySettings.warmth),
                        binding: warmthBinding,
                        range: -1...1,
                        step: 0.05,
                        leading: String(localized: "차갑게"),
                        trailing: String(localized: "따뜻하게")
                    )

                    control(
                        title: String(localized: "채도"),
                        value: "\(Int((uplink.videoQualitySettings.saturation * 100).rounded()))%",
                        binding: saturationBinding,
                        range: 0...2,
                        step: 0.05,
                        leading: "0%",
                        trailing: "200%"
                    )

                    Button {
                        uplink.setExposureEV(0)
                        cameraManager.setExposureEV(0)
                        uplink.setColor(warmth: 0, saturation: 1)
                    } label: {
                        Label(String(localized: "초기화"), systemImage: "arrow.counterclockwise")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }
                .padding(.horizontal, 24)
                .padding(.top, 16)
                .padding(.bottom, 10)
            }
            .navigationTitle(String(localized: "영상 조절"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "닫기")) { dismiss() }
                }
            }
        }
    }

    private func presetButton(_ look: BroadcastVideoLook) -> some View {
        let selected = look.matches(uplink.videoQualitySettings)
        return Button {
            uplink.setExposureEV(look.exposureEV)
            cameraManager.setExposureEV(look.exposureEV)
            uplink.setColor(warmth: look.warmth, saturation: look.saturation)
        } label: {
            Text(look.title)
                .font(.body.weight(.semibold))
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .tint(selected ? Color.accentColor : Color.secondary)
        .accessibilityLabel(look.title)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var exposureBinding: Binding<Double> {
        Binding(
            get: { Double(uplink.videoQualitySettings.exposureEV) },
            set: { value in
                let ev = Float(value)
                uplink.setExposureEV(ev)
                cameraManager.setExposureEV(ev)
            }
        )
    }

    private var warmthBinding: Binding<Double> {
        Binding(
            get: { Double(uplink.videoQualitySettings.warmth) },
            set: { uplink.setColor(warmth: Float($0), saturation: uplink.videoQualitySettings.saturation) }
        )
    }

    private var saturationBinding: Binding<Double> {
        Binding(
            get: { Double(uplink.videoQualitySettings.saturation) },
            set: { uplink.setColor(warmth: uplink.videoQualitySettings.warmth, saturation: Float($0)) }
        )
    }

    private func control(
        title: String,
        value: String,
        binding: Binding<Double>,
        range: ClosedRange<Double>,
        step: Double,
        leading: String,
        trailing: String
    ) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title)
                    .font(.body.weight(.semibold))
                Spacer()
                Text(value)
                    .font(.body.monospacedDigit())
                    .foregroundStyle(.secondary)
            }

            Slider(value: binding, in: range, step: step)
                .accessibilityLabel(title)
                .accessibilityValue(value)

            HStack {
                Text(leading)
                Spacer()
                Text(trailing)
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
    }
}

private enum BroadcastVideoLook: CaseIterable, Identifiable {
    case bright
    case vivid
    case warm

    var id: Self { self }

    var title: String {
        switch self {
        case .bright: String(localized: "화사")
        case .vivid: String(localized: "선명")
        case .warm: String(localized: "따뜻")
        }
    }

    var exposureEV: Float {
        switch self {
        case .bright: 0.8
        case .vivid: 0
        case .warm: 0.2
        }
    }

    var warmth: Float {
        switch self {
        case .bright: 0.2
        case .vivid: -0.2
        case .warm: 0.6
        }
    }

    var saturation: Float {
        switch self {
        case .bright: 1.2
        case .vivid: 1.4
        case .warm: 1.1
        }
    }

    func matches(_ settings: BroadcastVideoQualitySettings) -> Bool {
        abs(settings.exposureEV - exposureEV) < 0.001
            && abs(settings.warmth - warmth) < 0.001
            && abs(settings.saturation - saturation) < 0.001
    }
}
