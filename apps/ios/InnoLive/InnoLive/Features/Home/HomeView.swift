//
//  HomeView.swift
//  InnoLive
//
//  Created by chaeyn on 7/25/26.
//

import AVFoundation
import Combine
import SwiftUI

struct HomeView: View {
    @State private var isBroadcasting = false
    @State private var previewTransition: BroadcastPreviewTransition = .none
    @State private var isShowingCameraPermissionAlert = false
    @State private var isSwitchingCamera = false
    @State private var isStartingServerConnection = false
    @State private var cameraSwitchErrorMessage: String?
    @State private var isHomeVisible = false
    @State private var previewCorner: LocalPreviewCorner = .topLeading
    @State private var previewDragOffset: CGSize = .zero
    @State private var topTrailingReserved = CGSize(width: 44, height: 44)
    @State private var bottomReservedHeight: CGFloat = 56
    @State private var pinchStartZoom: CGFloat?
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    @Environment(CameraManager.self) private var cameraManager
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase

    private var usesSimulatorVideo: Bool {
        SimulatorVideoInput.isEnabled
    }

    private var isCameraAccessDenied: Bool {
        !usesSimulatorVideo
            && (cameraManager.authorizationStatus == .denied
                || cameraManager.authorizationStatus == .restricted)
    }

    var body: some View {
        ZStack {
            zoomableRemoteStream

            if localPreviewPresentation != .hidden {
                GeometryReader { geo in
                    let layout = snapLayout(in: geo.size)
                    let restOrigin = layout.origin(for: previewCorner)
                    let draggedOrigin = layout.clampedOrigin(
                        CGPoint(
                            x: restOrigin.x + previewDragOffset.width,
                            y: restOrigin.y + previewDragOffset.height
                        )
                    )

                    originalPreview(for: localPreviewPresentation)
                    .frame(
                        width: BroadcastVideoLayout.previewWidth,
                        height: BroadcastVideoLayout.previewHeight
                    )
                    .position(
                        x: draggedOrigin.x + BroadcastVideoLayout.previewWidth / 2,
                        y: draggedOrigin.y + BroadcastVideoLayout.previewHeight / 2
                    )
                    .gesture(
                        DragGesture()
                            .onChanged { value in
                                previewDragOffset = value.translation
                            }
                            .onEnded { value in
                                let endOrigin = layout.clampedOrigin(
                                    CGPoint(
                                        x: restOrigin.x + value.translation.width,
                                        y: restOrigin.y + value.translation.height
                                    )
                                )
                                let nextCorner = layout.nearestCorner(toPreviewOrigin: endOrigin)
                                withAnimation(.spring(response: 0.32, dampingFraction: 0.82)) {
                                    previewCorner = nextCorner
                                    previewDragOffset = .zero
                                }
                            }
                    )
                }
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
                        || usesSimulatorVideo
                        || youtube.videoUplink.isConnecting
                        || CameraDeviceCatalog.devices.count < 2
                )
                .accessibilityLabel(String(localized: "카메라 전환"))

                if isCameraAccessDenied {
                    Button {
                        isShowingCameraPermissionAlert = true
                    } label: {
                        Label(String(localized: "카메라 권한 필요"), systemImage: "camera.fill")
                            .font(.caption.weight(.semibold))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                    }
                    .buttonStyle(.glass)
                    .buttonBorderShape(.capsule)
                }
            }
            .onGeometryChange(for: CGSize.self) { proxy in
                proxy.size
            } action: { _, size in
                topTrailingReserved = size
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
                .onGeometryChange(for: CGFloat.self) { proxy in
                    proxy.size.height
                } action: { _, height in
                    bottomReservedHeight = height
                }
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
            guard !youtube.videoUplink.isCapturingMedia else { return }
            if usesSimulatorVideo {
                Task {
                    await startCameraAndConnect()
                }
                return
            }
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
            if !usesSimulatorVideo,
               status == .authorized,
               !youtube.videoUplink.isCapturingMedia {
                Task {
                    await startCameraAndConnect()
                }
            } else if !usesSimulatorVideo,
                      status == .denied || status == .restricted {
                isShowingCameraPermissionAlert = true
            }
        }
        .onReceive(youtube.videoUplink.$state.removeDuplicates()) { state in
            guard state == .failed, isBroadcasting else { return }
            isBroadcasting = false
            Task {
                await youtube.recoverFromVideoUplinkFailure(
                    accessToken: authentication.currentAccessToken()
                )
                if !usesSimulatorVideo, cameraManager.authorizationStatus == .authorized {
                    cameraManager.adoptZoomFactor(youtube.videoUplink.currentZoomFactor)
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
        .alert(String(localized: "카메라 권한이 필요합니다"), isPresented: $isShowingCameraPermissionAlert) {
            Button(String(localized: "설정 열기")) {
                openCameraSettings()
            }

            Button(String(localized: "취소"), role: .cancel) { }
        } message: {
            Text(String(localized: "카메라를 사용하려면 설정에서 카메라 접근을 허용해 주세요."))
        }
        .alert(
            String(localized: "카메라를 전환하지 못했습니다"),
            isPresented: Binding(
                get: { cameraSwitchErrorMessage != nil },
                set: { if !$0 { cameraSwitchErrorMessage = nil } }
            )
        ) {
            Button(String(localized: "확인"), role: .cancel) { }
        } message: {
            Text(cameraSwitchErrorMessage ?? String(localized: "다시 시도해 주세요."))
        }
    }

    private var localPreviewPresentation: LocalPreviewPresentation {
        LocalPreviewPresentation.current(
            isHomeVisible: isHomeVisible,
            previewTransition: previewTransition,
            isCapturingMedia: youtube.videoUplink.isCapturingMedia,
            isReleasingCamera: youtube.videoUplink.isReleasingCamera
        )
    }

    @ViewBuilder
    private func originalPreview(for presentation: LocalPreviewPresentation) -> some View {
        switch presentation {
        case .cameraSession:
            LocalPreviewView(
                session: cameraManager.session,
                // 카메라 전환 시 프리뷰 회전 기준도 함께 갱신
                cameraID: cameraManager.currentCameraID
            )
        case .webrtcLocal:
            OriginalPreviewFrame {
                WebRTCLocalPreviewView(uplink: youtube.videoUplink)
            }
        case .hidden:
            EmptyView()
        }
    }

    private func snapLayout(in containerSize: CGSize) -> LocalPreviewSnapLayout {
        LocalPreviewSnapLayout(
            containerSize: containerSize,
            previewSize: CGSize(
                width: BroadcastVideoLayout.previewWidth,
                height: BroadcastVideoLayout.previewHeight
            ),
            horizontalPadding: LocalPreviewSnapLayout.defaultHorizontalPadding,
            topPadding: LocalPreviewSnapLayout.defaultTopPadding,
            bottomPadding: LocalPreviewSnapLayout.defaultBottomPadding,
            topTrailingReserved: topTrailingReserved,
            bottomReservedHeight: bottomReservedHeight,
            cornerSpacing: LocalPreviewSnapLayout.defaultCornerSpacing
        )
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
        guard usesSimulatorVideo || cameraManager.authorizationStatus == .authorized else {
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

        if !usesSimulatorVideo, !youtube.videoUplink.isCapturingCamera {
            await cameraManager.startDefaultCamera()
        }
        guard await youtube.prepareSession(accessToken: authentication.currentAccessToken()) else {
            return
        }

        previewTransition = .starting
        defer { previewTransition = .none }
        let zoomToKeep = cameraManager.currentZoomFactor
        await cameraManager.stopSession()
        youtube.videoUplink.adoptZoomFactor(zoomToKeep)

        if await youtube.connectVideo(
            accessToken: authentication.currentAccessToken(),
            preferredCameraID: usesSimulatorVideo ? nil : cameraManager.currentCameraID,
            preferredAudioID: UserDefaults.standard.string(forKey: "selectedAudioID"),
            preferredVideoQuality: CameraQualityPreset(
                rawValue: UserDefaults.standard.string(forKey: "selectedResolution") ?? ""
            ) ?? .defaultValue
        ) {
            if !usesSimulatorVideo,
               let activeCameraID = youtube.videoUplink.currentCameraID {
                _ = await cameraManager.switchCamera(to: activeCameraID)
            }
            isBroadcasting = true
        } else if !usesSimulatorVideo {
            cameraManager.adoptZoomFactor(youtube.videoUplink.currentZoomFactor)
            await cameraManager.startDefaultCamera()
        }
    }

    private var zoomableRemoteStream: some View {
        RemoteStreamView(
            uplink: youtube.videoUplink,
            previewTransition: previewTransition
        )
        .ignoresSafeArea()
        .contentShape(Rectangle())
        .simultaneousGesture(cameraZoomGesture)
        .modifier(CameraZoomAccessibility(
            value: zoomAccessibilityValue,
            onAdjust: adjustCameraZoom
        ))
    }

    private var canZoomCamera: Bool {
        !usesSimulatorVideo
            && cameraManager.authorizationStatus == .authorized
            && (youtube.videoUplink.isCapturingCamera || cameraManager.canApplyLiveZoom)
    }

    private var activeZoomFactor: CGFloat {
        youtube.videoUplink.isCapturingCamera
            ? youtube.videoUplink.currentZoomFactor
            : cameraManager.currentZoomFactor
    }

    private var activeZoomRange: ClosedRange<CGFloat> {
        youtube.videoUplink.isCapturingCamera
            ? youtube.videoUplink.zoomRange
            : cameraManager.zoomRange
    }

    private var zoomAccessibilityValue: String {
        String(format: String(localized: "%.1f배"), activeZoomFactor)
    }

    private var cameraZoomGesture: some Gesture {
        MagnifyGesture()
            .onChanged { value in
                guard canZoomCamera else { return }
                if pinchStartZoom == nil {
                    pinchStartZoom = activeZoomFactor
                }
                let start = pinchStartZoom ?? activeZoomFactor
                applyUserZoom(
                    CameraZoom.requestedPinchFactor(
                        fromPinchStart: start,
                        magnification: value.magnification
                    )
                )
            }
            .onEnded { _ in
                pinchStartZoom = nil
            }
    }

    private func applyUserZoom(_ factor: CGFloat) {
        guard canZoomCamera else { return }
        if youtube.videoUplink.isCapturingCamera {
            youtube.videoUplink.applyZoom(factor)
        } else {
            cameraManager.applyZoom(factor)
        }
    }

    private func adjustCameraZoom(_ direction: AccessibilityAdjustmentDirection) {
        guard canZoomCamera else { return }
        let range = activeZoomRange
        switch direction {
        case .increment:
            applyUserZoom(
                CameraZoom.steppedUp(
                    from: activeZoomFactor,
                    min: range.lowerBound,
                    max: range.upperBound
                )
            )
        case .decrement:
            applyUserZoom(
                CameraZoom.steppedDown(
                    from: activeZoomFactor,
                    min: range.lowerBound,
                    max: range.upperBound
                )
            )
        @unknown default:
            break
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
                        cameraSwitchErrorMessage = String(localized: "카메라 선택을 저장하지 못해 기존 카메라를 계속 사용합니다.")
                        return
                    }
                } catch {
                    cameraSwitchErrorMessage = (error as? LocalizedError)?.errorDescription
                        ?? String(localized: "카메라를 전환하지 못해 기존 카메라를 계속 사용합니다.")
                }
                return
            }

            guard await cameraManager.switchCamera(to: nextCamera.uniqueID) else {
                cameraSwitchErrorMessage = String(localized: "카메라를 전환하지 못해 기존 카메라를 계속 사용합니다.")
                return
            }
        }
    }
}

private struct CameraZoomAccessibility: ViewModifier {
    let value: String
    let onAdjust: (AccessibilityAdjustmentDirection) -> Void

    func body(content: Content) -> some View {
        content
            .accessibilityLabel(String(localized: "카메라 줌"))
            .accessibilityHint(String(localized: "두 손가락으로 확대하거나 축소합니다"))
            .accessibilityValue(value)
            .accessibilityAdjustableAction(onAdjust)
    }
}

#Preview {
    NavigationStack {
        HomeView(authentication: AuthSession(), youtube: YouTubeIntegration())
    }
    .environment(CameraManager())
}
