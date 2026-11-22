package com.oscar.endulink.platform

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.oscar.endulink.bike.AndroidBikeKeysStorage

private data class AppNotificationStyle(
    val appName: String,
    val category: TftNotificationCategory,
    val accentArgb: Long
)

class EnduNotificationListenerService : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        if (AndroidAppContext.appContext == null) {
            AndroidAppContext.appContext = applicationContext
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (AndroidAppContext.appContext == null) {
            AndroidAppContext.appContext = applicationContext
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!PhoneNotificationCenter.enabledOnTft) return

        val ownPkg = applicationContext?.packageName
        if (ownPkg != null && sbn.packageName == ownPkg && sbn.id == BikeForegroundService.NOTIFICATION_ID) {
            return
        }

        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val isCall = notification.category == Notification.CATEGORY_CALL
        if ((sbn.isOngoing || (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) && !isCall) {
            return
        }

        val pkg = sbn.packageName ?: return
        if (pkg == "android" || pkg == "com.android.systemui" || pkg == "com.android.providers.downloads") {
            return
        }

        val extras = notification.extras
        if (extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true ||
            notification.category == Notification.CATEGORY_TRANSPORT
        ) {
            return
        }

        val convTitle = extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()
        val rawTitle = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val bigTitle = extras?.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.trim().orEmpty()
        val title = when {
            convTitle.isNotEmpty() && rawTitle.isNotEmpty() && !rawTitle.equals(convTitle, ignoreCase = true) ->
                "$convTitle: $rawTitle"
            convTitle.isNotEmpty() -> convTitle
            rawTitle.isNotEmpty() -> rawTitle
            else -> bigTitle
        }

        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val baseText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val ticker = notification.tickerText?.toString()?.trim().orEmpty()
        val text = bigText.ifEmpty { baseText }.ifEmpty { subText }.ifEmpty { ticker }

        if (title.isBlank() && text.isBlank()) return

        val style = resolveAppStyle(pkg, notification.category, ownPkg, title)
        PhoneNotificationCenter.postNotification(
            appName = style.appName,
            title = title,
            text = text,
            category = style.category,
            accentArgb = style.accentArgb,
            key = sbn.key
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.notification?.category == Notification.CATEGORY_CALL) {
            PhoneNotificationCenter.dismissByKey(sbn.key)
        }
    }

    private fun resolveAppStyle(
        packageName: String,
        notificationCategory: String?,
        ownPkg: String?,
        title: String
    ): AppNotificationStyle {
        val pkg = packageName.lowercase()
        return when {
            pkg == "com.whatsapp" -> AppNotificationStyle("WhatsApp", TftNotificationCategory.MESSAGE, 0xFF25D366L)
            pkg == "com.whatsapp.w4b" -> AppNotificationStyle("WhatsApp Bus.", TftNotificationCategory.MESSAGE, 0xFF25D366L)
            pkg.startsWith("org.telegram") || pkg == "tw.nekomimi.nekogram" ->
                AppNotificationStyle("Telegram", TftNotificationCategory.MESSAGE, 0xFF2AABEEL)
            pkg == "com.google.android.apps.messaging" ||
                pkg == "com.samsung.android.messaging" ||
                pkg == "com.android.mms" ->
                AppNotificationStyle("Mensajes", TftNotificationCategory.MESSAGE, 0xFF00D4FFL)
            pkg == "com.facebook.orca" -> AppNotificationStyle("Messenger", TftNotificationCategory.MESSAGE, 0xFF0084FFL)
            pkg == "com.instagram.android" -> AppNotificationStyle("Instagram", TftNotificationCategory.MESSAGE, 0xFFE1306CL)
            pkg == "org.thoughtcrime.securesms" -> AppNotificationStyle("Signal", TftNotificationCategory.MESSAGE, 0xFF3A76F0L)
            pkg == "com.google.android.gm" || pkg == "com.microsoft.office.outlook" ->
                AppNotificationStyle("Correo", TftNotificationCategory.APP, 0xFFEA4335L)
            notificationCategory == Notification.CATEGORY_CALL ||
                pkg == "com.google.android.dialer" ||
                pkg == "com.samsung.android.dialer" ||
                pkg == "com.android.incallui" ||
                pkg == "com.android.dialer" ->
                AppNotificationStyle("Llamada", TftNotificationCategory.CALL, 0xFF30D158L)
            pkg == "com.waze" -> AppNotificationStyle("Waze", TftNotificationCategory.NAVIGATION, 0xFF00D4FFL)
            pkg == "com.google.android.apps.maps" -> AppNotificationStyle("Maps", TftNotificationCategory.NAVIGATION, 0xFF00D4FFL)
            ownPkg != null && packageName == ownPkg -> {
                val isUrgent = title.contains("SOS", ignoreCase = true) || title.contains("Aver", ignoreCase = true)
                AppNotificationStyle(
                    appName = "EnduLink",
                    category = if (isUrgent) TftNotificationCategory.ALERT else TftNotificationCategory.APP,
                    accentArgb = if (isUrgent) 0xFFFF3B30L else 0xFFFF6B00L
                )
            }
            else -> {
                val label = resolvePackageLabel(packageName)
                val cat = when (notificationCategory) {
                    Notification.CATEGORY_CALL -> TftNotificationCategory.CALL
                    Notification.CATEGORY_MESSAGE,
                    Notification.CATEGORY_SOCIAL -> TftNotificationCategory.MESSAGE
                    Notification.CATEGORY_NAVIGATION -> TftNotificationCategory.NAVIGATION
                    Notification.CATEGORY_ALARM,
                    Notification.CATEGORY_ERROR -> TftNotificationCategory.ALERT
                    else -> TftNotificationCategory.APP
                }
                AppNotificationStyle(label, cat, cat.defaultAccentArgb)
            }
        }
    }

    private fun resolvePackageLabel(packageName: String): String {
        return try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString().trim().ifEmpty {
                packageName.substringAfterLast('.')
            }
        } catch (_: Exception) {
            packageName.substringAfterLast('.').ifEmpty { "App" }
        }
    }
}

actual fun isPhoneNotificationAccessGranted(): Boolean {
    val ctx = AndroidAppContext.appContext ?: AndroidBikeKeysStorage.appContext ?: return false
    return try {
        val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?: return false
        if (enabled.isBlank()) return false
        enabled.split(':').any { entry ->
            val component = ComponentName.unflattenFromString(entry)
            component?.packageName == ctx.packageName
        } || enabled.contains(ctx.packageName)
    } catch (_: Exception) {
        false
    }
}

actual fun openPhoneNotificationAccessSettings() {
    val ctx = AndroidAppContext.appContext ?: AndroidBikeKeysStorage.appContext ?: return
    try {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    } catch (_: Exception) {
        try {
            val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(fallback)
        } catch (_: Exception) {}
    }
}

// Desactivado temporalmente en AndroidManifest (ver NOTIFICACIONES_TFT_ANDROID.md) para evitar
// el bloqueo de Play Protect Enhanced Fraud Protection al instalar APKs por WhatsApp/navegador.
actual fun supportsSystemWideNotificationCapture(): Boolean = false
