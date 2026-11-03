package com.oscar.endulink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oscar.endulink.trail.GPXPoint
import com.oscar.endulink.trail.GPXRoute
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun ElevationProfileSheet(
    route: GPXRoute,
    onScrubPoint: (GPXPoint?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pts = route.points
    if (pts.isEmpty()) return

    // Muestreo del perfil para rendimiento óptimo
    val step = max(1, pts.size / 150)
    val samples = remember(route) {
        val list = mutableListOf<GPXPoint>()
        var i = 0
        while (i < pts.size) {
            list.add(pts[i])
            i += step
        }
        if (pts.isNotEmpty() && list.lastOrNull() != pts.last()) {
            list.add(pts.last())
        }
        list
    }

    val elevations = samples.mapNotNull { it.elevation }
    val minEle = (elevations.minOrNull() ?: 0.0)
    val maxEle = max(minEle + 20.0, elevations.maxOrNull() ?: 100.0)
    val totalKm = pts.lastOrNull()?.cumulativeKm ?: 0.0

    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val selectedPoint = remember(scrubFraction, samples) {
        val frac = scrubFraction ?: return@remember null
        val targetIdx = (frac * (samples.size - 1)).roundToInt().coerceIn(0, samples.size - 1)
        samples[targetIdx]
    }

    LaunchedEffect(selectedPoint) {
        onScrubPoint(selectedPoint)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(EnduPalette.Surface)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {

            // Encabezado
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Perfil de Altitud · ${route.name}",
                        color = EnduPalette.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = "${minEle.roundToInt()} m – ${maxEle.roundToInt()} m  |  +${route.elevationGainM.roundToInt()} m desnivel  |  ${(totalKm * 10.0).roundToInt() / 10.0} km",
                        color = EnduPalette.TextMuted,
                        fontSize = 12.sp
                    )
                }
                Text(
                    text = "Cerrar",
                    color = EnduPalette.Accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable {
                        onScrubPoint(null)
                        onClose()
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Indicador de punto inspeccionado
            if (selectedPoint != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(EnduPalette.Accent.copy(alpha = 0.2f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📍 Km ${(selectedPoint.cumulativeKm * 10.0).roundToInt() / 10.0}  ·  ${selectedPoint.elevation?.roundToInt() ?: 0} m",
                        color = EnduPalette.Accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Soltar",
                        color = EnduPalette.TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable { scrubFraction = null }
                    )
                }
            } else {
                Text(
                    text = "Toca o arrastra sobre la gráfica para ver altitud y posición exacta",
                    color = EnduPalette.TextMuted.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Lienzo interactivo de altitud
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(EnduPalette.SurfaceHigh)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            scrubFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, _ ->
                                scrubFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                // Mantenemos el punto seleccionado
                            }
                        )
                    }
            ) {
                val w = size.width
                val h = size.height

                if (samples.size < 2) return@Canvas

                val range = max(1.0, maxEle - minEle)
                val curvePath = Path()
                val fillPath = Path()

                fillPath.moveTo(0f, h)

                for (idx in samples.indices) {
                    val pt = samples[idx]
                    val x = (idx.toFloat() / (samples.size - 1)) * w
                    val ele = pt.elevation ?: minEle
                    val normY = ((ele - minEle) / range).toFloat()
                    val y = h - (normY * (h - 24f)) - 12f

                    if (idx == 0) {
                        curvePath.moveTo(x, y)
                        fillPath.lineTo(x, y)
                    } else {
                        curvePath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }

                fillPath.lineTo(w, h)
                fillPath.close()

                // Relleno degradado bajo la curva
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(route.color.rgbHex).copy(alpha = 0.45f),
                            Color(route.color.rgbHex).copy(alpha = 0.05f)
                        )
                    )
                )

                // Línea de perfil principal
                drawPath(
                    path = curvePath,
                    color = Color(route.color.rgbHex),
                    style = Stroke(width = 3.5f, cap = StrokeCap.Round)
                )

                // Línea y punto de cursor de arrastre
                scrubFraction?.let { frac ->
                    val scrubX = frac * w
                    val targetIdx = (frac * (samples.size - 1)).roundToInt().coerceIn(0, samples.size - 1)
                    val ele = samples[targetIdx].elevation ?: minEle
                    val normY = ((ele - minEle) / range).toFloat()
                    val scrubY = h - (normY * (h - 24f)) - 12f

                    // Línea vertical punteada
                    drawLine(
                        color = EnduPalette.TextPrimary.copy(alpha = 0.8f),
                        start = Offset(scrubX, 0f),
                        end = Offset(scrubX, h),
                        strokeWidth = 2f
                    )

                    // Círculo del punto
                    drawCircle(
                        color = Color.White,
                        radius = 6f,
                        center = Offset(scrubX, scrubY)
                    )
                    drawCircle(
                        color = EnduPalette.Accent,
                        radius = 4f,
                        center = Offset(scrubX, scrubY)
                    )
                }
            }
        }
    }
}

