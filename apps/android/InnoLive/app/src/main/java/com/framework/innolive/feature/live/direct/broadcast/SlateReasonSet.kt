package com.framework.innolive.feature.live.direct.broadcast

/**
 * 대기 화면을 보내는 이유의 모음입니다. 이유마다 따로 켜고 끄므로, 일시 중지 중에 앱이 화면으로
 * 돌아와도 사용자가 재개하기 전까지 대기 화면이 유지됩니다.
 */
internal data class SlateReasonSet(val reasons: Set<SlateReason> = emptySet()) {
    /** 하나라도 남아 있으면 카메라 대신 대기 화면, 마이크 대신 무음을 보냅니다. */
    val isActive: Boolean
        get() = reasons.isNotEmpty()

    fun with(reason: SlateReason, active: Boolean): SlateReasonSet =
        SlateReasonSet(if (active) reasons + reason else reasons - reason)
}
