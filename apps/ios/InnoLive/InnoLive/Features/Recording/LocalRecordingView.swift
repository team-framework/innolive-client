import AVFoundation
import Combine
import Foundation
import SwiftUI

struct LocalRecordingView: View {
    let authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    @ObservedObject var uplink: WebRTCVideoUplink
    @ObservedObject var lifecycle: BroadcastLifecycle
    @Environment(CameraManager.self) private var cameraManager
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var openError: String?
    @State private var isClosing = false
    @State private var thermalNotice: String?
    @State private var isShowingNotice = false
    @State private var isSelectingFace = false
    @StateObject private var guestRegistrar = GuestFaceRegistrar()
    @ObservedObject private var readiness: ModelReadinessStore

    init(authentication: AuthSession, youtube: YouTubeIntegration, uplink: WebRTCVideoUplink, lifecycle: BroadcastLifecycle) {
        self.authentication = authentication
        _youtube = ObservedObject(wrappedValue: youtube)
        _uplink = ObservedObject(wrappedValue: uplink)
        _lifecycle = ObservedObject(wrappedValue: lifecycle)
        _readiness = ObservedObject(wrappedValue: youtube.modelReadiness)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                LocalModeChips(monitor: youtube.operatingMode)
                preview
                status
                    .frame(minHeight: 44)
                if let thermalNotice {
                    Text(thermalNotice)
                        .font(.footnote)
                        .foregroundStyle(.orange)
                        .multilineTextAlignment(.center)
                }
                HStack(spacing: 12) {
                    QuickFaceRegistrationButton(videoUplink: uplink, isSelecting: $isSelectingFace,
                                                isEnabled: canSelectFace)
                    recordButton
                }
                GuestRegistrationToast(registrar: guestRegistrar) {
                    if canSelectFace { isSelectingFace = true }
                }
                .animation(.easeOut(duration: 0.2), value: guestRegistrar.status)
                ModelReadinessReadout(store: readiness)
                if youtube.isOfflineGrantStale {
                    Text(OfflineGrant.staleNotice)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .accessibilityIdentifier("offline-grant-stale")
                }
                Text(String(localized: "서버 연결 없이 이 기기에만 녹화합니다."))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(InnoLiveSettingsLayout.pageInset)
            .navigationTitle(String(localized: "로컬 녹화"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "닫기"), action: close)
                        .disabled(isClosing)
                        .accessibilityIdentifier("local-recording-close")
                }
            }
        }
        .overlay {
            if isSelectingFace {
                ZStack(alignment: .topTrailing) {
                    FaceSelectionLayer(videoUplink: uplink, usesUplinkCamera: true, bottomInset: 96) { frame, faceIndex in
                        // Local mode has no server, so the face always goes to the on-device library.
                        guestRegistrar.register(frame: frame, faceIndex: faceIndex, name: GuestFaceName.make(),
                                                mode: .onDevice, authentication: authentication)
                        isSelectingFace = false
                    }
                    QuickFaceRegistrationButton(videoUplink: uplink, isSelecting: $isSelectingFace, isEnabled: true)
                        .padding(16)
                }
            }
        }
        .onChange(of: canSelectFace) { _, canSelect in
            if !canSelect { isSelectingFace = false }
        }
        .sheet(isPresented: $isShowingNotice) {
            LocalRecordingNotice(onAcknowledge: {
                youtube.consentStore.recordLocalRecordingNotice()
                isShowingNotice = false
                startTake()
            }, onCancel: { isShowingNotice = false })
        }
        .task { await open() }
        .task { await readiness.warm() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { youtube.stopLocalTake() }
        }
        // Processing failures record no frames, so end the take and save what was protected.
        .onChange(of: uplink.errorMessage) { _, message in
            if message != nil { youtube.stopLocalTake() }
        }
        .onReceive(NotificationCenter.default.publisher(for: ProcessInfo.thermalStateDidChangeNotification)
            .receive(on: RunLoop.main)) { _ in
            guard ProcessInfo.processInfo.thermalState == .critical, youtube.isLocalTakeRecording else { return }
            thermalNotice = Self.thermalStopMessage
            youtube.stopLocalTake()
        }
    }

    private var preview: some View {
        RemoteStreamView(uplink: uplink, previewTransition: .none, isPreparingSession: false,
                         isConnectingVideo: false, showsStatusOverlay: false)
            .overlay {
                if uplink.state != .connected, openError == nil, uplink.errorMessage == nil {
                    VStack(spacing: 12) {
                        ProgressView().tint(.white)
                        Text(String(localized: "카메라를 준비하는 중"))
                    }
                    .foregroundStyle(.white)
                }
            }
            .aspectRatio(BroadcastVideoLayout.aspectRatio, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
    }

    @ViewBuilder
    private var status: some View {
        if let message = openError ?? uplink.errorMessage {
            Text(message).foregroundStyle(.red)
        } else if youtube.isLocalTakeRecording, let startedAt = lifecycle.startedAt {
            TimelineView(.periodic(from: startedAt, by: 1)) { context in
                Label(Duration.seconds(max(0, context.date.timeIntervalSince(startedAt).rounded(.down)))
                        .formatted(.time(pattern: .minuteSecond)),
                      systemImage: "record.circle.fill")
                    .foregroundStyle(.red)
                    .monospacedDigit()
            }
        } else {
            switch lifecycle.recording {
            case .finalizing:
                Text(String(localized: "영상을 저장하는 중"))
            case .saved:
                Text(String(localized: "비식별 영상을 앱에 저장했어요"))
                    .accessibilityIdentifier("local-recording-saved")
            case .failed(let message):
                VStack(spacing: 4) {
                    Text(String(localized: "영상을 저장하지 못했어요")).font(.headline)
                    Text(message).font(.footnote)
                }
                .accessibilityIdentifier("local-recording-failed")
            case .idle, .armed, .recording:
                EmptyView()
            }
        }
    }

    private var recordButton: some View {
        Button {
            if youtube.isLocalTakeRecording {
                youtube.stopLocalTake()
            } else if ProcessInfo.processInfo.thermalState == .critical {
                thermalNotice = Self.thermalBlockedMessage
            } else if !youtube.consentStore.hasAcknowledgedLocalRecordingNotice {
                isShowingNotice = true
            } else {
                startTake()
            }
        } label: {
            Text(youtube.isLocalTakeRecording ? String(localized: "녹화 종료") : String(localized: "녹화 시작"))
                .font(.headline)
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .innoLiveGlassButtonStyle()
        .buttonBorderShape(.capsule)
        .disabled((!youtube.isLocalTakeRecording && (uplink.state != .connected || !readiness.isReady))
                  || isClosing || lifecycle.recording == .finalizing)
        .accessibilityIdentifier("local-recording-toggle")
    }

    private var canSelectFace: Bool {
        uplink.state == .connected && readiness.isReady && !isClosing
    }

    private func startTake() {
        do {
            thermalNotice = nil
            try youtube.startLocalTake()
        } catch {
            thermalNotice = error.localizedDescription
        }
    }

    private static let thermalStopMessage = String(localized: "기기 온도가 너무 높아 녹화를 종료하고 저장했어요.")
    private static let thermalBlockedMessage = String(localized: "기기 온도가 너무 높아 녹화를 시작할 수 없어요. 잠시 후 다시 시도해 주세요.")

    private func open() async {
        await cameraManager.stopSession()
        let quality = CameraQualityPreset(
            rawValue: UserDefaults.standard.string(forKey: CameraQualityPreset.storageKey) ?? ""
        ) ?? .defaultValue
        do {
            try await youtube.openLocalCapture(
                preferredCameraID: cameraManager.currentCameraID,
                preferredAudioID: UserDefaults.standard.string(forKey: "selectedAudioID"),
                preferredVideoQuality: quality
            )
        } catch {
            openError = error.localizedDescription
        }
    }

    private func close() {
        isClosing = true
        Task {
            await youtube.closeLocalCapture()
            if cameraManager.authorizationStatus == .authorized {
                await cameraManager.startDefaultCamera()
            }
            dismiss()
        }
    }
}
