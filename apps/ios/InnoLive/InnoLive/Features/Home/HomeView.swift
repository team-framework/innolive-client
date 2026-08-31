//
//  HomeView.swift
//  InnoLive
//
//  Created by chaeyn on 7/25/26.
//

import AVFoundation
import SwiftUI

struct HomeView: View {
    @State private var isBroadcasting = false
    @State private var previewTransition: BroadcastPreviewTransition = .none
    @State private var isShowingCameraPermissionAlert = false
    @State private var isSwitchingCamera = false
    @State private var isStartingServerConnection = false
    @State private var cameraSwitchErrorMessage: String?
    @State private var isHomeVisible = false
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    @Environment(CameraManager.self) private var cameraManager
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase

    private var isCameraAccessDenied: Bool {
        cameraManager.authorizationStatus == .denied
            || cameraManager.authorizationStatus == .restricted
    }

    var body: some View {
        ZStack {
            RemoteStreamView(
                uplink: youtube.videoUplink,
                previewTransition: previewTransition,
                isPreparingSession: youtube.isPreparingSession,
                isConnectingVideo: youtube.isConnectingVideo
            )
                .ignoresSafeArea()

            if isHomeVisible
                && !youtube.videoUplink.isCapturingCamera
                && previewTransition == .none {
                LocalPreviewView(
                    session: cameraManager.session,
                    // 카메라 전환 시 프리뷰 회전 기준도 함께 갱신
                    cameraID: cameraManager.currentCameraID
                )
                    .frame(
                        width: BroadcastVideoLayout.previewWidth,
                        height: BroadcastVideoLayout.previewHeight
                    )
                    .padding(.leading, 24)
                    // 이 ZStack은 이미 safe area 아래에서 시작하므로 추가 보정을 하지 않는다.
                    .frame(
                        maxWidth: .infinity,
                        maxHeight: .infinity,
                        alignment: .topLeading
                    )
                    .allowsHitTesting(false)
            }

            VStack(alignment: .trailing, spacing: 12) {
                Button(action: switchCamera) {
                    Group {
                        if isSwitchingCamera {
                            ProgressView()
                                .controlSize(.small)
                        } else {
                            Image(systemName: "arrow.triangle.2.circlepath.camera.fill")
                                .font(.body.weight(.semibold))
                        }
                    }
                    .frame(width: 44, height: 44)
                }
                .buttonStyle(.glass)
                .buttonBorderShape(.circle)
                .disabled(
                    isSwitchingCamera
                        || cameraManager.authorizationStatus != .authorized
                        || youtube.videoUplink.isConnecting
                        || CameraDeviceCatalog.devices.count < 2
                )
                .accessibilityLabel("카메라 전환")

                if isCameraAccessDenied {
                    Button {
                        isShowingCameraPermissionAlert = true
                    } label: {
                        Label("카메라 권한 필요", systemImage: "camera.fill")
                            .font(.caption.weight(.semibold))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                    }
                    .buttonStyle(.glass)
                    .buttonBorderShape(.capsule)
                }
            }
            .padding(.top, 8)
            .padding(.horizontal, 24)
            .frame(
                maxWidth: .infinity,
                maxHeight: .infinity,
                alignment: .topTrailing
            )

            BroadcastControllsView(
                isBroadcasting: $isBroadcasting,
                previewTransition: $previewTransition,
                authentication: authentication,
                youtube: youtube,
                isStartingServerConnection: isStartingServerConnection,
                onRetryConnection: retryServerConnection
            )
                .padding(.horizontal, 24)
                .padding(.bottom, 12)
                .frame(
                    maxWidth: .infinity,
                    maxHeight: .infinity,
                    alignment: .bottomTrailing
                )
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .toolbar(.hidden, for: .navigationBar) // 네비게이션 바를 숨김
        .onAppear {
            isHomeVisible = true
            if youtube.isVideoConnected {
                isBroadcasting = true
                return
            }
            guard !youtube.videoUplink.isCapturingCamera else { return }
            switch cameraManager.authorizationStatus {
            case .authorized:
                Task {
                    await startCameraAndConnect()
                }

            case .notDetermined:
                cameraManager.requestCameraAccess()

            case .denied, .restricted:
                // 이미 거부된 권한은 우측 상단 버튼으로 다시 안내함
                break

            @unknown default:
                break
            }
        }
        .onDisappear {
            isHomeVisible = false
        }
        .onChange(of: cameraManager.authorizationStatus) { _, status in
            if status == .authorized, !youtube.videoUplink.isCapturingCamera {
                Task {
                    await startCameraAndConnect()
                }
            } else if status == .denied || status == .restricted {
                isShowingCameraPermissionAlert = true
            }
        }
        .onChange(of: youtube.videoUplink.state) { _, state in
            guard state == .failed, isBroadcasting else { return }
            isBroadcasting = false
            Task {
                await youtube.recoverFromVideoUplinkFailure(
                    accessToken: authentication.currentAccessToken()
                )
                if cameraManager.authorizationStatus == .authorized {
                    await cameraManager.startDefaultCamera()
                }
            }
        }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else {
                return
            }

            // 설정 앱에서 권한을 바꾼 뒤 돌아오면 안내 UI 상태를 즉시 갱신함
            cameraManager.refreshAuthorizationStatus()
        }
        .alert("카메라 권한이 필요합니다", isPresented: $isShowingCameraPermissionAlert) {
            Button("설정 열기") {
                openCameraSettings()
            }

            Button("취소", role: .cancel) { }
        } message: {
            Text("카메라를 사용하려면 설정에서 카메라 접근을 허용해 주세요.")
        }
        .alert(
            "카메라를 전환하지 못했습니다",
            isPresented: Binding(
                get: { cameraSwitchErrorMessage != nil },
                set: { if !$0 { cameraSwitchErrorMessage = nil } }
            )
        ) {
            Button("확인", role: .cancel) { }
        } message: {
            Text(cameraSwitchErrorMessage ?? "다시 시도해 주세요.")
        }
    }

    // 앱 설정 화면을 열어 사용자가 카메라 권한을 직접 변경할 수 있게 함
    private func openCameraSettings() {
        guard let settingsURL = URL(string: UIApplication.openSettingsURLString) else {
            return
        }

        openURL(settingsURL)
    }

    private func retryServerConnection() {
        Task {
            await startCameraAndConnect()
        }
    }

    private func startCameraAndConnect() async {
        guard cameraManager.authorizationStatus == .authorized else {
            isShowingCameraPermissionAlert = true
            return
        }
        if youtube.isVideoConnected {
            isBroadcasting = true
            return
        }
        guard !isStartingServerConnection,
              !youtube.isPreparingSession,
              !youtube.isConnectingVideo,
              !youtube.videoUplink.isConnecting else {
            return
        }

        isStartingServerConnection = true
        defer { isStartingServerConnection = false }

        if !youtube.videoUplink.isCapturingCamera {
            await cameraManager.startDefaultCamera()
        }
        guard await youtube.prepareSession(accessToken: authentication.currentAccessToken()) else {
            return
        }

        previewTransition = .starting
        defer { previewTransition = .none }
        await cameraManager.stopSession()

        if await youtube.connectVideo(
            accessToken: authentication.currentAccessToken(),
            preferredCameraID: cameraManager.currentCameraID,
            preferredAudioID: UserDefaults.standard.string(forKey: "selectedAudioID"),
            preferredVideoQuality: CameraQualityPreset(
                rawValue: UserDefaults.standard.string(forKey: "selectedResolution") ?? ""
            ) ?? .defaultValue
        ) {
            if let activeCameraID = youtube.videoUplink.currentCameraID {
                _ = await cameraManager.switchCamera(to: activeCameraID)
            }
            isBroadcasting = true
        } else {
            await cameraManager.startDefaultCamera()
        }
    }

    private func switchCamera() {
        guard !isSwitchingCamera else { return }
        let previousCameraID = youtube.videoUplink.currentCameraID
            ?? cameraManager.currentCameraID
        guard let nextCamera = CameraDeviceCatalog.nextCamera(after: previousCameraID),
              nextCamera.uniqueID != previousCameraID else { return }

        isSwitchingCamera = true
        cameraSwitchErrorMessage = nil
        Task { @MainActor in
            defer { isSwitchingCamera = false }

            if youtube.videoUplink.isCapturingCamera {
                do {
                    try await youtube.videoUplink.switchCamera(to: nextCamera.uniqueID)
                    guard await cameraManager.switchCamera(to: nextCamera.uniqueID) else {
                        if let previousCameraID {
                            try? await youtube.videoUplink.switchCamera(to: previousCameraID)
                        }
                        cameraSwitchErrorMessage = "카메라 선택을 저장하지 못해 기존 카메라를 계속 사용합니다."
                        return
                    }
                } catch {
                    cameraSwitchErrorMessage = (error as? LocalizedError)?.errorDescription
                        ?? "카메라를 전환하지 못해 기존 카메라를 계속 사용합니다."
                }
                return
            }

            guard await cameraManager.switchCamera(to: nextCamera.uniqueID) else {
                cameraSwitchErrorMessage = "카메라를 전환하지 못해 기존 카메라를 계속 사용합니다."
                return
            }
        }
    }
}

#Preview {
    NavigationStack {
        HomeView(authentication: AuthSession(), youtube: YouTubeIntegration())
    }
    .environment(CameraManager())
}
