import SwiftUI

struct BroadcastVideoControlsView: View {
    @ObservedObject var uplink: WebRTCVideoUplink
    @Environment(CameraManager.self) private var cameraManager
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
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
                        Label(String(localized: "조절 초기화"), systemImage: "arrow.counterclockwise")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }
                .padding(.horizontal, 24)
                .padding(.top, 16)
                .padding(.bottom, 18)
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
