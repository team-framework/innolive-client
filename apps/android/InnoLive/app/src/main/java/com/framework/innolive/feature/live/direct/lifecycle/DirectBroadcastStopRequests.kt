package com.framework.innolive.feature.live.direct.lifecycle

import java.util.concurrent.atomic.AtomicReference

/**
 * 알림의 "방송 종료"를 진행 중인 방송에 전달합니다. 서비스는 송출 경로를 모르므로 라이브 동안
 * 등록된 처리기 하나에 넘깁니다.
 */
internal class DirectBroadcastStopRequests {
    private class Registration(val handler: () -> Unit)

    private val current = AtomicReference<Registration?>(null)

    /** 처리기를 등록합니다. 돌려준 값을 닫으면 그 등록만 지우고, 나중에 등록된 처리기는 남깁니다. */
    fun register(handler: () -> Unit): AutoCloseable {
        val registration = Registration(handler)
        current.set(registration)
        return AutoCloseable { current.compareAndSet(registration, null) }
    }

    /** 처리기가 있으면 부르고 true를 돌려줍니다. */
    fun request(): Boolean {
        val registration = current.get() ?: return false
        registration.handler()
        return true
    }

    companion object {
        val shared = DirectBroadcastStopRequests()
    }
}
