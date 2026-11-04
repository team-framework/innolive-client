import SwiftUI

struct MediaTransmissionConsentView: View {
    let onAccept: (SignupConsent) -> Void

    var body: some View {
        ConsentDocumentSheet(
            navigationTitle: String(localized: "방송 영상·음성 전송 동의"),
            sections: [
                ConsentDocumentSection(
                    title: String(localized: "전송하는 방송 정보"),
                    body: String(localized: "카메라와 마이크로 수집한 방송 영상·음성을 서버로 전송합니다. 비식별화 처리를 위해 AI 서비스가 이 영상·음성을 처리·중계합니다.")
                ),
                ConsentDocumentSection(
                    title: String(localized: "이용 목적"),
                    body: String(localized: "등록 얼굴과 다른 얼굴을 구분하고, 방송 영상에서 다른 얼굴을 비식별화하기 위해 이용합니다.")
                ),
                ConsentDocumentSection(
                    title: String(localized: "보유 및 처리"),
                    body: String(localized: "AI 서비스는 처리 중 얼굴 특징값을 메모리에 보관합니다. 보관한 얼굴 특징값은 등록 얼굴을 삭제하거나 계정 삭제가 완료되거나 AI 서비스가 종료될 때 정리합니다. 기준 사진 원본은 별도로 보관하지 않습니다.")
                )
            ],
            closingNotice: String(localized: "동의하지 않으면 카메라와 마이크를 서버에 연결하지 않습니다. 개인정보 관련 문의와 동의 철회는 개인정보처리방침의 연락처로 요청할 수 있습니다."),
            scrollHint: String(localized: "내용을 끝까지 스크롤하면 동의할 수 있습니다."),
            acceptTitle: String(localized: "동의하고 계속"),
            acceptIdentifier: "mediaTransmissionConsent.continue",
            onAccept: onAccept
        )
    }
}
