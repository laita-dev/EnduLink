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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.oscar.endulink.platform.AppReleaseInfo
import com.oscar.endulink.platform.AppUpdateManager
import com.oscar.endulink.platform.PlatformAppUpdater
import com.oscar.endulink.platform.getPlatformName
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class UpdateUiState {
    CHECKING,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    DOWNLOADING,
    ERROR
}

@Composable
fun AppUpdateDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val currentVersion = remember { PlatformAppUpdater.getAppVersionName() }
    val isAndroid = remember { getPlatformName().startsWith("Android", ignoreCase = true) }

    var state by remember { mutableStateOf(UpdateUiState.CHECKING) }
    var releaseInfo by remember { mutableStateOf<AppReleaseInfo?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf(0f) }

    fun checkUpdates() {
        state = UpdateUiState.CHECKING
        errorMessage = null
        coroutineScope.launch {
            val result = AppUpdateManager.checkForUpdate()
            result.onSuccess { info ->
                releaseInfo = info
                state = if (info.isNewer) {
                    UpdateUiState.UPDATE_AVAILABLE
                } else {
                    UpdateUiState.UP_TO_DATE
                }
            }.onFailure { err ->
                errorMessage = err.message ?: "Error al conectar con GitHub"
                state = UpdateUiState.ERROR
            }
        }
    }

    LaunchedEffect(Unit) {
        checkUpdates()
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = EnduPalette.Surface),
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                // Cabecera
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(EnduPalette.Accent.copy(alpha = 0.2f))
                        ) {
                            Text(text = "🏍️", fontSize = 16.sp)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "EnduLink",
                                color = EnduPalette.TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Versión instalada: v$currentVersion",
                                color = EnduPalette.TextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Text(
                        text = "✕",
                        color = EnduPalette.TextMuted,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { onDismiss() }
                            .padding(4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Contenido dinámico según estado
                when (state) {
                    UpdateUiState.CHECKING -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                color = EnduPalette.Accent,
                                modifier = Modifier.size(36.dp),
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Comprobando actualizaciones en GitHub...",
                                color = EnduPalette.TextSecondary,
                                fontSize = 12.5.sp
                            )
                        }
                    }

                    UpdateUiState.UP_TO_DATE -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(EnduPalette.Success.copy(alpha = 0.2f))
                            ) {
                                Text(text = "✓", color = EnduPalette.Success, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "¡Estás al día!",
                                color = EnduPalette.TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tienes instalada la versión más reciente (v$currentVersion).",
                                color = EnduPalette.TextMuted,
                                fontSize = 12.sp
                            )
                        }
                    }

                    UpdateUiState.UPDATE_AVAILABLE -> {
                        val rel = releaseInfo
                        if (rel != null) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Banner de versión nueva
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(EnduPalette.Accent.copy(alpha = 0.18f))
                                        .border(1.dp, EnduPalette.Accent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = "🚀", fontSize = 20.sp)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "Nueva versión: v${rel.versionName}",
                                            color = EnduPalette.TextPrimary,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = if (isAndroid) {
                                                rel.apkSizeMb?.let { "Tamaño APK: $it MB" } ?: "Lista para descargar"
                                            } else {
                                                rel.ipaSizeMb?.let { "Tamaño IPA: $it MB" } ?: "Lista para descargar"
                                            },
                                            color = EnduPalette.TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                // Notas de versión (Changelog)
                                Text(
                                    text = "Novedades de la actualización:",
                                    color = EnduPalette.TextMuted,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 140.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(EnduPalette.SurfaceHigh)
                                        .padding(10.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = rel.releaseNotes.ifBlank { "Mejoras de rendimiento y corrección de errores." },
                                        color = EnduPalette.TextSecondary,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 15.sp
                                    )
                                }

                                // Botón de acción principal
                                if (isAndroid && rel.apkDownloadUrl != null) {
                                    Button(
                                        onClick = {
                                            state = UpdateUiState.DOWNLOADING
                                            coroutineScope.launch {
                                                val ok = PlatformAppUpdater.downloadAndInstallApk(rel.apkDownloadUrl) { p ->
                                                    downloadProgress = p
                                                }
                                                if (!ok) {
                                                    errorMessage = "Error al descargar o abrir el instalador del APK."
                                                    state = UpdateUiState.ERROR
                                                } else {
                                                    onDismiss()
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = EnduPalette.Success),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth().height(42.dp)
                                    ) {
                                        Text(
                                            text = "⬇️ Descargar e Instalar APK",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                    }
                                } else {
                                    // iOS o sin APK directo
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (rel.ipaDownloadUrl != null) {
                                            Button(
                                                onClick = { PlatformAppUpdater.openExternalUrl(rel.ipaDownloadUrl) },
                                                colors = ButtonDefaults.buttonColors(containerColor = EnduPalette.Accent),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.weight(1f).height(42.dp)
                                            ) {
                                                Text("⬇️ Descargar IPA", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            }
                                        }
                                        Button(
                                            onClick = { PlatformAppUpdater.openExternalUrl(rel.htmlUrl) },
                                            colors = ButtonDefaults.buttonColors(containerColor = EnduPalette.SurfaceHigh),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.weight(1f).height(42.dp)
                                        ) {
                                            Text("🌐 Ver en GitHub", color = EnduPalette.TextPrimary, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    UpdateUiState.DOWNLOADING -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Descargando actualización...",
                                color = EnduPalette.TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = EnduPalette.Success,
                                trackColor = EnduPalette.SurfaceHigh
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "${(downloadProgress * 100).roundToInt()}%",
                                color = EnduPalette.TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    UpdateUiState.ERROR -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(text = "⚠️", fontSize = 28.sp)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "No se pudo comprobar",
                                color = EnduPalette.Danger,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = errorMessage ?: "Comprueba tu conexión a Internet.",
                                color = EnduPalette.TextMuted,
                                fontSize = 11.5.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { checkUpdates() },
                                colors = ButtonDefaults.buttonColors(containerColor = EnduPalette.SurfaceHigh),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Reintentar", color = EnduPalette.TextPrimary, fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = EnduPalette.Outline.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(10.dp))

                // Pie de diálogo
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "github.com/laita-dev/EnduLink",
                        color = EnduPalette.Accent,
                        fontSize = 10.5.sp,
                        modifier = Modifier.clickable {
                            PlatformAppUpdater.openExternalUrl("https://github.com/laita-dev/EnduLink")
                        }
                    )

                    Text(
                        text = "Cerrar",
                        color = EnduPalette.TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { onDismiss() }
                            .padding(4.dp)
                    )
                }
            }
        }
    }
}

