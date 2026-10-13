package com.framework.innolive.feature.live.direct.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.framework.innolive.feature.live.direct.broadcast.SlateReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectBroadcastLifecycleObserverTest {
    private val calls = mutableListOf<Pair<SlateReason, Boolean>>()
    private val observer = DirectBroadcastLifecycleObserver { reason, active -> calls += reason to active }

    @Test fun backgroundAndReturnToggleBackgroundSlate() {
        val lifecycle = FakeLifecycle(Lifecycle.State.RESUMED)
        observer.attach(lifecycle)

        lifecycle.moveTo(Lifecycle.State.CREATED)
        lifecycle.moveTo(Lifecycle.State.RESUMED)

        assertEquals(
            listOf(SlateReason.BACKGROUND to true, SlateReason.BACKGROUND to false),
            calls,
        )
    }

    @Test fun attachingWhileInBackgroundStartsWithSlate() {
        observer.attach(FakeLifecycle(Lifecycle.State.CREATED))

        assertEquals(listOf(SlateReason.BACKGROUND to true), calls)
    }

    @Test fun repeatedStopDoesNotRepeatSlate() {
        val lifecycle = FakeLifecycle(Lifecycle.State.RESUMED)
        observer.attach(lifecycle)

        lifecycle.moveTo(Lifecycle.State.CREATED)
        lifecycle.moveTo(Lifecycle.State.CREATED)

        assertEquals(listOf(SlateReason.BACKGROUND to true), calls)
    }

    @Test fun detachClearsSlateAndStopsListening() {
        val lifecycle = FakeLifecycle(Lifecycle.State.RESUMED)
        observer.attach(lifecycle)
        lifecycle.moveTo(Lifecycle.State.CREATED)

        observer.detach()
        lifecycle.moveTo(Lifecycle.State.RESUMED)
        lifecycle.moveTo(Lifecycle.State.CREATED)

        assertEquals(
            listOf(SlateReason.BACKGROUND to true, SlateReason.BACKGROUND to false),
            calls,
        )
        assertTrue(lifecycle.observers.isEmpty())
    }

    /** 시작·멈춤 이벤트만 흉내 내는 수명 주기입니다. */
    private class FakeLifecycle(initial: State) : Lifecycle() {
        val observers = mutableListOf<LifecycleObserver>()
        private val owner = object : LifecycleOwner {
            override val lifecycle: Lifecycle get() = this@FakeLifecycle
        }
        private var state = initial

        override val currentState: State get() = state

        override fun addObserver(observer: LifecycleObserver) {
            observers += observer
        }

        override fun removeObserver(observer: LifecycleObserver) {
            observers -= observer
        }

        fun moveTo(next: State) {
            val wasStarted = state.isAtLeast(State.STARTED)
            state = next
            val started = next.isAtLeast(State.STARTED)
            observers.filterIsInstance<DefaultLifecycleObserver>().forEach {
                when {
                    !wasStarted && started -> it.onStart(owner)
                    wasStarted && !started -> it.onStop(owner)
                }
            }
        }
    }
}
