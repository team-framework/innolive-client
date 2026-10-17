import AVFoundation
import SwiftUI

struct FaceRegistrationCaptureView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CameraManager.self) private var cameraManager
    @ObservedObject var model: FaceRegistrationViewModel
    @ObservedObject var videoUplink: WebRTCVideoUplink
    @State private var lifecycleSessionID = UUID()
    @FocusState private var isNameFocused: Bool

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 20) {
                Spacer(minLength: 12)

                // The name card needs the room while the keyboard is up.
                if model.selection == nil {
                    VStack(spacing: 8) {
                        Text(String(localized: "얼굴 등록"))
                            .font(.largeTitle.weight(.bold))
                        Text(String(localized: "화면에 보이는 얼굴을 탭하면 바로 등록할 수 있어요."))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                }

                ZStack {
                    Group {
                        if model.isUsingWebRTCFrames {
                            WebRTCFaceRegistrationPreview(videoUplink: videoUplink)
                        } else {
                            CameraPreview(
                                session: cameraManager.session,
                                cameraID: cameraManager.currentCameraID,
                                videoGravity: .resizeAspectFill
                            )
                        }
                    }

                    if let selection = model.selection {
                        FrozenFaceSelectionView(image: selection.previewImage, faceRect: selection.faceRect)
                    } else if case .detecting = model.phase {
                        FaceSelectionOverlay(faces: model.faces, imageSize: model.frameSize) { index in
                            Task {
                                await model.selectFace(
                                    at: index,
                                    lifecycleSessionID: lifecycleSessionID,
                                    using: cameraManager,
                                    videoUplink: videoUplink
                                )
                            }
                        }
                    }
                }
                .aspectRatio(FaceSelectionGeometry.previewAspectRatio, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 32, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 32, style: .continuous)
                        .stroke(previewBorderColor, lineWidth: 3)
                        .allowsHitTesting(false)
                }
                .overlay {
                    if phaseBlocksPreview {
                        RoundedRectangle(cornerRadius: 32, style: .continuous)
                            .fill(.black.opacity(0.48))
                    }
                }
                .padding(.horizontal, 24)

                statusGlass
                    .padding(.horizontal, 24)

                if model.selection != nil {
                    namingCard
                        .padding(.horizontal, 24)
                }

                actionButton
                    .padding(.horizontal, 24)

                Spacer(minLength: 20)
            }
        }
        .foregroundStyle(.white)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button(String(localized: "취소")) {
                    Task {
                        await model.stopDetection(
                            lifecycleSessionID: lifecycleSessionID,
                            using: cameraManager,
                            videoUplink: videoUplink
                        )
                        dismiss()
                    }
                }
                .disabled(model.phase == .registering)
            }
        }
        .task {
            await model.beginDetection(
                lifecycleSessionID: lifecycleSessionID,
                using: cameraManager,
                videoUplink: videoUplink
            )
        }
        .onChange(of: model.phase) { _, phase in
            if phase == .success {
                dismiss()
            } else if phase == .naming {
                isNameFocused = true
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
        HStack(spacing: 12) {
            phaseIcon
            Text(phaseMessage)
                .font(.body.weight(.semibold))
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 18)
        .frame(maxWidth: .infinity)
        .frame(minHeight: 62)
        .innoLiveGlassBackground(cornerRadius: 20)
        .accessibilityElement(children: .combine)
    }

    private var namingCard: some View {
        VStack(spacing: 12) {
            TextField(String(localized: "이름"), text: $model.name)
                .textFieldStyle(.roundedBorder)
                .submitLabel(.done)
                .focused($isNameFocused)
                .onSubmit(confirmSelection)
                .disabled(model.phase == .registering)

            if let message = model.selectionErrorMessage {
                Label(message, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
                    .foregroundStyle(.orange)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            HStack(spacing: 12) {
                Button {
                    Task {
                        await model.reselectFace(
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

                Button(action: confirmSelection) {
                    Text(String(localized: "확인"))
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                }
                .innoLiveGlassButtonStyle(prominent: true)
                .tint(.blue)
                .disabled(!model.canConfirmName)
            }
            .disabled(model.phase == .registering)
        }
        // The sheet background is always black, so keep the field readable in light mode.
        .environment(\.colorScheme, .dark)
    }

    private func confirmSelection() {
        model.confirmSelection(lifecycleSessionID: lifecycleSessionID)
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
        case .failed:
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
        case .naming:
            Image(systemName: "person.crop.square")
                .foregroundStyle(.blue)
        case .idle, .detecting:
            Image(systemName: "viewfinder")
                .foregroundStyle(.blue)
        }
    }

    @ViewBuilder
    private var actionButton: some View {
        switch model.phase {
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
                    .frame(height: 52)
            }
            .innoLiveGlassButtonStyle(prominent: true)
            .tint(.blue)
        default:
            EmptyView()
        }
    }

    private var phaseMessage: String {
        switch model.phase {
        case .idle, .preparing:
            return String(localized: "카메라를 준비하고 있어요.")
        case let .detecting(message):
            return message
        case .naming:
            return String(localized: "이름을 입력하고 확인을 눌러 주세요.")
        case .registering:
            return String(localized: "얼굴을 등록하고 있어요. 잠시만 기다려 주세요.")
        case .success:
            return String(localized: "얼굴 등록이 완료됐어요.")
        case let .failed(message):
            return message
        }
    }

    private var previewBorderColor: Color {
        switch model.phase {
        case .success: return .green
        case .failed: return .orange
        case .naming, .registering: return .blue
        default: return .white.opacity(0.7)
        }
    }

    private var phaseBlocksPreview: Bool {
        switch model.phase {
        case .preparing, .registering, .failed, .success: return true
        case .idle, .detecting, .naming: return false
        }
    }
}
