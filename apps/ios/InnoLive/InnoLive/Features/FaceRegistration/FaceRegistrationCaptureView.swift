import AVFoundation
import SwiftUI

struct FaceRegistrationCaptureView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CameraManager.self) private var cameraManager
    @ObservedObject var model: FaceRegistrationViewModel
    @ObservedObject var videoUplink: WebRTCVideoUplink
    @State private var lifecycleSessionID = UUID()

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 24) {
                Spacer(minLength: 12)

                VStack(spacing: 8) {
                    Text("얼굴 등록")
                        .font(.largeTitle.weight(.bold))
                    Text("얼굴을 가운데 영역에 맞춰 주세요.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }

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
                .aspectRatio(1, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 32, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 32, style: .continuous)
                        .stroke(previewBorderColor, lineWidth: 3)
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

                actionButton
                    .padding(.horizontal, 24)

                Spacer(minLength: 20)
            }
        }
        .foregroundStyle(.white)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button("취소") {
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
        .glassEffect(.regular, in: .rect(cornerRadius: 20))
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
        case .failed:
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
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
                Text("다시 시도")
                    .font(.body.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .frame(height: 52)
            }
            .buttonStyle(.glassProminent)
            .tint(.blue)
        default:
            EmptyView()
        }
    }

    private var phaseMessage: String {
        switch model.phase {
        case .idle, .preparing:
            return "카메라를 준비하고 있어요."
        case let .detecting(message):
            return message
        case .registering:
            return "얼굴을 등록하고 있어요. 잠시만 기다려 주세요."
        case .success:
            return "얼굴 등록이 완료됐어요."
        case let .failed(message):
            return message
        }
    }

    private var previewBorderColor: Color {
        switch model.phase {
        case .success: return .green
        case .failed: return .orange
        default: return .white.opacity(0.7)
        }
    }

    private var phaseBlocksPreview: Bool {
        switch model.phase {
        case .preparing, .registering, .failed, .success: return true
        case .idle, .detecting: return false
        }
    }
}
