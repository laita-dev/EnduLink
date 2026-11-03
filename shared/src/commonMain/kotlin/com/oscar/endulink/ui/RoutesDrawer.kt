package com.oscar.endulink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.oscar.endulink.trail.GPXRoute

@Composable
fun RoutesDrawer(
    routes: List<GPXRoute>,
    activeRouteId: String?,
    onSelectRoute: (GPXRoute) -> Unit,
    onToggleVisibility: (GPXRoute) -> Unit,
    onInvertRoute: (GPXRoute) -> Unit,
    onCycleColor: (GPXRoute) -> Unit,
    onImportGPX: () -> Unit,
    onLoadSample: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        colors = CardDefaults.cardColors(containerColor = EnduPalette.Surface),
        modifier = modifier.fillMaxWidth().fillMaxHeight(0.65f)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Cabecera
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Gestor de Rutas GPX",
                    color = EnduPalette.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "✕",
                    color = EnduPalette.TextMuted,
                    fontSize = 18.sp,
                    modifier = Modifier.clickable { onClose() }.padding(4.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Botón Importar GPX desde almacenamiento del móvil
            Button(
                onClick = onImportGPX,
                colors = ButtonDefaults.buttonColors(containerColor = EnduPalette.Accent),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "📂 Importar archivo .GPX del teléfono", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Botón secundario para pista de muestra
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "O cargar track de muestra (Guadarrama)",
                    color = EnduPalette.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable { onLoadSample() }
                        .padding(vertical = 4.dp, horizontal = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (routes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "No hay rutas cargadas", color = EnduPalette.TextMuted, fontSize = 14.sp)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(routes) { route ->
                        val isActive = route.id == activeRouteId
                        RouteItemRow(
                            route = route,
                            isActive = isActive,
                            onSelect = { onSelectRoute(route) },
                            onToggleVisibility = { onToggleVisibility(route) },
                            onInvert = { onInvertRoute(route) },
                            onCycleColor = { onCycleColor(route) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteItemRow(
    route: GPXRoute,
    isActive: Boolean,
    onSelect: () -> Unit,
    onToggleVisibility: () -> Unit,
    onInvert: () -> Unit,
    onCycleColor: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isActive) EnduPalette.SurfaceElevated else EnduPalette.Background)
            .clickable { onSelect() }
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            // Círculo de color selector
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color(route.color.rgbHex))
                    .clickable { onCycleColor() }
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column {
                Text(
                    text = route.name + if (route.isInverted) " (Inv)" else "",
                    color = if (isActive) EnduPalette.Accent else EnduPalette.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${route.totalDistanceKm} km · +${route.elevationGainM.toInt()} m",
                    color = EnduPalette.TextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Invertir
            Text(
                text = "🔄",
                fontSize = 16.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onInvert() }.padding(4.dp)
            )
            // Visibilidad
            Text(
                text = if (route.isVisible) "👁️" else "🙈",
                fontSize = 16.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onToggleVisibility() }.padding(4.dp)
            )
        }
    }
}

