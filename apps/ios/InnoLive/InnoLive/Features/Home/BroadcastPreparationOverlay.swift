import SwiftUI

/// A single modal layer keeps the live preview visible while preparation runs.
struct BroadcastPreparationOverlay: View {
    let status: BroadcastPreparationStatus
    let onRetry: () async -> Void
    let onCancel: () async -> Void

    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast
    @State private var isPerformingAction = false

    private var isCancelling: Bool { status.phase == .cancelling && !status.isFailed }
    private var title: String {
        status.failedPhase?.failureMessage ?? status.phase.title
    }
    private var detail: String { status.message ?? status.phase.detail }

    var body: some View {
        ZStack {
            Color.black.opacity(reduceTransparency || contrast == .increased ? 1 : 0.82)
                .ignoresSafeArea()
                .accessibilityHidden(true)

            GeometryReader { geometry in
                ScrollView {
                    VStack(spacing: 24) {
                        if status.isFailed {
                            Image(systemName: "exclamationmark.triangle")
                                .font(.largeTitle)
                                .accessibilityHidden(true)
                        } else {
                            ProgressView()
                                .controlSize(.large)
                                .tint(.white)
                                .accessibilityHidden(true)
                        }

                        VStack(spacing: 10) {
                            Text(title)
                                .font(.title2.weight(.semibold))
                                .accessibilityAddTraits(.isHeader)
                                .accessibilityIdentifier("broadcast-preparation-title")
                            Text(detail)
                                .font(.body)
                                .foregroundStyle(.white.opacity(0.9))
                                .accessibilityIdentifier("broadcast-preparation-detail")
                        }
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityElement(children: .combine)

                        InnoLiveGlassContainer {
                            VStack(spacing: 12) {
                                if status.isFailed {
                                    Button {
                                        perform(onRetry)
                                    } label: {
                                        Text(String(localized: "다시 시도"))
                                            .frame(maxWidth: .infinity, minHeight: 44)
                                    }
                                    .innoLiveGlassButtonStyle(prominent: true, overVideo: true)
                                    .accessibilityIdentifier("broadcast-preparation-retry")
                                }
                                Button {
                                    perform(onCancel)
                                } label: {
                                    Text(isCancelling
                                         ? String(localized: "준비를 취소하는 중")
                                         : String(localized: "방송 준비 취소"))
                                        .frame(maxWidth: .infinity, minHeight: 44)
                                }
                                .innoLiveGlassButtonStyle(overVideo: true)
                                .accessibilityIdentifier("broadcast-preparation-cancel")
                            }
                            .buttonBorderShape(.capsule)
                            .disabled(isCancelling || isPerformingAction)
                        }
                        .frame(maxWidth: 320)
                    }
                    .foregroundStyle(.white)
                    .padding(32)
                    .frame(maxWidth: 520)
                    .frame(maxWidth: .infinity, minHeight: geometry.size.height)
                }
                .scrollBounceBehavior(.basedOnSize)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
        .accessibilityIdentifier("broadcast-preparation-overlay")
    }

    private func perform(_ action: @escaping () async -> Void) {
        guard !isPerformingAction, !isCancelling else { return }
        isPerformingAction = true
        Task {
            await action()
            isPerformingAction = false
        }
    }
}
