package com.oscar.endulink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oscar.endulink.trail.GeoCoord
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun PlaceSearchSheet(
    riderCoord: GeoCoord?,
    title: String = "Buscar Destino",
    onSelectDestination: (SearchResultItem) -> Unit,
    onPickOnMap: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResultItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }

    // Auto-focus al abrir
    LaunchedEffect(Unit) {
        delay(100)
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    // Búsqueda reactiva con debounce de 300 ms conforme el usuario escribe
    LaunchedEffect(query) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            isLoading = false
            errorMessage = null
            return@LaunchedEffect
        }
        isLoading = true
        errorMessage = null
        searchJob = scope.launch {
            delay(280) // Debounce
            val list = PlaceSearchEngine.autocomplete(q, near = riderCoord)
            isLoading = false
            results = list
            if (list.isEmpty()) {
                errorMessage = "No se encontraron resultados cercanos para «$q»"
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = EnduPalette.Background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            // Barra superior con título y botón Cerrar
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = EnduPalette.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Cerrar",
                    color = EnduPalette.Accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { onClose() }
                        .padding(6.dp)
                )
            }

            // Cuadro de texto de búsqueda en la parte SUPERIOR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(EnduPalette.Surface)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "🔍", fontSize = 15.sp)
                Spacer(modifier = Modifier.width(8.dp))
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = {
                        Text(
                            text = "Pueblo, calle, puerto o coord...",
                            color = EnduPalette.TextMuted,
                            fontSize = 14.sp
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        val parsed = PlaceSearchEngine.parseCoordinateInput(query)
                        if (parsed != null) {
                            onSelectDestination(parsed)
                            onClose()
                        }
                    }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = EnduPalette.TextPrimary,
                        unfocusedTextColor = EnduPalette.TextPrimary
                    ),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester)
                )

                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = EnduPalette.Accent,
                        strokeWidth = 2.dp
                    )
                } else if (query.isNotEmpty()) {
                    Text(
                        text = "✕",
                        color = EnduPalette.TextMuted,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { query = "" }
                            .padding(6.dp)
                    )
                }
            }

            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = EnduPalette.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp, start = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Botón destacado para seleccionar directamente en el mapa
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(EnduPalette.SurfaceHigh)
                    .border(1.dp, EnduPalette.Accent.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .clickable {
                        onPickOnMap()
                    }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(EnduPalette.Accent.copy(alpha = 0.22f))
                ) {
                    Text(text = "🎯", fontSize = 16.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Seleccionar ubicación en el mapa",
                        color = EnduPalette.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Fija el punto con la mira interactiva",
                        color = EnduPalette.TextMuted,
                        fontSize = 11.sp
                    )
                }
                Text(
                    text = "➔",
                    color = EnduPalette.Accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Lista de resultados filtrados y ordenados por cercanía
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(results) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(EnduPalette.Surface)
                            .clickable {
                                onSelectDestination(item)
                                onClose()
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "📍", fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                color = EnduPalette.TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            Text(
                                text = item.subtitle,
                                color = EnduPalette.TextMuted,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }

                        if (item.distanceKm != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${(item.distanceKm * 10.0).roundToInt() / 10.0} km",
                                color = EnduPalette.Accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
