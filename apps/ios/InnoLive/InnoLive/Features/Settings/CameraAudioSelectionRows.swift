import SwiftUI

struct CameraQualitySelectionRow: View {
    @Binding var selectedQualityRaw: String
    let isChanging: Bool
    let isDisabled: Bool

    var body: some View {
        SettingsGlassRow {
            HStack(spacing: 12) {
                Image(systemName: "video.fill")
                    .frame(width: 24)
                Text("카메라 화질")
                    .font(.body.weight(.semibold))
                Spacer()
                Menu {
                    Picker("카메라 화질", selection: $selectedQualityRaw) {
                        ForEach(CameraQualityPreset.allCases) { quality in
                            Text(quality.rawValue).tag(quality.rawValue)
                        }
                    }
                } label: {
                    SelectionValueLabel(value: selectedQualityRaw, isLoading: isChanging)
                }
                .disabled(isChanging || isDisabled)
            }
        }
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
        SettingsGlassRow {
            HStack(spacing: 12) {
                Image(systemName: "camera.fill")
                    .frame(width: 24)
                Text("카메라 기기")
                    .font(.body.weight(.semibold))
                Spacer()
                Menu {
                    Picker("카메라 기기", selection: $selectedCameraID) {
                        ForEach(options) { option in
                            Text(option.name).tag(option.id)
                        }
                    }
                } label: {
                    SelectionValueLabel(value: selectedName, isLoading: isChanging)
                }
                .disabled(isChanging || options.isEmpty)
            }
        }
    }
}

struct AudioDeviceSelectionRow: View {
    @Binding var selectedAudioID: String
    let options: [AudioOption]
    let isChanging: Bool

    private var selectedName: String {
        options.first { $0.id == selectedAudioID }?.name ?? "시스템 기본값"
    }

    var body: some View {
        SettingsGlassRow {
            HStack(spacing: 12) {
                Image(systemName: "mic.fill")
                    .frame(width: 24)
                Text("오디오 기기")
                    .font(.body.weight(.semibold))
                Spacer()
                Menu {
                    Picker("오디오 기기", selection: $selectedAudioID) {
                        ForEach(options) { option in
                            Text(option.name).tag(option.id)
                        }
                    }
                } label: {
                    SelectionValueLabel(value: selectedName, isLoading: isChanging)
                }
                .disabled(isChanging || options.isEmpty)
            }
        }
    }
}

private struct SelectionValueLabel: View {
    let value: String
    let isLoading: Bool

    var body: some View {
        HStack(spacing: 4) {
            if isLoading {
                ProgressView()
                    .controlSize(.small)
            } else {
                Text(value)
                    .lineLimit(1)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.caption2.weight(.semibold))
            }
        }
        .foregroundStyle(.primary)
    }
}
