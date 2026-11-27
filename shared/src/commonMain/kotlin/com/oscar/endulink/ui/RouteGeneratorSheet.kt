package com.oscar.endulink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
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
import com.oscar.endulink.trail.LoopDirection
import com.oscar.endulink.trail.RouteTerrainProfile
import kotlin.math.roundToInt

/**
 * Pantalla completa generadora de rutas estilo DMD2 con iconografía vectorial minimalista:
 * - Modo Punto a Punto (A -> B) o Ruta Circular (Loop por km y rumbo).
 * - Selector de tipo de camino y dificultad (Carretera, Curvas, Trail Fácil, Off-Road Medio, Enduro Técnico).
 */
@Composable
fun RouteGeneratorSheet(
    isLoopMode: Boolean,
    originPin: SearchResultItem?,
    destPin: SearchResultItem?,
    loopKm: Int,
    loopDirection: LoopDirection,
    selectedProfile: RouteTerrainProfile,
    onSetLoopMode: (Boolean) -> Unit,
    onResetOriginToGps: () -> Unit,
    onSearchOrigin: () -> Unit,
    onPickOriginOnMap: () -> Unit,
    onSearchDest: () -> Unit,
    onPickDestOnMap: () -> Unit,
    onSetLoopKm: (Int) -> Unit,
    onSetLoopDirection: (LoopDirection) -> Unit,
    onSelectProfile: (RouteTerrainProfile) -> Unit,
    onGenerateRoute: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = EnduPalette.Background
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    GeneratorHeader(onClose = onClose)
                }

                item {
                    GeneratorModeSwitch(
                        isLoopMode = isLoopMode,
                        onSetLoopMode = onSetLoopMode
                    )
                }

                item {
                    GeneratorPointsCard(
                        isLoopMode = isLoopMode,
                        originPin = originPin,
                        destPin = destPin,
                        onResetOriginToGps = onResetOriginToGps,
                        onSearchOrigin = onSearchOrigin,
                        onPickOriginOnMap = onPickOriginOnMap,
                        onSearchDest = onSearchDest,
                        onPickDestOnMap = onPickDestOnMap
                    )
                }

                if (isLoopMode) {
                    item {
                        GeneratorLoopConfigCard(
                            loopKm = loopKm,
                            loopDirection = loopDirection,
                            onSetLoopKm = onSetLoopKm,
                            onSetLoopDirection = onSetLoopDirection
                        )
                    }
                }

                item {
                    Text(
                        text = "TIPO DE RUTA Y DIFICULTAD",
                        color = EnduPalette.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.8.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                items(RouteTerrainProfile.entries.size) { idx ->
                    val profile = RouteTerrainProfile.entries[idx]
                    TerrainProfileOptionCard(
                        profile = profile,
                        selected = profile == selectedProfile,
                        onClick = { onSelectProfile(profile) }
                    )
                }

                item {
                    val canGenerate = isLoopMode || destPin != null
                    Button(
                        onClick = onGenerateRoute,
                        enabled = canGenerate,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EnduPalette.Accent,
                            disabledContainerColor = EnduPalette.SurfaceHigh
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        val contentColor = if (canGenerate) Color.White else EnduPalette.TextMuted
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (canGenerate) {
                                if (isLoopMode) {
                                    LoopRouteIcon(color = contentColor, size = 16.dp)
                                } else {
                                    RoutePathIcon(color = contentColor, size = 16.dp)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            val label = if (isLoopMode) {
                                "Generar Loop · $loopKm km (${selectedProfile.shortLabel})"
                            } else if (destPin != null) {
                                "Generar Ruta ${selectedProfile.shortLabel}"
                            } else {
                                "Selecciona un destino (Punto B)"
                            }
                            Text(
                                text = label,
                                color = contentColor,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }
        }
    }
}

@Composable
private fun GeneratorHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(EnduPalette.Accent.copy(alpha = 0.16f))
                    .border(1.dp, EnduPalette.Accent.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            ) {
                RoutePathIcon(color = EnduPalette.Accent, size = 18.dp)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Generador de Rutas",
                    color = EnduPalette.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Punto a punto o Loop circular por dificultad",
                    color = EnduPalette.TextMuted,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(EnduPalette.SurfaceHigh)
                .border(1.dp, EnduPalette.Outline, RoundedCornerShape(10.dp))
                .clickable { onClose() }
        ) {
            CloseCrossIcon(color = EnduPalette.TextPrimary, size = 14.dp)
        }
    }
}

@Composable
private fun GeneratorModeSwitch(
    isLoopMode: Boolean,
    onSetLoopMode: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(EnduPalette.Surface)
            .border(1.dp, EnduPalette.Outline, RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        GeneratorModeTab(
            title = "Punto a Punto",
            selected = !isLoopMode,
            icon = { tint -> RoutePathIcon(color = tint, size = 15.dp) },
            onClick = { onSetLoopMode(false) },
            modifier = Modifier.weight(1f)
        )
        GeneratorModeTab(
            title = "Circular (Loop)",
            selected = isLoopMode,
            icon = { tint -> LoopRouteIcon(color = tint, size = 15.dp) },
            onClick = { onSetLoopMode(true) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun GeneratorModeTab(
    title: String,
    selected: Boolean,
    icon: @Composable (Color) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = if (selected) Color.White else EnduPalette.TextSecondary
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) EnduPalette.Accent else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            icon(tint)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                color = tint,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun GeneratorPointsCard(
    isLoopMode: Boolean,
    originPin: SearchResultItem?,
    destPin: SearchResultItem?,
    onResetOriginToGps: () -> Unit,
    onSearchOrigin: () -> Unit,
    onPickOriginOnMap: () -> Unit,
    onSearchDest: () -> Unit,
    onPickDestOnMap: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(EnduPalette.Surface)
            .border(1.dp, EnduPalette.Outline, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PointPickerRow(
            badge = if (isLoopMode) "O" else "A",
            badgeColor = EnduPalette.Success,
            label = if (isLoopMode) "Punto de Salida / Llegada" else "Origen (Punto A)",
            value = originPin?.title ?: "Mi ubicación GPS actual",
            subtitle = originPin?.subtitle ?: "Usa tu posición en tiempo real",
            showResetGps = originPin != null,
            onResetGps = onResetOriginToGps,
            onSearch = onSearchOrigin,
            onPickOnMap = onPickOriginOnMap
        )

        if (!isLoopMode) {
            HorizontalDivider(color = EnduPalette.Outline)
            PointPickerRow(
                badge = "B",
                badgeColor = EnduPalette.Accent,
                label = "Destino (Punto B)",
                value = destPin?.title ?: "Elegir destino...",
                subtitle = destPin?.subtitle ?: "Busca un lugar o márcalo en el mapa",
                showResetGps = false,
                onResetGps = {},
                onSearch = onSearchDest,
                onPickOnMap = onPickDestOnMap
            )
        }
    }
}

@Composable
private fun PointPickerRow(
    badge: String,
    badgeColor: Color,
    label: String,
    value: String,
    subtitle: String,
    showResetGps: Boolean,
    onResetGps: () -> Unit,
    onSearch: () -> Unit,
    onPickOnMap: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(badgeColor.copy(alpha = 0.22f))
                    .border(1.dp, badgeColor, CircleShape)
            ) {
                Text(
                    text = badge,
                    color = badgeColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, color = EnduPalette.TextMuted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = value,
                    color = EnduPalette.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = EnduPalette.TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (showResetGps) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(EnduPalette.SurfaceHigh)
                        .clickable { onResetGps() }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Text(text = "GPS", color = EnduPalette.Success, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(EnduPalette.SurfaceHigh)
                    .clickable { onSearch() }
            ) {
                SearchLensIcon(color = EnduPalette.TextSecondary, size = 13.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Buscar",
                    color = EnduPalette.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(EnduPalette.Accent.copy(alpha = 0.16f))
                    .border(1.dp, EnduPalette.Accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .clickable { onPickOnMap() }
            ) {
                MapPinTargetIcon(color = EnduPalette.Accent, size = 14.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Marcar en mapa",
                    color = EnduPalette.Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun GeneratorLoopConfigCard(
    loopKm: Int,
    loopDirection: LoopDirection,
    onSetLoopKm: (Int) -> Unit,
    onSetLoopDirection: (LoopDirection) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(EnduPalette.Surface)
            .border(1.dp, EnduPalette.Outline, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Distancia aproximada del Loop",
                color = EnduPalette.TextPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "$loopKm km",
                color = EnduPalette.Accent,
                fontSize = 17.sp,
                fontWeight = FontWeight.Black
            )
        }

        Slider(
            value = loopKm.toFloat(),
            onValueChange = { onSetLoopKm(it.roundToInt()) },
            valueRange = 15f..250f,
            colors = SliderDefaults.colors(
                thumbColor = EnduPalette.Accent,
                activeTrackColor = EnduPalette.Accent,
                inactiveTrackColor = EnduPalette.SurfaceHigh
            )
        )

        val presets = listOf(25, 50, 80, 120, 180)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            presets.forEach { preset ->
                val selected = loopKm == preset
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (selected) EnduPalette.Accent else EnduPalette.SurfaceHigh)
                        .clickable { onSetLoopKm(preset) }
                ) {
                    Text(
                        text = "$preset km",
                        color = if (selected) Color.White else EnduPalette.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = "Orientación del recorrido",
            color = EnduPalette.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LoopDirection.entries.forEach { dir ->
                val selected = dir == loopDirection
                val tint = if (selected) EnduPalette.Accent else EnduPalette.TextPrimary
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) EnduPalette.Accent.copy(alpha = 0.22f) else EnduPalette.SurfaceHigh)
                        .border(
                            width = 1.dp,
                            color = if (selected) EnduPalette.Accent else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onSetLoopDirection(dir) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    LoopDirectionIcon(direction = dir, color = tint, size = 13.dp)
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = dir.label,
                        color = tint,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun TerrainProfileOptionCard(
    profile: RouteTerrainProfile,
    selected: Boolean,
    onClick: () -> Unit
) {
    val badgeColor = Color(profile.trackColor.rgbHex)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) badgeColor.copy(alpha = 0.14f) else EnduPalette.Surface)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) badgeColor else EnduPalette.Outline,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(badgeColor.copy(alpha = 0.16f))
        ) {
            TerrainProfileIcon(profile = profile, color = badgeColor, size = 19.dp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = profile.title,
                    color = EnduPalette.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(badgeColor.copy(alpha = 0.22f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = profile.difficultyBadge,
                        color = badgeColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1
                    )
                }
            }
            Text(
                text = profile.subtitle,
                color = EnduPalette.TextSecondary,
                fontSize = 11.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selected) {
            Spacer(modifier = Modifier.width(8.dp))
            CheckMarkIcon(color = badgeColor, size = 16.dp)
        }
    }
}

/**
 * Tira horizontal compacta reutilizable para elegir o cambiar al instante el perfil de terreno
 * desde la búsqueda de direcciones, pin marcado o tarjeta de ruta generada.
 */
@Composable
fun TerrainProfileSelectorRow(
    selectedProfile: RouteTerrainProfile,
    onSelectProfile: (RouteTerrainProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RouteTerrainProfile.entries.forEach { profile ->
            val selected = profile == selectedProfile
            val accent = Color(profile.trackColor.rgbHex)
            val tint = if (selected) accent else EnduPalette.TextPrimary
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (selected) accent.copy(alpha = 0.22f) else EnduPalette.SurfaceHigh)
                    .border(
                        width = 1.dp,
                        color = if (selected) accent else Color.Transparent,
                        shape = RoundedCornerShape(7.dp)
                    )
                    .clickable { onSelectProfile(profile) }
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                TerrainProfileIcon(profile = profile, color = tint, size = 13.dp)
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = profile.shortLabel,
                    color = tint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
