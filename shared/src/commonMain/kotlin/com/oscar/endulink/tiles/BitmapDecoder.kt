package com.oscar.endulink.tiles

import androidx.compose.ui.graphics.ImageBitmap

expect fun decodeByteArrayToBitmap(bytes: ByteArray): ImageBitmap?

