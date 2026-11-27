package com.oscar.endulink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oscar.endulink.platform.BugReportCategory
import com.oscar.endulink.platform.BugSubmitOutcome

@Composable
fun BugReportSheet(
    diagnosticsPreview: String,
    lastCrashLog: String?,
    pendingReportsCount: Int,
    selectedMapPin: SearchResultItem?,
    isSubmitting: Boolean,
    onPickLocationOnMap: () -> Unit,
    onClearMapPin: () -> Unit,
    onDiscardCrashLog: () -> Unit,
    onRetryPendingReports: () -> Unit,
    onSubmit: (
        category: BugReportCategory,
        userTitle: String,
        userMessage: String,
        userContact: String,
        includeDiagnostics: Boolean,
        includeCrashLog: Boolean,
        includeCurrentMapContext: Boolean,
        onDone: (BugSubmitOutcome) -> Unit
    ) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var category by remember { mutableStateOf(BugReportCategory.BUG) }
    var userTitle by remember { mutableStateOf("") }
    var userMessage by remember { mutableStateOf("") }
    var userContact by remember { mutableStateOf("") }
    var includeDiagnostics by remember { mutableStateOf(true) }
    var includeCrashLog by remember(lastCrashLog) { mutableStateOf(lastCrashLog != null) }
    var includeCurrentMapContext by remember { mutableStateOf(true) }
    var showDiagnosticsDetail by remember { mutableStateOf(false) }

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
                    BugSheetHeader(onClose = onClose)
                }

                if (lastCrashLog != null) {
                    item {
                        BugCrashBannerCard(
                            includeCrashLog = includeCrashLog,
                            crashPreview = lastCrashLog,
                            onToggleInclude = { includeCrashLog = it },
                            onDiscard = onDiscardCrashLog
                        )
                    }
                }

                if (pendingReportsCount > 0) {
                    item {
                        BugPendingQueueCard(
                            pendingCount = pendingReportsCount,
                            onRetryNow = onRetryPendingReports
                        )
                    }
                }

                item {
                    BugCategorySelector(
                        selected = category,
                        onSelect = { category = it }
                    )
                }

                item {
                    BugFormFieldsCard(
                        userTitle = userTitle,
                        userMessage = userMessage,
                        userContact = userContact,
                        onTitleChange = { userTitle = it },
                        onMessageChange = { userMessage = it },
                        onContactChange = { userContact = it }
                    )
                }

                item {
                    BugContextOptionsCard(
                        selectedMapPin = selectedMapPin,
                        includeCurrentMapContext = includeCurrentMapContext,
                        includeDiagnostics = includeDiagnostics,
                        showDiagnosticsDetail = showDiagnosticsDetail,
                        diagnosticsPreview = diagnosticsPreview,
                        onPickLocationOnMap = onPickLocationOnMap,
                        onClearMapPin = onClearMapPin,
                        onToggleMapContext = { includeCurrentMapContext = it },
                        onToggleDiagnostics = { includeDiagnostics = it },
                        onTogglePreview = { showDiagnosticsDetail = !showDiagnosticsDetail }
                    )
                }

                item {
                    val canSubmit = userMessage.trim().length >= 5 && !isSubmitting
                    Button(
                        onClick = {
                            onSubmit(
                                category,
                                userTitle,
                                userMessage,
                                userContact,
                                includeDiagnostics,
                                includeCrashLog,
                                includeCurrentMapContext
                            ) { }
                        },
                        enabled = canSubmit,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EnduPalette.Accent,
                            disabledContainerColor = EnduPalette.SurfaceHigh
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        val contentTint = if (canSubmit) Color.White else EnduPalette.TextMuted
                        if (isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            BugReportIcon(color = contentTint, size = 16.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = if (isSubmitting) "Enviando reporte..." else "Enviar reporte",
                            color = contentTint,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }
        }
    }
}

@Composable
private fun BugSheetHeader(onClose: () -> Unit) {
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
                BugReportIcon(color = EnduPalette.Accent, size = 18.dp)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Reportar Bug o Mejora",
                    color = EnduPalette.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Triaje automático por IA y seguimiento en GitHub",
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
private fun BugCrashBannerCard(
    includeCrashLog: Boolean,
    crashPreview: String,
    onToggleInclude: (Boolean) -> Unit,
    onDiscard: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(EnduPalette.Danger.copy(alpha = 0.14f))
            .border(1.dp, EnduPalette.Danger.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                WarningTriangleIcon(color = EnduPalette.Danger, size = 16.dp)
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = "Cierre inesperado detectado",
                    color = EnduPalette.Danger,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "Descartar",
                color = EnduPalette.TextMuted,
                fontSize = 11.5.sp,
                modifier = Modifier.clickable { onDiscard() }.padding(4.dp)
            )
        }
        Text(
            text = crashPreview.lineSequence().take(3).joinToString(" · "),
            color = EnduPalette.TextSecondary,
            fontSize = 10.5.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onToggleInclude(!includeCrashLog) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Adjuntar registro del cierre",
                color = EnduPalette.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Switch(
                checked = includeCrashLog,
                onCheckedChange = onToggleInclude,
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = EnduPalette.Danger)
            )
        }
    }
}

@Composable
private fun BugPendingQueueCard(
    pendingCount: Int,
    onRetryNow: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(EnduPalette.Surface)
            .border(1.dp, EnduPalette.Warning.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$pendingCount reporte${if (pendingCount > 1) "s" else ""} en cola offline",
                color = EnduPalette.Warning,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Se enviarán automáticamente al recuperar Internet",
                color = EnduPalette.TextMuted,
                fontSize = 10.5.sp
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(EnduPalette.Warning.copy(alpha = 0.2f))
                .clickable { onRetryNow() }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text("Reintentar", color = EnduPalette.Warning, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BugCategorySelector(
    selected: BugReportCategory,
    onSelect: (BugReportCategory) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "CATEGORÍA",
            color = EnduPalette.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BugReportCategory.entries.forEach { cat ->
                val isSelected = cat == selected
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (isSelected) EnduPalette.Accent else EnduPalette.Surface)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) EnduPalette.Accent else EnduPalette.Outline,
                            shape = RoundedCornerShape(9.dp)
                        )
                        .clickable { onSelect(cat) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = cat.title,
                        color = if (isSelected) Color.White else EnduPalette.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Text(
            text = selected.subtitle,
            color = EnduPalette.TextMuted,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun BugFormFieldsCard(
    userTitle: String,
    userMessage: String,
    userContact: String,
    onTitleChange: (String) -> Unit,
    onMessageChange: (String) -> Unit,
    onContactChange: (String) -> Unit
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = EnduPalette.TextPrimary,
        unfocusedTextColor = EnduPalette.TextPrimary,
        focusedBorderColor = EnduPalette.Accent,
        unfocusedBorderColor = EnduPalette.Outline,
        focusedLabelColor = EnduPalette.Accent,
        unfocusedLabelColor = EnduPalette.TextMuted
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(EnduPalette.Surface)
            .border(1.dp, EnduPalette.Outline, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = userTitle,
            onValueChange = onTitleChange,
            label = { Text("Resumen corto (opcional)") },
            singleLine = true,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = userMessage,
            onValueChange = onMessageChange,
            label = { Text("Describe el problema o mejora *") },
            placeholder = { Text("Qué pasó, cómo reproducirlo o qué añadirías...") },
            minLines = 4,
            maxLines = 8,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "Tu mensaje original se enviará íntegro junto con el análisis de nuestro agente IA.",
            color = EnduPalette.TextMuted,
            fontSize = 10.5.sp
        )
        OutlinedTextField(
            value = userContact,
            onValueChange = onContactChange,
            label = { Text("Tu email o alias (opcional)") },
            singleLine = true,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun BugContextOptionsCard(
    selectedMapPin: SearchResultItem?,
    includeCurrentMapContext: Boolean,
    includeDiagnostics: Boolean,
    showDiagnosticsDetail: Boolean,
    diagnosticsPreview: String,
    onPickLocationOnMap: () -> Unit,
    onClearMapPin: () -> Unit,
    onToggleMapContext: (Boolean) -> Unit,
    onToggleDiagnostics: (Boolean) -> Unit,
    onTogglePreview: () -> Unit
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(EnduPalette.SurfaceHigh)
                    .clickable { onPickLocationOnMap() }
                    .padding(horizontal = 10.dp, vertical = 9.dp)
            ) {
                MapPinTargetIcon(color = EnduPalette.Accent, size = 15.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (selectedMapPin != null) "Punto fijado en mapa" else "Marcar punto del fallo en el mapa",
                        color = EnduPalette.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (selectedMapPin != null) {
                        Text(
                            text = selectedMapPin.subtitle,
                            color = EnduPalette.Accent,
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (selectedMapPin != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(EnduPalette.SurfaceHigh)
                        .clickable { onClearMapPin() }
                ) {
                    CloseCrossIcon(color = EnduPalette.TextMuted, size = 12.dp)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().clickable { onToggleMapContext(!includeCurrentMapContext) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = "Incluir estado actual del mapa",
                    color = EnduPalette.TextPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Capa, zoom y coordenada aproximada",
                    color = EnduPalette.TextMuted,
                    fontSize = 10.5.sp
                )
            }
            Switch(
                checked = includeCurrentMapContext,
                onCheckedChange = onToggleMapContext,
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = EnduPalette.Accent)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().clickable { onToggleDiagnostics(!includeDiagnostics) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = "Adjuntar diagnóstico y logs TFT",
                    color = EnduPalette.TextPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Versión, ruta activa, estado Wi-Fi y últimos logs",
                    color = EnduPalette.TextMuted,
                    fontSize = 10.5.sp
                )
            }
            Switch(
                checked = includeDiagnostics,
                onCheckedChange = onToggleDiagnostics,
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = EnduPalette.Accent)
            )
        }

        if (includeDiagnostics) {
            Text(
                text = if (showDiagnosticsDetail) "Ocultar vista previa técnica" else "Ver qué datos técnicos se adjuntan",
                color = EnduPalette.Accent,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onTogglePreview() }
            )
            if (showDiagnosticsDetail) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(EnduPalette.Background)
                        .border(1.dp, EnduPalette.Outline, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = diagnosticsPreview,
                        color = EnduPalette.TextSecondary,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}
