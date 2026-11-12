package com.oscar.endulink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oscar.endulink.tiles.MapLayer

/**
 * Tarjeta flotante para cambio rápido de capa del mapa (1:1 con MotIOS `floatingLayersAndSliderCard`).
 * Permite cambiar entre Carreteras (Google Vial), Satélite Híbrido, Satélite Puro, Trail OSM e IGN Topo.
 */
@Composable
fun LayersFloatingCard(
    currentLayer: MapLayer,
    onSelectLayer: (MapLayer) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(EnduPalette.Surface.copy(alpha = 0.98f))
            .border(1.dp, EnduPalette.Outline, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Cabecera con título y botón de cierre
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LayersIcon(color = EnduPalette.Accent, size = 18.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Capas del Mapa (HD)",
                    color = EnduPalette.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                CloseCrossIcon(color = EnduPalette.TextMuted, size = 13.dp)
            }
        }

        // Lista / Cuadrícula de capas
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val layers = listOf(
                Triple(MapLayer.CARRETERAS, "Carreteras (Vial HD)", "Cartografía vial limpia estilo Apple Maps / MotIOS"),
                Triple(MapLayer.OSMAND, "OsmAnd Outdoor (HD)", "Mapa oficial OsmAnd de pistas, senderos y curvas (Offline)"),
                Triple(MapLayer.TRAIL_OSM, "Trail OSM (CyclOSM)", "Pistas de tierra, senderos, fuentes y curvas (Offline)"),
                Triple(MapLayer.IGN_TOPO, "IGN Topográfico", "MTN25 España oficial con curvas de nivel (Offline)"),
                Triple(MapLayer.SATELITE_HIBRIDO, "Satélite Híbrido (HD)", "Foto aérea con carreteras y localidades (Offline)"),
                Triple(MapLayer.SATELITE_PURO, "Satélite Puro (Esri)", "Ortofoto aérea limpia sin rótulos (Offline)")
            )

            for ((lay, label, desc) in layers) {
                val isSelected = (lay == currentLayer)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) EnduPalette.Accent.copy(alpha = 0.22f) else EnduPalette.SurfaceHigh)
                        .border(
                            width = if (isSelected) 1.5.dp else 0.5.dp,
                            color = if (isSelected) EnduPalette.Accent else EnduPalette.Outline,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelectLayer(lay) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = label,
                            color = if (isSelected) EnduPalette.Accent else EnduPalette.TextPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = desc,
                            color = EnduPalette.TextMuted,
                            fontSize = 10.5.sp
                        )
                    }

                    if (isSelected) {
                        Text(
                            text = "✓",
                            color = EnduPalette.Accent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

