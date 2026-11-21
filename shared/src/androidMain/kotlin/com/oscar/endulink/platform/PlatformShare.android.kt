package com.oscar.endulink.platform

import android.content.Intent
import com.oscar.endulink.bike.AndroidBikeKeysStorage

actual fun sharePlainText(subject: String, text: String) {
    val ctx = AndroidAppContext.appContext ?: AndroidBikeKeysStorage.appContext ?: return
    try {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(sendIntent, subject).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(chooser)
    } catch (e: Exception) {
        showPlatformToast("No se pudo compartir: ${e.message}")
    }
}
