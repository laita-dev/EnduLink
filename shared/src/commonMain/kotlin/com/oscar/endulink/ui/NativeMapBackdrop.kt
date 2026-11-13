package com.oscar.endulink.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.oscar.endulink.tiles.MapLayer
import com.oscar.endulink.trail.GeoCoord

expect fun isNativeAppleMapsSupported(): Boolean

@Composable
expect fun NativeMapBackdrop(
    layer: MapLayer,
    center: GeoCoord,
    zoom: Double,
    heading: Double,
    baseTilePoints: Double,
    modifier: Modifier = Modifier
)

