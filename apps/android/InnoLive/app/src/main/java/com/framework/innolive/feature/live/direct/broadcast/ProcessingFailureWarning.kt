package com.framework.innolive.feature.live.direct.broadcast

/**
 * 연결 뒤 가림 처리 실패가 이어질 때만 화면 경고를 켭니다. 송출은 유지하고 실패한 프레임만 버립니다.
 * 한 번의 일시 실패로 경고가 깜빡이지 않도록 [showAfterMillis] 동안 실패가 이어져야 켜고,
 * [clearAfterMillis] 동안 실패가 없으면 처리가 회복된 것으로 보고 끕니다.
 * 시각은 `elapsedRealtime` 기준이며 상태 머신 스레드에서만 씁니다.
 */
internal class ProcessingFailureWarning(
    private val showAfterMillis: Long = SHOW_AFTER_MILLIS,
    private val clearAfterMillis: Long = CLEAR_AFTER_MILLIS,
) {
    private var streakStartedAt: Long? = null
    private var lastFailureAt: Long? = null

    var active = false
        private set

    /** 실패가 끝났는지 다시 확인해야 하면 true입니다. */
    val pending: Boolean get() = lastFailureAt != null

    /** 경고 표시가 바뀌면 true를 돌려줍니다. */
    fun onFailure(nowMillis: Long): Boolean {
        val last = lastFailureAt
        if (last == null || nowMillis - last >= clearAfterMillis) streakStartedAt = nowMillis
        lastFailureAt = nowMillis
        return update(nowMillis)
    }

    /** 실패가 멈췄는지 확인합니다. 경고 표시가 바뀌면 true를 돌려줍니다. */
    fun check(nowMillis: Long): Boolean = update(nowMillis)

    private fun update(nowMillis: Long): Boolean {
        val last = lastFailureAt
        val started = streakStartedAt
        val next = if (last == null || started == null || nowMillis - last >= clearAfterMillis) {
            lastFailureAt = null
            streakStartedAt = null
            false
        } else {
            nowMillis - started >= showAfterMillis
        }
        val changed = next != active
        active = next
        return changed
    }

    companion object {
        const val SHOW_AFTER_MILLIS = 3_000L
        const val CLEAR_AFTER_MILLIS = 2_000L
        /** 실패가 멈췄는지 확인하는 간격입니다. */
        const val CHECK_INTERVAL_MILLIS = 1_000L
    }
}
