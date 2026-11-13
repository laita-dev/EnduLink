package com.oscar.endulink.platform

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.oscar.endulink.bike.AndroidBikeKeysStorage

actual fun showPlatformToast(message: String) {
    val ctx = AndroidBikeKeysStorage.appContext ?: return
    if (Looper.myLooper() == Looper.getMainLooper()) {
        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
    } else {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        }
    }
}

actual fun closeApp() {
    val ctx = AndroidBikeKeysStorage.appContext ?: return
    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    ctx.startActivity(intent)
}

