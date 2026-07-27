//
//  CameraAudioSettingsView.swift
//  InnoLive
//

import AVFoundation
import SwiftUI

// 선택 가능한 해상도 값 목록
private enum CameraResolution: String, CaseIterable { // CaseIterable: 전체 case를 불러올 수 있게 함
    case fullHD30 = "1080p - 30fps"
    case fullHD24 = "1080p - 24fps"
    case hd30 = "720p - 30fps"
    case hd24 = "720p - 24fps"
}

struct CameraAudioSettingsView: View {
    @State private var selectedResolution: CameraResolution = .fullHD30
    @State private var selectedCamera = ""
    @State private var selectedAudio = ""
    @State private var cameraNames: [String] = []
    @State private var audioNames: [String] = []

    private var cameraOptions: [String] { cameraNames }

    private var audioOptions: [String] { audioNames }

    var body: some View {
        ScrollView {
            GlassEffectContainer {
                VStack(spacing: 12) {
                    selectionRow(
                        title: "카메라 해상도",
                        systemImage: "video.fill",
                        // 해상도 값을 선택 행에서 쓸 문자열 Binding으로 변환
                        selection: Binding(
                            get: { selectedResolution.rawValue },
                            set: { selectedResolution = CameraResolution(rawValue: $0) ?? .fullHD30 }
                        ),
                        // 모든 enum case를 문자열 목록으로 바꿈
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
            loadAvailableDevices()
            if selectedCamera.isEmpty {
                selectedCamera = cameraOptions.first ?? ""
            }
            if selectedAudio.isEmpty {
                selectedAudio = audioOptions.first ?? ""
            }
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

    private func loadAvailableDevices() {
        // AVFoundation이 현재 인식한 영상 촬영 장치 이름을 읽어옴
        let cameras = AVCaptureDevice.DiscoverySession(
            deviceTypes: [
                .builtInWideAngleCamera,
                .builtInTelephotoCamera,
                .external
            ],
            mediaType: .video,
            position: .unspecified
        ).devices
        cameraNames = cameras.map(\.localizedName)
        audioNames = (AVAudioSession.sharedInstance().availableInputs ?? []).map(\.portName)
    }
}

#Preview {
    NavigationStack {
        CameraAudioSettingsView()
    }
}
