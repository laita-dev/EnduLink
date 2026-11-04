package com.oscar.endulink.platform

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.oscar.endulink.bike.AndroidBikeKeysStorage

actual fun getPlatformBatteryStatus(): BatteryStatus {
    val ctx = AndroidBikeKeysStorage.appContext ?: return BatteryStatus(percentage = -1, isCharging = false)
    return try {
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) {
            (level * 100) / scale
        } else {
            -1
        }
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL
        BatteryStatus(percentage = percent, isCharging = isCharging)
    } catch (_: Exception) {
        BatteryStatus(percentage = -1, isCharging = false)
    }
}

