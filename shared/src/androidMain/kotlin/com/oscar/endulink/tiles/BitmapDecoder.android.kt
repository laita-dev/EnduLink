package com.oscar.endulink.tiles

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun decodeByteArrayToBitmap(bytes: ByteArray): ImageBitmap? {
    return try {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        bmp?.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}

