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
            // Cabecera
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Marcador Rápido Off-Road",
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

            Spacer(modifier = Modifier.height(14.dp))

            // Botones de 1 Toque
            Text(text = "Marcado rápido en tu posición actual:", color = EnduPalette.TextSecondary, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickButtonItem(
                    icon = "🚪",
                    title = "Cancela / Valla",
                    color = Color(0xFF, 0xAA, 0x00),
                    modifier = Modifier.weight(1f)
                ) {
                    onAddMarker("Cancela / Valla", "gate")
                    onClose()
                }

                QuickButtonItem(
                    icon = "🪨",
                    title = "Paso Difícil",
                    color = EnduPalette.Accent,
                    modifier = Modifier.weight(1f)
                ) {
                    onAddMarker("Paso Difícil", "rock")
                    onClose()
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickButtonItem(
                    icon = "💧",
                    title = "Fuente / Agua",
                    color = EnduPalette.AccentBlue,
                    modifier = Modifier.weight(1f)
                ) {
                    onAddMarker("Fuente / Agua", "water")
                    onClose()
                }

                QuickButtonItem(
                    icon = "⚠️",
                    title = "Peligro / Cortado",
                    color = EnduPalette.Danger,
                    modifier = Modifier.weight(1f)
                ) {
                    onAddMarker("Peligro en Pista", "danger")
                    onClose()
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Marcador Personalizado
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

            // Enlace a Gestión Completa de Waypoints
            OutlinedButton(
                onClick = {
                    onClose()
                    onOpenWaypointsManager()
                },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00D4FF)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "📋 Ver y administrar todos los Waypoints",
                    color = Color(0xFF00D4FF),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun QuickButtonItem(
    icon: String,
    title: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.15f))
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = icon, fontSize = 24.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = title, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

