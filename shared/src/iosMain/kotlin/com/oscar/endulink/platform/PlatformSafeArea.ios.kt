package com.oscar.endulink.platform

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.delay
import platform.UIKit.UIApplication
import platform.UIKit.UIScreen
import platform.UIKit.UIWindow

private data class SafeInsetsDp(
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float
)

@OptIn(ExperimentalForeignApi::class)
private fun queryIosHardwareSafeInsets(): SafeInsetsDp {
    val app = UIApplication.sharedApplication
    val keyWin = app.keyWindow ?: (app.windows.firstOrNull() as? UIWindow)
    var winTop = 0f
    var winBottom = 0f
    var winLeft = 0f
    var winRight = 0f

    if (keyWin != null) {
        keyWin.safeAreaInsets.useContents {
            winTop = top.toFloat()
            winBottom = bottom.toFloat()
            winLeft = left.toFloat()
            winRight = right.toFloat()
        }
    }

    val screenBounds = UIScreen.mainScreen.bounds
    val screenW = screenBounds.useContents { size.width.toFloat() }
    val screenH = screenBounds.useContents { size.height.toFloat() }
    val isPortrait = screenH >= screenW
    val maxDim = maxOf(screenW, screenH)

    if (isPortrait) {
        val minTop = when {
            maxDim >= 852f -> 59f // Dynamic Island (iPhone 14 Pro, 15/15 Pro/Plus/Max, 16/16 Pro/Plus/Max)
            maxDim >= 780f -> 47f // Notch (iPhone X, XS, XR, 11, 12, 13, 14)
            else -> 20f           // Clásico / SE / iPad (Status Bar)
        }
        val minBottom = if (maxDim >= 780f) 24f else 0f
        winTop = maxOf(winTop, minTop)
        winBottom = maxOf(winBottom, minBottom)
    } else {
        if (maxDim >= 780f) {
            val minSide = if (maxDim >= 852f) 59f else 47f
            winLeft = maxOf(winLeft, minSide)
            winRight = maxOf(winRight, minSide)
            winBottom = maxOf(winBottom, 21f)
        }
    }

    return SafeInsetsDp(
        top = winTop,
        bottom = winBottom,
        left = winLeft,
        right = winRight
    )
}

@Composable
actual fun rememberPlatformSafeAreaPadding(): PaddingValues {
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        delay(80)
        tick++
        delay(250)
        tick++
    }

    val hwInsets = remember(tick, WindowInsets.safeDrawing) { queryIosHardwareSafeInsets() }
    val currentHw = queryIosHardwareSafeInsets()
    val composeInsets = WindowInsets.safeDrawing.asPaddingValues()
    val layoutDir = LocalLayoutDirection.current

    val topDp = maxOf(composeInsets.calculateTopPadding(), hwInsets.top.dp, currentHw.top.dp)
    val bottomDp = maxOf(composeInsets.calculateBottomPadding(), hwInsets.bottom.dp, currentHw.bottom.dp)
    val leftDp = maxOf(composeInsets.calculateLeftPadding(layoutDir), hwInsets.left.dp, currentHw.left.dp)
    val rightDp = maxOf(composeInsets.calculateRightPadding(layoutDir), hwInsets.right.dp, currentHw.right.dp)

    return PaddingValues(
        start = leftDp,
        top = topDp,
        end = rightDp,
        bottom = bottomDp
    )
}
