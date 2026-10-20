package com.framework.innolive.feature.live.direct.broadcast

import com.framework.innolive.feature.live.BroadcastEvent
import com.framework.innolive.feature.live.BroadcastFailure
import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.ServerApiException
import com.framework.innolive.feature.live.chzzkBroadcastErrorMessage
import com.framework.innolive.feature.live.simulcastGoLiveFailedMessage
import com.framework.innolive.feature.live.toBroadcastEvent
import com.framework.innolive.ui.text.directPublishErrorGuidance

/*
 * 직접 송출 상태 머신이 화면에 보내는 안내를 한곳에 모읍니다. 서버 오류는 직접 송출 전용 안내
 * ([directGuidance])를 먼저 쓰고, 앱 상황은 [DirectBroadcastNotice]로 알립니다. 해당 문구가 없는
 * 상황만 기존 방송 안내 문구를 씁니다.
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

    fun toBroadcastEvent(): BroadcastEvent = when (this) {
        PREPARED_EXPIRED -> DirectBroadcastNotice.PreparedExpired.toBroadcastEvent()
        else -> BroadcastEvent.Failure(when (this) {
            PREPARE_FAILED -> BroadcastFailure.YOUTUBE_PREPARE
            NOT_READY -> BroadcastFailure.YOUTUBE_NOT_READY
            STOPPED_DURING_GO_LIVE, SERVER_STOPPED, CONNECTION_LOST, PREPARED_EXPIRED -> BroadcastFailure.YOUTUBE_STOPPED
            CONNECT_FAILED, REQUEST -> BroadcastFailure.REQUEST
        })
    }
}

/** 재연결을 포기해 [provider] 방송을 끝냈습니다. */
internal fun directConnectionLostEvent(provider: BroadcastProvider): BroadcastEvent =
    DirectBroadcastNotice.ConnectionLost(provider).toBroadcastEvent()

/** 서버가 끝낸 대상입니다. 한 플랫폼이면 그 이름으로, 여럿이면 방송 종료 안내로 알립니다. */
internal fun directTargetsStoppedEvent(providers: List<BroadcastProvider>): BroadcastEvent =
    providers.singleOrNull()?.let { DirectBroadcastNotice.TargetStoppedByServer(it).toBroadcastEvent() }
        ?: DirectEvent.SERVER_STOPPED.toBroadcastEvent()

/**
 * 서버 오류를 화면 안내로 바꿉니다. 직접 송출 전용 안내가 있으면 그것을 쓰고, 치지직 설정 입력 오류·요청 한도처럼
 * 치지직 문구가 있는 경우와 알 수 없는 코드는 기존 변환을 따릅니다.
 */
internal fun directServerEvent(exception: ServerApiException, provider: BroadcastProvider): BroadcastEvent {
    val chzzkSpecific = provider == BroadcastProvider.CHZZK && exception.code in CHZZK_SPECIFIC_CODES
    if (!chzzkSpecific) exception.directGuidance()?.let { return BroadcastEvent.ApiFailure(it) }
    return exception.toBroadcastEvent(BroadcastFailure.REQUEST, provider, preserveServerMessage = false)
        ?: DirectEvent.REQUEST.toBroadcastEvent()
}

// 공통 안내 문구가 YouTube 기준이라 치지직에서는 치지직 문구를 씁니다.
private val CHZZK_SPECIFIC_CODES = setOf("bad_request", "streaming_rate_limited")

/**
 * golive `failed_targets[]`의 대상별 실패 코드입니다. 치지직 요청 한도는 치지직 문구로 알리고,
 * 다른 대상이 이어지는 YouTube 요청 한도는 동시 송출 문구로 알립니다.
 */
internal fun directTargetFailureEvent(provider: String, code: String?, othersContinue: Boolean = false): BroadcastEvent {
    val rateLimited = code == "streaming_rate_limited"
    return when {
        rateLimited && provider == BroadcastProvider.CHZZK.wireValue ->
            BroadcastEvent.Message(chzzkBroadcastErrorMessage(code, null))
        rateLimited && provider == BroadcastProvider.YOUTUBE.wireValue && othersContinue ->
            BroadcastEvent.Message(simulcastGoLiveFailedMessage(mapOf(BroadcastProvider.YOUTUBE to "streaming_rate_limited")))
        else -> directPublishErrorGuidance(code)?.let(BroadcastEvent::ApiFailure) ?: DirectEvent.REQUEST.toBroadcastEvent()
    }
}
