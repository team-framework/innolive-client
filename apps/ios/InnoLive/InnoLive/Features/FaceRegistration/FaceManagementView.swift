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

    private var isCameraTransitioning: Bool {
        let videoUplink = youtube.videoUplink
        return videoUplink.isSwitchingCamera
            || videoUplink.isReleasingCamera
            || (videoUplink.isActive && !videoUplink.canProvideFaceRegistrationFrames)
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
                        Label("얼굴 등록", systemImage: "viewfinder.circle.fill")
                            .font(.subheadline.weight(.semibold))
                            .frame(maxWidth: .infinity)
                            .frame(height: 46)
                    }
                    .buttonStyle(.glassProminent)
                    .tint(.blue)
                    .disabled(
                        isCameraTransitioning
                            || model.isDeleting
                            || cameraManager.authorizationStatus != .authorized
                    )

                    if isCameraTransitioning {
                        Text("카메라 연결이 완료된 뒤 얼굴을 등록할 수 있어요.")
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
        .sheet(isPresented: $isShowingRegistration) {
            NavigationStack {
                FaceRegistrationCaptureView(
                    model: model,
                    videoUplink: youtube.videoUplink
                )
                    .environment(cameraManager)
            }
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .interactiveDismissDisabled(model.phase == .registering)
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
            Text("삭제하면 해당 얼굴은 다시 비식별화 대상이 됩니다.")
        }
    }

    private var introductionCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Image(systemName: "faceid")
                .font(.system(size: 34, weight: .semibold))
                .foregroundStyle(.blue)

            Text("비식별화하지 않을 얼굴을 등록해주세요")
                .font(.title3.weight(.bold))

            Text("방송인이나 게스트의 얼굴을 등록하면, 방송 중 해당 인물은 비식별화 처리되지 않아 자연스러운 방송을 진행할 수 있습니다.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
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
                            Text(FaceRegistrationDateFormatting.displayDate(from: face.registeredAt))
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
}
