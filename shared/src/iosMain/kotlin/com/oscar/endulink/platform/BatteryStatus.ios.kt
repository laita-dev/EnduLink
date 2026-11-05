package com.oscar.endulink.platform

import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryStateCharging
import platform.UIKit.UIDeviceBatteryStateFull
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
        val isCharging = state == UIDeviceBatteryStateCharging ||
                         state == UIDeviceBatteryStateFull
        BatteryStatus(percentage = percent, isCharging = isCharging)
    } catch (_: Throwable) {
        BatteryStatus(percentage = -1, isCharging = false)
    }
}

