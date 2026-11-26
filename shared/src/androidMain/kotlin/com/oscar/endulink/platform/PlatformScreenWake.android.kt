package com.oscar.endulink.platform

import android.app.Activity
import android.view.WindowManager
import java.lang.ref.WeakReference

object AndroidScreenWakeHost {
    private var activityRef: WeakReference<Activity>? = null

    @Volatile
    private var desiredEnabled: Boolean = false

    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)
        applyToWindow(activity, desiredEnabled)
    }

    fun detach(activity: Activity) {
        if (activityRef?.get() === activity) {
            activityRef = null
        }
    }

    fun setEnabled(enabled: Boolean) {
        desiredEnabled = enabled
        val activity = activityRef?.get() ?: return
        applyToWindow(activity, enabled)
    }

    private fun applyToWindow(activity: Activity, enabled: Boolean) {
        activity.runOnUiThread {
            if (enabled) {
                activity.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

actual fun setPlatformKeepScreenOn(enabled: Boolean) {
    AndroidScreenWakeHost.setEnabled(enabled)
}
