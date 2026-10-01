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
    @State private var isShowingVideoControls = false
    @State private var isShowingMicrophonePermissionAlert = false
    @State private var pendingPreparationProvider: BroadcastSettingsProvider?
    @State private var cameraSwitchErrorMessage: String?
    @State private var isHomeVisible = false
    @State private var previewCorner: LocalPreviewCorner = .topLeading
    @State private var previewCornerBeforeVideoControls: LocalPreviewCorner?
    @State private var previewDragOffset: CGSize = .zero
    @State private var topTrailingReserved = CGSize(width: 44, height: 44)
    @State private var bottomReservedHeight: CGFloat = 56
    @GestureState private var pinchStartZoom: CGFloat?
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    @ObservedObject private var broadcastOrientation = BroadcastOrientationController.shared
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
                    let previewSize = BroadcastVideoLayout.previewSize(
                        containerSize: geo.size,
                        lockedOrientation: broadcastOrientation.lockedOrientation
                    )
                    let layout = snapLayout(in: geo.size, previewSize: previewSize)
                    let restOrigin = layout.origin(for: previewCorner)
                    let draggedOrigin = layout.clampedOrigin(
                        CGPoint(
                            x: restOrigin.x + previewDragOffset.width,
                            y: restOrigin.y + previewDragOffset.height
                        )
                    )

                    originalPreview(for: localPreviewPresentation)
                    .frame(width: previewSize.width, height: previewSize.height)
                    .position(
                        x: draggedOrigin.x + previewSize.width / 2,
                        y: draggedOrigin.y + previewSize.height / 2
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
                    .onChange(of: previewSize) { _, _ in
                        previewDragOffset = .zero
                    }
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
                .innoLiveGlassButtonStyle()
                .buttonBorderShape(.circle)
                .disabled(!canSwitchCameraSource)
                .accessibilityLabel(String(localized: "카메라 전환"))

                Button {
                    previewCornerBeforeVideoControls = previewCorner
                    previewDragOffset = .zero
                    withAnimation(.spring(response: 0.32, dampingFraction: 0.82)) {
                        previewCorner = .topLeading
                    }
                    isShowingVideoControls = true
                } label: {
                    Image(systemName: "camera.filters")
                        .font(.title3.weight(.semibold))
                        .frame(width: 44, height: 44)
                }
                .innoLiveGlassButtonStyle()
                .buttonBorderShape(.circle)
                .accessibilityLabel(String(localized: "영상 조절"))

                if isCameraAccessDenied {
                    Button {
                        isShowingCameraPermissionAlert = true
                    } label: {
                        Label(String(localized: "카메라 권한 필요"), systemImage: "camera.fill")
                            .font(.caption.weight(.semibold))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                    }
                    .innoLiveGlassButtonStyle()
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
                onPrepareBroadcast: beginBroadcastPreparation,
                onCancelPreparation: cancelBroadcastPreparation
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
            beginLocalPreviewIfNeeded()
        }
        .onDisappear {
            isHomeVisible = false
        }
        .sheet(isPresented: $isShowingVideoControls, onDismiss: restorePreviewCornerAfterVideoControls) {
            BroadcastVideoControlsView(uplink: youtube.videoUplink)
                .presentationDetents([.height(630), .large])
                .presentationDragIndicator(.visible)
        }
        .onChange(of: cameraManager.authorizationStatus) { _, status in
            guard !usesSimulatorVideo else { return }
            if status == .authorized {
                Task {
                    if let provider = pendingPreparationProvider {
                        pendingPreparationProvider = nil
                        await beginBroadcastPreparation(provider)
                    } else if !youtube.videoUplink.isCapturingMedia {
                        await startLocalPreview()
                    }
                }
            } else if status == .denied || status == .restricted {
                pendingPreparationProvider = nil
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
            switch phase {
            case .active:
                // 설정 앱에서 권한을 바꾼 뒤 돌아오면 안내 UI 상태를 즉시 갱신함
                cameraManager.refreshAuthorizationStatus()
                Task {
                    await youtube.handleAppBecameActive(
                        accessToken: authentication.currentAccessToken()
                    )
                }
            case .background:
                Task {
                    await youtube.handleAppMovedToBackground(
                        accessToken: authentication.currentAccessToken()
                    )
                }
            default:
                break
            }
        }
        .alert(String(localized: "카메라 권한이 필요합니다"), isPresented: $isShowingCameraPermissionAlert) {
            Button(String(localized: "설정 열기")) {
                openCameraSettings()
            }

            Button(String(localized: "취소"), role: .cancel) { }
        } message: {
            Text(String(localized: "카메라를 사용하려면 설정에서 카메라 접근을 허용해 주세요."))
        }
        .alert(String(localized: "마이크 권한이 필요합니다"), isPresented: $isShowingMicrophonePermissionAlert) {
            Button(String(localized: "설정 열기")) {
                openCameraSettings()
            }
            Button(String(localized: "취소"), role: .cancel) { }
        } message: {
            Text(String(localized: "방송을 준비하려면 설정에서 마이크 접근을 허용해 주세요."))
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
            WebRTCColorPreviewFrame(
                uplink: youtube.videoUplink
            )
        case .hidden:
            EmptyView()
        }
    }

    private func snapLayout(in containerSize: CGSize, previewSize: CGSize) -> LocalPreviewSnapLayout {
        LocalPreviewSnapLayout(
            containerSize: containerSize,
            previewSize: previewSize,
            horizontalPadding: LocalPreviewSnapLayout.defaultHorizontalPadding,
            topPadding: LocalPreviewSnapLayout.defaultTopPadding,
            bottomPadding: LocalPreviewSnapLayout.defaultBottomPadding,
            topTrailingReserved: topTrailingReserved,
            bottomReservedHeight: bottomReservedHeight,
            cornerSpacing: LocalPreviewSnapLayout.defaultCornerSpacing
        )
    }

    private func restorePreviewCornerAfterVideoControls() {
        guard let previousCorner = previewCornerBeforeVideoControls else { return }
        previewCornerBeforeVideoControls = nil
        previewDragOffset = .zero
        withAnimation(.spring(response: 0.32, dampingFraction: 0.82)) {
            previewCorner = previousCorner
        }
    }

    // 앱 설정 화면을 열어 사용자가 카메라 권한을 직접 변경할 수 있게 함
    private func openCameraSettings() {
        guard let settingsURL = URL(string: UIApplication.openSettingsURLString) else {
            return
        }

        openURL(settingsURL)
    }

    private func beginLocalPreviewIfNeeded() {
        guard !youtube.videoUplink.isCapturingMedia else {
            isBroadcasting = youtube.isVideoConnected
            return
        }
        if usesSimulatorVideo || cameraManager.authorizationStatus == .authorized {
            Task { await startLocalPreview() }
            return
        }
        switch cameraManager.authorizationStatus {
        case .notDetermined:
            cameraManager.requestCameraAccess()
        case .denied, .restricted:
            isShowingCameraPermissionAlert = true
        default:
            break
        }
    }

    private func startLocalPreview() async {
        guard !youtube.videoUplink.isCapturingMedia else { return }
        guard usesSimulatorVideo || cameraManager.authorizationStatus == .authorized else { return }
        if !usesSimulatorVideo {
            await cameraManager.startDefaultCamera()
        }
    }

    private func beginBroadcastPreparation(_ provider: BroadcastSettingsProvider) async {
        guard authentication.hasAcceptedMediaTransmission else { return }
        if !usesSimulatorVideo {
            switch cameraManager.authorizationStatus {
            case .authorized:
                break
            case .notDetermined:
                pendingPreparationProvider = provider
                cameraManager.requestCameraAccess()
                return
            default:
                pendingPreparationProvider = nil
                isShowingCameraPermissionAlert = true
                return
            }
            guard await requestMicrophoneAccess() else {
                isShowingMicrophonePermissionAlert = true
                return
            }
        }
        await runBroadcastPreparation(provider)
    }

    private func runBroadcastPreparation(_ provider: BroadcastSettingsProvider) async {
        let zoomToKeep = cameraManager.currentZoomFactor
        let quality = CameraQualityPreset(
            rawValue: UserDefaults.standard.string(forKey: "selectedResolution") ?? ""
        ) ?? .defaultValue
        let connected = await youtube.startBroadcastPreparation(
            accessToken: authentication.currentAccessToken(),
            provider: provider,
            permissions: BroadcastPreparationPermissions(
                hasMediaTransmissionConsent: authentication.hasAcceptedMediaTransmission,
                cameraAuthorized: usesSimulatorVideo || cameraManager.authorizationStatus == .authorized,
                microphoneAuthorized: true
            ),
            preferredCameraID: usesSimulatorVideo ? nil : cameraManager.currentCameraID,
            preferredAudioID: UserDefaults.standard.string(forKey: "selectedAudioID"),
            preferredVideoQuality: quality,
            handoffCamera: {
                previewTransition = .starting
                await cameraManager.stopSession()
                youtube.videoUplink.adoptZoomFactor(zoomToKeep)
            },
            restoreLocalPreview: {
                previewTransition = .none
                isBroadcasting = false
                await restoreLocalCameraPreview()
            }
        )
        previewTransition = .none
        if connected, youtube.isVideoConnected {
            if !usesSimulatorVideo, let activeCameraID = youtube.videoUplink.currentCameraID {
                _ = await cameraManager.switchCamera(to: activeCameraID)
            }
            isBroadcasting = true
        } else {
            isBroadcasting = false
            if !youtube.videoUplink.isCapturingMedia {
                await restoreLocalCameraPreview()
            }
        }
    }

    private func cancelBroadcastPreparation() async {
        previewTransition = .stopping
        await youtube.cancelBroadcastPreparation(accessToken: authentication.currentAccessToken())
        previewTransition = .none
        isBroadcasting = youtube.isVideoConnected
        if !isBroadcasting {
            await restoreLocalCameraPreview()
        }
    }

    private func restoreLocalCameraPreview() async {
        guard !usesSimulatorVideo, cameraManager.authorizationStatus == .authorized else { return }
        guard !youtube.videoUplink.isCapturingMedia else { return }
        cameraManager.adoptZoomFactor(youtube.videoUplink.currentZoomFactor)
        await cameraManager.startDefaultCamera()
    }

    private func requestMicrophoneAccess() async -> Bool {
        switch AVAudioApplication.shared.recordPermission {
        case .granted:
            return true
        case .denied:
            return false
        case .undetermined:
            return await withCheckedContinuation { continuation in
                AVAudioApplication.requestRecordPermission { granted in
                    continuation.resume(returning: granted)
                }
            }
        @unknown default:
            return false
        }
    }

    private var zoomableRemoteStream: some View {
        RemoteStreamView(
            uplink: youtube.videoUplink,
            previewTransition: previewTransition,
            isPreparingSession: youtube.isPreparingSession,
            isConnectingVideo: youtube.isConnectingVideo,
            preparationStatus: youtube.preparationStatus,
            recoveryStatus: youtube.videoRecoveryStatus
        )
        .ignoresSafeArea()
        .contentShape(Rectangle())
        .simultaneousGesture(cameraZoomGesture.exclusively(before: cameraSwipeGesture))
        .modifier(CameraZoomAccessibility(
            value: zoomAccessibilityValue,
            onAdjust: adjustCameraZoom
        ))
    }

    private var activeCameraID: String? {
        youtube.videoUplink.currentCameraID ?? cameraManager.currentCameraID
    }

    private var canSwitchCameraSource: Bool {
        cameraManager.authorizationStatus == .authorized
            && !usesSimulatorVideo
            && youtube.preparationStatus?.isRunning != true
            && youtube.preparationStatus?.phase != .cancelling
            && !youtube.isPreparingSession
            && !youtube.isConnectingVideo
            && !youtube.videoUplink.isConnecting
            && !isSwitchingCamera
            && !youtube.videoUplink.isSwitchingCamera
            && !youtube.videoUplink.isReleasingCamera
            && CameraDeviceCatalog.nextCamera(after: activeCameraID) != nil
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
            .updating($pinchStartZoom) { _, state, _ in
                if state == nil {
                    state = activeZoomFactor
                }
            }
            .onChanged { value in
                guard canZoomCamera else { return }
                applyUserZoom(
                    CameraZoom.requestedPinchFactor(
                        fromPinchStart: pinchStartZoom ?? activeZoomFactor,
                        magnification: value.magnification
                    )
                )
            }
    }

    private var cameraSwipeGesture: some Gesture {
        DragGesture(minimumDistance: 20)
            .onEnded { value in
                guard CameraInputSwipe.shouldSwitch(translation: value.translation) else { return }
                switchCamera()
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
        guard canSwitchCameraSource else { return }
        let previousCameraID = activeCameraID
        guard let nextCamera = CameraDeviceCatalog.nextCamera(after: previousCameraID),
              nextCamera.uniqueID != previousCameraID else { return }

        isSwitchingCamera = true
        cameraSwitchErrorMessage = nil
        Task { @MainActor in
            defer { isSwitchingCamera = false }
            guard !youtube.videoUplink.isSwitchingCamera,
                  !youtube.videoUplink.isReleasingCamera else {
                return
            }

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
