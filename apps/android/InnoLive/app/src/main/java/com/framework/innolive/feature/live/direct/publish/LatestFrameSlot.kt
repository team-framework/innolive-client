package com.framework.innolive.feature.live.direct.publish

internal enum class SlotOffer { ACCEPTED, REPLACED, REJECTED }

/**
 * 그리기를 기다리는 최신 항목 하나입니다. 넣기·꺼내기·닫기를 한 잠금으로 묶어, 닫힌 뒤 들어온 항목이나
 * 밀려난 항목을 [release]로 바로 돌려줍니다. 가림 처리 출력 텍스처는 동시에 빌릴 수 있는 수가 정해져
 * 있어 참조가 하나라도 남으면 이후 프레임이 막힙니다.
 */
internal class LatestFrameSlot<T : Any>(private val release: (T) -> Unit) {
    private val lock = Any()
    private var pending: T? = null
    private var closed = false

    /** 닫혔으면 [item]을 해제하고 [SlotOffer.REJECTED]를 돌려줍니다. */
    fun offer(item: T): SlotOffer {
        val displaced: T?
        synchronized(lock) {
            if (closed) {
                displaced = item
            } else {
                displaced = pending
                pending = item
            }
        }
        displaced?.let(release)
        return when {
            displaced === item -> SlotOffer.REJECTED
            displaced != null -> SlotOffer.REPLACED
            else -> SlotOffer.ACCEPTED
        }
    }

    fun take(): T? = synchronized(lock) { pending.also { pending = null } }

    /** 그리기 작업을 예약하지 못했을 때 [item]이 아직 남아 있으면 해제합니다. */
    fun discard(item: T) {
        val removed = synchronized(lock) {
            if (pending === item) {
                pending = null
                true
            } else false
        }
        if (removed) release(item)
    }

    fun close() {
        val remaining = synchronized(lock) {
            closed = true
            pending.also { pending = null }
        }
        remaining?.let(release)
    }
}
