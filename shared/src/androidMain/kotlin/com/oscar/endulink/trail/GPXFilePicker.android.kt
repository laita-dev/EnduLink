package com.oscar.endulink.trail

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

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

