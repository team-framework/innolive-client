import AVFoundation
import SwiftUI

extension WebRTCVideoUplink {
    /// Face registration waits until the uplink camera can hand over its frames.
    var isFaceRegistrationCameraTransitioning: Bool {
        isSwitchingCamera
            || isReleasingCamera
            || (isActive && !canProvideFaceRegistrationFrames)
    }
}

/// The name one-tap registration gives a new person, such as "10월 17일 오후 3시 - 게스트".
enum GuestFaceName {
    static func make(
        at date: Date = .now,
        locale: Locale = Locale(identifier: Bundle.main.preferredLocalizations.first ?? "ko"),
        timeZone: TimeZone = .current
    ) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.timeZone = timeZone
        formatter.setLocalizedDateFormatFromTemplate("MMMdj")
        return "\(formatter.string(from: date)) - \(String(localized: "게스트"))"
    }
}

/// Broadcast-screen shortcut that shows the raw camera full screen and registers a tapped face at once.
struct QuickFaceRegistrationButton: View {
    @Environment(CameraManager.self) private var cameraManager
    @ObservedObject var authentication: AuthSession
    @ObservedObject var videoUplink: WebRTCVideoUplink
    let isEnabled: Bool
    @State private var isPresented = false

    var body: some View {
        Button {
            isPresented = true
        } label: {
            Image(systemName: "person.crop.circle.badge.plus")
                .font(.title3.weight(.semibold))
                .frame(width: 44, height: 44)
        }
        .innoLiveGlassButtonStyle()
        .buttonBorderShape(.circle)
        .disabled(
            !isEnabled
                || cameraManager.authorizationStatus != .authorized
                || videoUplink.isFaceRegistrationCameraTransitioning
        )
        .accessibilityLabel(String(localized: "얼굴 등록"))
        .accessibilityHint(String(localized: "화면에 보이는 얼굴을 탭해 비식별화에서 제외합니다."))
        .fullScreenCover(isPresented: $isPresented) {
            QuickFaceRegistrationView(authentication: authentication, videoUplink: videoUplink)
                .environment(cameraManager)
        }
    }
}

private struct QuickFaceRegistrationView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CameraManager.self) private var cameraManager
    @StateObject private var model: QuickFaceRegistrationViewModel
    @ObservedObject var videoUplink: WebRTCVideoUplink
    @State private var lifecycleSessionID = UUID()

    init(authentication: AuthSession, videoUplink: WebRTCVideoUplink) {
        // Registers into the store the current AI setting uses, like the settings screen.
        _model = StateObject(wrappedValue: QuickFaceRegistrationViewModel(
            authentication: authentication,
            mode: .selected
        ))
        self.videoUplink = videoUplink
    }

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                Color.black

                // Both previews show the camera before anonymization; the broadcast keeps its own output.
                if model.isUsingWebRTCFrames {
                    WebRTCFaceRegistrationPreview(videoUplink: videoUplink)
                } else {
                    CameraPreview(
                        session: cameraManager.session,
                        cameraID: cameraManager.currentCameraID,
                        videoGravity: .resizeAspectFill
                    )
                }

                if let selection = model.selection {
                    FrozenFaceSelectionView(image: selection.previewImage, faceRect: selection.faceRect)
                } else if case .detecting = model.phase {
                    FaceSelectionOverlay(faces: model.faces, imageSize: model.frameSize) { index in
                        Task {
                            await model.registerFace(
                                at: index,
                                name: GuestFaceName.make(),
                                lifecycleSessionID: lifecycleSessionID,
                                using: cameraManager,
                                videoUplink: videoUplink
                            )
                        }
                    }
                }
            }
            .onAppear { model.setPreviewAspectRatio(proxy.size.width / proxy.size.height) }
            .onChange(of: proxy.size) { _, size in
                model.setPreviewAspectRatio(size.width / size.height)
            }
        }
        .ignoresSafeArea()
        .overlay(alignment: .top) {
            HStack(alignment: .top, spacing: 12) {
                Button {
                    Task {
                        await model.stopDetection(
                            lifecycleSessionID: lifecycleSessionID,
                            using: cameraManager,
                            videoUplink: videoUplink
                        )
                        dismiss()
                    }
                } label: {
                    Image(systemName: "xmark")
                        .font(.body.weight(.semibold))
                        .frame(width: 44, height: 44)
                }
                .innoLiveGlassButtonStyle()
                .buttonBorderShape(.circle)
                .disabled(model.phase == .registering)
                .accessibilityLabel(String(localized: "닫기"))

                statusGlass
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
        }
        .overlay(alignment: .bottom) {
            actionButtons
                .padding(.horizontal, 24)
                .padding(.bottom, 16)
        }
        .foregroundStyle(.white)
        .statusBarHidden()
        .task {
            await model.beginDetection(
                lifecycleSessionID: lifecycleSessionID,
                using: cameraManager,
                videoUplink: videoUplink
            )
        }
        .onChange(of: model.phase) { _, phase in
            guard phase == .success else { return }
            Task {
                // Leave the confirmation up long enough to read before returning to the broadcast.
                try? await Task.sleep(for: .milliseconds(800))
                dismiss()
            }
        }
        .onChange(of: videoUplink.canProvideFaceRegistrationFrames) { _, isAvailable in
            if model.isUsingWebRTCFrames, !isAvailable {
                model.handleWebRTCFrameSourceUnavailable(
                    lifecycleSessionID: lifecycleSessionID,
                    videoUplink: videoUplink
                )
            }
        }
        .onDisappear {
            Task {
                await model.stopDetection(
                    lifecycleSessionID: lifecycleSessionID,
                    using: cameraManager,
                    videoUplink: videoUplink
                )
            }
        }
    }

    private var statusGlass: some View {
        HStack(spacing: 10) {
            phaseIcon
            VStack(alignment: .leading, spacing: 2) {
                Text(phaseMessage)
                    .font(.subheadline.weight(.semibold))
                Text(String(localized: "이 화면은 송출되지 않아요."))
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.75))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(minHeight: 44)
        .innoLiveGlassBackground(cornerRadius: 22)
        .environment(\.colorScheme, .dark)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var phaseIcon: some View {
        switch model.phase {
        case .preparing, .registering:
            ProgressView()
                .tint(.white)
        case .success:
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(.green)
        case .failed, .registrationFailed:
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
        case .idle, .detecting:
            Image(systemName: "viewfinder")
                .foregroundStyle(.blue)
        }
    }

    private var phaseMessage: String {
        switch model.phase {
        case .idle, .preparing:
            return String(localized: "카메라를 준비하고 있어요.")
        case let .detecting(message):
            return message
        case let .registrationFailed(message):
            return message
        case .registering:
            return String(localized: "얼굴을 등록하고 있어요. 잠시만 기다려 주세요.")
        case .success:
            return String(localized: "게스트로 등록했어요.")
        case let .failed(message):
            return message
        }
    }

    @ViewBuilder
    private var actionButtons: some View {
        switch model.phase {
        case .registrationFailed:
            HStack(spacing: 12) {
                Button {
                    Task {
                        await model.retryDetection(
                            lifecycleSessionID: lifecycleSessionID,
                            using: cameraManager,
                            videoUplink: videoUplink
                        )
                    }
                } label: {
                    Text(String(localized: "다시 선택"))
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                }
                .innoLiveGlassButtonStyle()

                Button {
                    model.retryRegistration(lifecycleSessionID: lifecycleSessionID)
                } label: {
                    Text(String(localized: "다시 시도"))
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                }
                .innoLiveGlassButtonStyle(prominent: true)
                .tint(.blue)
            }
        case .failed:
            Button {
                Task {
                    await model.retryDetection(
                        lifecycleSessionID: lifecycleSessionID,
                        using: cameraManager,
                        videoUplink: videoUplink
                    )
                }
            } label: {
                Text(String(localized: "다시 시도"))
                    .font(.body.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .frame(height: 48)
            }
            .innoLiveGlassButtonStyle(prominent: true)
            .tint(.blue)
        default:
            EmptyView()
        }
    }
}
