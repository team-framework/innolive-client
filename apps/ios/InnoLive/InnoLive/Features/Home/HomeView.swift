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
    @State private var cameraSwitchErrorMessage: String?
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

            if !youtube.videoUplink.isCapturingCamera && previewTransition == .none {
                GeometryReader { proxy in
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
                        // Dynamic Island가 있는 기기에서는 safe area 아래에 여백을 더 둔다.
                        .padding(.top, max(proxy.safeAreaInsets.top, 54) + 12)
                        .frame(
                            maxWidth: .infinity,
                            maxHeight: .infinity,
                            alignment: .topLeading
                        )
                }
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
                    .frame(width: 40, height: 40)
                    .background(.regularMaterial, in: Circle())
                }
                .buttonStyle(.plain)
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
                            .background(.regularMaterial, in: Capsule())
                    }
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
                youtube: youtube
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
            guard !youtube.videoUplink.isCapturingCamera else { return }
            switch cameraManager.authorizationStatus {
            case .authorized:
                Task {
                    await cameraManager.startDefaultCamera()
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
        .onChange(of: cameraManager.authorizationStatus) { _, status in
            if status == .authorized, !youtube.videoUplink.isCapturingCamera {
                // 권한 팝업에서 허용을 누른 직후 첫 번째 카메라를 시작함
                Task {
                    await cameraManager.startDefaultCamera()
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
