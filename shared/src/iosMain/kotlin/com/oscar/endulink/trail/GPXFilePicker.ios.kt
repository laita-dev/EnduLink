package com.oscar.endulink.trail

import androidx.compose.runtime.Composable

@Composable
actual fun rememberGPXFilePicker(onGPXImported: (name: String, content: String) -> Unit): () -> Unit {
    return {
        // En iOS el picker se invoca vía UIDocumentPickerViewController
    }
}

