package com.oscar.endulink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oscar.endulink.trail.GPXWaypoint
import com.oscar.endulink.trail.GeoCoord
import kotlin.math.roundToInt

@Composable
fun WaypointDetailSheet(
    waypoint: GPXWaypoint,
    riderCoord: GeoCoord?,
    onNavigate: () -> Unit,
    onAddStop: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val distanceKm = riderCoord?.let {
        val distM = it.distanceTo(waypoint.coordinate)
        (distM / 100.0).roundToInt() / 10.0
    }

    val (categoryTitle, icon, color) = when (waypoint.symbol?.lowercase()) {
        "gate" -> Triple("Cancela / Valla", "🚪", Color(0xFFFF9800))
        "rock" -> Triple("Paso Difícil / Trialera", "🪨", Color(0xFFFF5722))
        "water" -> Triple("Fuente / Punto de Agua", "💧", Color(0xFF00E5FF))
        "danger" -> Triple("Peligro en Pista", "⚠️", Color(0xFFFF1744))
        "fuel" -> Triple("Gasolinera / Repostaje", "⛽", Color(0xFF00E676))
        "camp" -> Triple("Campamento / Mirador", "🏕️", Color(0xFFAB47BC))
        else -> Triple("Punto Marcado", "📍", Color(0xFFFFC107))
    }

    Card(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        colors = CardDefaults.cardColors(containerColor = EnduPalette.Surface),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            // Cabecera con Icono, Nombre y Cerrar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.22f))
                        .border(1.5.dp, color, CircleShape)
                ) {
                    Text(text = icon, fontSize = 22.sp)
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = waypoint.name,
                        color = EnduPalette.TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = categoryTitle,
                        color = color,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = "✕",
                    color = EnduPalette.TextMuted,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onClose() }
                        .padding(8.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Coordenadas y Distancia
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(EnduPalette.SurfaceHigh)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "COORDENADAS",
                        color = EnduPalette.TextMuted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    val latStr = (waypoint.latitude * 10000.0).roundToInt() / 10000.0
                    val lonStr = (waypoint.longitude * 10000.0).roundToInt() / 10000.0
                    Text(
                        text = "$latStr, $lonStr",
                        color = EnduPalette.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (distanceKm != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "DISTANCIA",
                            color = EnduPalette.TextMuted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$distanceKm km",
                            color = EnduPalette.Accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Botones de Acción Primarios
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Ir / Navegar
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1.2f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(EnduPalette.Accent)
                        .clickable {
                            onNavigate()
                            onClose()
                        }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "🚗", fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Ir a este punto",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Añadir como Parada
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF00D4FF).copy(alpha = 0.2f))
                        .border(1.dp, Color(0xFF00D4FF), RoundedCornerShape(10.dp))
                        .clickable {
                            onAddStop()
                            onClose()
                        }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "➕", fontSize = 11.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Añadir Parada",
                            color = Color(0xFF00D4FF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Botones Secundarios: Editar y Eliminar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Editar
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(EnduPalette.SurfaceHigh)
                        .clickable { onEdit() }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "✏️", fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Editar",
                            color = EnduPalette.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Eliminar
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(EnduPalette.Danger.copy(alpha = 0.15f))
                        .clickable {
                            onDelete()
                            onClose()
                        }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "🗑️", fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Eliminar",
                            color = EnduPalette.Danger,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

