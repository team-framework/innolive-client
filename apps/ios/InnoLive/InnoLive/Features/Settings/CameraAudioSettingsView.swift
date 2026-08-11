//
//  CameraAudioSettingsView.swift
//  InnoLive
//

import AVFoundation
import SwiftUI

struct CameraOption: Identifiable, Hashable {
    let id: String
    let name: String
}

struct AudioOption: Identifiable, Hashable {
    let id: String
    let name: String
}

struct CameraAudioSettingsView: View {
    @Environment(CameraManager.self) private var cameraManager
    @ObservedObject var youtube: YouTubeIntegration
    @AppStorage("selectedResolution") private var selectedQualityRaw = CameraQualityPreset.defaultValue.rawValue
    @State private var selectedCameraID = ""
    @State private var cameraOptions: [CameraOption] = []
    @State private var isChangingCamera = false
    @State private var isChangingQuality = false
    @State private var selectedAudioID = ""
    @State private var audioOptions: [AudioOption] = []
    @State private var isChangingAudio = false
    @State private var deviceErrorMessage: String?

    var body: some View {
        ScrollView {
            GlassEffectContainer {
                VStack(spacing: 12) {
                    CameraQualitySelectionRow(
                        selectedQualityRaw: $selectedQualityRaw,
                        isChanging: isChangingQuality,
                        isDisabled: isChangingCamera
                    )

                    CameraDeviceSelectionRow(
                        selectedCameraID: $selectedCameraID,
                        options: cameraOptions,
                        isChanging: isChangingCamera
                    )

                    AudioDeviceSelectionRow(
                        selectedAudioID: $selectedAudioID,
                        options: audioOptions,
                        isChanging: isChangingAudio
                    )

                    if let deviceErrorMessage {
                        Label(deviceErrorMessage, systemImage: "exclamationmark.triangle.fill")
                            .font(.footnote)
                            .foregroundStyle(.red)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 4)
                    }
                }
            }
            .padding(24)
        }
        .navigationTitle("카메라 및 오디오")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            loadAvailableDevices()
            let savedCameraID = UserDefaults.standard.string(forKey: "selectedCameraID")
            selectedCameraID = youtube.videoUplink.currentCameraID
                .flatMap(validCameraID)
                ?? cameraManager.currentCameraID.flatMap(validCameraID)
                ?? savedCameraID.flatMap { savedID in
                    validCameraID(savedID)
                }
                ?? cameraOptions.first?.id
                ?? ""
            restoreAudioSelection()
            if let activeQuality = youtube.videoUplink.currentVideoQuality {
                selectedQualityRaw = activeQuality.rawValue
            } else if CameraQualityPreset(rawValue: selectedQualityRaw) == nil {
                selectedQualityRaw = CameraQualityPreset.defaultValue.rawValue
            }
        }
        .onChange(of: selectedQualityRaw) { previousQuality, quality in
            applyQualitySelection(quality, fallbackQuality: previousQuality)
        }
        .onChange(of: selectedCameraID) { previousCameraID, cameraID in
            applyCameraSelection(cameraID, fallbackCameraID: previousCameraID)
        }
        .onChange(of: selectedAudioID) { previousAudioID, audioID in
            applyAudioSelection(audioID, fallbackAudioID: previousAudioID)
        }
    }

    private func applyCameraSelection(_ cameraID: String, fallbackCameraID: String) {
        let actualCameraID = youtube.videoUplink.currentCameraID ?? cameraManager.currentCameraID
        guard !cameraID.isEmpty,
              cameraID != actualCameraID,
              !isChangingCamera else { return }

        isChangingCamera = true
        deviceErrorMessage = nil
        Task { @MainActor in
            defer { isChangingCamera = false }

            let previousCameraID = actualCameraID ?? fallbackCameraID
            let didSwitch: Bool
            if youtube.videoUplink.isCapturingCamera {
                do {
                    try await youtube.videoUplink.switchCamera(to: cameraID)
                    let didStoreSelection = await cameraManager.switchCamera(to: cameraID)
                    if !didStoreSelection, !previousCameraID.isEmpty {
                        try? await youtube.videoUplink.switchCamera(to: previousCameraID)
                    }
                    if didStoreSelection {
                        didSwitch = true
                    } else if youtube.videoUplink.currentCameraID == previousCameraID
                                || youtube.videoUplink.currentCameraID == nil {
                        didSwitch = false
                        deviceErrorMessage = "카메라 선택을 저장하지 못해 기존 카메라를 계속 사용합니다."
                    } else {
                        // rollback도 실패했다면 실제 송출 중인 카메라를 UI에 유지한다.
                        didSwitch = true
                        selectedCameraID = youtube.videoUplink.currentCameraID ?? cameraID
                        deviceErrorMessage = "기존 카메라로 복구하지 못했습니다. 현재 카메라를 계속 사용합니다."
                    }
                } catch {
                    didSwitch = false
                    deviceErrorMessage = (error as? LocalizedError)?.errorDescription
                        ?? "카메라를 전환하지 못해 기존 카메라를 계속 사용합니다."
                }
            } else {
                didSwitch = await cameraManager.switchCamera(to: cameraID)
                if !didSwitch {
                    deviceErrorMessage = "카메라를 전환하지 못해 기존 카메라를 계속 사용합니다."
                }
            }

            if !didSwitch {
                selectedCameraID = previousCameraID
            }
        }
    }

    private func applyQualitySelection(_ qualityRaw: String, fallbackQuality: String) {
        guard let quality = CameraQualityPreset(rawValue: qualityRaw) else {
            selectedQualityRaw = CameraQualityPreset.defaultValue.rawValue
            return
        }
        guard !isChangingQuality,
              quality != youtube.videoUplink.currentVideoQuality else { return }

        // 방송 전에는 AppStorage에 보관하고, 방송 중에는 같은 video track을
        // 유지한 채 실제 카메라 캡처 포맷을 즉시 바꾼다.
        guard youtube.videoUplink.isCapturingCamera else { return }

        isChangingQuality = true
        deviceErrorMessage = nil
        Task { @MainActor in
            defer { isChangingQuality = false }
            guard await youtube.switchVideoQuality(to: quality) else {
                deviceErrorMessage = youtube.errorMessage
                    ?? "화질을 변경하지 못해 기존 화질을 계속 사용합니다."
                youtube.dismissError()
                selectedQualityRaw = youtube.videoUplink.currentVideoQuality?.rawValue
                    ?? fallbackQuality
                return
            }
        }
    }

    private func applyAudioSelection(_ audioID: String, fallbackAudioID: String) {
        guard !audioID.isEmpty,
              !isChangingAudio,
              audioID != youtube.videoUplink.currentAudioInputID else { return }

        isChangingAudio = true
        deviceErrorMessage = nil
        defer { isChangingAudio = false }

        if youtube.videoUplink.isCapturingCamera,
           !youtube.switchAudioInput(to: audioID) {
            deviceErrorMessage = youtube.errorMessage
                ?? "오디오 기기를 전환하지 못해 기존 기기를 계속 사용합니다."
            youtube.dismissError()
            selectedAudioID = youtube.videoUplink.currentAudioInputID ?? fallbackAudioID
            return
        }

        UserDefaults.standard.set(audioID, forKey: "selectedAudioID")
    }

    private func validCameraID(_ cameraID: String) -> String? {
        cameraOptions.contains(where: { $0.id == cameraID }) ? cameraID : nil
    }

    private func restoreAudioSelection() {
        let defaults = UserDefaults.standard
        let savedAudioID = defaults.string(forKey: "selectedAudioID")
        let legacyAudioName = defaults.string(forKey: "selectedAudio")
        selectedAudioID = youtube.videoUplink.currentAudioInputID
            .flatMap(validAudioID)
            ?? savedAudioID.flatMap(validAudioID)
            ?? legacyAudioName.flatMap { legacyName in
                audioOptions.first(where: { $0.name == legacyName })?.id
            }
            ?? audioOptions.first?.id
            ?? ""
        if !selectedAudioID.isEmpty {
            defaults.set(selectedAudioID, forKey: "selectedAudioID")
        }
    }

    private func validAudioID(_ audioID: String) -> String? {
        audioOptions.contains(where: { $0.id == audioID }) ? audioID : nil
    }

    private func loadAvailableDevices() {
        // AVFoundation이 현재 인식한 영상 촬영 장치 이름을 읽어옴
        let cameras = CameraDeviceCatalog.devices

        cameraOptions = cameras.map {
            CameraOption(id: $0.uniqueID, name: $0.localizedName)
        }

        let audioSession = AVAudioSession.sharedInstance()
        do {
            // 방송 중에는 LKRTCAudioSession이 audio unit과 route를 소유하므로
            // 여기서 category를 다시 설정하지 않고 현재 입력 목록만 읽는다.
            if !youtube.videoUplink.isActive,
               !youtube.videoUplink.isCapturingCamera {
                try audioSession.setCategory(
                    .playAndRecord,
                    mode: .videoChat,
                    options: [.allowBluetoothHFP, .defaultToSpeaker]
                )
            }
            audioOptions = (audioSession.availableInputs ?? []).map {
                AudioOption(id: $0.uid, name: $0.portName)
            }
        } catch {
            audioOptions = []
            deviceErrorMessage = "오디오 기기 목록을 불러오지 못했습니다."
        }
    }
}

#Preview {
    NavigationStack {
        CameraAudioSettingsView(youtube: YouTubeIntegration())
    }
    .environment(CameraManager())
}
