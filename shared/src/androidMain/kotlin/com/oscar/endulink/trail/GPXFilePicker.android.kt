package com.oscar.endulink.trail

import android.content.Context
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
import com.oscar.endulink.ui.EnduController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

private const val MAX_GPX_FILE_BYTES = 25 * 1024 * 1024
private val gpxImportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private sealed interface GpxUriReadResult {
    data class Success(val fileName: String, val content: String) : GpxUriReadResult
    data class Error(val message: String) : GpxUriReadResult
}

private fun decodeGpxBytes(bytes: ByteArray): String {
    var text = bytes.toString(Charsets.UTF_8)
    if (text.contains('\uFFFD')) {
        text = bytes.toString(Charsets.ISO_8859_1)
    }
    return text.removePrefix("\uFEFF")
}

private fun readGpxFromUri(context: Context, uri: Uri): GpxUriReadResult {
    var displayName: String? = null
    var reportedSize: Long? = null

    try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    displayName = cursor.getString(nameIndex)
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                    reportedSize = cursor.getLong(sizeIndex)
                }
            }
        }
    } catch (_: Exception) {}

    val knownSize = reportedSize
    if (knownSize != null && knownSize > MAX_GPX_FILE_BYTES) {
        return GpxUriReadResult.Error("El archivo es demasiado grande para ser un GPX válido.")
    }

    val bytes = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > MAX_GPX_FILE_BYTES) {
                    return GpxUriReadResult.Error("El archivo supera el límite de 25 MB para GPX.")
                }
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: return GpxUriReadResult.Error("No se pudo abrir el archivo GPX.")
    } catch (_: SecurityException) {
        return GpxUriReadResult.Error("Sin permiso para leer el archivo GPX compartido.")
    } catch (e: Exception) {
        return GpxUriReadResult.Error("Error leyendo archivo: ${e.message ?: "desconocido"}")
    }

    val text = decodeGpxBytes(bytes)
    if (text.isBlank()) {
        return GpxUriReadResult.Error("El archivo GPX está vacío.")
    }

    val rawName = displayName?.trim()?.takeIf { it.isNotEmpty() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.trim()?.takeIf { it.isNotEmpty() }
        ?: "Ruta Importada.gpx"

    val hasGpxExtension = rawName.endsWith(".gpx", ignoreCase = true)
    val looksLikeGpx = text.contains("<gpx", ignoreCase = true) ||
        text.contains("<trkpt", ignoreCase = true) ||
        text.contains("<rtept", ignoreCase = true)

    if (!hasGpxExtension && !looksLikeGpx) {
        return GpxUriReadResult.Error("El archivo seleccionado no es un GPX válido.")
    }

    val normalizedName = if (hasGpxExtension) rawName else "$rawName.gpx"
    return GpxUriReadResult.Success(fileName = normalizedName, content = text)
}

/**
 * Importa un archivo GPX abierto externamente desde WhatsApp, Archivos o menú Compartir.
 */
fun importGPXFromUri(context: Context, uri: Uri) {
    val appCtx = context.applicationContext
    gpxImportScope.launch {
        val result = readGpxFromUri(appCtx, uri)
        withContext(Dispatchers.Main) {
            when (result) {
                is GpxUriReadResult.Success -> {
                    EnduController.shared.importGPXContent(result.fileName, result.content)
                }
                is GpxUriReadResult.Error -> {
                    showPlatformToast(result.message)
                }
            }
        }
    }
}

@Composable
actual fun rememberGPXFilePicker(onGPXImported: (name: String, content: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            when (val result = readGpxFromUri(context, uri)) {
                is GpxUriReadResult.Success -> onGPXImported(result.fileName, result.content)
                is GpxUriReadResult.Error -> showPlatformToast(result.message)
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


