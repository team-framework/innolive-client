import Foundation
import SwiftUI

struct LocalRecordingView: View {
    @ObservedObject var youtube: YouTubeIntegration
    @ObservedObject var uplink: WebRTCVideoUplink
    @ObservedObject var lifecycle: BroadcastLifecycle
    @Environment(CameraManager.self) private var cameraManager
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var openError: String?
    @State private var isClosing = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                preview
                status
                    .frame(minHeight: 44)
                recordButton
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
        .task { await open() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { youtube.stopLocalTake() }
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
                Text(String(localized: "원본 영상을 앱에 저장했어요"))
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
            if youtube.isLocalTakeRecording { youtube.stopLocalTake() } else { youtube.startLocalTake() }
        } label: {
            Text(youtube.isLocalTakeRecording ? String(localized: "녹화 종료") : String(localized: "녹화 시작"))
                .font(.headline)
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .innoLiveGlassButtonStyle()
        .buttonBorderShape(.capsule)
        .disabled((uplink.state != .connected && !youtube.isLocalTakeRecording) || isClosing || lifecycle.recording == .finalizing)
        .accessibilityIdentifier("local-recording-toggle")
    }

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
