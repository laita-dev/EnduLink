package com.oscar.endulink.trail

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.oscar.endulink.platform.AndroidAppContext
import com.oscar.endulink.platform.showPlatformToast
import java.io.File

@Composable
actual fun rememberGPXFilePicker(onGPXImported: (name: String, content: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val text = inputStream?.bufferedReader()?.use { it.readText() } ?: ""

                var fileName = "Ruta Importada"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex).replace(".gpx", "", ignoreCase = true)
                    }
                }

                if (text.isNotBlank()) {
                    onGPXImported(fileName, text)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    return {
        launcher.launch(arrayOf("*/*", "application/gpx+xml", "text/xml", "application/xml"))
    }
}

actual fun shareGPXFile(fileName: String, gpxContent: String) {
    val ctx = AndroidAppContext.appContext ?: return
    try {
        val safeBase = fileName
            .replace(Regex("[^a-zA-Z0-9_\\-áéíóúÁÉÍÓÚñÑ ]"), "")
            .trim()
            .replace(Regex("\\s+"), "_")
            .ifBlank { "track_endulink" }
        val finalName = if (safeBase.endsWith(".gpx", ignoreCase = true)) safeBase else "$safeBase.gpx"

        val dir = File(ctx.cacheDir, "shared_gpx")
        if (!dir.exists()) dir.mkdirs()

        val gpxFile = File(dir, finalName)
        gpxFile.writeText(gpxContent, Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", gpxFile)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, finalName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "Compartir o guardar GPX: $finalName").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(chooser)
    } catch (e: Exception) {
        showPlatformToast("No se pudo compartir el GPX: ${e.message}")
    }
}


