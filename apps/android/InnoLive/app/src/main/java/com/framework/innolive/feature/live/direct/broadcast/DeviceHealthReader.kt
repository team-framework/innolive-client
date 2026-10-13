package com.framework.innolive.feature.live.direct.broadcast

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import com.framework.innolive.feature.live.direct.DeviceThermal
import com.framework.innolive.feature.live.direct.HeartbeatDevice

/**
 * 하트비트에 넣을 기기 상태입니다. 발열은 [PowerManager.getCurrentThermalStatus], 배터리는
 * [BatteryManager]에서 읽습니다. CPU 사용률은 앱이 신뢰할 수 있게 읽을 수 없어 보내지 않습니다.
 */
internal class DeviceHealthReader(context: Context) {
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val batteryManager = context.getSystemService(BatteryManager::class.java)

    /** 읽은 값이 하나도 없으면 null입니다. */
    fun read(): HeartbeatDevice? = deviceHealthOf(
        thermal = powerManager?.let { deviceThermalOf(it.currentThermalStatus) },
        batteryCapacity = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
        charging = batteryManager?.isCharging,
    )
}

/** Android 발열 단계를 계약의 4단계로 바꿉니다. 알 수 없는 값은 보내지 않습니다. */
internal fun deviceThermalOf(status: Int): DeviceThermal? = when (status) {
    PowerManager.THERMAL_STATUS_NONE,
    PowerManager.THERMAL_STATUS_LIGHT -> DeviceThermal.NOMINAL
    PowerManager.THERMAL_STATUS_MODERATE -> DeviceThermal.FAIR
    PowerManager.THERMAL_STATUS_SEVERE -> DeviceThermal.SERIOUS
    PowerManager.THERMAL_STATUS_CRITICAL,
    PowerManager.THERMAL_STATUS_EMERGENCY,
    PowerManager.THERMAL_STATUS_SHUTDOWN -> DeviceThermal.CRITICAL
    else -> null
}

/** 지원하지 않는 기기는 0이나 `Int.MIN_VALUE`를 돌려주므로 1~100만 잔량으로 봅니다. */
internal fun batteryPercentOf(capacity: Int?): Int? = capacity?.takeIf { it in 1..100 }

internal fun deviceHealthOf(thermal: DeviceThermal?, batteryCapacity: Int?, charging: Boolean?): HeartbeatDevice? {
    val batteryPct = batteryPercentOf(batteryCapacity)
    if (thermal == null && batteryPct == null && charging == null) return null
    return HeartbeatDevice(thermal = thermal, batteryPct = batteryPct, charging = charging)
}
