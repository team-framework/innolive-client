import SwiftUI

struct BroadcastTransmissionNoticeView: View {
    let provider: BroadcastSettingsProvider
    let onConfirm: (SignupConsent) -> Void

    var body: some View {
        ConsentDocumentSheet(
            navigationTitle: provider == .youtube ? String(localized: "YouTube 전송 안내") : String(localized: "치지직 전송 안내"),
            sections: sections,
            closingNotice: String(localized: "이 안내를 확인하지 않으면 방송 준비를 진행하지 않습니다. 개인정보 관련 문의는 개인정보처리방침의 연락처로 요청할 수 있습니다."),
            scrollHint: String(localized: "내용을 끝까지 스크롤하면 계속할 수 있습니다."),
            acceptTitle: String(localized: "확인하고 방송 준비"),
            acceptIdentifier: provider == .youtube ? "youtubeTransmissionNotice.continue" : "chzzkTransmissionNotice.continue",
            onAccept: onConfirm
        )
    }

    private var sections: [ConsentDocumentSection] {
        switch provider {
        case .youtube:
            return [
                ConsentDocumentSection(
                    title: String(localized: "전송하는 정보"),
                    body: String(localized: "처리된 방송 영상·음성을 연결한 YouTube 채널로 전송합니다. YouTube 연결과 방송을 위해 채널 식별 정보, 암호화한 연결 토큰과 방송 연결 정보를 처리합니다.")
                ),
                ConsentDocumentSection(
                    title: String(localized: "이용 목적"),
                    body: String(localized: "연결한 YouTube 채널로 처리한 영상과 음성을 전송하고 방송 상태를 관리합니다.")
                ),
                ConsentDocumentSection(
                    title: String(localized: "YouTube에서의 처리"),
                    body: String(localized: "YouTube에 전송된 영상은 YouTube의 정책과 사용자 채널 설정에 따라 처리됩니다. 계정 삭제는 YouTube 채널의 과거 영상을 삭제하지 않습니다.")
                )
            ]
        case .chzzk:
            return [
                ConsentDocumentSection(
                    title: String(localized: "전송하는 정보"),
                    body: String(localized: "처리된 방송 영상·음성과 방송 제목·카테고리·태그를 연결한 치지직 채널로 전송합니다. 치지직 연결과 방송을 위해 채널 식별 정보와 채널 이름, 암호화한 연결 토큰, 송출에 필요한 스트림 키를 처리합니다.")
                ),
                ConsentDocumentSection(
                    title: String(localized: "요청하는 치지직 권한"),
                    body: String(localized: "유저 조회, 방송 스트림키 조회, 방송 설정 조회·변경 권한을 요청합니다. 네 권한을 모두 허용해야 연결할 수 있으며, 채팅 권한은 요청하지 않습니다.")
                ),
                ConsentDocumentSection(
                    title: String(localized: "이용 목적"),
                    body: String(localized: "연결한 치지직 채널로 처리한 영상과 음성을 전송하고, 방송 제목·카테고리·태그와 방송 상태를 관리합니다.")
                ),
                ConsentDocumentSection(
                    title: String(localized: "치지직에서의 처리"),
                    body: String(localized: "치지직에 전송된 영상은 치지직(NAVER)의 정책과 사용자 채널 설정에 따라 처리됩니다. 설정에서 치지직 연결을 해제하면 InnoLive에 저장된 연결 토큰과 연결 정보를 삭제합니다. 방송 중에는 연결을 해제할 수 없습니다. 계정 삭제와 연결 해제는 치지직 채널의 과거 영상을 삭제하지 않습니다.")
                )
            ]
        }
    }
}
