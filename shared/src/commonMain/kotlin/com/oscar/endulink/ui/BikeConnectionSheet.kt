package com.oscar.endulink.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oscar.endulink.bike.BikeMapConfig
import com.oscar.endulink.bike.BikeQr
import com.oscar.endulink.bike.BikeState
import com.oscar.endulink.bike.WifiSnapshot
import com.oscar.endulink.platform.EncoderStats
import com.oscar.endulink.tiles.MapLayer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable
fun BikeConnectionSheet(
    state: BikeState,
    bikeQr: BikeQr?,
    stats: EncoderStats,
    logs: List<String>,
    config: BikeMapConfig,
    phoneLayer: MapLayer,
    livePreviewBitmap: ImageBitmap?,
    allowLocalLanTesting: Boolean,
    wifiSnapshot: WifiSnapshot?,
    onSetZoom: (Int) -> Unit,
    onSetOrientation: (String) -> Unit,
    onSetTileMagnification: (Double) -> Unit,
    onSetAutoZoomBySpeed: (Boolean) -> Unit,
    onSetBitrate: (Int) -> Unit,
    onSetBikeMapLayer: (MapLayer?) -> Unit,
    onSetAllowLocalLanTesting: (Boolean) -> Unit,
    onSaveQr: (String) -> Boolean,
    onStartStream: () -> Unit,
    onStopStream: () -> Unit,
    onKnock: (String) -> Unit,
    onClearLogs: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showQrInputDialog by remember { mutableStateOf(false) }
    var qrTextInput by remember { mutableStateOf("") }
    var qrError by remember { mutableStateOf<String?>(null) }
    var showLogsDialog by remember { mutableStateOf(false) }
    var bikeIpInput by remember { mutableStateOf(wifiSnapshot?.gatewayCandidateIP ?: "192.168.43.1") }
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    val isRunning = (state != BikeState.CLOSED)

    val (statusColor, statusTitle, statusSubtitle) = when {
        !isRunning -> Triple(
            EnduPalette.TextMuted,
            "Sin conectar",
            "Listo para transmitir por Wi-Fi"
        )
        state == BikeState.SHOWING -> Triple(
            EnduPalette.Success,
            "Enviando mapa a la moto",
            "Vídeo H.264 activo en segundo plano"
        )
        state == BikeState.GREETED -> Triple(
            EnduPalette.Accent,
            "Pantalla detectada · Negociando",
            "Sesión establecida"
        )
        state == BikeState.LISTENING -> Triple(
            EnduPalette.Warning,
            "Buscando pantalla Wi-Fi...",
            "Llamando en puerto 10930 y escuchando en 10920-10922"
        )
        else -> Triple(
            EnduPalette.TextMuted,
            "Cerrado",
            "Detenido"
        )
    }

    Card(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        colors = CardDefaults.cardColors(containerColor = EnduPalette.Background),
        modifier = modifier.fillMaxWidth().fillMaxHeight(0.92f)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ==========================================
            // BARRA SUPERIOR (1:1 con MotIOS NavigationBar)
            // ==========================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Cerrar",
                    color = EnduPalette.Accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { onClose() }
                        .padding(4.dp)
                )

                Text(
                    text = "Enviar a Pantalla de Moto",
                    color = EnduPalette.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showLogsDialog = true }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text(text = "📄", fontSize = 13.sp)
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "Registro",
                        color = EnduPalette.TextPrimary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Divider(color = EnduPalette.Outline.copy(alpha = 0.5f))

            // ==========================================
            // CONTENIDO DESPLAZABLE (Tarjetas 1:1 MotIOS)
            // ==========================================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {

                // ------------------------------------------
                // TARJETA 1: Estado de Transmisión
                // ------------------------------------------
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
                            text = "ESTADO DE TRANSMISIÓN",
                            color = EnduPalette.TextMuted,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )

                        // Fila de estado
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = statusTitle,
                                    color = EnduPalette.TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = statusSubtitle,
                                    color = EnduPalette.TextMuted,
                                    fontSize = 11.5.sp
                                )
                            }

                            if (state == BikeState.SHOWING) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "${stats.fps.toInt()} FPS",
                                        color = EnduPalette.Success,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = "${stats.bitrateKbps} kbps",
                                        color = EnduPalette.TextMuted,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }

                        // Botón de Acción Principal (Conectar / Detener)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isRunning) EnduPalette.Danger else EnduPalette.Accent)
                                .clickable {
                                    if (isRunning) onStopStream() else onStartStream()
                                }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (isRunning) "⏹️" else "📡",
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isRunning) "Detener envío a la moto" else "Conectar y enviar mapa",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Text(
                            text = "El mapa enviado a la moto va siempre centrado en tu GPS (puedes mover libremente el mapa del teléfono o bloquear la pantalla en el bolsillo).",
                            color = EnduPalette.TextMuted,
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp
                        )
                    }
                }

                // ------------------------------------------
                // TARJETA 2: Ajustes Independientes en la Pantalla de la Moto
                // ------------------------------------------
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
                            text = "AJUSTES INDEPENDIENTES EN LA PANTALLA DE LA MOTO",
                            color = EnduPalette.TextMuted,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )

                        // Fila con Preview y Controles de Zoom/Orientación/Tamaño
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // 1. VISTA PREVIA (Aspect ratio 592 / 816)
                            Box(
                                modifier = Modifier
                                    .width(127.dp)
                                    .height(175.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(EnduPalette.SurfaceHigh)
                                    .border(1.dp, EnduPalette.Outline, RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (livePreviewBitmap != null) {
                                    Image(
                                        bitmap = livePreviewBitmap,
                                        contentDescription = "Vista Previa Pantalla Moto",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                } else {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(8.dp)
                                    ) {
                                        MotorcycleSilhouetteIcon(
                                            color = EnduPalette.TextMuted,
                                            size = 26.dp
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "Vista previa TFT",
                                            color = EnduPalette.TextMuted,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            // 2. CONTROLES INDEPENDIENTES LATERALES
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Zoom en moto
                                Text(
                                    text = "Zoom en moto: Z${config.zoom}",
                                    color = EnduPalette.TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(34.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(EnduPalette.SurfaceHigh)
                                            .clickable { onSetZoom(max(8, config.zoom - 1)) }
                                    ) {
                                        Text("−", color = EnduPalette.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(34.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(EnduPalette.SurfaceHigh)
                                            .clickable { onSetZoom(min(18, config.zoom + 1)) }
                                    ) {
                                        Text("+", color = EnduPalette.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                // Orientación en moto
                                Text(
                                    text = "Orientación en moto",
                                    color = EnduPalette.TextMuted,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    val isNorth = (config.orientation == "north")
                                    val isHeading = (config.orientation == "heading")

                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(32.dp)
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(if (isNorth) EnduPalette.Accent else EnduPalette.SurfaceHigh)
                                            .clickable { onSetOrientation("north") }
                                    ) {
                                        Text(
                                            text = "🧭 Norte",
                                            color = if (isNorth) Color.White else EnduPalette.TextPrimary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(32.dp)
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(if (isHeading) EnduPalette.Accent else EnduPalette.SurfaceHigh)
                                            .clickable { onSetOrientation("heading") }
                                    ) {
                                        Text(
                                            text = "▲ Rumbo",
                                            color = if (isHeading) Color.White else EnduPalette.TextPrimary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                // Tamaño texto/mapa
                                Text(
                                    text = "Tamaño texto/mapa",
                                    color = EnduPalette.TextMuted,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    val options = listOf(
                                        Triple("Peq", 1.10, abs(config.tileMagnification - 1.10) < 0.12),
                                        Triple("Med", 1.45, abs(config.tileMagnification - 1.45) < 0.12),
                                        Triple("Grd", 1.80, abs(config.tileMagnification - 1.80) < 0.12)
                                    )

                                    options.forEach { (label, value, isSelected) ->
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(28.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (isSelected) EnduPalette.Accent else EnduPalette.SurfaceHigh)
                                                .clickable { onSetTileMagnification(value) }
                                        ) {
                                            Text(
                                                text = label,
                                                color = if (isSelected) Color.White else EnduPalette.TextPrimary,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Bitrate de vídeo H.264
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Bitrate vídeo H.264",
                                color = EnduPalette.TextPrimary,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                val bitrateOptions = listOf(
                                    "Auto" to 0,
                                    "2 Mbps" to 2000,
                                    "3 Mbps" to 3000,
                                    "5 Mbps" to 5000
                                )
                                bitrateOptions.forEach { (label, br) ->
                                    val selected = (config.bitrateKbps == br)
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (selected) EnduPalette.Accent.copy(alpha = 0.25f) else EnduPalette.SurfaceHigh)
                                            .border(
                                                1.dp,
                                                if (selected) EnduPalette.Accent else Color.Transparent,
                                                RoundedCornerShape(6.dp)
                                            )
                                            .clickable { onSetBitrate(br) }
                                            .padding(horizontal = 7.dp, vertical = 5.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (selected) EnduPalette.Accent else EnduPalette.TextSecondary,
                                            fontSize = 10.5.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = EnduPalette.Outline.copy(alpha = 0.4f))

                        // Mapa independiente en la pantalla TFT (por defecto = igual que el móvil)
                        val selectedBikeLayer = config.mapLayerRaw?.let { MapLayer.fromRaw(it) }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Mapa en la moto",
                                    color = EnduPalette.TextPrimary,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = selectedBikeLayer?.shortBadge ?: "Igual que el móvil (${phoneLayer.shortBadge})",
                                    color = EnduPalette.Accent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val sameAsPhoneSelected = (selectedBikeLayer == null)
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (sameAsPhoneSelected) EnduPalette.Accent.copy(alpha = 0.25f) else EnduPalette.SurfaceHigh)
                                        .border(
                                            1.dp,
                                            if (sameAsPhoneSelected) EnduPalette.Accent else Color.Transparent,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable { onSetBikeMapLayer(null) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "Móvil (${phoneLayer.shortBadge})",
                                        color = if (sameAsPhoneSelected) EnduPalette.Accent else EnduPalette.TextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = if (sameAsPhoneSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }

                                MapLayer.entries.forEach { layerOption ->
                                    val isSel = (selectedBikeLayer == layerOption)
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSel) EnduPalette.Accent.copy(alpha = 0.25f) else EnduPalette.SurfaceHigh)
                                            .border(
                                                1.dp,
                                                if (isSel) EnduPalette.Accent else Color.Transparent,
                                                RoundedCornerShape(8.dp)
                                            )
                                            .clickable { onSetBikeMapLayer(layerOption) }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = layerOption.shortBadge,
                                            color = if (isSel) EnduPalette.Accent else EnduPalette.TextSecondary,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = EnduPalette.Outline.copy(alpha = 0.4f))

                        // Toggle Auto-Zoom dinámico por velocidad
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Auto-Zoom dinámico por velocidad",
                                    color = EnduPalette.TextPrimary,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Z17 al parar o ir en trialera lenta (<25 km/h), Z15 a ritmo medio y Z13 rápido en carretera (>75 km/h).",
                                    color = EnduPalette.TextMuted,
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.sp
                                )
                            }
                            Switch(
                                checked = config.autoZoomBySpeed,
                                onCheckedChange = { onSetAutoZoomBySpeed(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = EnduPalette.Accent,
                                    uncheckedThumbColor = EnduPalette.TextMuted,
                                    uncheckedTrackColor = EnduPalette.SurfaceHigh
                                )
                            )
                        }
                    }
                }

                // ------------------------------------------
                // TARJETA 3: Red Wi-Fi de la Pantalla
                // ------------------------------------------
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = EnduPalette.Surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "RED WI-FI DE LA PANTALLA",
                            color = EnduPalette.TextMuted,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )

                        if (bikeQr != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "SSID: ${bikeQr.ssid}",
                                        color = EnduPalette.TextPrimary,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Contraseña guardada",
                                        color = EnduPalette.TextMuted,
                                        fontSize = 11.sp
                                    )
                                }

                                if (bikeQr.password.isNotEmpty()) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(EnduPalette.SurfaceHigh)
                                            .clickable {
                                                clipboardManager.setText(AnnotatedString(bikeQr.password))
                                            }
                                            .padding(horizontal = 9.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = "Copiar clave",
                                            color = EnduPalette.Accent,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(EnduPalette.SurfaceHigh)
                                        .clickable { showQrInputDialog = true }
                                ) {
                                    Text("📷", fontSize = 15.sp)
                                }
                            }
                        } else {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(EnduPalette.SurfaceHigh)
                                    .clickable { showQrInputDialog = true }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("📷", fontSize = 14.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Escanear QR Wi-Fi de la pantalla",
                                        color = EnduPalette.Accent,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Divider(color = EnduPalette.Outline.copy(alpha = 0.4f))

                        // Estado de red IP
                        if (wifiSnapshot != null) {
                            Text(
                                text = "IP Wi-Fi actual: ${wifiSnapshot.phoneIP} · Puerta de enlace: ${wifiSnapshot.gatewayCandidateIP}",
                                color = EnduPalette.TextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        } else {
                            Text(
                                text = "Conecta el Wi-Fi del teléfono a la red de la pantalla antes de enviar.",
                                color = EnduPalette.Warning,
                                fontSize = 11.5.sp
                            )
                        }

                        // Toggle Simulador LAN
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Aceptar conexión de simulador en red local",
                                color = EnduPalette.TextMuted,
                                fontSize = 11.5.sp
                            )
                            Switch(
                                checked = allowLocalLanTesting,
                                onCheckedChange = { onSetAllowLocalLanTesting(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = EnduPalette.Accent,
                                    uncheckedThumbColor = EnduPalette.TextMuted,
                                    uncheckedTrackColor = EnduPalette.SurfaceHigh
                                )
                            )
                        }

                        // Despertar pantalla (Knock)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = bikeIpInput,
                                onValueChange = { bikeIpInput = it },
                                label = { Text("IP Pantalla", fontSize = 10.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = EnduPalette.Accent,
                                    unfocusedBorderColor = EnduPalette.Outline
                                )
                            )

                            Button(
                                onClick = { onKnock(bikeIpInput.trim()) },
                                colors = ButtonDefaults.buttonColors(containerColor = EnduPalette.SurfaceHigh),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.padding(top = 6.dp)
                            ) {
                                Text(text = "🔔 Despertar", color = EnduPalette.TextPrimary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // ==========================================
    // MODAL DE REGISTRO COMPLETO (1:1 con MotIOS LogSheetView)
    // ==========================================
    if (showLogsDialog) {
        AlertDialog(
            onDismissRequest = { showLogsDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Registro de Transmisión",
                        color = EnduPalette.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Borrar",
                        color = EnduPalette.Danger,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { onClearLogs() }
                            .padding(4.dp)
                    )
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(8.dp)
                ) {
                    if (logs.isEmpty()) {
                        Text(
                            text = "Sin eventos registrados.\nPulsa «Conectar y enviar mapa» para iniciar la conexión con la moto.",
                            color = EnduPalette.TextMuted,
                            fontSize = 11.5.sp
                        )
                    } else {
                        LazyColumn(reverseLayout = true) {
                            items(logs.reversed()) { line ->
                                Text(
                                    text = line,
                                    color = when {
                                        line.contains("error", ignoreCase = true) || line.contains("corta", ignoreCase = true) -> EnduPalette.Danger
                                        line.contains("aceptado", ignoreCase = true) || line.contains("iniciado", ignoreCase = true) -> EnduPalette.Success
                                        line.contains("knock", ignoreCase = true) || line.contains("reconocido", ignoreCase = true) -> EnduPalette.Accent
                                        else -> EnduPalette.TextSecondary
                                    },
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showLogsDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = EnduPalette.SurfaceHigh)
                ) {
                    Text("Cerrar", color = EnduPalette.TextPrimary)
                }
            },
            containerColor = EnduPalette.Surface
        )
    }

    // ==========================================
    // DIÁLOGO PARA INTRODUCIR O PEGAR QR
    // ==========================================
    if (showQrInputDialog) {
        AlertDialog(
            onDismissRequest = {
                showQrInputDialog = false
                qrError = null
            },
            title = {
                Text(
                    text = "Código QR de la Pantalla",
                    color = EnduPalette.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "Pega aquí el enlace o texto escaneado del código QR que muestra la pantalla de tu moto (ej. Rieju 500, CFMoto):",
                        color = EnduPalette.TextMuted,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = qrTextInput,
                        onValueChange = {
                            qrTextInput = it
                            qrError = null
                        },
                        placeholder = { Text("http://www.carbit.com.cn/... o texto del QR", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = EnduPalette.Accent,
                            unfocusedBorderColor = EnduPalette.Outline
                        )
                    )
                    if (qrError != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = qrError!!, color = EnduPalette.Danger, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val ok = onSaveQr(qrTextInput.trim())
                        if (ok) {
                            showQrInputDialog = false
                            qrTextInput = ""
                            qrError = null
                        } else {
                            qrError = "El texto no contiene un QR válido de Carbit/EasyConn con SSID y clave"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EnduPalette.Accent)
                ) {
                    Text("Guardar QR", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showQrInputDialog = false }) {
                    Text("Cancelar", color = EnduPalette.TextMuted)
                }
            },
            containerColor = EnduPalette.Surface
        )
    }
}
