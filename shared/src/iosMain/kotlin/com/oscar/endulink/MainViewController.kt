package com.oscar.endulink

import androidx.compose.ui.window.ComposeUIViewController
import com.oscar.endulink.platform.PhoneNotificationCenter
import com.oscar.endulink.platform.showPlatformToast
import com.oscar.endulink.ui.App
import com.oscar.endulink.ui.EnduController
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController(
    configure = {
        enforceStrictPlistSanityCheck = false
    }
) {
    App()
}

fun importExternalGpx(fileName: String, content: String) {
    EnduController.shared.importGPXContent(name = fileName, content = content)
}

fun notifyExternalGpxError(message: String) {
    showPlatformToast(message)
}

fun handleIncomingGroupLink(url: String) {
    EnduController.shared.handleIncomingGroupLink(url)
}

fun postIosIncomingNotification(appName: String, title: String, body: String) {
    PhoneNotificationCenter.postNotification(
        appName = appName,
        title = title,
        text = body
    )
}

