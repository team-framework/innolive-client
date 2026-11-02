package com.framework.innolive.feature.youtube

import java.util.concurrent.atomic.AtomicLong

/**
 * 가장 최근 비동기 작업을 식별해, 먼저 끝난 이전 요청이 로그아웃·계정 교체·재생성 뒤에
 * 상태를 반영하지 못하게 합니다.
 */
internal class OperationGeneration(initialValue: Long = 0L) {
    private val value = AtomicLong(initialValue)

    val current: Long
        get() = value.get()

    fun begin(): Long = value.incrementAndGet()

    fun invalidate(): Long = value.incrementAndGet()

    fun isCurrent(operation: Long): Boolean = value.get() == operation
}
