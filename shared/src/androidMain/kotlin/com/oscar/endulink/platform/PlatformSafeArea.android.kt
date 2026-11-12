package com.oscar.endulink.platform

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.runtime.Composable

@Composable
actual fun rememberPlatformSafeAreaPadding(): PaddingValues {
    return WindowInsets.displayCutout.asPaddingValues()
}

