package com.framework.innolive.feature.live.direct.broadcast

import android.os.PowerManager
import com.framework.innolive.feature.live.direct.DeviceThermal
import com.framework.innolive.feature.live.direct.HeartbeatDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceHealthReaderTest {
    @Test fun mapsAndroidThermalStatusToContractLevels() {
        val expected = mapOf(
            PowerManager.THERMAL_STATUS_NONE to DeviceThermal.NOMINAL,
            PowerManager.THERMAL_STATUS_LIGHT to DeviceThermal.NOMINAL,
            PowerManager.THERMAL_STATUS_MODERATE to DeviceThermal.FAIR,
            PowerManager.THERMAL_STATUS_SEVERE to DeviceThermal.SERIOUS,
            PowerManager.THERMAL_STATUS_CRITICAL to DeviceThermal.CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY to DeviceThermal.CRITICAL,
            PowerManager.THERMAL_STATUS_SHUTDOWN to DeviceThermal.CRITICAL,
        )
        expected.forEach { (status, thermal) -> assertEquals(thermal, deviceThermalOf(status)) }
        assertNull(deviceThermalOf(-1))
        assertNull(deviceThermalOf(99))
    }

    @Test fun batteryPercentIgnoresUnsupportedValues() {
        assertEquals(1, batteryPercentOf(1))
        assertEquals(100, batteryPercentOf(100))
        assertNull(batteryPercentOf(0))
        assertNull(batteryPercentOf(Int.MIN_VALUE))
        assertNull(batteryPercentOf(101))
        assertNull(batteryPercentOf(null))
    }

    @Test fun deviceHealthIsNullWhenNothingWasRead() {
        assertNull(deviceHealthOf(null, Int.MIN_VALUE, null))
        assertEquals(
            HeartbeatDevice(thermal = DeviceThermal.SERIOUS, batteryPct = 42, charging = true),
            deviceHealthOf(DeviceThermal.SERIOUS, 42, true),
        )
        assertEquals(HeartbeatDevice(charging = false), deviceHealthOf(null, 0, false))
    }
}
