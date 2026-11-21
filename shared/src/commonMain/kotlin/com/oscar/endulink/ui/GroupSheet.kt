package com.oscar.endulink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oscar.endulink.bike.BikeMapConfig
import com.oscar.endulink.bike.rememberBikeQrScanner
import com.oscar.endulink.group.*
import com.oscar.endulink.platform.sharePlainText
import com.oscar.endulink.platform.showPlatformToast
import com.oscar.endulink.trail.GPXRoute
import com.oscar.endulink.trail.GeoCoord
import kotlinx.datetime.Clock
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
fun GroupSheet(
    roomCode: String?,
    connectionState: GroupConnectionState,
    onlineConnected: Boolean,
    lanActive: Boolean,
    members: List<GroupMember>,
    myStatus: RiderStatus,
    myStatusMessage: String,
    config: GroupConfig,
    sharedTrack: SharedGroupTrack?,
    pendingJoinCode: String?,
    activeShareableRouteName: String?,
    bikeMapConfig: BikeMapConfig,
    riderCoord: GeoCoord?,
    onCreateGroup: () -> Unit,
    onJoinGroup: (String) -> Boolean,
    onLeaveGroup: () -> Unit,
    onConsumePendingJoinCode: () -> Unit,
    onUpdateProfile: (nickname: String, color: RiderColor) -> Unit,
    onUpdateNetworkSettings: (batterySaver: Boolean, enableOnline: Boolean, enableLan: Boolean, customBrokerUrl: String) -> Unit,
    onSetMyStatus: (RiderStatus, String) -> Unit,
    onShareActiveRoute: () -> Unit,
    onImportSharedTrack: () -> Unit,
    onDismissSharedTrack: () -> Unit,
    onCenterMember: (GroupMember) -> Unit,
    onNavigateToMember: (GroupMember) -> Unit,
    onFitGroupOnMap: () -> Unit,
    onSetBikeGroupDisplayConfig: (showMembers: Boolean, showNames: Boolean, showEdgeRadar: Boolean, showHudBadge: Boolean, showAlerts: Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    var nicknameInput by remember(config.nickname) { mutableStateOf(config.nickname) }
    var selectedColor by remember(config.riderColorRaw) { mutableStateOf(config.riderColor) }
    var joinCodeInput by remember(pendingJoinCode) {
        mutableStateOf(pendingJoinCode?.let { GroupInvite.formatForDisplay(it) } ?: "")
    }
    var showQrCard by remember { mutableStateOf(false) }
    var showAdvancedBroker by remember { mutableStateOf(config.customBrokerUrl.isNotBlank()) }
    var customBrokerInput by remember(config.customBrokerUrl) { mutableStateOf(config.customBrokerUrl) }
    var statusNoteInput by remember(myStatusMessage) { mutableStateOf(myStatusMessage) }

    val launchGroupQrScanner = rememberBikeQrScanner(
        title = "Escanear QR de Grupo",
        hint = "Apunta la cámara al QR de invitación en el móvil de tu compañero",
        manualButtonLabel = "Introducir código manualmente",
        onQrScanned = { scanned ->
            val parsed = GroupInvite.parseQrInvite(scanned)
            if (parsed != null) {
                joinCodeInput = GroupInvite.formatForDisplay(parsed)
                onUpdateProfile(nicknameInput, selectedColor)
                onJoinGroup(parsed)
            } else {
                showPlatformToast("El QR escaneado no corresponde a una sala de grupo EnduLink")
            }
        },
        onManualInputRequested = {},
        onScannerError = { msg -> showPlatformToast(msg) }
    )

    Card(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        colors = CardDefaults.cardColors(containerColor = EnduPalette.Background),
        modifier = modifier.fillMaxWidth().fillMaxHeight(0.92f)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Barra superior
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

                Row(verticalAlignment = Alignment.CenterVertically) {
                    GroupRidersIcon(color = EnduPalette.Accent, size = 18.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (roomCode != null) "Grupo Activo (${members.size + 1})" else "Grupo en Ruta",
                        color = EnduPalette.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (roomCode != null && members.isNotEmpty()) {
                    Text(
                        text = "Ver todos",
                        color = EnduPalette.AccentBlue,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onFitGroupOnMap()
                                onClose()
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                } else {
                    Spacer(modifier = Modifier.width(60.dp))
                }
            }

            HorizontalDivider(color = EnduPalette.Outline.copy(alpha = 0.5f))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Banner si llegó una invitación por enlace mientras estábamos en pantalla
                if (!pendingJoinCode.isNullOrBlank() && pendingJoinCode != roomCode) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = EnduPalette.Accent.copy(alpha = 0.16f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, EnduPalette.Accent.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Invitación recibida",
                                    color = EnduPalette.Accent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Sala ${GroupInvite.formatForDisplay(pendingJoinCode)}",
                                    color = EnduPalette.TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(EnduPalette.SurfaceHigh)
                                        .clickable { onConsumePendingJoinCode() }
                                        .padding(horizontal = 10.dp, vertical = 7.dp)
                                ) {
                                    Text("Descartar", color = EnduPalette.TextSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(EnduPalette.Accent)
                                        .clickable {
                                            onUpdateProfile(nicknameInput, selectedColor)
                                            onJoinGroup(pendingJoinCode)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 7.dp)
                                ) {
                                    Text("Unirse", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                if (roomCode == null) {
                    // ==================================================
                    // VISTA SIN GRUPO: Perfil + Crear/Unirse + Batería/Red
                    // ==================================================
                    GroupProfileCard(
                        nickname = nicknameInput,
                        selectedColor = selectedColor,
                        onNicknameChange = { nicknameInput = it },
                        onColorChange = {
                            selectedColor = it
                            onUpdateProfile(nicknameInput, it)
                        },
                        onSaveProfile = {
                            onUpdateProfile(nicknameInput, selectedColor)
                            showPlatformToast("Perfil de piloto guardado")
                        }
                    )

                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = EnduPalette.Surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "CREAR O UNIRSE A UN GRUPO",
                                color = EnduPalette.TextMuted,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(EnduPalette.Accent)
                                    .clickable {
                                        onUpdateProfile(nicknameInput, selectedColor)
                                        onCreateGroup()
                                    }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    GroupRidersIcon(color = Color.White, size = 18.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Crear grupo nuevo",
                                        color = Color.White,
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            HorizontalDivider(color = EnduPalette.Outline.copy(alpha = 0.4f))

                            Text(
                                text = "Unirse con código o QR de un compañero",
                                color = EnduPalette.TextPrimary,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = joinCodeInput,
                                    onValueChange = { joinCodeInput = it.uppercase().take(28) },
                                    placeholder = { Text("Ej: K7M-9P2", color = EnduPalette.TextMuted, fontSize = 13.sp) },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = EnduPalette.TextPrimary,
                                        unfocusedTextColor = EnduPalette.TextPrimary,
                                        focusedBorderColor = EnduPalette.Accent,
                                        unfocusedBorderColor = EnduPalette.Outline,
                                        focusedContainerColor = EnduPalette.SurfaceHigh,
                                        unfocusedContainerColor = EnduPalette.SurfaceHigh
                                    ),
                                    modifier = Modifier.weight(1f)
                                )

                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(EnduPalette.SurfaceHigh)
                                        .border(1.dp, EnduPalette.Outline, RoundedCornerShape(10.dp))
                                        .clickable { launchGroupQrScanner() }
                                ) {
                                    QrScannerIcon(color = EnduPalette.Accent, size = 20.dp)
                                }
                            }

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(EnduPalette.AccentBlue.copy(alpha = 0.22f))
                                    .border(1.dp, EnduPalette.AccentBlue, RoundedCornerShape(10.dp))
                                    .clickable {
                                        onUpdateProfile(nicknameInput, selectedColor)
                                        onJoinGroup(joinCodeInput)
                                    }
                            ) {
                                Text(
                                    text = "Unirse al grupo",
                                    color = EnduPalette.AccentBlue,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            val lastCode = config.lastRoomCode
                            if (!lastCode.isNullOrBlank()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(EnduPalette.SurfaceHigh.copy(alpha = 0.7f))
                                        .clickable {
                                            onUpdateProfile(nicknameInput, selectedColor)
                                            onJoinGroup(lastCode)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Reconectar al último grupo",
                                        color = EnduPalette.TextSecondary,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = GroupInvite.formatForDisplay(lastCode),
                                        color = EnduPalette.Accent,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }

                    GroupBatteryAndNetworkCard(
                        config = config,
                        showAdvancedBroker = showAdvancedBroker,
                        customBrokerInput = customBrokerInput,
                        onToggleAdvancedBroker = { showAdvancedBroker = !showAdvancedBroker },
                        onCustomBrokerChange = { customBrokerInput = it },
                        onApplySettings = onUpdateNetworkSettings
                    )
                } else {
                    // ==================================================
                    // VISTA EN GRUPO ACTIVO
                    // ==================================================
                    // 1. Tarjeta de Sala Activa, Código y QR
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = EnduPalette.Surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "CÓDIGO DE SALA",
                                        color = EnduPalette.TextMuted,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = GroupInvite.formatForDisplay(roomCode),
                                        color = EnduPalette.TextPrimary,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    val statusColor = when (connectionState) {
                                        GroupConnectionState.ONLINE, GroupConnectionState.HYBRID -> EnduPalette.Success
                                        GroupConnectionState.OFFLINE_LAN_ONLY -> Color(0xFF00E5FF)
                                        GroupConnectionState.RECONNECTING, GroupConnectionState.CONNECTING -> EnduPalette.Warning
                                        GroupConnectionState.DISCONNECTED -> EnduPalette.TextMuted
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(statusColor.copy(alpha = 0.16f))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(statusColor)
                                        )
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text(
                                            text = connectionState.label,
                                            color = statusColor,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        text = buildString {
                                            append(if (onlineConnected) "4G/5G OK" else if (config.enableOnlineSync) "Sin red 4G" else "4G desact.")
                                            append(" · ")
                                            append(if (lanActive) "LAN OK" else "LAN espera")
                                        },
                                        color = EnduPalette.TextMuted,
                                        fontSize = 10.sp
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(EnduPalette.SurfaceHigh)
                                        .clickable {
                                            clipboard.setText(AnnotatedString(GroupInvite.formatForDisplay(roomCode)))
                                            showPlatformToast("Código ${GroupInvite.formatForDisplay(roomCode)} copiado")
                                        }
                                ) {
                                    Text("Copiar", color = EnduPalette.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }

                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(if (showQrCard) EnduPalette.Accent.copy(alpha = 0.22f) else EnduPalette.SurfaceHigh)
                                        .border(
                                            1.dp,
                                            if (showQrCard) EnduPalette.Accent else Color.Transparent,
                                            RoundedCornerShape(9.dp)
                                        )
                                        .clickable { showQrCard = !showQrCard }
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        QrCodeIcon(
                                            color = if (showQrCard) EnduPalette.Accent else EnduPalette.TextPrimary,
                                            size = 14.dp
                                        )
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text(
                                            text = if (showQrCard) "Ocultar QR" else "Ver QR",
                                            color = if (showQrCard) EnduPalette.Accent else EnduPalette.TextPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(EnduPalette.Accent)
                                        .clickable {
                                            val msg = GroupInvite.buildShareMessage(roomCode, config.nickname)
                                            sharePlainText("Grupo de ruta EnduLink", msg)
                                        }
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        ShareUploadIcon(color = Color.White, size = 14.dp)
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text("Invitar", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            if (showQrCard) {
                                val inviteUri = remember(roomCode) { GroupInvite.buildInviteUri(roomCode) }
                                val qrMatrix = remember(inviteUri) { QrCodeMatrix.encode(inviteUri) }
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(188.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(Color.White)
                                            .padding(12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        QrMatrixCanvas(
                                            matrix = qrMatrix,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    Text(
                                        text = "Escanea este QR desde el botón de cámara en otro móvil EnduLink",
                                        color = EnduPalette.TextMuted,
                                        fontSize = 10.5.sp
                                    )
                                }
                            }
                        }
                    }

                    // 2. Mi Estado / Aviso Rápido (SOS / Avería / Esperadme)
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = EnduPalette.Surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "MI AVISO RÁPIDO (MÓVIL + TFT)",
                                color = EnduPalette.TextMuted,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )

                            val quickStatuses = listOf(
                                RiderStatus.RIDING to EnduPalette.Success,
                                RiderStatus.STOPPED_WAIT to EnduPalette.Warning,
                                RiderStatus.MECHANICAL to EnduPalette.Accent,
                                RiderStatus.SOS to EnduPalette.Danger
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                quickStatuses.forEach { (st, clr) ->
                                    val selected = (myStatus == st)
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(36.dp)
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(if (selected) clr.copy(alpha = 0.25f) else EnduPalette.SurfaceHigh)
                                            .border(
                                                1.2.dp,
                                                if (selected) clr else Color.Transparent,
                                                RoundedCornerShape(9.dp)
                                            )
                                            .clickable { onSetMyStatus(st, statusNoteInput) }
                                    ) {
                                        Text(
                                            text = when (st) {
                                                RiderStatus.RIDING -> "En ruta"
                                                RiderStatus.STOPPED_WAIT -> "Esperad"
                                                RiderStatus.MECHANICAL -> "Avería"
                                                RiderStatus.SOS -> "SOS"
                                                RiderStatus.LEFT -> ""
                                            },
                                            color = if (selected) clr else EnduPalette.TextPrimary,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            if (myStatus.isAlert) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = statusNoteInput,
                                        onValueChange = { statusNoteInput = it.take(50) },
                                        placeholder = { Text("Nota breve (ej: pinchazo rueda trasera)", color = EnduPalette.TextMuted, fontSize = 12.sp) },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = EnduPalette.TextPrimary,
                                            unfocusedTextColor = EnduPalette.TextPrimary,
                                            focusedBorderColor = EnduPalette.Accent,
                                            unfocusedBorderColor = EnduPalette.Outline,
                                            focusedContainerColor = EnduPalette.SurfaceHigh,
                                            unfocusedContainerColor = EnduPalette.SurfaceHigh
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .height(48.dp)
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(EnduPalette.Accent)
                                            .clickable {
                                                onSetMyStatus(myStatus, statusNoteInput)
                                                showPlatformToast("Nota actualizada")
                                            }
                                            .padding(horizontal = 12.dp)
                                    ) {
                                        Text("Enviar", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // 3. Compartir Track GPX con el Grupo
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = EnduPalette.Surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "TRACK GPX DEL GRUPO",
                                color = EnduPalette.TextMuted,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )

                            if (activeShareableRouteName != null) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(EnduPalette.Accent.copy(alpha = 0.2f))
                                        .border(1.dp, EnduPalette.Accent.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                                        .clickable { onShareActiveRoute() }
                                ) {
                                    Text(
                                        text = "Enviar track «$activeShareableRouteName» al grupo",
                                        color = EnduPalette.Accent,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 10.dp)
                                    )
                                }
                            } else {
                                Text(
                                    text = "Selecciona o calcula una ruta para enviarla a todos en 1 toque.",
                                    color = EnduPalette.TextMuted,
                                    fontSize = 11.sp
                                )
                            }

                            if (sharedTrack != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(EnduPalette.AccentBlue.copy(alpha = 0.14f))
                                        .border(1.dp, EnduPalette.AccentBlue.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Recibido de ${sharedTrack.senderName}",
                                            color = EnduPalette.AccentBlue,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "${sharedTrack.name} · ${(sharedTrack.distanceKm * 10.0).roundToInt() / 10.0} km",
                                            color = EnduPalette.TextPrimary,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(EnduPalette.AccentBlue)
                                            .clickable {
                                                onImportSharedTrack()
                                                onClose()
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text("Cargar", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "✕",
                                        color = EnduPalette.TextMuted,
                                        fontSize = 14.sp,
                                        modifier = Modifier
                                            .clickable { onDismissSharedTrack() }
                                            .padding(4.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 4. Lista en vivo de Compañeros
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = EnduPalette.Surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "COMPAÑEROS (${members.size})",
                                    color = EnduPalette.TextMuted,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Tú: ${config.nickname.ifBlank { "Piloto" }}",
                                    color = Color(config.riderColor.argb),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (members.isEmpty()) {
                                Text(
                                    text = "Esperando a que se unan compañeros con el código ${GroupInvite.formatForDisplay(roomCode)}…",
                                    color = EnduPalette.TextMuted,
                                    fontSize = 12.sp
                                )
                            } else {
                                val nowMs = Clock.System.now().toEpochMilliseconds()
                                members.forEach { member ->
                                    GroupMemberRow(
                                        member = member,
                                        riderCoord = riderCoord,
                                        nowMs = nowMs,
                                        onCenter = {
                                            onCenterMember(member)
                                            onClose()
                                        },
                                        onNavigate = {
                                            onNavigateToMember(member)
                                            onClose()
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // 5. Perfil y Opciones en Pantalla TFT
                    GroupProfileCard(
                        nickname = nicknameInput,
                        selectedColor = selectedColor,
                        onNicknameChange = { nicknameInput = it },
                        onColorChange = {
                            selectedColor = it
                            onUpdateProfile(nicknameInput, it)
                        },
                        onSaveProfile = {
                            onUpdateProfile(nicknameInput, selectedColor)
                            showPlatformToast("Nombre/color actualizado")
                        }
                    )

                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = EnduPalette.Surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TftGroupDisplaySwitches(
                                config = bikeMapConfig,
                                onUpdate = onSetBikeGroupDisplayConfig
                            )
                        }
                    }

                    GroupBatteryAndNetworkCard(
                        config = config,
                        showAdvancedBroker = showAdvancedBroker,
                        customBrokerInput = customBrokerInput,
                        onToggleAdvancedBroker = { showAdvancedBroker = !showAdvancedBroker },
                        onCustomBrokerChange = { customBrokerInput = it },
                        onApplySettings = onUpdateNetworkSettings
                    )

                    // Botón Salir del grupo
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(EnduPalette.Danger.copy(alpha = 0.2f))
                            .border(1.dp, EnduPalette.Danger, RoundedCornerShape(12.dp))
                            .clickable {
                                onLeaveGroup()
                                onClose()
                            }
                    ) {
                        Text(
                            text = "Salir del grupo",
                            color = EnduPalette.Danger,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupProfileCard(
    nickname: String,
    selectedColor: RiderColor,
    onNicknameChange: (String) -> Unit,
    onColorChange: (RiderColor) -> Unit,
    onSaveProfile: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = EnduPalette.Surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "TU PERFIL EN EL GRUPO",
                color = EnduPalette.TextMuted,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { onNicknameChange(it.take(24)) },
                    placeholder = { Text("Tu nombre o mote (ej: Óscar)", color = EnduPalette.TextMuted, fontSize = 13.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = EnduPalette.TextPrimary,
                        unfocusedTextColor = EnduPalette.TextPrimary,
                        focusedBorderColor = EnduPalette.Accent,
                        unfocusedBorderColor = EnduPalette.Outline,
                        focusedContainerColor = EnduPalette.SurfaceHigh,
                        unfocusedContainerColor = EnduPalette.SurfaceHigh
                    ),
                    modifier = Modifier.weight(1f)
                )

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(EnduPalette.SurfaceHigh)
                        .border(1.dp, EnduPalette.Outline, RoundedCornerShape(10.dp))
                        .clickable { onSaveProfile() }
                        .padding(horizontal = 12.dp)
                ) {
                    Text("Guardar", color = EnduPalette.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RiderColor.entries.forEach { clr ->
                    val isSelected = (clr == selectedColor)
                    Box(
                        modifier = Modifier
                            .size(if (isSelected) 32.dp else 26.dp)
                            .clip(CircleShape)
                            .background(Color(clr.argb))
                            .border(
                                width = if (isSelected) 2.5.dp else 1.dp,
                                color = if (isSelected) Color.White else Color.Black.copy(alpha = 0.5f),
                                shape = CircleShape
                            )
                            .clickable { onColorChange(clr) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupBatteryAndNetworkCard(
    config: GroupConfig,
    showAdvancedBroker: Boolean,
    customBrokerInput: String,
    onToggleAdvancedBroker: () -> Unit,
    onCustomBrokerChange: (String) -> Unit,
    onApplySettings: (batterySaver: Boolean, enableOnline: Boolean, enableLan: Boolean, customBrokerUrl: String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = EnduPalette.Surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "BATERÍA Y CONEXIÓN (GRATIS · CIFRADO E2E)",
                color = EnduPalette.TextMuted,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold
            )

            GroupToggleRow(
                title = "Modo Ahorro Batería",
                subtitle = if (config.batterySaverMode) {
                    "Envía cada 10 s en ruta / 45 s parado (máxima autonomía)"
                } else {
                    "Envía cada 4 s en ruta / 25 s parado (socket único eficiente)"
                },
                checked = config.batterySaverMode,
                onCheckedChange = {
                    onApplySettings(it, config.enableOnlineSync, config.enableOfflineLanSync, customBrokerInput)
                }
            )

            GroupToggleRow(
                title = "Online 4G/5G (Alcance ilimitado)",
                subtitle = "Funciona simultáneamente mientras proyectas al TFT de la moto",
                checked = config.enableOnlineSync,
                onCheckedChange = {
                    onApplySettings(config.batterySaverMode, it, config.enableOfflineLanSync, customBrokerInput)
                }
            )

            GroupToggleRow(
                title = "Offline sin Internet (Wi-Fi Local / Hotspot)",
                subtitle = "Sincroniza sin cobertura en un Wi-Fi común o Punto de Acceso (sin usar TFT a la vez)",
                checked = config.enableOfflineLanSync,
                onCheckedChange = {
                    onApplySettings(config.batterySaverMode, config.enableOnlineSync, it, customBrokerInput)
                }
            )

            Text(
                text = if (showAdvancedBroker) "Ocultar servidor personalizado ▲" else "Servidor MQTT WSS personalizado (opcional) ▼",
                color = EnduPalette.TextSecondary,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { onToggleAdvancedBroker() }
                    .padding(vertical = 4.dp)
            )

            if (showAdvancedBroker) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customBrokerInput,
                        onValueChange = onCustomBrokerChange,
                        placeholder = { Text("wss://tu-broker:8084/mqtt", color = EnduPalette.TextMuted, fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = EnduPalette.TextPrimary,
                            unfocusedTextColor = EnduPalette.TextPrimary,
                            focusedBorderColor = EnduPalette.Accent,
                            unfocusedBorderColor = EnduPalette.Outline,
                            focusedContainerColor = EnduPalette.SurfaceHigh,
                            unfocusedContainerColor = EnduPalette.SurfaceHigh
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(48.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(EnduPalette.SurfaceHigh)
                            .border(1.dp, EnduPalette.Outline, RoundedCornerShape(9.dp))
                            .clickable {
                                onApplySettings(
                                    config.batterySaverMode,
                                    config.enableOnlineSync,
                                    config.enableOfflineLanSync,
                                    customBrokerInput
                                )
                                showPlatformToast("Servidor actualizado")
                            }
                            .padding(horizontal = 10.dp)
                    ) {
                        Text("Aplicar", color = EnduPalette.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupMemberRow(
    member: GroupMember,
    riderCoord: GeoCoord?,
    nowMs: Long,
    onCenter: () -> Unit,
    onNavigate: () -> Unit
) {
    val memberColor = Color(member.color.argb)
    val ageSec = member.ageSeconds(nowMs)
    val ageLabel = if (ageSec < 60L) "hace ${ageSec}s" else "hace ${ageSec / 60L}m"
    val distLabel = riderCoord?.let { GroupTftLayout.formatCompactDistance(it.distanceTo(member.coordinate)) } ?: "—"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(EnduPalette.SurfaceHigh.copy(alpha = 0.75f))
            .border(
                1.dp,
                if (member.status.isAlert) EnduPalette.Danger.copy(alpha = 0.75f) else Color.Transparent,
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(memberColor)
                .border(1.dp, Color.White.copy(alpha = 0.7f), CircleShape)
        )
        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = member.name,
                    color = EnduPalette.TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (member.status.isAlert) {
                    Text(
                        text = member.status.shortLabel,
                        color = Color.White,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (member.status == RiderStatus.SOS) EnduPalette.Danger else EnduPalette.Accent)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
            Text(
                text = "$distLabel · ${member.speedKmh.roundToInt()} km/h · ${member.source.label} · $ageLabel",
                color = EnduPalette.TextMuted,
                fontSize = 10.5.sp,
                maxLines = 1
            )
            if (member.statusMessage.isNotBlank()) {
                Text(
                    text = "“${member.statusMessage}”",
                    color = EnduPalette.Warning,
                    fontSize = 10.5.sp,
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(EnduPalette.Surface)
                    .clickable { onCenter() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text("Ver", color = EnduPalette.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(EnduPalette.Accent)
                    .clickable { onNavigate() }
                    .padding(horizontal = 9.dp, vertical = 6.dp)
            ) {
                Text("Ir", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun TftGroupDisplaySwitches(
    config: BikeMapConfig,
    onUpdate: (showMembers: Boolean, showNames: Boolean, showEdgeRadar: Boolean, showHudBadge: Boolean, showAlerts: Boolean) -> Unit
) {
    Text(
        text = "COMPAÑEROS DE GRUPO EN PANTALLA TFT",
        color = EnduPalette.TextMuted,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Bold
    )

    GroupToggleRow(
        title = "Mostrar compañeros en el TFT",
        subtitle = "Pines de posición y dirección sobre el mapa de la moto",
        checked = config.showGroupMembersOnTft,
        onCheckedChange = {
            onUpdate(it, config.showGroupNamesOnTft, config.showGroupEdgeRadarOnTft, config.showGroupHudBadgeOnTft, config.showGroupAlertsOnTft)
        }
    )

    GroupToggleRow(
        title = "Nombres sobre los pines",
        subtitle = "Etiqueta corta encima de cada compañero en pantalla",
        checked = config.showGroupNamesOnTft,
        enabled = config.showGroupMembersOnTft,
        onCheckedChange = {
            onUpdate(config.showGroupMembersOnTft, it, config.showGroupEdgeRadarOnTft, config.showGroupHudBadgeOnTft, config.showGroupAlertsOnTft)
        }
    )

    GroupToggleRow(
        title = "Radar en bordes (fuera de pantalla)",
        subtitle = "Flecha, iniciales y distancia cuando están lejos del encuadre",
        checked = config.showGroupEdgeRadarOnTft,
        enabled = config.showGroupMembersOnTft,
        onCheckedChange = {
            onUpdate(config.showGroupMembersOnTft, config.showGroupNamesOnTft, it, config.showGroupHudBadgeOnTft, config.showGroupAlertsOnTft)
        }
    )

    GroupToggleRow(
        title = "Contador discreto en HUD (GRP)",
        subtitle = "Pastilla con el número de pilotos activos bajo la batería",
        checked = config.showGroupHudBadgeOnTft,
        onCheckedChange = {
            onUpdate(config.showGroupMembersOnTft, config.showGroupNamesOnTft, config.showGroupEdgeRadarOnTft, it, config.showGroupAlertsOnTft)
        }
    )

    GroupToggleRow(
        title = "Banner de alertas rápidas (SOS / Avería)",
        subtitle = "Aviso destacado en rojo/naranja si un compañero pide ayuda",
        checked = config.showGroupAlertsOnTft,
        onCheckedChange = {
            onUpdate(config.showGroupMembersOnTft, config.showGroupNamesOnTft, config.showGroupEdgeRadarOnTft, config.showGroupHudBadgeOnTft, it)
        }
    )
}

@Composable
private fun GroupToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = title,
                color = if (enabled) EnduPalette.TextPrimary else EnduPalette.TextMuted,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = EnduPalette.TextMuted,
                fontSize = 10.5.sp,
                lineHeight = 14.sp
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = EnduPalette.Accent,
                uncheckedThumbColor = EnduPalette.TextMuted,
                uncheckedTrackColor = EnduPalette.SurfaceHigh
            )
        )
    }
}

@Composable
private fun QrMatrixCanvas(
    matrix: QrMatrix,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        drawRect(color = Color.White)
        val quiet = 2
        val total = matrix.size + quiet * 2
        val cell = floor(minOf(size.width, size.height) / total.toFloat()).coerceAtLeast(1f)
        val offsetX = (size.width - cell * total) * 0.5f
        val offsetY = (size.height - cell * total) * 0.5f

        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix.get(x, y)) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(offsetX + (x + quiet) * cell, offsetY + (y + quiet) * cell),
                        size = Size(cell, cell)
                    )
                }
            }
        }
    }
}
