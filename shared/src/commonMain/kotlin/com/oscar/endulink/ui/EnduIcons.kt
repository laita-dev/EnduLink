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

        // Rombo superior (capa activa)
        val topPath = Path().apply {
            moveTo(w * 0.5f, h * 0.12f)
            lineTo(w * 0.92f, h * 0.34f)
            lineTo(w * 0.5f, h * 0.56f)
            lineTo(w * 0.08f, h * 0.34f)
            close()
        }
        drawPath(
            path = topPath,
            color = color,
            style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Chevron inferior (segunda capa superpuesta)
        val bottomPath = Path().apply {
            moveTo(w * 0.10f, h * 0.58f)
            lineTo(w * 0.5f, h * 0.80f)
            lineTo(w * 0.90f, h * 0.58f)
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

        // Flecha hacia abajo
        // Vástago vertical
        drawLine(
            color = color,
            start = Offset(w * 0.5f, h * 0.12f),
            end = Offset(w * 0.5f, h * 0.58f),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )
        // Punta de la flecha
        val arrowHead = Path().apply {
            moveTo(w * 0.28f, h * 0.40f)
            lineTo(w * 0.5f, h * 0.60f)
            lineTo(w * 0.72f, h * 0.40f)
        }
        drawPath(
            path = arrowHead,
            color = color,
            style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Bandeja de recepción inferior
        val tray = Path().apply {
            moveTo(w * 0.14f, h * 0.64f)
            lineTo(w * 0.14f, h * 0.84f)
            lineTo(w * 0.86f, h * 0.84f)
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

        // Rueda trasera
        val rearWheel = Offset(w * 0.22f, h * 0.75f)
        drawCircle(color = color, radius = wheelR, center = rearWheel, style = Stroke(strokeW))

        // Rueda delantera
        val frontWheel = Offset(w * 0.78f, h * 0.75f)
        drawCircle(color = color, radius = wheelR, center = frontWheel, style = Stroke(strokeW))

        // Cuadro moto / chasis / horquilla
        val frame = Path().apply {
            moveTo(rearWheel.x, rearWheel.y)
            lineTo(w * 0.40f, h * 0.55f) // Asiento / basculante
            lineTo(w * 0.62f, h * 0.38f) // Manillar / dirección
            lineTo(frontWheel.x, frontWheel.y) // Horquilla delantera
        }
        drawPath(frame, color, style = Stroke(strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Depósito y manillar
        drawLine(
            color = color,
            start = Offset(w * 0.55f, h * 0.28f),
            end = Offset(w * 0.70f, h * 0.34f),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )
    }
}

