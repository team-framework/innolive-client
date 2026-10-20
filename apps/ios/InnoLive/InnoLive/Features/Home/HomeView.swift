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
    @State private var studioPage: StudioPage = .basic
    @State private var isShowingBroadcastPresentation = false
    @AppStorage("studioPagingHintSeen") private var hasSeenPagingHint = false
    @State private var isBroadcasting = false
    @State private var previewTransition: BroadcastPreviewTransition = .none
    @State private var isShowingCameraPermissionAlert = false
    @State private var isSwitchingCamera = false
    @State private var isShowingVideoControls = false
    @State private var isShowingRecordings = false
    @State private var isSelectingFace = false
    @State private var recordingPlayback: BroadcastRecordingPlayback?
    @State private var recordingExport: BroadcastRecordingPlayback?
    @State private var isShowingMicrophonePermissionAlert = false
    @State private var isRequestingPreparation = false
    @State private var pendingPreparationProvider: BroadcastSettingsProvider?
    @State private var cameraSwitchErrorMessage: String?
    @State private var isHomeVisible = false
    @State private var previewCorner: LocalPreviewCorner = .topLeading
    @State private var previewCornerBeforeVideoControls: LocalPreviewCorner?
    @State private var previewDragOffset: CGSize = .zero
    @State private var topTrailingReserved = CGSize(width: 44, height: 44)
    @State private var chatReservedHeight: CGFloat = 0
    @State private var bottomReservedHeight: CGFloat = 56
    @State private var liveHeaderHeight: CGFloat = 44
    @State private var studioNavigationHeight: CGFloat = 56
    @State private var isShowingAccount = false
    @GestureState private var pinchStartZoom: CGFloat?
    @StateObject private var tutorial = BroadcastTutorialCoordinator()
    @StateObject private var guestRegistrar = GuestFaceRegistrar()
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    @ObservedObject private var broadcastOrientation = BroadcastOrientationController.shared
    @Environment(CameraManager.self) private var cameraManager
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase

    private let startsLifecycle: Bool

    init(authentication: AuthSession, youtube: YouTubeIntegration,
         initialPage: StudioPage = .basic, startsLifecycle: Bool = true) {
        self.authentication = authentication
        self.youtube = youtube
        self.startsLifecycle = startsLifecycle
        _studioPage = State(initialValue: initialPage)
        #if DEBUG
        if startsLifecycle {
            let arguments = ProcessInfo.processInfo.arguments
            if arguments.contains("--studio-page-preview") {
                _studioPage = State(initialValue: .preview)
            } else if arguments.contains("--studio-page-details") {
                _studioPage = State(initialValue: .details)
            }
        }
        #endif
        _isBroadcasting = State(initialValue: youtube.isVideoConnected)
    }

    private var isCompactLandscape: Bool { verticalSizeClass == .compact }

    private var usesSimulatorVideo: Bool {
        SimulatorVideoInput.isEnabled
    }

    private var isCameraAccessDenied: Bool {
        !usesSimulatorVideo
            && (cameraManager.authorizationStatus == .denied
                || cameraManager.authorizationStatus == .restricted)
    }

    private var studioContent: some View {
        ZStack {
            // Face selection swaps the views: the original takes the main area and the broadcast the corner.
            if isSelectingFace {
                FaceSelectionLayer(
                    videoUplink: youtube.videoUplink,
                    usesUplinkCamera: localPreviewPresentation == .webrtcLocal,
                    bottomInset: bottomReservedHeight + 24
                ) { frame, faceIndex in
                    // Register in the background and go straight back to the broadcast view.
                    guestRegistrar.register(
                        frame: frame,
                        faceIndex: faceIndex,
                        name: GuestFaceName.make(),
                        mode: .selected,
                        authentication: authentication
                    )
                    isSelectingFace = false
                }
            } else {
                zoomableRemoteStream
                    .blur(radius: studioPage == .details ? 18 : 0)
                    .overlay {
                        if studioPage == .details {
                            Color.black.opacity(reduceTransparency ? 0.8 : 0.2)
                                .ignoresSafeArea().allowsHitTesting(false)
                        }
                    }
            }

            studioPager

            if localPreviewPresentation != .hidden && (isSelectingFace || (studioPage == .basic && usesProcessedStage)) {
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

                    Group {
                        if isSelectingFace {
                            OriginalPreviewFrame {
                                CompactRemoteStreamView(uplink: youtube.videoUplink)
                            }
                        } else {
                            originalPreview(for: localPreviewPresentation)
                        }
                    }
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

            BroadcastControllsView(
                isBroadcasting: $isBroadcasting,
                previewTransition: $previewTransition,
                authentication: authentication,
                youtube: youtube,
                onPrepareBroadcast: beginBroadcastPreparation,
                onCancelPreparation: cancelBroadcastPreparation,
                tutorial: startsLifecycle ? tutorial : nil,
                displayMode: studioPage == .basic ? .basic : .minimal,
                // 방송 준비를 마치기 전에는 비식별화 제외 대상을 추가하지 못하게 버튼을 두지 않는다.
                faceRegistrationControl: isBroadcasting ? AnyView(QuickFaceRegistrationButton(
                    videoUplink: youtube.videoUplink,
                    isSelecting: $isSelectingFace,
                    isEnabled: !usesSimulatorVideo && !isSwitchingCamera,
                    isEmbedded: true
                )) : nil,
                onPresentationChange: { isShowingBroadcastPresentation = $0 },
                onBottomHeightChange: { bottomReservedHeight = $0 },
                onTopHeightChange: { liveHeaderHeight = $0 },
                isConnectionProgressPresented: usesProcessedStage && studioPage != .details
            )
                .opacity(isLifecycleOverlayPresented ? 0 : 1)
                .allowsHitTesting(!isLifecycleOverlayPresented)
                .frame(maxWidth: isCompactLandscape ? 300 : .infinity)
                .padding(.horizontal, 24)
                .padding(.top, studioHeaderTopInset)
                .padding(.bottom, 12)
                .frame(
                    maxWidth: .infinity,
                    maxHeight: .infinity,
                    alignment: .bottomTrailing
                )

            studioNavigation
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .dynamicTypeSize(.medium)
        .accessibilityHidden(isLifecycleOverlayPresented)
    }

    var body: some View {
        ZStack {
            studioContent
            if let status = youtube.preparationStatus {
                BroadcastPreparationOverlay(status: status) {
                    // 비식별화만 준비했다면 대상이 없으므로 기본 플랫폼 값은 쓰이지 않는다.
                    await beginBroadcastPreparation(youtube.lastPreparationProvider ?? .youtube)
                } onCancel: {
                    await cancelBroadcastPreparation()
                }
            } else if isRequestingPreparation {
                ZStack {
                    Color.black.opacity(0.82).ignoresSafeArea()
                    ProgressView(String(localized: "방송을 준비하는 중"))
                        .controlSize(.large)
                        .tint(.white)
                        .foregroundStyle(.white)
                }
                .accessibilityAddTraits(.isModal)
                .accessibilityIdentifier("broadcast-preparation-loading")
            }
            BroadcastStartOverlay(lifecycle: youtube.lifecycle,
                                  orientation: broadcastOrientation.lockedOrientation,
                                  onCancel: youtube.cancelBroadcastCountdown)
            BroadcastEndPresentation(lifecycle: youtube.lifecycle, isRecordingEnabled: true,
                onPlay: { recordingPlayback = BroadcastRecordingPlayback(url: $0) },
                onSave: { recordingExport = BroadcastRecordingPlayback(url: $0) },
                onRetrySaving: youtube.recordingCoordinator.canRetry ? {
                    Task { await youtube.recordingCoordinator.retrySaving() }
                } : nil)
        }
        .sheet(isPresented: $isShowingAccount) {
            NavigationStack {
                AccountSettingsView(authentication: authentication, youtube: youtube)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button(String(localized: "닫기")) { isShowingAccount = false }
                                .accessibilityIdentifier("studio-account-close")
                        }
                    }
            }
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $isShowingRecordings) {
            NavigationStack { BroadcastRecordingLibraryView(store: youtube.recordingStore) }
        }
        .sheet(item: $recordingPlayback) { item in
            NavigationStack { BroadcastRecordingPlayerView(url: item.url) }
        }
        .sheet(item: $recordingExport) { item in
            BroadcastRecordingExportSheet(sourceURL: item.url)
        }
        .onChange(of: youtube.isYouTubeBroadcastActive) { _, active in
            if active {
                isShowingRecordings = false
                recordingPlayback = nil
            }
        }
        .broadcastTutorialHost(startsLifecycle ? tutorial : nil, host: .home)
        .toolbar(.hidden, for: .navigationBar) // 네비게이션 바를 숨김
        .task(id: canStartTutorial) {
            guard canStartTutorial else { return }
            // 카메라 미리보기가 먼저 보이도록 잠시 기다린 뒤 안내를 띄운다.
            try? await Task.sleep(for: .milliseconds(600))
            guard !Task.isCancelled, canStartTutorial else { return }
            tutorial.startIfNeeded()
        }
        .onChange(of: tutorial.stage) { _, stage in
            guard startsLifecycle else { return }
            if stage != nil { studioPage = .basic }
        }
        .onChange(of: tutorial.isShowingLiveStatusTip) { _, isShowing in
            guard startsLifecycle else { return }
            if isShowing { studioPage = .basic }
        }
        .onAppear {
            isHomeVisible = true
            if startsLifecycle { beginLocalPreviewIfNeeded() }
        }
        .onDisappear {
            isHomeVisible = false
            if startsLifecycle { youtube.cancelBroadcastCountdown() }
        }
        .sheet(isPresented: $isShowingVideoControls, onDismiss: restorePreviewCornerAfterVideoControls) {
            BroadcastVideoControlsView(uplink: youtube.videoUplink, youtube: youtube, accessToken: authentication.currentAccessToken)
        }
        .onChange(of: localPreviewPresentation) { _, presentation in
            if presentation == .hidden {
                isSelectingFace = false
            }
        }
        .onChange(of: isBroadcasting) { _, isBroadcasting in
            if !isBroadcasting {
                isSelectingFace = false
            }
        }
        .onChange(of: cameraManager.authorizationStatus) { _, status in
            guard startsLifecycle, !usesSimulatorVideo else { return }
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
            guard startsLifecycle, state == .failed, isBroadcasting else { return }
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
            guard startsLifecycle else { return }
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

    private var isPagingLocked: Bool {
        isSelectingFace || isShowingBroadcastPresentation || youtube.preparationStatus != nil
            || youtube.lifecycle.isPresentingStart || youtube.lifecycle.isPresentingEnd
            || isShowingVideoControls || isShowingRecordings || isShowingAccount
            || recordingPlayback != nil || recordingExport != nil
            || isShowingCameraPermissionAlert || isShowingMicrophonePermissionAlert
            || tutorial.stage != nil || tutorial.isShowingLiveStatusTip
    }

    private var usesProcessedStage: Bool {
        youtube.session != nil || youtube.videoUplink.isCapturingMedia
            || youtube.videoUplink.isReleasingCamera || previewTransition != .none
            || youtube.isPreparingSession || youtube.isConnectingVideo
            || youtube.preparationStatus != nil || youtube.videoRecoveryStatus != nil
    }

    private var studioPageSelection: Binding<StudioPage> {
        Binding(get: { studioPage }, set: { page in
            let destination = StudioPage.destination(from: studioPage, requested: page, isLocked: isPagingLocked)
            guard destination != studioPage else { return }
            studioPage = destination
            hasSeenPagingHint = true
        })
    }

    private var studioHeaderTopInset: CGFloat { studioNavigationHeight + 12 }

    private var studioChromeTopInset: CGFloat {
        if youtube.hasStartedYouTubeBroadcast { return studioHeaderTopInset + liveHeaderHeight + 12 }
        return studioHeaderTopInset + (showsPagingHint ? 40 : 6)
    }

    private var studioPager: some View {
        TabView(selection: studioPageSelection) {
            ForEach(StudioPage.allCases) { page in
                ZStack {
                    Color.clear
                        .contentShape(Rectangle())
                        .simultaneousGesture(cameraZoomGesture.exclusively(before: cameraSwipeGesture))
                        .modifier(CameraZoomAccessibility(value: zoomAccessibilityValue, onAdjust: adjustCameraZoom))
                    if page == .basic {
                        basicStudioChrome
                    } else if page == .details {
                        StudioDetailsView(authentication: authentication, youtube: youtube, tutorial: startsLifecycle ? tutorial : nil)
                            .padding(.top, studioChromeTopInset)
                            .padding(.bottom, isCompactLandscape ? (bottomReservedHeight > 0 ? bottomReservedHeight + 12 : 8) : bottomReservedHeight + 24)
                            .padding(.horizontal, 16)
                            .frame(maxWidth: isCompactLandscape ? .infinity : 560)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .trailing)
                    }
                }
                .background(StudioPagerScrollLock(isLocked: isPagingLocked))
                .tag(page)
                .accessibilityHidden(studioPage != page || isSelectingFace)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .opacity(isSelectingFace || isLifecycleOverlayPresented ? 0 : 1)
        .allowsHitTesting(!isSelectingFace)
    }

    private var showsPagingHint: Bool {
        !hasSeenPagingHint && studioPage == .basic && !isPagingLocked
            && !youtube.hasStartedYouTubeBroadcast
    }

    private var basicTopInset: CGFloat {
        studioChromeTopInset
    }

    private var studioNavigation: some View {
        VStack(spacing: 8) {
            if isSelectingFace {
                Button(String(localized: "얼굴 선택 취소")) { isSelectingFace = false }
                    .buttonStyle(.borderedProminent)
                    .tint(.black.opacity(0.8))
            } else {
                InnoLiveGlassContainer {
                    ZStack(alignment: .top) {
                        HStack(spacing: 0) {
                            ForEach(StudioPage.allCases) { page in
                                Button {
                                    guard !isPagingLocked else { return }
                                    withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.25)) {
                                        studioPageSelection.wrappedValue = page
                                    }
                                } label: {
                                    Text(page == .details ? String(localized: "설정") : page.title)
                                        .font(.caption.weight(.semibold))
                                        .frame(minWidth: 52, minHeight: 44)
                                        .overlay(alignment: .bottom) {
                                            Capsule().fill(studioPage == page ? Color.white : Color.clear)
                                                .frame(width: 18, height: 3)
                                                .padding(.bottom, 4)
                                        }
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .foregroundStyle(studioPage == page ? .white : .white.opacity(0.65))
                                .accessibilityAddTraits(studioPage == page ? .isSelected : [])
                                .accessibilityLabel(page.title)
                                .accessibilityIdentifier("studio-page-\(page.rawValue)")
                            }
                        }
                        .padding(.horizontal, 8)

                        .innoLiveGlassSurface(in: Capsule(), overVideo: true)
                        .disabled(isPagingLocked)
                        .accessibilityElement(children: .contain)
                        .accessibilityLabel(String(localized: "방송 화면 페이지"))
                        .accessibilityValue(studioPage.title)
                        .accessibilityAdjustableAction { direction in
                            switch direction {
                            case .increment: studioPageSelection.wrappedValue = studioPage.next
                            case .decrement: studioPageSelection.wrappedValue = studioPage.previous
                            @unknown default: break
                            }
                        }
                        HStack {
                            Button(action: switchCamera) {
                                Group {
                                    if isSwitchingCamera {
                                        ProgressView().controlSize(.small)
                                    } else {
                                        Image(systemName: "arrow.triangle.2.circlepath.camera.fill")
                                            .font(.system(size: 17))
                                    }
                                }
                                .foregroundStyle(.white)
                                .frame(width: 44, height: 44)
                                .innoLiveGlassSurface(in: Circle(), interactive: true, overVideo: true)
                                .contentShape(Circle())
                            }
                            .buttonStyle(.plain)
                            .disabled(!canSwitchCameraSource || isPagingLocked)
                            .accessibilityLabel(String(localized: "카메라 전환"))
                            .accessibilityIdentifier("studio-switch-camera")
                            Spacer(minLength: 0)
                            if !youtube.hasStartedYouTubeBroadcast {
                                Button { isShowingAccount = true } label: {
                                    Image(systemName: "person.crop.circle")
                                        .font(.system(size: 17))
                                        .foregroundStyle(.white)
                                        .frame(width: 44, height: 44)
                                        .innoLiveGlassSurface(in: Circle(), interactive: true, overVideo: true)
                                        .contentShape(Circle())
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(String(localized: "계정 설정"))
                                .accessibilityIdentifier("studio-account")
                                .disabled(isPagingLocked)
                            }
                        }
                    }
                }
                .frame(maxWidth: .infinity)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { _, height in
                    studioNavigationHeight = height
                }
                if showsPagingHint {
                    Text(String(localized: "좌우로 밀어 화면과 방송 설정을 확인하세요"))
                        .font(.caption2)
                        .foregroundStyle(.white)
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .innoLiveGlassSurface(in: Capsule(), overVideo: true)
                        .allowsHitTesting(false)
                }
            }
            Spacer()
        }
        .padding(.horizontal, 24)
    }

    private var basicStudioChrome: some View {
        ZStack {
            VStack(alignment: .trailing, spacing: isCompactLandscape ? 8 : 12) {

                Button {
                    previewCornerBeforeVideoControls = previewCorner
                    previewDragOffset = .zero
                    withAnimation(.spring(response: 0.32, dampingFraction: 0.82)) {
                        previewCorner = .topLeading
                    }
                    isShowingVideoControls = true
                } label: {
                    Image(systemName: "camera.filters")
                        .font(.system(size: 17, weight: .semibold))
                        .frame(width: 28, height: 28)
                }
                .innoLiveGlassButtonStyle(overVideo: true)
                .frame(minWidth: 44, minHeight: 44)
                .foregroundStyle(.white)
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
                    .innoLiveGlassButtonStyle(overVideo: true)
                    .foregroundStyle(.white)
                    .buttonBorderShape(.capsule)
                }
            }
            .onGeometryChange(for: CGSize.self) { proxy in
                proxy.size
            } action: { _, size in
                topTrailingReserved = size
            }
            .padding(.top, basicTopInset)
            .padding(.trailing, isCompactLandscape ? 312 : 0)
            .padding(.horizontal, 24)
            .frame(
                maxWidth: .infinity,
                maxHeight: .infinity,
                alignment: .topTrailing
            )

            if !isSelectingFace {
                if youtube.hasStartedYouTubeBroadcast {
                    YouTubeViewerChatOverlay(availability: YouTubeViewerChatPolicy.availability(
                        targets: youtube.responseState.details.targets,
                        hasSession: youtube.session != nil
                    ))
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { _, height in
                        chatReservedHeight = height
                    }
                    .padding(.horizontal, 24)
                    .padding(.bottom, isCompactLandscape ? 24 : bottomReservedHeight + 24)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                }

                GuestRegistrationToast(registrar: guestRegistrar) {
                    guard isBroadcasting else { return }
                    isSelectingFace = true
                }
                .padding(.horizontal, 24)
                .padding(.bottom, bottomReservedHeight + chatReservedHeight + 36)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                .animation(.easeOut(duration: 0.2), value: guestRegistrar.status)
            }


        }
    }

    /// 카메라 권한 요청이나 권한 안내가 떠 있는 동안에는 안내를 겹쳐 띄우지 않는다.
    private var canStartTutorial: Bool {
        startsLifecycle && isHomeVisible
            && (usesSimulatorVideo || cameraManager.authorizationStatus != .notDetermined)
            && !isShowingCameraPermissionAlert
            && !isShowingMicrophonePermissionAlert
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
            topPadding: max(LocalPreviewSnapLayout.defaultTopPadding, basicTopInset + 4),
            bottomPadding: LocalPreviewSnapLayout.defaultBottomPadding,
            topTrailingReserved: topTrailingReserved,
            bottomReservedHeight: isCompactLandscape
                ? max(bottomReservedHeight, isSelectingFace ? 0 : chatReservedHeight + 12)
                : bottomReservedHeight + (isSelectingFace ? 0 : chatReservedHeight + 12),
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
        guard authentication.hasAcceptedMediaTransmission, !isRequestingPreparation else { return }
        isRequestingPreparation = true
        defer { isRequestingPreparation = false }
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
            rawValue: UserDefaults.standard.string(forKey: CameraQualityPreset.storageKey) ?? ""
        ) ?? .defaultValue
        _ = await youtube.startBroadcastPreparation(
            accessToken: authentication.currentAccessToken(),
            provider: provider,
            providers: youtube.preparationStatus?.isFailed == true ? youtube.lastPreparationProviders
                : BroadcastSettingsProvider.allCases.filter(youtube.selectedBroadcastProviders.contains),
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
        if youtube.isVideoConnected {
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

    private var isLifecycleOverlayPresented: Bool {
        isRequestingPreparation || youtube.preparationStatus != nil || youtube.lifecycle.isPresentingStart || youtube.lifecycle.isPresentingEnd
    }

    private var zoomableRemoteStream: some View {
        ZStack {
            Color.black
            if usesProcessedStage {
                RemoteStreamView(
                    uplink: youtube.videoUplink,
                    previewTransition: previewTransition,
                    isPreparingSession: youtube.isPreparingSession,
                    isConnectingVideo: youtube.isConnectingVideo,
                    preparationStatus: youtube.preparationStatus,
                    recoveryStatus: youtube.videoRecoveryStatus,
                    showsStatusOverlay: studioPage != .details && !isLifecycleOverlayPresented,
                    showsEmptyState: studioPage == .preview
                )
            } else {
                CameraPreview(session: cameraManager.session, cameraID: cameraManager.currentCameraID,
                              videoGravity: .resizeAspectFill)
                    .accessibilityLabel(String(localized: "방송 준비 전 카메라 미리보기"))
            }
        }
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
