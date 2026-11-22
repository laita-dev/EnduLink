package com.oscar.endulink.trail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.oscar.endulink.tiles.PlatformPaths
import com.oscar.endulink.tiles.TileDiskStorage
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private fun findTopViewController(): UIViewController? {
    val root = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
    var top: UIViewController = root
    while (top.presentedViewController != null) {
        top = top.presentedViewController ?: break
    }
    return top
}

private class IosGpxDocumentPickerDelegate(
    private val onPicked: (String, String) -> Unit
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        val accessed = url.startAccessingSecurityScopedResource()
        try {
            val path = url.path ?: return
            val bytes = TileDiskStorage.readBytes(path) ?: return
            val text = bytes.decodeToString().removePrefix("\uFEFF")
            if (text.isNotBlank()) {
                val rawName = url.lastPathComponent?.trim()?.takeIf { it.isNotEmpty() } ?: "Ruta Importada.gpx"
                val finalName = if (rawName.endsWith(".gpx", ignoreCase = true)) rawName else "$rawName.gpx"
                onPicked(finalName, text)
            }
        } finally {
            if (accessed) {
                url.stopAccessingSecurityScopedResource()
            }
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
            findTopViewController()?.presentViewController(
                picker,
                animated = true,
                completion = null
            )
        } catch (_: Exception) {}
    }
}

@OptIn(ExperimentalForeignApi::class)
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
        val present: () -> Unit = {
            try {
                val top = findTopViewController()
                if (top != null) {
                    val activityVC = UIActivityViewController(
                        activityItems = listOf(fileUrl),
                        applicationActivities = null
                    )
                    activityVC.popoverPresentationController?.let { popover ->
                        popover.sourceView = top.view
                        val centerRect = top.view.bounds.useContents {
                            CGRectMake(size.width / 2.0, size.height / 2.0, 1.0, 1.0)
                        }
                        popover.sourceRect = centerRect
                    }
                    top.presentViewController(
                        activityVC,
                        animated = true,
                        completion = null
                    )
                }
            } catch (_: Exception) {}
        }
        if (NSThread.isMainThread) {
            present()
        } else {
            dispatch_async(dispatch_get_main_queue(), present)
        }
    } catch (_: Exception) {}
}


