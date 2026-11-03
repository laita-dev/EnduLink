package com.oscar.endulink

import androidx.compose.ui.window.ComposeUIViewController
import com.oscar.endulink.ui.App
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
    App()
}

