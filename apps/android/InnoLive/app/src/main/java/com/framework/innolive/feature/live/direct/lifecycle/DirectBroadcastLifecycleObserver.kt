package com.framework.innolive.feature.live.direct.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.framework.innolive.feature.live.direct.broadcast.DirectMediaControl
import com.framework.innolive.feature.live.direct.broadcast.SlateReason

/**
 * 앱이 백그라운드로 가거나 화면이 꺼지면 [SlateReason.BACKGROUND] 대기 화면을 켜고, 돌아오면 끕니다.
 *
 * 프로세스 수명 주기(`ProcessLifecycleOwner`)에 붙여 화면 회전처럼 Activity만 다시 만들어지는 경우에는
 * 대기 화면으로 바뀌지 않게 합니다. 메인 스레드에서만 씁니다.
 */
internal class DirectBroadcastLifecycleObserver(
    private val control: DirectMediaControl,
) : DefaultLifecycleObserver {
    private var lifecycle: Lifecycle? = null
    private var backgrounded = false

    /** 수명 주기에 붙입니다. 이미 백그라운드면 바로 대기 화면을 켭니다. */
    fun attach(lifecycle: Lifecycle) {
        if (this.lifecycle === lifecycle) return
        detach()
        this.lifecycle = lifecycle
        lifecycle.addObserver(this)
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) setBackground(true)
    }

    /** 방송이 끝나면 떼어 냅니다. 켜 둔 대기 화면 이유도 지웁니다. */
    fun detach() {
        lifecycle?.removeObserver(this)
        lifecycle = null
        setBackground(false)
    }

    override fun onStart(owner: LifecycleOwner) = setBackground(false)

    override fun onStop(owner: LifecycleOwner) = setBackground(true)

    private fun setBackground(value: Boolean) {
        if (backgrounded == value) return
        backgrounded = value
        control.setSlate(SlateReason.BACKGROUND, value)
    }
}
