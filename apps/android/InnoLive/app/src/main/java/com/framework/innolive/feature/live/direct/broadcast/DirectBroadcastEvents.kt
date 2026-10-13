package com.framework.innolive.feature.live.direct.broadcast

import com.framework.innolive.feature.live.BroadcastEvent
import com.framework.innolive.feature.live.BroadcastFailure
import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.ServerApiException
import com.framework.innolive.feature.live.toBroadcastEvent
import com.framework.innolive.ui.text.serverErrorGuidance

/*
 * 직접 송출 상태 머신이 화면에 보내는 안내를 한곳에 모읍니다. 직접 송출 전용 문구(C8)가 들어오면
 * 이 파일의 대응만 바꿉니다. 지금은 기존 방송 안내 문구를 씁니다.
 */

/** 서버 응답이 아닌 앱 상황입니다. */
internal enum class DirectEvent {
    /** 준비 응답 주소로 송출을 시작하지 못했거나 필요한 설정이 없습니다. */
    PREPARE_FAILED,

    /** prepared 10분 제한 등으로 서버가 세션을 지웠습니다. 새 세션으로 다시 준비해야 합니다. */
    PREPARED_EXPIRED,

    /** 라이브 전환 전 송출 연결을 확인하지 못했습니다. */
    NOT_READY,

    /** 라이브 전환 중 stop이 먼저 처리됐습니다. */
    STOPPED_DURING_GO_LIVE,

    /** 서버가 방송을 끝냈습니다(한도, 입력 없음, 플랫폼에서 종료). */
    SERVER_STOPPED,

    /** 송출 연결이 끊겨 재연결을 포기했습니다. */
    CONNECTION_LOST,

    /** 플랫폼 연결을 열지 못했거나 송출 주소가 없습니다. */
    CONNECT_FAILED,

    REQUEST,
    ;

    fun toBroadcastEvent(): BroadcastEvent = BroadcastEvent.Failure(when (this) {
        PREPARE_FAILED -> BroadcastFailure.YOUTUBE_PREPARE
        PREPARED_EXPIRED -> BroadcastFailure.YOUTUBE_PREPARE
        NOT_READY -> BroadcastFailure.YOUTUBE_NOT_READY
        STOPPED_DURING_GO_LIVE, SERVER_STOPPED, CONNECTION_LOST -> BroadcastFailure.YOUTUBE_STOPPED
        CONNECT_FAILED, REQUEST -> BroadcastFailure.REQUEST
    })
}

/** 서버 오류를 화면 안내로 바꿉니다. 알려진 코드는 서버 안내 문구, 그 밖에는 요청 실패입니다. */
internal fun directServerEvent(exception: ServerApiException, provider: BroadcastProvider): BroadcastEvent =
    exception.toBroadcastEvent(BroadcastFailure.REQUEST, provider, preserveServerMessage = false)
        ?: DirectEvent.REQUEST.toBroadcastEvent()

/** golive `failed_targets[]`의 대상별 실패 코드입니다. */
internal fun directTargetFailureEvent(code: String?): BroadcastEvent =
    serverErrorGuidance(code)?.let(BroadcastEvent::ApiFailure) ?: DirectEvent.REQUEST.toBroadcastEvent()
