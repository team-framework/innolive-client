import AVFoundation
import SwiftUI
import UIKit

struct FaceManagementView: View {
    @Environment(CameraManager.self) private var cameraManager
    @Environment(\.openURL) private var openURL
    @ObservedObject var youtube: YouTubeIntegration
    @StateObject private var model: FaceRegistrationViewModel
    @State private var isShowingRegistration = false
    @State private var isShowingDeleteAllConfirmation = false

    init(authentication: AuthSession, youtube: YouTubeIntegration) {
        self.youtube = youtube
        _model = StateObject(wrappedValue: FaceRegistrationViewModel(authentication: authentication))
    }

    private var isCameraInUseByBroadcast: Bool {
        youtube.videoUplink.isCapturingCamera || youtube.videoUplink.isConnecting
    }

    private var hasRegisteredFaces: Bool {
        model.status?.faces.isEmpty == false
    }

    var body: some View {
        ScrollView {
            GlassEffectContainer {
                VStack(spacing: 14) {
                    introductionCard

                    if !hasRegisteredFaces {
                        statusCard
                    }

                    if let status = model.status, !status.faces.isEmpty {
                        registeredFacesSection(status.faces)
                    }

                    if let errorMessage = model.statusErrorMessage {
                        Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                            .font(.footnote)
                            .foregroundStyle(.red)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 4)
                    }

                    Button {
                        isShowingRegistration = true
                    } label: {
                        Label("얼굴 등록 시작", systemImage: "viewfinder.circle.fill")
                            .font(.subheadline.weight(.semibold))
                            .frame(maxWidth: .infinity)
                            .frame(height: 46)
                    }
                    .buttonStyle(.glassProminent)
                    .tint(.blue)
                    .disabled(
                        isCameraInUseByBroadcast
                            || model.isDeleting
                            || cameraManager.authorizationStatus != .authorized
                    )

                    if isCameraInUseByBroadcast {
                        Text("서버에 카메라가 연결된 동안에는 얼굴을 등록할 수 없습니다. 연결을 종료한 뒤 다시 시도해 주세요.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 4)
                    } else if cameraManager.authorizationStatus != .authorized {
                        HStack {
                            Text("얼굴을 등록하려면 카메라 권한이 필요합니다.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                            Spacer()
                            Button("설정 열기") {
                                guard let settingsURL = URL(string: UIApplication.openSettingsURLString) else { return }
                                openURL(settingsURL)
                            }
                            .font(.footnote.weight(.semibold))
                            .buttonStyle(.plain)
                        }
                        .padding(.horizontal, 4)
                    }
                }
            }
            .padding(24)
        }
        .navigationTitle("얼굴 관리")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await model.loadStatus()
        }
        .refreshable {
            await model.loadStatus()
        }
        .fullScreenCover(isPresented: $isShowingRegistration) {
            NavigationStack {
                FaceRegistrationCaptureView(model: model)
                    .environment(cameraManager)
            }
        }
        .confirmationDialog(
            "등록된 얼굴을 모두 삭제할까요?",
            isPresented: $isShowingDeleteAllConfirmation,
            titleVisibility: .visible
        ) {
            Button("모두 삭제", role: .destructive) {
                Task { await model.deleteAll() }
            }
            Button("취소", role: .cancel) {}
        } message: {
            Text("삭제하면 다음 방송부터 해당 얼굴도 비식별화됩니다.")
        }
    }

    private var introductionCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Image(systemName: "faceid")
                .font(.system(size: 34, weight: .semibold))
                .foregroundStyle(.blue)

            Text("등록된 얼굴은 비식별화되지 않아요.")
                .font(.title3.weight(.bold))
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassEffect(.regular, in: .rect(cornerRadius: 22))
    }

    private var statusCard: some View {
        SettingsGlassRow {
            HStack(spacing: 12) {
                ZStack {
                    Circle()
                        .fill(statusColor.opacity(0.16))
                    Image(systemName: statusIcon)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(statusColor)
                }
                .frame(width: 34, height: 34)

                VStack(alignment: .leading, spacing: 2) {
                    Text(statusTitle)
                        .font(.body.weight(.semibold))
                    Text(statusDetail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Spacer()

                if model.isLoadingStatus {
                    ProgressView()
                        .controlSize(.small)
                } else {
                    Button {
                        Task { await model.loadStatus() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                            .font(.body.weight(.semibold))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("등록 상태 새로고침")
                }
            }
        }
    }

    private func registeredFacesSection(_ faces: [ReferenceFace]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("등록된 얼굴")
                    .font(.headline)
                Spacer()
                Button("모두 삭제", role: .destructive) {
                    isShowingDeleteAllConfirmation = true
                }
                .font(.caption.weight(.semibold))
                .disabled(model.isDeleting)
            }
            .padding(.horizontal, 4)

            ForEach(Array(faces.enumerated()), id: \.element.id) { index, face in
                SettingsGlassRow {
                    HStack(spacing: 12) {
                        Image(systemName: "person.crop.circle.fill")
                            .font(.title3)
                            .foregroundStyle(.blue)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("등록 얼굴 \(index + 1)")
                                .font(.body.weight(.semibold))
                            Text(formattedDate(face.registeredAt))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button(role: .destructive) {
                            Task { await model.delete(faceID: face.id) }
                        } label: {
                            Image(systemName: "trash")
                        }
                        .buttonStyle(.plain)
                        .disabled(model.isDeleting)
                        .accessibilityLabel("등록 얼굴 \(index + 1) 삭제")
                    }
                }
            }
        }
    }

    private var statusTitle: String {
        guard let status = model.status else { return "등록 상태 확인 필요" }
        return status.registered ? "얼굴 등록됨" : "등록된 얼굴 없음"
    }

    private var statusDetail: String {
        guard let status = model.status else { return "서버 상태를 불러와 주세요" }
        return status.registered ? "\(status.count)개의 얼굴 정보" : "등록하면 내 얼굴의 비식별화를 제외합니다"
    }

    private var statusIcon: String {
        model.status?.registered == true ? "checkmark" : "minus"
    }

    private var statusColor: Color {
        model.status?.registered == true ? .green : .secondary
    }

    private func formattedDate(_ value: String) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let date = formatter.date(from: value)
            ?? ISO8601DateFormatter().date(from: value)
        return date?.formatted(date: .abbreviated, time: .shortened) ?? value
    }
}

private struct FaceRegistrationCaptureView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CameraManager.self) private var cameraManager
    @ObservedObject var model: FaceRegistrationViewModel

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

                CameraPreview(
                    session: cameraManager.session,
                    cameraID: cameraManager.currentCameraID,
                    videoGravity: .resizeAspectFill
                )
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
                        await model.stopDetection(using: cameraManager)
                        dismiss()
                    }
                }
                .disabled(model.phase == .registering)
            }
        }
        .task {
            await model.beginDetection(using: cameraManager)
        }
        .onDisappear {
            Task { await model.stopDetection(using: cameraManager) }
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
                Task { await model.retryDetection(using: cameraManager) }
            } label: {
                Text("다시 시도")
                    .font(.body.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .frame(height: 52)
            }
            .buttonStyle(.glassProminent)
            .tint(.blue)
        case .success:
            Button {
                dismiss()
            } label: {
                Text("완료")
                    .font(.body.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .frame(height: 52)
            }
            .buttonStyle(.glassProminent)
            .tint(.green)
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
