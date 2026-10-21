import SwiftUI

/// 해상도는 방송 설정의 송출 화질이 정하므로 여기서는 프레임 레이트만 바꾼다.
struct CameraFrameRateSelectionRow: View {
    @Binding var selectedQualityRaw: String
    let isChanging: Bool
    let isDisabled: Bool

    private var quality: CameraQualityPreset { CameraQualityPreset(rawValue: selectedQualityRaw) ?? .defaultValue }

    var body: some View {
        Menu {
            Picker(String(localized: "프레임 레이트"), selection: Binding(
                get: { quality.framesPerSecond },
                set: { selectedQualityRaw = quality.with(framesPerSecond: $0).rawValue }
            )) {
                ForEach(CameraQualityPreset.frameRates, id: \.self) { fps in
                    Text(verbatim: "\(fps)fps").tag(fps)
                }
            }
        } label: {
            SettingsSelectionLabel(title: String(localized: "프레임 레이트"), systemImage: "video.fill",
                                   value: "\(quality.framesPerSecond)fps", isLoading: isChanging)
        }
        .buttonStyle(.plain)
        .disabled(isChanging || isDisabled)
        .accessibilityIdentifier("camera-frame-rate-menu")
    }
}

struct CameraDeviceSelectionRow: View {
    @Binding var selectedCameraID: String
    let options: [CameraOption]
    let isChanging: Bool

    private var selectedName: String {
        options.first { $0.id == selectedCameraID }?.name ?? ""
    }

    var body: some View {
        Menu {
            Picker(String(localized: "카메라 기기"), selection: $selectedCameraID) {
                ForEach(options) { option in
                    Text(option.name).tag(option.id)
                }
            }
        } label: {
            SettingsSelectionLabel(title: String(localized: "카메라 기기"), systemImage: "camera.fill",
                                   value: selectedName, isLoading: isChanging)
        }
        .buttonStyle(.plain)
        .disabled(isChanging || options.isEmpty)
        .accessibilityIdentifier("camera-device-menu")
    }
}

struct AudioDeviceSelectionRow: View {
    @Binding var selectedAudioID: String
    let options: [AudioOption]
    let isChanging: Bool

    private var selectedName: String {
        options.first { $0.id == selectedAudioID }?.name ?? String(localized: "시스템 기본값")
    }

    var body: some View {
        Menu {
            Picker(String(localized: "오디오 기기"), selection: $selectedAudioID) {
                ForEach(options) { option in
                    Text(option.name).tag(option.id)
                }
            }
        } label: {
            SettingsSelectionLabel(title: String(localized: "오디오 기기"), systemImage: "mic.fill",
                                   value: selectedName, isLoading: isChanging)
        }
        .buttonStyle(.plain)
        .disabled(isChanging || options.isEmpty)
        .accessibilityIdentifier("audio-device-menu")
    }
}

private struct SettingsSelectionLabel: View {
    let title: String
    let systemImage: String
    let value: String
    let isLoading: Bool

    var body: some View {
        SettingsGlassRow {
            HStack(spacing: 10) {
                Image(systemName: systemImage).frame(width: 22).accessibilityHidden(true)
                Text(title).font(.subheadline.weight(.semibold))
                Spacer(minLength: 8)
                if isLoading {
                    ProgressView().controlSize(.small)
                } else {
                    Text(value).foregroundStyle(.secondary).multilineTextAlignment(.trailing)
                    Image(systemName: "chevron.up.chevron.down")
                        .font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                }
            }
            .foregroundStyle(.primary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue(value)
    }
}
