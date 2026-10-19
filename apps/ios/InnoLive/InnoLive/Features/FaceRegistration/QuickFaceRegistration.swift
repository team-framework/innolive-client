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

/// Home-screen toggle for face selection. Selecting swaps the original and broadcast views.
struct QuickFaceRegistrationButton: View {
    @Environment(CameraManager.self) private var cameraManager
    @ObservedObject var videoUplink: WebRTCVideoUplink
    @Binding var isSelecting: Bool
    let isEnabled: Bool

    var body: some View {
        Button {
            isSelecting.toggle()
        } label: {
            Image(systemName: isSelecting ? "xmark" : "person.crop.circle.badge.plus")
                .font(.system(size: 17, weight: .semibold))
                .frame(width: 28, height: 28)
        }
        .innoLiveGlassButtonStyle(prominent: isSelecting, overVideo: true)
        .frame(minWidth: 44, minHeight: 44)
        .foregroundStyle(.white)
        .tint(.blue)
        .buttonBorderShape(.circle)
        .disabled(
            !isSelecting
                && (!isEnabled
                    || cameraManager.authorizationStatus != .authorized
                    || videoUplink.isFaceRegistrationCameraTransitioning)
        )
        .accessibilityLabel(isSelecting ? String(localized: "얼굴 선택 취소") : String(localized: "얼굴 등록"))
        .accessibilityHint(String(localized: "화면에 보이는 얼굴을 탭해 비식별화에서 제외합니다."))
    }
}

/// Takes the home screen's main area while selecting: the original camera with face outlines.
/// A tap hands the face to `onSelect` and selection ends; registration continues elsewhere.
struct FaceSelectionLayer: View {
    @Environment(CameraManager.self) private var cameraManager
    @StateObject private var model = QuickFaceRegistrationViewModel()
    @ObservedObject var videoUplink: WebRTCVideoUplink
    let usesUplinkCamera: Bool
    let bottomInset: CGFloat
    let onSelect: (FaceSelectionFrame, Int) -> Void
    @State private var lifecycleSessionID = UUID()

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                Color.black

                // Both show the camera before anonymization; the broadcast output is unchanged.
                if usesUplinkCamera {
                    WebRTCFaceRegistrationPreview(videoUplink: videoUplink)
                } else {
                    CameraPreview(
                        session: cameraManager.session,
                        cameraID: cameraManager.currentCameraID,
                        videoGravity: .resizeAspectFill
                    )
                }

                if case .detecting = model.phase {
                    FaceSelectionOverlay(faces: model.faces, imageSize: model.frameSize) { index in
                        guard let frame = model.frameForSelection(at: index) else { return }
                        onSelect(frame, index)
                    }
                }
            }
            .onAppear { model.setPreviewAspectRatio(proxy.size.width / proxy.size.height) }
            .onChange(of: proxy.size) { _, size in
                model.setPreviewAspectRatio(size.width / size.height)
            }
        }
        .ignoresSafeArea()
        .overlay(alignment: .bottom) {
            VStack(spacing: 10) {
                statusGlass
                if case .failed = model.phase {
                    Button {
                        Task {
                            await model.beginDetection(
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
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, bottomInset)
        }
        .foregroundStyle(.white)
        .task {
            await model.beginDetection(
                lifecycleSessionID: lifecycleSessionID,
                using: cameraManager,
                videoUplink: videoUplink
            )
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
            switch model.phase {
            case .idle, .preparing:
                ProgressView()
                    .tint(.white)
            case .failed:
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange)
            case .detecting:
                Image(systemName: "viewfinder")
                    .foregroundStyle(.blue)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(phaseMessage)
                    .font(.subheadline.weight(.semibold))
                Text(String(localized: "원본은 송출되지 않아요."))
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

    private var phaseMessage: String {
        switch model.phase {
        case .idle, .preparing:
            return String(localized: "카메라를 준비하고 있어요.")
        case let .detecting(message), let .failed(message):
            return message
        }
    }
}

/// Background registration progress on the home screen after a face was tapped.
struct GuestRegistrationToast: View {
    @ObservedObject var registrar: GuestFaceRegistrar
    let onReselect: () -> Void

    var body: some View {
        if let status = registrar.status {
            HStack(spacing: 10) {
                switch status {
                case .registering:
                    ProgressView()
                        .tint(.white)
                    Text(String(localized: "게스트를 등록하고 있어요."))
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity, alignment: .leading)
                case .registered:
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                    Text(String(localized: "게스트로 등록했어요."))
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity, alignment: .leading)
                case let .failed(message, canRetry):
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                    Text(message)
                        .font(.footnote.weight(.semibold))
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button(canRetry ? String(localized: "다시 시도") : String(localized: "다시 선택")) {
                        if canRetry {
                            registrar.retry()
                        } else {
                            registrar.dismiss()
                            onReselect()
                        }
                    }
                    .font(.footnote.weight(.bold))
                    Button {
                        registrar.dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.footnote.weight(.bold))
                    }
                    .accessibilityLabel(String(localized: "닫기"))
                }
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .frame(minHeight: 44)
            .innoLiveGlassBackground(cornerRadius: 22)
            .environment(\.colorScheme, .dark)
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }
}
