package com.oscar.endulink.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.oscar.endulink.tiles.MapLayer
import com.oscar.endulink.trail.GeoCoord
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.MapKit.MKMapCamera
import platform.MapKit.MKMapTypeStandard
import platform.MapKit.MKMapView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow

actual fun isNativeAppleMapsSupported(): Boolean = true

private class NativeCameraState {
    var lat: Double = Double.NaN
    var lon: Double = Double.NaN
    var distanceM: Double = Double.NaN
    var headingDeg: Double = Double.NaN
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun NativeMapBackdrop(
    layer: MapLayer,
    center: GeoCoord,
    zoom: Double,
    heading: Double,
    baseTilePoints: Double,
    modifier: Modifier
) {
    if (layer != MapLayer.CARRETERAS) return

    val lastCamera = remember { NativeCameraState() }

    BoxWithConstraints(modifier = modifier) {
        val fallbackHeightPt = maxHeight.value.toDouble().coerceAtLeast(320.0)
        UIKitView(
            factory = {
                MKMapView().apply {
                    mapType = MKMapTypeStandard
                    showsCompass = false
                    showsScale = false
                    showsUserLocation = false
                    showsTraffic = false
                    showsBuildings = true
                    userInteractionEnabled = false
                    rotateEnabled = false
                    pitchEnabled = false
                    zoomEnabled = false
                    scrollEnabled = false
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { mapView ->
                val measuredHeight = mapView.bounds.useContents { size.height }
                val viewHeightPt = if (measuredHeight > 1.0) measuredHeight else fallbackHeightPt
                val earthCircumferenceM = 40_075_016.686
                val cosLat = cos(center.latitude * PI / 180.0).coerceAtLeast(0.05)
                val worldPoints = baseTilePoints * 2.0.pow(zoom)
                val visibleHeightMeters = (earthCircumferenceM * cosLat * viewHeightPt) / worldPoints
                // Apertura vertical estándar de MKMapView: 30° -> 2 * tan(15°) ≈ 0.5358983848622454
                val distanceMeters = (visibleHeightMeters / 0.5358983848622454).coerceIn(120.0, 25_000_000.0)
                val normalizedHeading = ((heading % 360.0) + 360.0) % 360.0

                val latChanged = lastCamera.lat.isNaN() || abs(center.latitude - lastCamera.lat) > 1e-7
                val lonChanged = lastCamera.lon.isNaN() || abs(center.longitude - lastCamera.lon) > 1e-7
                val distChanged = lastCamera.distanceM.isNaN() || abs(distanceMeters - lastCamera.distanceM) > 0.25
                val headDiff = if (lastCamera.headingDeg.isNaN()) {
                    999.0
                } else {
                    abs(((normalizedHeading - lastCamera.headingDeg + 540.0) % 360.0) - 180.0)
                }

                if (latChanged || lonChanged || distChanged || headDiff > 0.15) {
                    lastCamera.lat = center.latitude
                    lastCamera.lon = center.longitude
                    lastCamera.distanceM = distanceMeters
                    lastCamera.headingDeg = normalizedHeading

                    val cam = MKMapCamera.cameraLookingAtCenterCoordinate(
                        centerCoordinate = CLLocationCoordinate2DMake(center.latitude, center.longitude),
                        fromDistance = distanceMeters,
                        pitch = 0.0,
                        heading = normalizedHeading
                    )
                    mapView.setCamera(cam, animated = false)
                }
            }
        )
    }
}

