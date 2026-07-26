//
//  CameraAudioSettingsView.swift
//  InnoLive
//

import AVFoundation
import SwiftUI

// 선택 가능한 해상도 값 목록
private enum CameraResolution: String, CaseIterable {
    case fullHD30 = "1080p - 30fps"
    case fullHD24 = "1080p - 24fps"
    case hd30 = "720p - 30fps"
    case hd24 = "720p - 24fps"
}

struct CameraAudioSettingsView: View {
    @State private var selectedResolution: CameraResolution = .fullHD30
    @State private var selectedCamera = "전면 카메라"
    @State private var selectedAudio = "기본 마이크"
    @State private var externalCameraNames: [String] = []
    @State private var externalAudioNames: [String] = []

    private var cameraOptions: [String] {
        ["전면 카메라", "후면 카메라"] + externalCameraNames
    }

    private var audioOptions: [String] {
        ["기본 마이크"] + externalAudioNames
    }

    var body: some View {
        ScrollView {
            GlassEffectContainer {
                VStack(spacing: 12) {
                    // enum 해상도 값을 공통 선택 행에서 쓸 문자열 Binding으로 변환
                    selectionRow(
                        title: "카메라 해상도",
                        systemImage: "video.fill",
                        selection: Binding(
                            get: { selectedResolution.rawValue },
                            set: { selectedResolution = CameraResolution(rawValue: $0) ?? .fullHD30 }
                        ),
                        options: CameraResolution.allCases.map(\.rawValue)
                    )

                    selectionRow(
                        title: "카메라 기기",
                        systemImage: "camera.fill",
                        selection: $selectedCamera,
                        options: cameraOptions
                    )

                    selectionRow(
                        title: "오디오 기기",
                        systemImage: "mic.fill",
                        selection: $selectedAudio,
                        options: audioOptions
                    )
                }
            }
            .padding(24)
        }
        .navigationTitle("카메라 및 오디오")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            loadConnectedDevices()
        }
    }

    private func selectionRow(
        title: String,
        systemImage: String,
        selection: Binding<String>,
        options: [String]
    ) -> some View {
        SettingsGlassRow {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .frame(width: 24)

                Text(title)
                    .font(.body.weight(.semibold))

                Spacer()

                // 누르면 선택 목록을 열고, 현재 선택값은 오른쪽에 표시하는 메뉴
                Menu {
                    Picker(title, selection: selection) {
                        ForEach(options, id: \.self) { option in
                            Text(option).tag(option)
                        }
                    }
                } label: {
                    HStack(spacing: 4) {
                        Text(selection.wrappedValue)
                            .lineLimit(1)
                        Image(systemName: "chevron.up.chevron.down")
                            .font(.caption2.weight(.semibold))
                    }
                    .foregroundStyle(.primary)
                }
            }
        }
    }

    private func loadConnectedDevices() {
        // USB 등으로 연결된 외부 카메라 목록을 AVFoundation에서 읽어옴
        let externalCameras = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.external],
            mediaType: .video,
            position: .unspecified
        ).devices
        externalCameraNames = externalCameras.map(\.localizedName)

        // 현재 기기에서 선택 가능한 기본 마이크 외 오디오 입력 목록을 읽어옴
        let availableInputs = AVAudioSession.sharedInstance().availableInputs ?? []
        externalAudioNames = availableInputs
            .filter { $0.portType != .builtInMic }
            .map(\.portName)
    }
}

#Preview {
    NavigationStack {
        CameraAudioSettingsView()
    }
}
