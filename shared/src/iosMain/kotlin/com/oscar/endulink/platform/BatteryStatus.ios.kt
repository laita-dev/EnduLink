package com.oscar.endulink.platform

import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState
import kotlin.math.roundToInt

actual fun getPlatformBatteryStatus(): BatteryStatus {
    return try {
        val device = UIDevice.currentDevice
        if (!device.batteryMonitoringEnabled) {
            device.batteryMonitoringEnabled = true
        }
        val level = device.batteryLevel
        val percent = if (level >= 0f) (level * 100f).roundToInt() else -1
        val state = device.batteryState
        val isCharging = state == UIDeviceBatteryState.UIDeviceBatteryStateCharging ||
                         state == UIDeviceBatteryState.UIDeviceBatteryStateFull
        BatteryStatus(percentage = percent, isCharging = isCharging)
    } catch (_: Exception) {
        BatteryStatus(percentage = -1, isCharging = false)
    }
}

