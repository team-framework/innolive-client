package com.framework.innolive.feature.live

import org.junit.Assert.*
import org.junit.Test

class LiveUserMessageTest {
    @Test fun idleIsHiddenAndProgressDoesNotExposeTechnicalDetails() {
        assertEquals("", connectionUserMessage(WebRtcConnectionState.IDLE, "WebRTC 연결 대기"))
        assertEquals("미리보기 연결 중…", connectionUserMessage(WebRtcConnectionState.CONNECTING, "ICE candidate"))
        assertEquals("미리보기 연결됨", connectionUserMessage(WebRtcConnectionState.CONNECTED, "WebRTC 연결됨"))
    }

    @Test fun arbitraryServerAndLibraryErrorsNeverReachScreen() {
        for (detail in listOf("ICE candidate", "HTTP 500", "WebRTC offer", "token expired", "임의 서버 오류 내용")) {
            assertEquals("미리보기를 연결하지 못했습니다. 다시 시도해 주세요.", connectionUserMessage(WebRtcConnectionState.FAILED, detail))
            assertEquals("요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.", broadcastUserMessage(detail))
        }
    }

    @Test fun actionableFailuresKeepUserGuidance() {
        assertEquals("연결이 지연되고 있습니다. 다시 시도해 주세요.", connectionUserMessage(WebRtcConnectionState.FAILED, "WebRTC 연결 시간이 초과되었습니다."))
        assertEquals("선택한 마이크를 사용할 수 없습니다. 다른 마이크를 선택해 주세요.", connectionUserMessage(WebRtcConnectionState.FAILED, "선택한 오디오 기기를 실제 입력으로 적용하지 못했습니다."))
        assertEquals("YouTube 계정을 다시 연결해 주세요.", broadcastUserMessage("YouTube 계정을 다시 연결해 주세요."))
        val existingSession = "이미 활성화된 방송 세션이 있습니다. 기존 방송을 종료한 뒤 다시 시도해 주세요."
        assertEquals(existingSession, connectionUserMessage(WebRtcConnectionState.FAILED, existingSession))
    }
}
