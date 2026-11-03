package com.oscar.endulink.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oscar.endulink.trail.GPXRoute
import kotlin.math.roundToInt

@Composable
fun ApproachPromptDialog(
    route: GPXRoute,
    distanceMeters: Double,
    onApproachToStart: () -> Unit,
    onApproachToFinish: () -> Unit,
    onApproachToNearest: () -> Unit,
    onStartWithoutApproach: () -> Unit,
    onDismiss: () -> Unit
) {
    val distText = if (distanceMeters >= 1000) {
        "${(distanceMeters / 100.0).roundToInt() / 10.0} km"
    } else {
        "${distanceMeters.roundToInt()} m"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "No estás en el track",
                color = EnduPalette.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Te encuentras a $distText de «${route.name}». ¿Quieres calcular una ruta de enlace por carretera?",
                    color = EnduPalette.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(14.dp))

                // Opción 1: Al inicio
                Button(
                    onClick = onApproachToStart,
                    colors = ButtonDefaults.buttonColors(containerColor = EnduPalette.Accent),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🛣️ Enlace al inicio del track", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Opción 2: Al punto más cercano
                Button(
                    onClick = onApproachToNearest,
                    colors = ButtonDefaults.buttonColors(containerColor = EnduPalette.SurfaceHigh),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📍 Enlace al punto más cercano", color = EnduPalette.TextPrimary, fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Opción 3: Al final
                Button(
                    onClick = onApproachToFinish,
                    colors = ButtonDefaults.buttonColors(containerColor = EnduPalette.SurfaceHigh),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🏁 Enlace al final del track", color = EnduPalette.TextPrimary, fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Opción 4: Sin enlace
                TextButton(
                    onClick = onStartWithoutApproach,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Iniciar ruta sin enlace", color = EnduPalette.TextMuted, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = EnduPalette.Danger)
            }
        },
        containerColor = EnduPalette.Surface,
        shape = RoundedCornerShape(16.dp)
    )
}

