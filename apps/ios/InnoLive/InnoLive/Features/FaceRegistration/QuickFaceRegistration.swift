import AVFoundation
import SwiftUI

extension WebRTCVideoUplink {
    /// 업링크 카메라가 프레임을 넘겨줄 수 있을 때까지 얼굴 등록을 기다리게 한다.
    var isFaceRegistrationCameraTransitioning: Bool {
        isSwitchingCamera
            || isReleasingCamera
            || (isActive && !canProvideFaceRegistrationFrames)
    }
}

/// 원탭 등록이 새 사람에게 붙이는 이름이다. 예: "10월 17일 오후 3시 - 게스트".
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

/// 홈 화면의 얼굴 선택 토글이다. 선택하는 동안 원본 화면과 송출 화면을 맞바꾼다.
struct QuickFaceRegistrationButton: View {
    @Environment(CameraManager.self) private var cameraManager
    @ObservedObject var videoUplink: WebRTCVideoUplink
    @Binding var isSelecting: Bool
    let isEnabled: Bool
    var isEmbedded = false

    var body: some View {
        Group {
            if isEmbedded {
                button.buttonStyle(.plain)
            } else {
                button.innoLiveGlassButtonStyle(prominent: isSelecting, overVideo: true)
            }
        }
        .frame(minWidth: 44, minHeight: 44)
        .foregroundStyle(.white)
        .tint(.blue)
        .buttonBorderShape(.circle)
    }

    private var button: some View {
        Button {
            isSelecting.toggle()
        } label: {
            Image(systemName: isSelecting ? "xmark" : "person.crop.circle.badge.plus")
                .font(.system(size: 17, weight: .semibold))
                .frame(width: isEmbedded ? 44 : 28, height: isEmbedded ? 44 : 28)
                .contentShape(Rectangle())
        }
        .disabled(
            !isSelecting
                && (!isEnabled
                    || cameraManager.authorizationStatus != .authorized
                    || videoUplink.isFaceRegistrationCameraTransitioning)
        )
        .accessibilityLabel(isSelecting ? String(localized: "얼굴 선택 취소") : String(localized: "얼굴 등록"))
        .accessibilityHint(String(localized: "화면에 보이는 얼굴을 탭해 비식별화에서 제외합니다."))
        .accessibilityIdentifier("studio-face-registration")
    }
}

/// 선택하는 동안 홈 화면의 메인 영역을 차지하고 원본 카메라 위에 얼굴 윤곽선을 보여 준다.
/// 얼굴을 탭하면 `onSelect`로 넘기고 선택을 끝낸다. 등록은 다른 곳에서 이어서 진행한다.
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

                // 둘 다 비식별화 전 카메라 화면을 보여 준다. 송출 영상은 바뀌지 않는다.
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

/// 얼굴을 탭한 뒤 홈 화면에 백그라운드 등록 진행 상황을 보여 준다.
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
                    Button {
                        if canRetry {
                            registrar.retry()
                        } else {
                            registrar.dismiss()
                            onReselect()
                        }
                    } label: {
                        Text(canRetry ? String(localized: "다시 시도") : String(localized: "다시 선택"))
                            .font(.footnote.weight(.bold))
                            .frame(minWidth: 44, minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    Button {
                        registrar.dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.footnote.weight(.bold))
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
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
