package com.oscar.endulink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oscar.endulink.platform.getPlatformName
import com.oscar.endulink.trail.AppDisplaySettings
import com.oscar.endulink.trail.HudButtonSlot
import com.oscar.endulink.trail.HudButtonsLayout
import com.oscar.endulink.trail.HudLayoutPreset
import com.oscar.endulink.trail.KeepScreenOnMode
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun AppSettingsScreen(
    settings: AppDisplaySettings,
    showGpxOffTrackWarning: Boolean,
    downloadedPackagesCount: Int,
    onUpdateSettings: ((AppDisplaySettings) -> AppDisplaySettings) -> Unit,
    onSetGpxOffTrackWarning: (Boolean) -> Unit,
    onResetSettings: () -> Unit,
    onOpenOfflineMaps: () -> Unit,
    onUpdateAvailabilityChanged: (Boolean) -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val isAndroid = remember { getPlatformName().startsWith("Android", ignoreCase = true) }

    Surface(
        color = EnduPalette.Background,
        modifier = modifier.fillMaxSize()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ==========================================
            // BARRA SUPERIOR A PANTALLA COMPLETA
            // ==========================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "← Volver",
                    color = EnduPalette.Accent,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onClose() }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )

                Text(
                    text = "Ajustes",
                    color = EnduPalette.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Restablecer",
                    color = EnduPalette.TextSecondary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onResetSettings() }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }

            HorizontalDivider(color = EnduPalette.Outline.copy(alpha = 0.5f))

            // ==========================================
            // CONTENIDO DESPLAZABLE CENTRADO
            // ==========================================
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 680.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // ------------------------------------------
                    // VISTA PREVIA EN VIVO DEL TAMAÑO DE FUENTE
                    // ------------------------------------------
                    LiveTypographyPreviewCard(settings = settings)

                    // ------------------------------------------
                    // TARJETA 1: PANTALLA Y LEGIBILIDAD
                    // ------------------------------------------
                    SettingsSectionCard(title = "PANTALLA Y LEGIBILIDAD") {
                        // 1. Slider continuo + presets para fuente en móvil
                        val hudPct = (settings.hudFontScale * 100f).roundToInt()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Tamaño de fuente en pantalla",
                                    color = EnduPalette.TextPrimary,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Amplía km, velocidad, altitud, avisos y waypoints en el móvil",
                                    color = EnduPalette.TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(EnduPalette.Accent.copy(alpha = 0.2f))
                                    .border(1.dp, EnduPalette.Accent.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "$hudPct%",
                                    color = EnduPalette.Accent,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Slider(
                            value = settings.hudFontScale,
                            onValueChange = { raw ->
                                val stepped = (raw * 20f).roundToInt() / 20f
                                onUpdateSettings { it.copy(hudFontScale = stepped) }
                            },
                            valueRange = 0.9f..1.8f,
                            steps = 17,
                            colors = SliderDefaults.colors(
                                thumbColor = EnduPalette.Accent,
                                activeTrackColor = EnduPalette.Accent,
                                inactiveTrackColor = EnduPalette.SurfaceHigh
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        val mobilePresets = listOf(
                            "Normal" to 1.0f,
                            "Grande" to 1.25f,
                            "Muy grd." to 1.5f,
                            "XL" to 1.75f
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            mobilePresets.forEach { (label, scale) ->
                                val selected = abs(settings.hudFontScale - scale) < 0.03f
                                SettingsChoiceChip(
                                    label = label,
                                    selected = selected,
                                    onClick = { onUpdateSettings { it.copy(hudFontScale = scale) } },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        HorizontalDivider(color = EnduPalette.Outline.copy(alpha = 0.45f))

                        // 2. Escala de HUD y waypoints en pantalla TFT de la moto
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Tamaño de datos en pantalla TFT (Moto)",
                                color = EnduPalette.TextPrimary,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Amplía km restantes, ETA, altitud, hora y waypoints enviados al cuadro TFT",
                                color = EnduPalette.TextMuted,
                                fontSize = 11.sp
                            )
                            val tftPresets = listOf(
                                "Normal 1.0x" to 1.0f,
                                "Grande 1.2x" to 1.2f,
                                "Extra 1.4x" to 1.4f
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                tftPresets.forEach { (label, scale) ->
                                    val selected = abs(settings.tftHudFontScale - scale) < 0.05f
                                    SettingsChoiceChip(
                                        label = label,
                                        selected = selected,
                                        onClick = { onUpdateSettings { it.copy(tftHudFontScale = scale) } },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = EnduPalette.Outline.copy(alpha = 0.45f))

                        // 3. Mostrar etiquetas de waypoints en mapa
                        SettingsSwitchRow(
                            title = "Mostrar nombres de waypoints",
                            subtitle = "Desactívalo si un track tiene muchos puntos y prefieres ver solo las chinchetas",
                            checked = settings.showWaypointLabelsOnMap,
                            onCheckedChange = { checked ->
                                onUpdateSettings { it.copy(showWaypointLabelsOnMap = checked) }
                            }
                        )

                        HorizontalDivider(color = EnduPalette.Outline.copy(alpha = 0.45f))

                        // 4. Telemetría siempre visible
                        SettingsSwitchRow(
                            title = "Telemetría siempre visible",
                            subtitle = "Muestra velocidad y altitud arriba aunque no tengas una ruta iniciada",
                            checked = settings.alwaysShowTelemetryBar,
                            onCheckedChange = { checked ->
                                onUpdateSettings { it.copy(alwaysShowTelemetryBar = checked) }
                            }
                        )

                        HorizontalDivider(color = EnduPalette.Outline.copy(alpha = 0.45f))

                        // 5. Mantener pantalla encendida
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Mantener pantalla encendida",
                                color = EnduPalette.TextPrimary,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Evita que el móvil se apague si lo llevas en soporte de manillar",
                                color = EnduPalette.TextMuted,
                                fontSize = 11.sp
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                SettingsChoiceChip(
                                    label = "No",
                                    selected = settings.keepScreenOnMode == KeepScreenOnMode.OFF,
                                    onClick = { onUpdateSettings { it.copy(keepScreenOnMode = KeepScreenOnMode.OFF) } },
                                    modifier = Modifier.weight(1f)
                                )
                                SettingsChoiceChip(
                                    label = "En ruta / REC",
                                    selected = settings.keepScreenOnMode == KeepScreenOnMode.WHILE_ACTIVE,
                                    onClick = { onUpdateSettings { it.copy(keepScreenOnMode = KeepScreenOnMode.WHILE_ACTIVE) } },
                                    modifier = Modifier.weight(1.3f)
                                )
                                SettingsChoiceChip(
                                    label = "Siempre",
                                    selected = settings.keepScreenOnMode == KeepScreenOnMode.ALWAYS,
                                    onClick = { onUpdateSettings { it.copy(keepScreenOnMode = KeepScreenOnMode.ALWAYS) } },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // ------------------------------------------
                    // TARJETA 2: MAPA Y NAVEGACIÓN GPX
                    // ------------------------------------------
                    SettingsSectionCard(title = "MAPA Y NAVEGACIÓN GPX") {
                        // Grosor de la traza GPX
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Grosor de la línea del track GPX",
                                color = EnduPalette.TextPrimary,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Aumenta el grosor para ver mejor el track bajo sol directo",
                                color = EnduPalette.TextMuted,
                                fontSize = 11.sp
                            )
                            val strokePresets = listOf(
                                "Fino" to 0.8f,
                                "Normal" to 1.0f,
                                "Grueso" to 1.4f
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                strokePresets.forEach { (label, scale) ->
                                    val selected = abs(settings.trackStrokeScale - scale) < 0.08f
                                    SettingsChoiceChip(
                                        label = label,
                                        selected = selected,
                                        onClick = { onUpdateSettings { it.copy(trackStrokeScale = scale) } },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = EnduPalette.Outline.copy(alpha = 0.45f))

                        // Flechas de dirección
                        SettingsSwitchRow(
                            title = "Flechas de sentido en el track",
                            subtitle = "Dibuja chevrones blancos indicando la dirección de avance",
                            checked = settings.showTrackDirectionArrows,
                            onCheckedChange = { checked ->
                                onUpdateSettings { it.copy(showTrackDirectionArrows = checked) }
                            }
                        )

                        HorizontalDivider(color = EnduPalette.Outline.copy(alpha = 0.45f))

                        // Aviso de salida de GPX
                        SettingsSwitchRow(
                            title = "Aviso de salida de track GPX",
                            subtitle = "Muestra alerta y línea directa al track tanto en móvil como en TFT",
                            checked = showGpxOffTrackWarning,
                            onCheckedChange = onSetGpxOffTrackWarning
                        )

                        // Tolerancia fuera de track
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Tolerancia fuera de track",
                                color = if (showGpxOffTrackWarning) EnduPalette.TextPrimary else EnduPalette.TextMuted,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Distancia a la que salta el aviso (subirla evita avisos en zonas con poco GPS)",
                                color = EnduPalette.TextMuted,
                                fontSize = 11.sp
                            )
                            val thresholds = listOf(35, 50, 80, 100)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                thresholds.forEach { meters ->
                                    val selected = settings.gpxOffTrackThresholdMeters == meters
                                    SettingsChoiceChip(
                                        label = "$meters m",
                                        selected = selected,
                                        onClick = { onUpdateSettings { it.copy(gpxOffTrackThresholdMeters = meters) } },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    // ------------------------------------------
                    // TARJETA 3: BOTONES EN PANTALLA (HUD)
                    // ------------------------------------------
                    HudLayoutSettingsCard(
                        layout = settings.hudLayout.normalized(),
                        onUpdateLayout = { updatedLayout ->
                            onUpdateSettings { it.copy(hudLayout = updatedLayout.normalized()) }
                        }
                    )

                    // ------------------------------------------
                    // TARJETA 4: MAPAS OFFLINE Y ALMACENAMIENTO
                    // ------------------------------------------
                    SettingsSectionCard(title = "MAPAS OFFLINE") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(EnduPalette.SurfaceHigh)
                                .clickable { onOpenOfflineMaps() }
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                DownloadTrayIcon(color = EnduPalette.Accent, size = 18.dp)
                                Column {
                                    Text(
                                        text = "Gestionar mapas descargados",
                                        color = EnduPalette.TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = if (downloadedPackagesCount == 0) {
                                            "Ningún paquete offline descargado aún"
                                        } else {
                                            "$downloadedPackagesCount paquete${if (downloadedPackagesCount > 1) "s" else ""} disponible${if (downloadedPackagesCount > 1) "s" else ""} sin cobertura"
                                        },
                                        color = EnduPalette.TextMuted,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            Text(
                                text = "Abrir →",
                                color = EnduPalette.Accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // ------------------------------------------
                    // TARJETA 4 (ABAJO): ACTUALIZACIONES Y VERSIÓN
                    // ------------------------------------------
                    AppUpdateSectionCard(
                        onUpdateAvailabilityChanged = onUpdateAvailabilityChanged,
                        onInstallStarted = onClose
                    )

                    // Pie de pantalla
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = if (isAndroid) "EnduLink para Android" else "EnduLink para iOS",
                            color = EnduPalette.TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "© 2026 Oscar Laita (laita-dev) · Todos los derechos reservados",
                            color = EnduPalette.TextMuted.copy(alpha = 0.75f),
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTypographyPreviewCard(settings: AppDisplaySettings) {
    val s = settings.hudFontScale
    val labelSp = (9f * s.coerceAtMost(1.45f)).sp
    val valueSp = (16f * s).sp
    val unitSp = (10f * s.coerceAtMost(1.5f)).sp
    val wptSp = (10f * s).sp

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = EnduPalette.Surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, EnduPalette.Accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "VISTA PREVIA EN VIVO",
                    color = EnduPalette.Accent,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Móvil ${(s * 100f).roundToInt()}% · TFT ${(settings.tftHudFontScale * 100f).roundToInt()}%",
                    color = EnduPalette.TextMuted,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Mini barra de telemetría real escalada
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(EnduPalette.Background)
                    .border(1.dp, EnduPalette.Outline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PreviewTelemetryCol("VEL", "64", "km/h", EnduPalette.TextPrimary, labelSp, valueSp, unitSp)
                PreviewTelemetryCol("RESTAN", "18.4", "km", EnduPalette.Success, labelSp, valueSp, unitSp)
                PreviewTelemetryCol("ALT", "1240", "m", EnduPalette.Accent, labelSp, valueSp, unitSp)
            }

            // Mini muestra de waypoint y línea de track
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(EnduPalette.Background)
                    .border(1.dp, EnduPalette.Outline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    val pinScale = 1f + (s - 1f) * 0.45f
                    Box(
                        modifier = Modifier
                            .size((14f * pinScale).dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E676))
                            .border(2.dp, Color.Black, CircleShape)
                    )
                    if (settings.showWaypointLabelsOnMap) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color(0xEE0B111A))
                                .border(1.dp, Color(0xFF00E676), RoundedCornerShape(5.dp))
                                .padding(horizontal = (6f * pinScale).dp, vertical = (3f * pinScale).dp)
                        ) {
                            Text(
                                text = "Gasolinera · 4.2 km",
                                color = Color.White,
                                fontSize = wptSp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        Text(
                            text = "Nombres ocultos (solo chincheta)",
                            color = EnduPalette.TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }

                // Muestra visual del grosor de línea GPX
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .width(42.dp)
                        .height((4.5f * settings.trackStrokeScale).dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(EnduPalette.Accent)
                )
            }
        }
    }
}

@Composable
private fun PreviewTelemetryCol(
    label: String,
    value: String,
    unit: String,
    color: Color,
    labelSp: androidx.compose.ui.unit.TextUnit,
    valueSp: androidx.compose.ui.unit.TextUnit,
    unitSp: androidx.compose.ui.unit.TextUnit
) {
    Column {
        Text(
            text = label,
            color = EnduPalette.TextMuted,
            fontSize = labelSp,
            fontWeight = FontWeight.Bold
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = color,
                fontSize = valueSp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = unit,
                color = EnduPalette.TextMuted,
                fontSize = unitSp,
                modifier = Modifier.padding(bottom = 1.dp)
            )
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = EnduPalette.Surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                color = EnduPalette.TextMuted,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold
            )
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                text = title,
                color = EnduPalette.TextPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = EnduPalette.TextMuted,
                fontSize = 11.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = EnduPalette.Accent,
                uncheckedThumbColor = EnduPalette.TextSecondary,
                uncheckedTrackColor = EnduPalette.SurfaceHigh
            )
        )
    }
}

@Composable
private fun SettingsChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) EnduPalette.Accent else EnduPalette.SurfaceHigh)
            .border(
                width = 1.dp,
                color = if (selected) EnduPalette.Accent else EnduPalette.Outline,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 6.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else EnduPalette.TextPrimary,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HudLayoutSettingsCard(
    layout: HudButtonsLayout,
    onUpdateLayout: (HudButtonsLayout) -> Unit
) {
    SettingsSectionCard(title = "BOTONES EN PANTALLA (HUD)") {
        Text(
            text = "Distribuye los botones por las guías de la pantalla (Izquierda, Derecha o Barra inferior) o elige una plantilla rápida.",
            color = EnduPalette.TextMuted,
            fontSize = 11.sp
        )

        HudLayoutMiniPreview(layout = layout)

        Text(
            text = "Plantillas predeterminadas",
            color = EnduPalette.TextPrimary,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HudLayoutPreset.entries.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    pair.forEach { preset ->
                        val presetLayout = HudButtonsLayout.fromPreset(preset)
                        val isSelected = layout == presetLayout
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (isSelected) EnduPalette.Accent.copy(alpha = 0.16f) else EnduPalette.SurfaceHigh)
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) EnduPalette.Accent else EnduPalette.Outline,
                                    shape = RoundedCornerShape(9.dp)
                                )
                                .clickable { onUpdateLayout(presetLayout) }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = preset.title,
                                color = if (isSelected) EnduPalette.Accent else EnduPalette.TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = preset.subtitle,
                                color = EnduPalette.TextMuted,
                                fontSize = 10.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = EnduPalette.Outline.copy(alpha = 0.45f))

        Text(
            text = "Posición individual por botón",
            color = EnduPalette.TextPrimary,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold
        )

        val actionSlots = listOf(HudButtonSlot.BOTTOM, HudButtonSlot.RIGHT, HudButtonSlot.LEFT, HudButtonSlot.HIDDEN)
        val requiredActionSlots = listOf(HudButtonSlot.BOTTOM, HudButtonSlot.RIGHT, HudButtonSlot.LEFT)
        val sideOnlySlots = listOf(HudButtonSlot.RIGHT, HudButtonSlot.LEFT, HudButtonSlot.HIDDEN)
        val gpsSlots = listOf(HudButtonSlot.RIGHT, HudButtonSlot.LEFT)

        HudSlotConfigRow("Grabación REC", layout.recSlot, actionSlots) { onUpdateLayout(layout.copy(recSlot = it)) }
        HudSlotConfigRow("Tracks", layout.routesSlot, requiredActionSlots) { onUpdateLayout(layout.copy(routesSlot = it)) }
        HudSlotConfigRow("Crear Ruta", layout.routeGeneratorSlot, actionSlots) { onUpdateLayout(layout.copy(routeGeneratorSlot = it)) }
        HudSlotConfigRow("Marcador Rápido", layout.quickPinSlot, actionSlots) { onUpdateLayout(layout.copy(quickPinSlot = it)) }
        HudSlotConfigRow("Lista de Puntos", layout.waypointsSlot, actionSlots) { onUpdateLayout(layout.copy(waypointsSlot = it)) }
        HudSlotConfigRow("Grupo en Vivo", layout.groupSlot, actionSlots) { onUpdateLayout(layout.copy(groupSlot = it)) }
        HudSlotConfigRow("Pantalla Moto", layout.bikeSlot, actionSlots) { onUpdateLayout(layout.copy(bikeSlot = it)) }
        HudSlotConfigRow("Brújula", layout.compassSlot, sideOnlySlots) { onUpdateLayout(layout.copy(compassSlot = it)) }
        HudSlotConfigRow("Zoom + / -", layout.zoomSlot, sideOnlySlots) { onUpdateLayout(layout.copy(zoomSlot = it)) }
        HudSlotConfigRow("Centrar GPS", layout.gpsSide, gpsSlots) { onUpdateLayout(layout.copy(gpsSide = it)) }
    }
}

@Composable
private fun HudLayoutMiniPreview(layout: HudButtonsLayout) {
    fun itemsIn(slot: HudButtonSlot): List<String> {
        val list = mutableListOf<String>()
        if (slot != HudButtonSlot.BOTTOM) {
            if (layout.compassSlot == slot) list.add("N")
            if (layout.zoomSlot == slot) list.add("+/-")
        }
        if (layout.recSlot == slot) list.add("REC")
        if (layout.routesSlot == slot) list.add("Tracks")
        if (layout.routeGeneratorSlot == slot) list.add("Crear")
        if (layout.quickPinSlot == slot) list.add("Pin")
        if (layout.waypointsSlot == slot) list.add("Pts")
        if (layout.groupSlot == slot) list.add("Grupo")
        if (layout.bikeSlot == slot) list.add("Moto")
        if (slot != HudButtonSlot.BOTTOM && layout.gpsSide == slot) list.add("GPS")
        return list
    }

    val leftItems = itemsIn(HudButtonSlot.LEFT)
    val rightItems = itemsIn(HudButtonSlot.RIGHT)
    val bottomItems = itemsIn(HudButtonSlot.BOTTOM)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(EnduPalette.Background)
            .border(1.dp, EnduPalette.Outline, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Izq: ${leftItems.joinToString(" · ").ifEmpty { "Vacía" }}",
                color = if (leftItems.isNotEmpty()) EnduPalette.TextPrimary else EnduPalette.TextMuted,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Der: ${rightItems.joinToString(" · ").ifEmpty { "Vacía" }}",
                color = if (rightItems.isNotEmpty()) EnduPalette.TextPrimary else EnduPalette.TextMuted,
                fontSize = 10.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(7.dp))
                .background(EnduPalette.SurfaceHigh)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Abajo:",
                color = EnduPalette.Accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = bottomItems.joinToString(" · ").ifEmpty { "Oculta cuando no hay ruta" },
                color = EnduPalette.TextPrimary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HudSlotConfigRow(
    label: String,
    selectedSlot: HudButtonSlot,
    options: List<HudButtonSlot>,
    onSelect: (HudButtonSlot) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            color = EnduPalette.TextSecondary,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            options.forEach { slot ->
                val selected = slot == selectedSlot
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(29.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (selected) EnduPalette.Accent else EnduPalette.SurfaceHigh)
                        .clickable { onSelect(slot) }
                        .padding(horizontal = 4.dp)
                ) {
                    Text(
                        text = slot.shortLabel,
                        color = if (selected) Color.White else EnduPalette.TextMuted,
                        fontSize = 10.5.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
