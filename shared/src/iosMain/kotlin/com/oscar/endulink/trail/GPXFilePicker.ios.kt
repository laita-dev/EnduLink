package com.oscar.endulink.trail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.oscar.endulink.tiles.PlatformPaths
import com.oscar.endulink.tiles.TileDiskStorage
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.darwin.NSObject

private class IosGpxDocumentPickerDelegate(
    private val onPicked: (String, String) -> Unit
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        val path = url.path ?: return
        val bytes = TileDiskStorage.readBytes(path) ?: return
        val text = bytes.decodeToString()
        if (text.isNotBlank()) {
            val rawName = url.lastPathComponent ?: "Ruta Importada"
            val cleanName = rawName.replace(".gpx", "", ignoreCase = true)
            onPicked(cleanName, text)
        }
    }
}

@Composable
actual fun rememberGPXFilePicker(onGPXImported: (name: String, content: String) -> Unit): () -> Unit {
    val currentCallback = rememberUpdatedState(onGPXImported)
    val delegate = remember {
        IosGpxDocumentPickerDelegate { name, content ->
            currentCallback.value(name, content)
        }
    }

    return {
        try {
            val picker = UIDocumentPickerViewController(
                documentTypes = listOf("public.xml", "public.data", "public.content", "public.item"),
                inMode = UIDocumentPickerMode.UIDocumentPickerModeImport
            )
            picker.delegate = delegate
            picker.allowsMultipleSelection = false
            UIApplication.sharedApplication.keyWindow?.rootViewController?.presentViewController(
                picker,
                animated = true,
                completion = null
            )
        } catch (_: Exception) {}
    }
}

actual fun shareGPXFile(fileName: String, gpxContent: String) {
    try {
        val safeBase = fileName
            .replace(Regex("[^a-zA-Z0-9_\\-áéíóúÁÉÍÓÚñÑ ]"), "")
            .trim()
            .replace(Regex("\\s+"), "_")
            .ifBlank { "track_endulink" }
        val finalName = if (safeBase.endsWith(".gpx", ignoreCase = true)) safeBase else "$safeBase.gpx"

        val path = "${PlatformPaths.getAppDataDir()}/$finalName"
        TileDiskStorage.writeBytes(path, gpxContent.encodeToByteArray())

        val fileUrl = NSURL.fileURLWithPath(path)
        val activityVC = UIActivityViewController(
            activityItems = listOf(fileUrl),
            applicationActivities = null
        )
        UIApplication.sharedApplication.keyWindow?.rootViewController?.presentViewController(
            activityVC,
            animated = true,
            completion = null
        )
    } catch (_: Exception) {}
}


