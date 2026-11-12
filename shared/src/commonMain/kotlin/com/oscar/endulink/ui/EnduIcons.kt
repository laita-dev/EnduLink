package com.oscar.endulink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Colección de iconos vectoriales limpios y minimalistas en Canvas para EnduLink.
 * Reemplazan emojis inconsistentes con gráficos nítidos y vectoriales estilo rally/GPS.
 */

@Composable
fun LayersIcon(
    color: Color = EnduPalette.TextPrimary,
    modifier: Modifier = Modifier,
    size: Dp = 19.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokeW = 1.8f.dp.toPx()

        // Rombo superior (capa activa) — centrado verticalmente (0.16..0.84 -> centro 0.50)
        val topPath = Path().apply {
            moveTo(w * 0.5f, h * 0.16f)
            lineTo(w * 0.90f, h * 0.38f)
            lineTo(w * 0.5f, h * 0.60f)
            lineTo(w * 0.10f, h * 0.38f)
            close()
        }
        drawPath(
            path = topPath,
            color = color,
            style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Chevron inferior (segunda capa superpuesta)
        val bottomPath = Path().apply {
            moveTo(w * 0.12f, h * 0.62f)
            lineTo(w * 0.5f, h * 0.84f)
            lineTo(w * 0.88f, h * 0.62f)
        }
        drawPath(
            path = bottomPath,
            color = color,
            style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun DownloadTrayIcon(
    color: Color = EnduPalette.TextPrimary,
    modifier: Modifier = Modifier,
    size: Dp = 19.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokeW = 1.8f.dp.toPx()

        // Flecha hacia abajo (0.14..0.86 -> centro 0.50)
        drawLine(
            color = color,
            start = Offset(w * 0.5f, h * 0.14f),
            end = Offset(w * 0.5f, h * 0.60f),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )
        // Punta de la flecha
        val arrowHead = Path().apply {
            moveTo(w * 0.28f, h * 0.42f)
            lineTo(w * 0.5f, h * 0.62f)
            lineTo(w * 0.72f, h * 0.42f)
        }
        drawPath(
            path = arrowHead,
            color = color,
            style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Bandeja de recepción inferior
        val tray = Path().apply {
            moveTo(w * 0.14f, h * 0.66f)
            lineTo(w * 0.14f, h * 0.86f)
            lineTo(w * 0.86f, h * 0.86f)
            lineTo(w * 0.86f, h * 0.66f)
        }
        drawPath(
            path = tray,
            color = color,
            style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun SettingsGearIcon(
    color: Color = EnduPalette.TextPrimary,
    modifier: Modifier = Modifier,
    size: Dp = 19.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val c = Offset(w / 2f, h / 2f)
        val outerR = w * 0.38f
        val innerR = w * 0.20f
        val strokeW = 1.8f.dp.toPx()

        // Círculo central con hueco
        drawCircle(color = color, radius = innerR, center = c, style = Stroke(strokeW))

        // 6 dientes engranados limpios
        for (i in 0 until 6) {
            val angleRad = (i * 60.0) * (PI / 180.0)
            val x1 = (c.x + (innerR + 1.dp.toPx()) * cos(angleRad)).toFloat()
            val y1 = (c.y + (innerR + 1.dp.toPx()) * sin(angleRad)).toFloat()
            val x2 = (c.x + outerR * cos(angleRad)).toFloat()
            val y2 = (c.y + outerR * sin(angleRad)).toFloat()
            drawLine(
                color = color,
                start = Offset(x1, y1),
                end = Offset(x2, y2),
                strokeWidth = strokeW * 1.5f,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun SearchLensIcon(
    color: Color = EnduPalette.TextSecondary,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokeW = 1.8f.dp.toPx()
        val c = Offset(w * 0.42f, h * 0.42f)
        val r = w * 0.30f

        drawCircle(
            color = color,
            radius = r,
            center = c,
            style = Stroke(width = strokeW)
        )

        // Mango en 45 grados
        drawLine(
            color = color,
            start = Offset(w * 0.64f, h * 0.64f),
            end = Offset(w * 0.92f, h * 0.92f),
            strokeWidth = strokeW * 1.1f,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun MapPinTargetIcon(
    color: Color = EnduPalette.Accent,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val c = Offset(w / 2f, h / 2f)
        val r = w * 0.36f
        val strokeW = 1.6f.dp.toPx()

        drawCircle(color = color, radius = r, center = c, style = Stroke(strokeW))
        drawCircle(color = color, radius = r * 0.30f, center = c)

        val tick = 2.5f.dp.toPx()
        drawLine(color = color, start = Offset(c.x, c.y - r - tick), end = Offset(c.x, c.y - r + 1f), strokeWidth = strokeW, cap = StrokeCap.Round)
        drawLine(color = color, start = Offset(c.x, c.y + r - 1f), end = Offset(c.x, c.y + r + tick), strokeWidth = strokeW, cap = StrokeCap.Round)
        drawLine(color = color, start = Offset(c.x - r - tick, c.y), end = Offset(c.x - r + 1f, c.y), strokeWidth = strokeW, cap = StrokeCap.Round)
        drawLine(color = color, start = Offset(c.x + r - 1f, c.y), end = Offset(c.x + r + tick, c.y), strokeWidth = strokeW, cap = StrokeCap.Round)
    }
}

@Composable
fun ZoomPlusIcon(
    color: Color = EnduPalette.TextPrimary,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokeW = 2.dp.toPx()

        // Línea horizontal
        drawLine(
            color = color,
            start = Offset(w * 0.16f, h * 0.5f),
            end = Offset(w * 0.84f, h * 0.5f),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )
        // Línea vertical
        drawLine(
            color = color,
            start = Offset(w * 0.5f, h * 0.16f),
            end = Offset(w * 0.5f, h * 0.84f),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun ZoomMinusIcon(
    color: Color = EnduPalette.TextPrimary,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokeW = 2.dp.toPx()

        // Línea horizontal
        drawLine(
            color = color,
            start = Offset(w * 0.16f, h * 0.5f),
            end = Offset(w * 0.84f, h * 0.5f),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun FitRouteBoundsIcon(
    color: Color = EnduPalette.Accent,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokeW = 1.8f.dp.toPx()

        // Esquina superior izquierda
        val tl = Path().apply {
            moveTo(w * 0.12f, h * 0.40f)
            lineTo(w * 0.12f, h * 0.12f)
            lineTo(w * 0.40f, h * 0.12f)
        }
        drawPath(tl, color, style = Stroke(strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Esquina superior derecha
        val tr = Path().apply {
            moveTo(w * 0.60f, h * 0.12f)
            lineTo(w * 0.88f, h * 0.12f)
            lineTo(w * 0.88f, h * 0.40f)
        }
        drawPath(tr, color, style = Stroke(strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Esquina inferior izquierda
        val bl = Path().apply {
            moveTo(w * 0.12f, h * 0.60f)
            lineTo(w * 0.12f, h * 0.88f)
            lineTo(w * 0.40f, h * 0.88f)
        }
        drawPath(bl, color, style = Stroke(strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Esquina inferior derecha
        val br = Path().apply {
            moveTo(w * 0.60f, h * 0.88f)
            lineTo(w * 0.88f, h * 0.88f)
            lineTo(w * 0.88f, h * 0.60f)
        }
        drawPath(br, color, style = Stroke(strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Punto central
        drawCircle(color = color, radius = 2.dp.toPx(), center = Offset(w / 2f, h / 2f))
    }
}

@Composable
fun QuickPinIcon(
    color: Color = Color(0xFF00E5FF),
    modifier: Modifier = Modifier,
    size: Dp = 18.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cX = w * 0.5f
        val topY = h * 0.36f
        val r = w * 0.30f

        val pinPath = Path().apply {
            moveTo(cX, h * 0.90f)
            cubicTo(cX - r * 0.8f, h * 0.65f, cX - r, topY + r * 0.5f, cX - r, topY)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(cX - r, topY - r, cX + r, topY + r),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false
            )
            cubicTo(cX + r, topY + r * 0.5f, cX + r * 0.8f, h * 0.65f, cX, h * 0.90f)
            close()
        }
        drawPath(pinPath, color)
        drawCircle(color = Color.Black.copy(alpha = 0.7f), radius = r * 0.42f, center = Offset(cX, topY))
    }
}

@Composable
fun NavArrowIcon(
    color: Color = Color.White,
    modifier: Modifier = Modifier,
    size: Dp = 13.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.05f)
            lineTo(w * 0.95f, h * 0.90f)
            lineTo(w * 0.5f, h * 0.68f)
            lineTo(w * 0.05f, h * 0.90f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
fun StopSquareIcon(
    color: Color = Color.White,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.12f, h * 0.12f),
            size = Size(w * 0.76f, h * 0.76f),
            cornerRadius = CornerRadius(2.5f.dp.toPx(), 2.5f.dp.toPx())
        )
    }
}

@Composable
fun MotorcycleSilhouetteIcon(
    color: Color = EnduPalette.TextPrimary,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokeW = 1.5f.dp.toPx()
        val wheelR = w * 0.18f

        // Rueda trasera (centrada verticalmente: 0.18..0.83 -> centro ~0.50)
        val rearWheel = Offset(w * 0.22f, h * 0.65f)
        drawCircle(color = color, radius = wheelR, center = rearWheel, style = Stroke(strokeW))

        // Rueda delantera
        val frontWheel = Offset(w * 0.78f, h * 0.65f)
        drawCircle(color = color, radius = wheelR, center = frontWheel, style = Stroke(strokeW))

        // Cuadro moto / chasis / horquilla
        val frame = Path().apply {
            moveTo(rearWheel.x, rearWheel.y)
            lineTo(w * 0.40f, h * 0.45f) // Asiento / basculante
            lineTo(w * 0.62f, h * 0.28f) // Manillar / dirección
            lineTo(frontWheel.x, frontWheel.y) // Horquilla delantera
        }
        drawPath(frame, color, style = Stroke(strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Depósito y manillar
        drawLine(
            color = color,
            start = Offset(w * 0.55f, h * 0.18f),
            end = Offset(w * 0.70f, h * 0.24f),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun ShareUploadIcon(
    color: Color = EnduPalette.TextPrimary,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokeW = 1.7f.dp.toPx()

        // Vástago vertical hacia arriba (0.14..0.86 -> centro 0.50)
        drawLine(
            color = color,
            start = Offset(w * 0.5f, h * 0.62f),
            end = Offset(w * 0.5f, h * 0.16f),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )
        // Punta de flecha superior
        val arrowHead = Path().apply {
            moveTo(w * 0.28f, h * 0.36f)
            lineTo(w * 0.5f, h * 0.14f)
            lineTo(w * 0.72f, h * 0.36f)
        }
        drawPath(
            path = arrowHead,
            color = color,
            style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Bandeja inferior
        val tray = Path().apply {
            moveTo(w * 0.14f, h * 0.64f)
            lineTo(w * 0.14f, h * 0.86f)
            lineTo(w * 0.86f, h * 0.86f)
            lineTo(w * 0.86f, h * 0.64f)
        }
        drawPath(
            path = tray,
            color = color,
            style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun ElevationChartIcon(
    color: Color = EnduPalette.TextPrimary,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokeW = 1.7f.dp.toPx()

        // Base horizontal del perfil (0.18..0.82 -> centro 0.50)
        drawLine(
            color = color.copy(alpha = 0.5f),
            start = Offset(w * 0.10f, h * 0.82f),
            end = Offset(w * 0.90f, h * 0.82f),
            strokeWidth = strokeW * 0.85f,
            cap = StrokeCap.Round
        )

        // Curva de cumbres / altitud
        val chart = Path().apply {
            moveTo(w * 0.10f, h * 0.74f)
            lineTo(w * 0.34f, h * 0.36f)
            lineTo(w * 0.52f, h * 0.56f)
            lineTo(w * 0.76f, h * 0.18f)
            lineTo(w * 0.90f, h * 0.46f)
        }
        drawPath(
            path = chart,
            color = color,
            style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun InvertRouteArrowsIcon(
    color: Color = EnduPalette.TextPrimary,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokeW = 1.7f.dp.toPx()

        // Flecha superior hacia la derecha
        drawLine(
            color = color,
            start = Offset(w * 0.14f, h * 0.32f),
            end = Offset(w * 0.84f, h * 0.32f),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )
        val topHead = Path().apply {
            moveTo(w * 0.66f, h * 0.16f)
            lineTo(w * 0.86f, h * 0.32f)
            lineTo(w * 0.66f, h * 0.48f)
        }
        drawPath(topHead, color, style = Stroke(strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Flecha inferior hacia la izquierda
        drawLine(
            color = color,
            start = Offset(w * 0.86f, h * 0.68f),
            end = Offset(w * 0.16f, h * 0.68f),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )
        val bottomHead = Path().apply {
            moveTo(w * 0.34f, h * 0.52f)
            lineTo(w * 0.14f, h * 0.68f)
            lineTo(w * 0.34f, h * 0.84f)
        }
        drawPath(bottomHead, color, style = Stroke(strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun EyeVisibilityIcon(
    visible: Boolean,
    color: Color = EnduPalette.TextPrimary,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokeW = 1.6f.dp.toPx()

        // Contorno almendrado del ojo
        val eyePath = Path().apply {
            moveTo(w * 0.08f, h * 0.5f)
            quadraticTo(w * 0.5f, h * 0.14f, w * 0.92f, h * 0.5f)
            quadraticTo(w * 0.5f, h * 0.86f, w * 0.08f, h * 0.5f)
            close()
        }
        drawPath(
            path = eyePath,
            color = color,
            style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Pupila central
        drawCircle(
            color = color,
            radius = w * 0.13f,
            center = Offset(w * 0.5f, h * 0.5f)
        )

        // Línea diagonal si está oculto
        if (!visible) {
            drawLine(
                color = color,
                start = Offset(w * 0.16f, h * 0.84f),
                end = Offset(w * 0.84f, h * 0.16f),
                strokeWidth = strokeW,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun TrashDeleteIcon(
    color: Color = EnduPalette.Danger,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokeW = 1.6f.dp.toPx()

        // Tapa superior
        drawLine(
            color = color,
            start = Offset(w * 0.16f, h * 0.24f),
            end = Offset(w * 0.84f, h * 0.24f),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )
        // Asa de la tapa
        drawLine(
            color = color,
            start = Offset(w * 0.38f, h * 0.14f),
            end = Offset(w * 0.62f, h * 0.14f),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )

        // Cuerpo de la papelera
        val body = Path().apply {
            moveTo(w * 0.24f, h * 0.28f)
            lineTo(w * 0.28f, h * 0.86f)
            lineTo(w * 0.72f, h * 0.86f)
            lineTo(w * 0.76f, h * 0.28f)
        }
        drawPath(
            path = body,
            color = color,
            style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Líneas interiores
        drawLine(
            color = color,
            start = Offset(w * 0.42f, h * 0.42f),
            end = Offset(w * 0.43f, h * 0.72f),
            strokeWidth = strokeW * 0.85f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(w * 0.58f, h * 0.42f),
            end = Offset(w * 0.57f, h * 0.72f),
            strokeWidth = strokeW * 0.85f,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun FolderImportIcon(
    color: Color = Color.White,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokeW = 1.7f.dp.toPx()

        val folder = Path().apply {
            moveTo(w * 0.10f, h * 0.18f)
            lineTo(w * 0.38f, h * 0.18f)
            lineTo(w * 0.48f, h * 0.32f)
            lineTo(w * 0.90f, h * 0.32f)
            lineTo(w * 0.90f, h * 0.82f)
            lineTo(w * 0.10f, h * 0.82f)
            close()
        }
        drawPath(
            path = folder,
            color = color,
            style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun EditPencilIcon(
    color: Color = EnduPalette.TextPrimary,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokeW = 1.6f.dp.toPx()

        // Cuerpo del lápiz inclinado (0.14..0.86 -> centro 0.50)
        val pencil = Path().apply {
            moveTo(w * 0.68f, h * 0.14f)
            lineTo(w * 0.86f, h * 0.32f)
            lineTo(w * 0.34f, h * 0.84f)
            lineTo(w * 0.14f, h * 0.86f)
            lineTo(w * 0.16f, h * 0.66f)
            close()
        }
        drawPath(
            path = pencil,
            color = color,
            style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        // Línea del cabezal
        drawLine(
            color = color,
            start = Offset(w * 0.56f, h * 0.26f),
            end = Offset(w * 0.74f, h * 0.44f),
            strokeWidth = strokeW * 0.9f,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun CompassNorthIcon(
    isHeadingMode: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokeW = 2.0f.dp.toPx()

        if (isHeadingMode) {
            // Flecha de rumbo centrada exactamente en (0.50, 0.50)
            val arrow = Path().apply {
                moveTo(w * 0.50f, h * 0.14f)
                lineTo(w * 0.86f, h * 0.84f)
                lineTo(w * 0.50f, h * 0.66f)
                lineTo(w * 0.14f, h * 0.84f)
                close()
            }
            drawPath(arrow, color)
        } else {
            // Letra "N" vectorial centrada geométricamente (0.20..0.80 en X, 0.16..0.84 en Y)
            val nPath = Path().apply {
                moveTo(w * 0.22f, h * 0.82f)
                lineTo(w * 0.22f, h * 0.18f)
                lineTo(w * 0.78f, h * 0.82f)
                lineTo(w * 0.78f, h * 0.18f)
            }
            drawPath(
                path = nPath,
                color = color,
                style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

