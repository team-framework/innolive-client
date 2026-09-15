package com.framework.innolive.feature.live

// 화면에 허용한 안내만 전달하여 서버·라이브러리의 내부 오류가 노출되지 않게 합니다.
internal fun connectionUserMessage(state: WebRtcConnectionState, detail: String): String = when (state) {
    WebRtcConnectionState.IDLE -> ""
    WebRtcConnectionState.CONNECTED -> "미리보기 연결됨"
    WebRtcConnectionState.CONNECTING -> "미리보기 연결 중…"
    WebRtcConnectionState.FAILED -> when (detail) {
        "WebRTC 연결 시간이 초과되었습니다." -> "연결이 지연되고 있습니다. 다시 시도해 주세요."
        "WebRTC 연결이 끊겼습니다.", "WebRTC signaling 연결이 종료되었습니다." ->
            "연결이 끊겼습니다. 다시 연결해 주세요."
        "INNOLIVE_SERVER_URL must use HTTPS." -> "연결 설정에 문제가 있습니다. 관리자에게 문의해 주세요."
        "선택한 Bluetooth 오디오 기기의 통신용 출력을 찾지 못했습니다.",
        "선택한 Bluetooth 오디오 기기를 통신 장치로 설정하지 못했습니다.",
        "선택한 오디오 기기를 실제 입력으로 적용하지 못했습니다.",
        "Bluetooth 오디오 기기를 준비하지 못했습니다." ->
            "선택한 마이크를 사용할 수 없습니다. 다른 마이크를 선택해 주세요."
        "오디오 입력이 중지되었습니다.", "다른 앱 또는 시스템 정책으로 마이크 입력이 차단되었습니다." ->
            "마이크를 사용할 수 없습니다. 권한과 다른 앱의 마이크 사용 여부를 확인해 주세요."
        else -> "미리보기를 연결하지 못했습니다. 다시 시도해 주세요."
    }
}

internal fun broadcastUserMessage(detail: String): String = when (detail) {
    "WebRTC 세션이 없습니다.", "WebRTC 연결이 종료되었습니다." -> "미리보기를 먼저 연결해 주세요."
    "아동용 콘텐츠 여부를 선택해 주세요.",
    "YouTube 계정을 먼저 연결해 주세요.",
    "YouTube 라이브 기능을 먼저 활성화해 주세요.",
    "YouTube 계정을 다시 연결해 주세요.",
    "YouTube 방송을 준비하지 못했습니다.",
    "라이브 전환 중 방송이 종료되었습니다.",
    "YouTube가 아직 영상을 받을 준비가 되지 않았습니다. 잠시 후 다시 시도해 주세요." -> detail
    else -> "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."
}
