package com.framework.innolive.feature.live.direct.lifecycle

import android.content.Context
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** 기기 발열이 SEVERE 이상이면 경고합니다. 인코딩 조정은 이번 범위 밖입니다. */
internal fun isThermalWarning(status: Int): Boolean = status >= PowerManager.THERMAL_STATUS_SEVERE

/**
 * 발열 상태를 듣고 경고 여부가 바뀔 때만 [onChanged]를 부릅니다(메인 스레드).
 * 상태 머신이 라이브 동안 [start]·[close]하고 결과를 `DirectBroadcastUiState.thermalWarning`에 넣습니다.
 */
internal class ThermalWarningMonitor(
    context: Context,
    private val onChanged: (Boolean) -> Unit,
) : AutoCloseable {
    private val context = context.applicationContext
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private var warning: Boolean? = null
    private val listener = PowerManager.OnThermalStatusChangedListener { status -> update(isThermalWarning(status)) }
    private var started = false

    fun start() {
        val manager = powerManager ?: return
        if (started) return
        started = true
        // 등록 즉시 현재 상태가 한 번 전달됩니다.
        manager.addThermalStatusListener(ContextCompat.getMainExecutor(context), listener)
    }

    override fun close() {
        if (!started) return
        started = false
        powerManager?.removeThermalStatusListener(listener)
        warning = null
    }

    private fun update(value: Boolean) {
        if (warning == value) return
        warning = value
        onChanged(value)
    }
}
