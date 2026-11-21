package com.oscar.endulink.group

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertEquals

class QrCodeMatrixZxingTest {

    @Test
    fun testGeneratedQrIsReadableByZxing() {
        val payloads = listOf(
            "endulink://group/K7M9P2",
            "endulink://group/ABCD",
            "endulink://group/9Z8Y7X6W5V4U"
        )

        val reader = QRCodeReader()
        for (payload in payloads) {
            val matrix = QrCodeMatrix.encode(payload)
            val quiet = 4
            val modPx = 8
            val side = (matrix.size + quiet * 2) * modPx
            val pixels = IntArray(side * side) { 0xFFFFFFFF.toInt() }

            for (my in 0 until matrix.size) {
                for (mx in 0 until matrix.size) {
                    if (matrix.get(mx, my)) {
                        val px0 = (mx + quiet) * modPx
                        val py0 = (my + quiet) * modPx
                        for (dy in 0 until modPx) {
                            val rowOffset = (py0 + dy) * side + px0
                            for (dx in 0 until modPx) {
                                pixels[rowOffset + dx] = 0xFF000000.toInt()
                            }
                        }
                    }
                }
            }

            val source = RGBLuminanceSource(side, side, pixels)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decode(bitmap)
            reader.reset()
            assertEquals(payload, result.text)
        }
    }
}
