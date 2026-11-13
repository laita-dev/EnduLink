package com.oscar.endulink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextOverflow
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
    onOpenElevation: (GPXRoute) -> Unit = {},
    onEditRoute: (GPXRoute) -> Unit = {},
    onDownloadCorridor: (GPXRoute) -> Unit = {},
    onShareRoute: (GPXRoute) -> Unit = {},
    onDeleteRoute: (GPXRoute) -> Unit = {},
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    FolderImportIcon(color = Color.White, size = 16.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Importar archivo .GPX del teléfono",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
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
                            onCycleColor = { onCycleColor(route) },
                            onOpenElevation = { onOpenElevation(route) },
                            onEdit = { onEditRoute(route) },
                            onDownloadCorridor = { onDownloadCorridor(route) },
                            onShare = { onShareRoute(route) },
                            onDelete = { onDeleteRoute(route) }
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
    onCycleColor: () -> Unit,
    onOpenElevation: () -> Unit,
    onEdit: () -> Unit,
    onDownloadCorridor: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isActive) EnduPalette.SurfaceElevated else EnduPalette.Background)
            .border(
                width = 1.dp,
                color = if (isActive) EnduPalette.Accent.copy(alpha = 0.6f) else EnduPalette.Outline,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onSelect() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Fila superior: Color + Nombre completo + Distancia/Desnivel + Insignia activa
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            ) {
                // Círculo de color selector
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color(route.color.rgbHex))
                        .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                        .clickable { onCycleColor() }
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = route.name + if (route.isInverted) " (Inv)" else "",
                        color = if (isActive) EnduPalette.Accent else EnduPalette.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${route.totalDistanceKm} km · +${route.elevationGainM.toInt()} m",
                        color = EnduPalette.TextSecondary,
                        fontSize = 11.5.sp,
                        maxLines = 1
                    )
                }
            }

            if (isActive) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(EnduPalette.Accent.copy(alpha = 0.2f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "ACTIVA",
                        color = EnduPalette.Accent,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }

        // Fila inferior: botones de acción vectoriales 100% alineados en cuadrícula uniforme
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Editar nombre, color y ajustes del track
            RouteActionIconButton(
                onClick = onEdit,
                modifier = Modifier.weight(1f)
            ) {
                EditPencilIcon(color = EnduPalette.TextPrimary, size = 15.dp)
            }
            // 2. Descargar corredor de mapa offline alrededor del track
            RouteActionIconButton(
                onClick = onDownloadCorridor,
                modifier = Modifier.weight(1f)
            ) {
                DownloadTrayIcon(color = EnduPalette.TextPrimary, size = 15.dp)
            }
            // 2. Compartir / Exportar .GPX
            RouteActionIconButton(
                onClick = onShare,
                modifier = Modifier.weight(1f)
            ) {
                ShareUploadIcon(color = EnduPalette.TextPrimary, size = 15.dp)
            }
            // 3. Perfil de altitud
            RouteActionIconButton(
                onClick = onOpenElevation,
                modifier = Modifier.weight(1f)
            ) {
                ElevationChartIcon(color = Color(0xFF00D4FF), size = 15.dp)
            }
            // 4. Invertir sentido del track
            RouteActionIconButton(
                onClick = onInvert,
                containerColor = if (route.isInverted) EnduPalette.Accent.copy(alpha = 0.22f) else EnduPalette.SurfaceHigh,
                modifier = Modifier.weight(1f)
            ) {
                InvertRouteArrowsIcon(
                    color = if (route.isInverted) EnduPalette.Accent else EnduPalette.TextPrimary,
                    size = 15.dp
                )
            }
            // 5. Visibilidad en el mapa
            RouteActionIconButton(
                onClick = onToggleVisibility,
                modifier = Modifier.weight(1f)
            ) {
                EyeVisibilityIcon(
                    visible = route.isVisible,
                    color = if (route.isVisible) EnduPalette.TextPrimary else EnduPalette.TextMuted,
                    size = 15.dp
                )
            }
            // 6. Eliminar track
            RouteActionIconButton(
                onClick = onDelete,
                containerColor = EnduPalette.Danger.copy(alpha = 0.15f),
                modifier = Modifier.weight(1f)
            ) {
                TrashDeleteIcon(color = EnduPalette.Danger, size = 15.dp)
            }
        }
    }
}

@Composable
private fun RouteActionIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = EnduPalette.SurfaceHigh,
    content: @Composable () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .clickable { onClick() }
    ) {
        content()
    }
}

