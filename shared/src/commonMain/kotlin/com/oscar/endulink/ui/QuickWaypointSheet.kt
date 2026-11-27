package com.oscar.endulink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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

@Composable
fun QuickWaypointSheet(
    onAddMarker: (name: String, symbol: String) -> Unit,
    onOpenWaypointsManager: () -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var customName by remember { mutableStateOf("") }

    Card(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        colors = CardDefaults.cardColors(containerColor = EnduPalette.Surface),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Puntos y Marcadores",
                    color = EnduPalette.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(EnduPalette.SurfaceHigh)
                        .clickable { onClose() }
                ) {
                    CloseCrossIcon(color = EnduPalette.TextPrimary, size = 13.dp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(text = "Marcado rápido en tu posición actual:", color = EnduPalette.TextSecondary, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickButtonItem(
                    title = "Cancela / Valla",
                    color = Color(0xFFFFAA00),
                    icon = { tint -> GateBarrierIcon(color = tint, size = 20.dp) },
                    modifier = Modifier.weight(1f)
                ) {
                    onAddMarker("Cancela / Valla", "gate")
                    onClose()
                }

                QuickButtonItem(
                    title = "Paso Difícil",
                    color = EnduPalette.Accent,
                    icon = { tint -> EnduroMountainIcon(color = tint, size = 20.dp) },
                    modifier = Modifier.weight(1f)
                ) {
                    onAddMarker("Paso Difícil", "rock")
                    onClose()
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickButtonItem(
                    title = "Fuente / Agua",
                    color = EnduPalette.AccentBlue,
                    icon = { tint -> WaterDropIcon(color = tint, size = 20.dp) },
                    modifier = Modifier.weight(1f)
                ) {
                    onAddMarker("Fuente / Agua", "water")
                    onClose()
                }

                QuickButtonItem(
                    title = "Peligro / Cortado",
                    color = EnduPalette.Danger,
                    icon = { tint -> WarningTriangleIcon(color = tint, size = 20.dp) },
                    modifier = Modifier.weight(1f)
                ) {
                    onAddMarker("Peligro en Pista", "danger")
                    onClose()
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = customName,
                onValueChange = { customName = it },
                label = { Text("Nombre personalizado") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = EnduPalette.Accent,
                    unfocusedBorderColor = EnduPalette.Outline
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    if (customName.isNotBlank()) {
                        onAddMarker(customName.trim(), "custom")
                        onClose()
                    }
                },
                enabled = customName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = EnduPalette.Accent),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Guardar Marcador", color = Color.White, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = {
                    onClose()
                    onOpenWaypointsManager()
                },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00D4FF)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    ListLinesIcon(color = Color(0xFF00D4FF), size = 15.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Ver y administrar todos los Waypoints",
                        color = Color(0xFF00D4FF),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickButtonItem(
    title: String,
    color: Color,
    icon: @Composable (Color) -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.14f))
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            icon(color)
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = title, color = color, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
        }
    }
}
