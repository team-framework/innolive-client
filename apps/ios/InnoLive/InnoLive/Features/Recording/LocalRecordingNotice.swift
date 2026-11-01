import SwiftUI

/// Shown once per device before the first local take.
struct LocalRecordingNotice: View {
    let onAcknowledge: () -> Void
    let onCancel: () -> Void

    static let items: [String] = [
        String(localized: "카메라 영상과 마이크 소리를 이 기기에만 녹화합니다. 영상은 서버로 보내지 않습니다."),
        String(localized: "등록하지 않은 사람의 얼굴은 이 기기에서 바로 비식별 처리한 뒤 저장합니다."),
        String(localized: "등록한 얼굴 목록은 사진 없이 얼굴 특징값만 암호화해 이 기기에만 보관합니다."),
        String(localized: "녹화 시작·종료 시각과 길이는 인터넷에 다시 연결되면 사용량 집계를 위해 서버로 보냅니다."),
        String(localized: "녹화 영상은 '저장된 방송'에서, 등록한 얼굴은 '얼굴 관리'에서 지울 수 있습니다. 로그아웃하면 등록한 얼굴 목록도 이 기기에서 지워집니다.")
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    ForEach(Self.items, id: \.self) { item in
                        Label {
                            Text(item).fixedSize(horizontal: false, vertical: true)
                        } icon: {
                            Image(systemName: "checkmark.circle")
                        }
                    }
                }
                .padding(InnoLiveSettingsLayout.pageInset)
            }
            .safeAreaInset(edge: .bottom) {
                Button(action: onAcknowledge) {
                    Text(String(localized: "확인하고 녹화 시작"))
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .innoLiveGlassButtonStyle()
                .buttonBorderShape(.capsule)
                .padding(InnoLiveSettingsLayout.pageInset)
                .accessibilityIdentifier("local-recording-notice-acknowledge")
            }
            .navigationTitle(String(localized: "로컬 녹화 안내"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "닫기"), action: onCancel)
                }
            }
        }
        .interactiveDismissDisabled()
    }
}
