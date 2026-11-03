package com.oscar.endulink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun SaveTrackDialog(
    distanceKm: Double,
    elevationGainM: Double,
    durationSeconds: Long,
    onSave: (String) -> Unit,
    onDiscard: () -> Unit,
    onContinue: () -> Unit
) {
    var trackName by remember {
        mutableStateOf("Ruta Grabada ${(distanceKm * 10.0).roundToInt() / 10.0} km")
    }

    val minutes = durationSeconds / 60
    val hours = minutes / 60
    val remMin = minutes % 60
    val timeStr = if (hours > 0) "${hours}h ${remMin}m" else "${minutes} min"

    AlertDialog(
        onDismissRequest = onContinue,
        title = {
            Text(
                text = "Finalizar Grabación de Ruta",
                color = EnduPalette.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Tarjeta de resumen de ruta
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(EnduPalette.SurfaceHigh)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${(distanceKm * 10.0).roundToInt() / 10.0} km",
                            color = EnduPalette.Accent,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text("Distancia", color = EnduPalette.TextMuted, fontSize = 11.sp)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "+${elevationGainM.roundToInt()} m",
                            color = EnduPalette.Success,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text("Desnivel", color = EnduPalette.TextMuted, fontSize = 11.sp)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = timeStr,
                            color = EnduPalette.TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text("Tiempo", color = EnduPalette.TextMuted, fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Nombre del track:",
                    color = EnduPalette.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                TextField(
                    value = trackName,
                    onValueChange = { trackName = it },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = EnduPalette.SurfaceHigh,
                        unfocusedContainerColor = EnduPalette.SurfaceHigh,
                        focusedTextColor = EnduPalette.TextPrimary,
                        unfocusedTextColor = EnduPalette.TextPrimary,
                        cursorColor = EnduPalette.Accent,
                        focusedIndicatorColor = EnduPalette.Accent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(trackName.ifBlank { "Ruta Grabada" }) },
                colors = ButtonDefaults.buttonColors(containerColor = EnduPalette.Accent)
            ) {
                Text("Guardar en Mis Rutas", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) {
                    Text("Descartar", color = EnduPalette.Danger)
                }
                TextButton(onClick = onContinue) {
                    Text("Continuar", color = EnduPalette.TextMuted)
                }
            }
        },
        containerColor = EnduPalette.Surface,
        shape = RoundedCornerShape(16.dp)
    )
}

