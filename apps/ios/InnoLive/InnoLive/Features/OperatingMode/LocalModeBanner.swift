import SwiftUI

/// Home banner for administrators while the app is in local mode.
struct LocalModeBanner: View {
    @ObservedObject var monitor: OperatingModeMonitor
    let canStartLocalRecording: Bool
    let onStartLocalRecording: () -> Void
    @State private var isSwitching = false

    var body: some View {
        if monitor.isMonitoring, case .local(let reason) = monitor.state.mode {
            VStack(alignment: .leading, spacing: 10) {
                Text(reason == .userChoice
                     ? String(localized: "항상 로컬 모드로 녹화합니다.")
                     : String(localized: "서버에 연결할 수 없어 로컬 모드로 녹화합니다."))
                    .font(.subheadline.weight(.semibold))
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 8) {
                    Button(String(localized: "로컬 녹화 시작"), action: onStartLocalRecording)
                        .buttonStyle(.borderedProminent)
                        .disabled(!canStartLocalRecording)
                        .accessibilityIdentifier("local-mode-start-recording")
                    if monitor.state.canSwitchToServer {
                        Button(String(localized: "서버 모드로 전환")) {
                            isSwitching = true
                            Task {
                                await monitor.switchToServer()
                                isSwitching = false
                            }
                        }
                        .buttonStyle(.bordered)
                        .disabled(isSwitching)
                        .accessibilityIdentifier("local-mode-switch-to-server")
                    }
                }
            }
            .padding(14)
            .frame(maxWidth: 420, alignment: .leading)
            .innoLiveGlassSurface(in: RoundedRectangle(cornerRadius: 20, style: .continuous), overVideo: true)
            .padding(.horizontal, 16)
            .accessibilityIdentifier("local-mode-banner")
        }
    }
}

/// "로컬" chip during a local capture, plus "서버 연결됨" once probes keep succeeding.
struct LocalModeChips: View {
    @ObservedObject var monitor: OperatingModeMonitor

    var body: some View {
        HStack(spacing: 6) {
            chip(String(localized: "로컬"), systemImage: "iphone")
            if monitor.state.serverAvailable {
                chip(String(localized: "서버 연결됨"), systemImage: "checkmark.icloud")
                    .accessibilityIdentifier("local-mode-server-available")
            }
        }
    }

    private func chip(_ title: String, systemImage: String) -> some View {
        Label(title, systemImage: systemImage)
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(.thinMaterial, in: Capsule())
    }
}
