package com.framework.innolive.feature.live.tutorial

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

interface BroadcastTutorialStore {
    var hasFinishedPreparationGuide: Boolean
    var hasSeenLiveStatusTip: Boolean
}

internal class BroadcastTutorialPreferences(context: Context) : BroadcastTutorialStore {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override var hasFinishedPreparationGuide: Boolean
        get() = preferences.getBoolean(KEY_PREPARATION_GUIDE, false)
        set(value) { preferences.edit().putBoolean(KEY_PREPARATION_GUIDE, value).apply() }

    override var hasSeenLiveStatusTip: Boolean
        get() = preferences.getBoolean(KEY_LIVE_STATUS_TIP, false)
        set(value) { preferences.edit().putBoolean(KEY_LIVE_STATUS_TIP, value).apply() }

    private companion object {
        const val PREFERENCES_NAME = "broadcast_tutorial"
        const val KEY_PREPARATION_GUIDE = "broadcast_preparation_v1"
        const val KEY_LIVE_STATUS_TIP = "live_status_v1"
    }
}

class BroadcastTutorialCoordinator(private val store: BroadcastTutorialStore) {
    var stage by mutableStateOf<BroadcastTutorialStage?>(null)
        private set
    var progress by mutableStateOf<BroadcastTutorialProgress?>(null)
        private set
    var isShowingLiveStatusTip by mutableStateOf(false)
        private set

    private var isRunning = false
    private var includesAccountStep = true
    private var hadStartedBroadcast = false
    private var lastSnapshot = BroadcastTutorialSnapshot()

    /** 홈은 Dialog 상태를 모르므로 방송 화면이 마지막으로 알려 준 상태로 시작한다. */
    fun startIfNeeded() = startIfNeeded(lastSnapshot)

    fun restart() = restart(lastSnapshot)

    /** 처음 쓰는 사용자가 아무 방송도 준비하지 않은 상태일 때만 자동으로 시작한다. */
    fun startIfNeeded(snapshot: BroadcastTutorialSnapshot) {
        if (isRunning || store.hasFinishedPreparationGuide) return
        if (BroadcastTutorialPolicy.stage(snapshot)?.step != BroadcastTutorialStep.OPEN_PREPARATION) return
        isRunning = true
        update(snapshot)
    }

    /** 사용자가 직접 다시 보기를 고르면 지금 상태에 맞는 단계부터 바로 보여 준다. */
    fun restart(snapshot: BroadcastTutorialSnapshot) {
        store.hasFinishedPreparationGuide = false
        isRunning = true
        update(snapshot)
    }

    fun update(snapshot: BroadcastTutorialSnapshot) {
        lastSnapshot = snapshot
        // 준비 안내가 라이브 시작으로 끝난 뒤에 상태 패널 안내를 판단한다.
        if (isRunning) advanceGuide(snapshot)
        updateLiveStatusTip(snapshot)
    }

    fun skip() = finish()

    fun finish() {
        store.hasFinishedPreparationGuide = true
        isRunning = false
        stage = null
        progress = null
    }

    fun dismissLiveStatusTip() {
        store.hasSeenLiveStatusTip = true
        isShowingLiveStatusTip = false
    }

    private fun advanceGuide(snapshot: BroadcastTutorialSnapshot) {
        val next = BroadcastTutorialPolicy.stage(snapshot) ?: run {
            finish()
            return
        }
        // 계정 연결 단계 수는 첫 단계에서만 정한다. 연결을 마친 뒤에도 전체 단계 수가 바뀌지 않게 한다.
        when (next.step) {
            BroadcastTutorialStep.OPEN_PREPARATION -> includesAccountStep = !snapshot.selectedAccountConnected
            BroadcastTutorialStep.CONNECT_ACCOUNT -> includesAccountStep = true
            else -> Unit
        }
        if (stage != next) stage = next
        val nextProgress = BroadcastTutorialPolicy.progress(next.step, includesAccountStep)
        if (progress != nextProgress) progress = nextProgress
    }

    private fun updateLiveStatusTip(snapshot: BroadcastTutorialSnapshot) {
        val wasStarted = hadStartedBroadcast
        hadStartedBroadcast = snapshot.hasStartedBroadcast
        // 안내 없이 라이브를 시작한 사용자도 방송 준비를 마친 것으로 보고 다음 실행에 안내를 다시 띄우지 않는다.
        if (snapshot.hasStartedBroadcast && !store.hasFinishedPreparationGuide) {
            store.hasFinishedPreparationGuide = true
        }
        if (!snapshot.hasStartedBroadcast) {
            if (isShowingLiveStatusTip) isShowingLiveStatusTip = false
            return
        }
        if (!wasStarted && !isRunning && !store.hasSeenLiveStatusTip) {
            isShowingLiveStatusTip = true
        }
    }
}
