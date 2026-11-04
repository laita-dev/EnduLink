package com.oscar.endulink.platform

data class BatteryStatus(
    val percentage: Int = -1,
    val isCharging: Boolean = false
)

expect fun getPlatformBatteryStatus(): BatteryStatus

