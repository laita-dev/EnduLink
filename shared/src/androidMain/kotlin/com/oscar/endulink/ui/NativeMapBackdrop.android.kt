package com.oscar.endulink.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.oscar.endulink.tiles.MapLayer
import com.oscar.endulink.trail.GeoCoord

actual fun isNativeAppleMapsSupported(): Boolean = false

@Composable
actual fun NativeMapBackdrop(
    layer: MapLayer,
    center: GeoCoord,
    zoom: Double,
    heading: Double,
    baseTilePoints: Double,
    modifier: Modifier
) {
    // En Android todas las capas se dibujan directamente sobre el Canvas raster de InteractiveMap.
}

