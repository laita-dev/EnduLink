package com.oscar.endulink.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object AndroidAppContext {
    var appContext: Context? = null
}

actual object PlatformAppUpdater {

    actual fun getAppVersionName(): String {
        val ctx = AndroidAppContext.appContext ?: return "0.1.0"
        return try {
            val pInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            pInfo.versionName ?: "0.1.0"
        } catch (_: Exception) {
            "0.1.0"
        }
    }

    actual fun openExternalUrl(url: String) {
        val ctx = AndroidAppContext.appContext ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (_: Exception) {}
    }

    actual suspend fun downloadAndInstallApk(url: String, onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val ctx = AndroidAppContext.appContext ?: return@withContext false
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "EnduLink-App")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("Pragma", "no-cache")
            connection.connect()

            val totalBytes = connection.contentLengthLong
            val input = connection.inputStream

            val updatesDir = File(ctx.cacheDir, "updates")
            if (!updatesDir.exists()) updatesDir.mkdirs()
            // Limpiar descargas anteriores para evitar residuos
            try {
                updatesDir.listFiles()?.forEach { it.delete() }
            } catch (_: Exception) {}

            // Nombre único para que el instalador de paquetes de Android no reutilice metadatos en caché
            val apkFile = File(updatesDir, "endulink-update-${System.currentTimeMillis()}.apk")
            if (apkFile.exists()) apkFile.delete()

            val output = FileOutputStream(apkFile)
            val buffer = ByteArray(32 * 1024)
            var downloaded = 0L
            var read: Int

            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                downloaded += read
                if (totalBytes > 0) {
                    val progress = (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    onProgress(progress)
                }
            }

            output.flush()
            output.close()
            input.close()
            connection.disconnect()

            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apkFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(installIntent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

