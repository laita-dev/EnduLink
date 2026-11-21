package com.oscar.endulink.group

/**
 * Generador compacto de códigos QR ISO/IEC 18004 en Kotlin puro (`commonMain`).
 * Soporta Byte Mode con corrección de errores Nivel M (versiones 1 a 4, hasta 62 bytes),
 * suficiente para enlaces `endulink://group/K7M9P2` (23 bytes -> versión 2, 25x25 módulos).
 */
data class QrMatrix(
    val size: Int,
    val modules: BooleanArray
) {
    fun get(x: Int, y: Int): Boolean {
        if (x !in 0 until size || y !in 0 until size) return false
        return modules[y * size + x]
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QrMatrix) return false
        return size == other.size && modules.contentEquals(other.modules)
    }

    override fun hashCode(): Int {
        var result = size
        result = 31 * result + modules.contentHashCode()
        return result
    }
}

object QrCodeMatrix {

    private data class VersionSpec(
        val version: Int,
        val size: Int,
        val dataCodewordsPerBlock: Int,
        val eccCodewordsPerBlock: Int,
        val numBlocks: Int,
        val maxBytesByteMode: Int,
        val alignmentCenter: Int?
    )

    // Especificaciones exactas ISO/IEC 18004 para ECC Nivel M (00)
    private val SPECS = listOf(
        VersionSpec(version = 1, size = 21, dataCodewordsPerBlock = 16, eccCodewordsPerBlock = 10, numBlocks = 1, maxBytesByteMode = 14, alignmentCenter = null),
        VersionSpec(version = 2, size = 25, dataCodewordsPerBlock = 28, eccCodewordsPerBlock = 16, numBlocks = 1, maxBytesByteMode = 26, alignmentCenter = 18),
        VersionSpec(version = 3, size = 29, dataCodewordsPerBlock = 44, eccCodewordsPerBlock = 26, numBlocks = 1, maxBytesByteMode = 42, alignmentCenter = 22),
        VersionSpec(version = 4, size = 33, dataCodewordsPerBlock = 32, eccCodewordsPerBlock = 18, numBlocks = 2, maxBytesByteMode = 62, alignmentCenter = 26)
    )

    fun encode(text: String): QrMatrix {
        val dataBytes = text.encodeToByteArray()
        val spec = SPECS.firstOrNull { dataBytes.size <= it.maxBytesByteMode }
            ?: throw IllegalArgumentException("Texto demasiado largo para QR compacto (${dataBytes.size} bytes)")

        val totalDataCodewords = spec.dataCodewordsPerBlock * spec.numBlocks
        val dataBits = ArrayList<Int>(totalDataCodewords * 8)

        // Mode indicator: Byte Mode (0100)
        appendBits(dataBits, 0b0100, 4)
        // Character count indicator (8 bits para versiones 1..9)
        appendBits(dataBits, dataBytes.size, 8)
        for (b in dataBytes) {
            appendBits(dataBits, b.toInt() and 0xFF, 8)
        }

        // Terminator (hasta 4 bits 0)
        val capacityBits = totalDataCodewords * 8
        val termLen = minOf(4, capacityBits - dataBits.size)
        appendBits(dataBits, 0, termLen)

        // Pad hasta múltiplo de 8 bits
        while (dataBits.size % 8 != 0) {
            dataBits.add(0)
        }

        // Bytes alternos 0xEC, 0x11 hasta completar capacidad
        var padIndex = 0
        while (dataBits.size < capacityBits) {
            appendBits(dataBits, if ((padIndex++ and 1) == 0) 0xEC else 0x11, 8)
        }

        val dataCodewords = IntArray(totalDataCodewords) { cwIdx ->
            var v = 0
            for (bit in 0 until 8) {
                v = (v shl 1) or dataBits[cwIdx * 8 + bit]
            }
            v
        }

        val eccGen = reedSolomonDivisor(spec.eccCodewordsPerBlock)
        val dataBlocks = Array(spec.numBlocks) { b ->
            val start = b * spec.dataCodewordsPerBlock
            dataCodewords.copyOfRange(start, start + spec.dataCodewordsPerBlock)
        }
        val eccBlocks = Array(spec.numBlocks) { b ->
            reedSolomonRemainder(dataBlocks[b], eccGen)
        }

        val allCodewords = ArrayList<Int>(totalDataCodewords + spec.eccCodewordsPerBlock * spec.numBlocks)
        for (i in 0 until spec.dataCodewordsPerBlock) {
            for (b in 0 until spec.numBlocks) {
                allCodewords.add(dataBlocks[b][i])
            }
        }
        for (i in 0 until spec.eccCodewordsPerBlock) {
            for (b in 0 until spec.numBlocks) {
                allCodewords.add(eccBlocks[b][i])
            }
        }

        val size = spec.size
        val isFunction = BooleanArray(size * size)
        val baseModules = BooleanArray(size * size)

        placeFunctionPatterns(spec, baseModules, isFunction)
        placeCodewords(size, allCodewords, baseModules, isFunction)

        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        var bestModules = baseModules

        for (mask in 0 until 8) {
            val candidate = baseModules.copyOf()
            applyMask(size, candidate, isFunction, mask)
            writeFormatInfo(size, candidate, mask)
            val penalty = computePenalty(size, candidate)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = mask
                bestModules = candidate
            }
        }

        // Garantizar coherencia final
        if (bestMask < 0) writeFormatInfo(size, bestModules, 0)
        return QrMatrix(size = size, modules = bestModules)
    }

    private fun appendBits(out: MutableList<Int>, value: Int, bitCount: Int) {
        for (i in bitCount - 1 downTo 0) {
            out.add((value ushr i) and 1)
        }
    }

    private fun setFuncModule(size: Int, modules: BooleanArray, isFunc: BooleanArray, x: Int, y: Int, dark: Boolean) {
        val idx = y * size + x
        modules[idx] = dark
        isFunc[idx] = true
    }

    private fun placeFunctionPatterns(spec: VersionSpec, modules: BooleanArray, isFunc: BooleanArray) {
        val s = spec.size

        // Timing patterns (fila 6 y columna 6)
        for (i in 0 until s) {
            val dark = (i and 1) == 0
            setFuncModule(s, modules, isFunc, i, 6, dark)
            setFuncModule(s, modules, isFunc, 6, i, dark)
        }

        // 3 Finder patterns + separadores blancos
        placeFinderPattern(s, modules, isFunc, 3, 3)
        placeFinderPattern(s, modules, isFunc, s - 4, 3)
        placeFinderPattern(s, modules, isFunc, 3, s - 4)

        // Alignment pattern (versiones 2..4 tienen exactamente 1 en (center, center))
        spec.alignmentCenter?.let { center ->
            for (dy in -2..2) {
                for (dx in -2..2) {
                    val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    setFuncModule(s, modules, isFunc, center + dx, center + dy, dist != 1)
                }
            }
        }

        // Reservar áreas de formato (se rellenan tras aplicar la máscara)
        for (i in 0..8) {
            if (i != 6) {
                setFuncModule(s, modules, isFunc, 8, i, false)
                setFuncModule(s, modules, isFunc, i, 8, false)
            }
        }
        for (i in 0 until 8) {
            setFuncModule(s, modules, isFunc, s - 1 - i, 8, false)
        }
        for (i in 0 until 7) {
            setFuncModule(s, modules, isFunc, 8, s - 1 - i, false)
        }

        // Dark module fijo en (8, 4*version + 9) = (8, s - 8)
        setFuncModule(s, modules, isFunc, 8, s - 8, true)
    }

    private fun placeFinderPattern(s: Int, modules: BooleanArray, isFunc: BooleanArray, cx: Int, cy: Int) {
        for (dy in -4..4) {
            for (dx in -4..4) {
                val xx = cx + dx
                val yy = cy + dy
                if (xx in 0 until s && yy in 0 until s) {
                    val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    val dark = dist != 2 && dist != 4
                    setFuncModule(s, modules, isFunc, xx, yy, dark)
                }
            }
        }
    }

    private fun placeCodewords(size: Int, codewords: List<Int>, modules: BooleanArray, isFunc: BooleanArray) {
        val totalBits = codewords.size * 8
        var bitIdx = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right--
            for (vert in 0 until size) {
                for (j in 0 until 2) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    val idx = y * size + x
                    if (!isFunc[idx] && bitIdx < totalBits) {
                        val cw = codewords[bitIdx ushr 3]
                        val bit = (cw ushr (7 - (bitIdx and 7))) and 1
                        modules[idx] = bit == 1
                        bitIdx++
                    }
                }
            }
            right -= 2
        }
    }

    private fun applyMask(size: Int, modules: BooleanArray, isFunc: BooleanArray, mask: Int) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                val idx = y * size + x
                if (isFunc[idx]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> ((x / 3) + (y / 2)) % 2 == 0
                    5 -> (x * y) % 2 + (x * y) % 3 == 0
                    6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
                    7 -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
                    else -> false
                }
                if (invert) {
                    modules[idx] = !modules[idx]
                }
            }
        }
    }

    private fun writeFormatInfo(size: Int, modules: BooleanArray, mask: Int) {
        // ECC M = 00 -> data = (0 shl 3) or mask
        val data = mask and 0x7
        var rem = data
        repeat(10) {
            rem = (rem shl 1) xor (((rem ushr 9) and 1) * 0x537)
        }
        val bits = ((data shl 10) or rem) xor 0x5412

        // Copia 1 (superior izquierda)
        for (i in 0..5) {
            modules[i * size + 8] = ((bits ushr i) and 1) != 0
        }
        modules[7 * size + 8] = ((bits ushr 6) and 1) != 0
        modules[8 * size + 8] = ((bits ushr 7) and 1) != 0
        modules[8 * size + 7] = ((bits ushr 8) and 1) != 0
        for (i in 9..14) {
            modules[8 * size + (14 - i)] = ((bits ushr i) and 1) != 0
        }

        // Copia 2 (superior derecha + inferior izquierda)
        for (i in 0 until 8) {
            modules[8 * size + (size - 1 - i)] = ((bits ushr i) and 1) != 0
        }
        for (i in 8 until 15) {
            modules[(size - 15 + i) * size + 8] = ((bits ushr i) and 1) != 0
        }
        modules[(size - 8) * size + 8] = true
    }

    private fun computePenalty(size: Int, modules: BooleanArray): Int {
        var penalty = 0

        // Regla 1: 5+ módulos iguales consecutivos en fila/columna
        for (y in 0 until size) {
            var runColor = modules[y * size]
            var runLen = 1
            var x = 1
            while (x < size) {
                val c = modules[y * size + x]
                if (c == runColor) {
                    runLen++
                    if (runLen == 5) penalty += 3 else if (runLen > 5) penalty += 1
                } else {
                    runColor = c
                    runLen = 1
                }
                x++
            }
        }
        for (x in 0 until size) {
            var runColor = modules[x]
            var runLen = 1
            var y = 1
            while (y < size) {
                val c = modules[y * size + x]
                if (c == runColor) {
                    runLen++
                    if (runLen == 5) penalty += 3 else if (runLen > 5) penalty += 1
                } else {
                    runColor = c
                    runLen = 1
                }
                y++
            }
        }

        // Regla 2: bloques 2x2 del mismo color
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = modules[y * size + x]
                if (c == modules[y * size + x + 1] &&
                    c == modules[(y + 1) * size + x] &&
                    c == modules[(y + 1) * size + x + 1]
                ) {
                    penalty += 3
                }
            }
        }

        // Regla 3: patrón 1:1:3:1:1 oscuro-claro-oscuro-claro-oscuro con 4 claros adyacentes
        for (y in 0 until size) {
            var bits = 0
            for (x in 0 until size) {
                bits = ((bits shl 1) and 0x7FF) or (if (modules[y * size + x]) 1 else 0)
                if (x >= 10 && (bits == 0x05D || bits == 0x5D0)) {
                    penalty += 40
                }
            }
        }
        for (x in 0 until size) {
            var bits = 0
            for (y in 0 until size) {
                bits = ((bits shl 1) and 0x7FF) or (if (modules[y * size + x]) 1 else 0)
                if (y >= 10 && (bits == 0x05D || bits == 0x5D0)) {
                    penalty += 40
                }
            }
        }

        // Regla 4: equilibrio claro/oscuro respecto al 50%
        var darkCount = 0
        for (m in modules) if (m) darkCount++
        val total = size * size
        val k = (kotlin.math.abs(darkCount * 20 - total * 10) + total - 1) / total - 1
        if (k > 0) penalty += k * 10

        return penalty
    }

    private fun reedSolomonDivisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                result[j] = gfMultiply(result[j], root)
                if (j + 1 < degree) {
                    result[j] = result[j] xor result[j + 1]
                }
            }
            root = gfMultiply(root, 0x02)
        }
        return result
    }

    private fun reedSolomonRemainder(data: IntArray, divisor: IntArray): IntArray {
        val result = IntArray(divisor.size)
        for (b in data) {
            val factor = b xor result[0]
            for (i in 0 until result.size - 1) {
                result[i] = result[i + 1]
            }
            result[result.size - 1] = 0
            for (i in divisor.indices) {
                result[i] = result[i] xor gfMultiply(divisor[i], factor)
            }
        }
        return result
    }

    private fun gfMultiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }
}
