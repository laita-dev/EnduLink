package com.oscar.endulink.trail

import androidx.compose.runtime.Composable

@Composable
expect fun rememberGPXFilePicker(onGPXImported: (name: String, content: String) -> Unit): () -> Unit

expect fun shareGPXFile(fileName: String, gpxContent: String)


