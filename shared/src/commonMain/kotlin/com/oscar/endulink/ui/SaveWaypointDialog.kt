package com.oscar.endulink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oscar.endulink.trail.GeoCoord
import kotlin.math.roundToInt

data class WaypointCategory(
    val id: String,
    val icon: String,
    val title: String,
    val color: Color
)

val WAYPOINT_CATEGORIES = listOf(
    WaypointCategory("gate", "🚪", "Cancela", Color(0xFFFF9800)),
    WaypointCategory("rock", "🪨", "Paso Difícil", Color(0xFFFF5722)),
    WaypointCategory("water", "💧", "Agua / Fuente", Color(0xFF00E5FF)),
    WaypointCategory("danger", "⚠️", "Peligro", Color(0xFFFF1744)),
    WaypointCategory("fuel", "⛽", "Gasolina", Color(0xFF00E676)),
    WaypointCategory("camp", "🏕️", "Mirador", Color(0xFFAB47BC)),
    WaypointCategory("flag", "📍", "Punto General", Color(0xFFFFC107))
)

@Composable
fun SaveWaypointDialog(
    initialName: String,
    initialSymbol: String = "flag",
    coordinate: GeoCoord,
    onSave: (name: String, symbol: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var selectedSymbol by remember { mutableStateOf(initialSymbol) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = EnduPalette.Surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Título
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Guardar Waypoint",
                    color = EnduPalette.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "✕",
                    color = EnduPalette.TextMuted,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onDismiss() }.padding(4.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Selector de Categoría (Chips horizontales)
            Text(
                text = "TIPO DE WAYPOINT",
                color = EnduPalette.TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(WAYPOINT_CATEGORIES) { cat ->
                    val isSelected = cat.id.equals(selectedSymbol, ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isSelected) cat.color.copy(alpha = 0.25f) else EnduPalette.SurfaceHigh)
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) cat.color else EnduPalette.Outline,
                                shape = RoundedCornerShape(20.dp)
                            )
                            .clickable { selectedSymbol = cat.id }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = cat.icon, fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = cat.title,
                            color = if (isSelected) cat.color else EnduPalette.TextPrimary,
                            fontSize = 11.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Campo de Nombre
            Text(
                text = "NOMBRE DEL PUNTO",
                color = EnduPalette.TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))

            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Escribe un nombre identificativo...", color = EnduPalette.TextMuted, fontSize = 13.sp) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = EnduPalette.SurfaceHigh,
                    unfocusedContainerColor = EnduPalette.SurfaceHigh,
                    focusedIndicatorColor = EnduPalette.Accent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = EnduPalette.TextPrimary,
                    unfocusedTextColor = EnduPalette.TextPrimary
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Coordenadas
            val latStr = (coordinate.latitude * 10000.0).roundToInt() / 10000.0
            val lonStr = (coordinate.longitude * 10000.0).roundToInt() / 10000.0
            Text(
                text = "📍 $latStr, $lonStr",
                color = EnduPalette.TextMuted,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Botones Cancelar y Guardar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(EnduPalette.SurfaceHigh)
                        .clickable { onDismiss() }
                ) {
                    Text(
                        text = "Cancelar",
                        color = EnduPalette.TextMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1.4f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(EnduPalette.Accent)
                        .clickable {
                            val finalName = name.trim().ifEmpty { "Punto" }
                            onSave(finalName, selectedSymbol)
                            onDismiss()
                        }
                ) {
                    Text(
                        text = "✓ Guardar Waypoint",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

